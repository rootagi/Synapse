package transfer

import (
	"archive/zip"
	"bufio"
	"bytes"
	"compress/gzip"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"

	"crypto/sha256"
	"github.com/example/synapse/pkg/ui"
	"github.com/example/synapse/pkg/utils"
	"github.com/klauspost/compress/zstd"
	"github.com/schollz/progressbar/v3"
)

// ReceiverOptions configures the receiver behavior for GUI support
type ReceiverOptions struct {
	DownloadDir     string
	PeerName        string // Local name to send to remote
	SenderName      string // Friendly name of the remote sender
	OnProgress      func(ProgressInfo)
	OnComplete      func(fileName string)
	OnError         func(err error)
	OnTransferStart func(net.Conn)
}

// ReceiveConnect connects to a specific peer and downloads the file/directory
func ReceiveConnect(address string) error {
	opts := ReceiverOptions{
		DownloadDir: "received_files",
	}
	return ReceiveConnectWithOptions(address, opts)
}

// ReceiveConnectWithOptions connects with extended options for GUI support
func ReceiveConnectWithOptions(address string, opts ReceiverOptions) error {
	ui.Info("Connecting to %s...", address)

	dialer := &net.Dialer{
		Timeout: 10 * time.Second,
	}
	rawConn, err := dialer.Dial("tcp", address)
	if err != nil {
		return fmt.Errorf("failed to connect to sender: %w", err)
	}
	defer rawConn.Close()

	if tcpConn, ok := rawConn.(*net.TCPConn); ok {
		_ = tcpConn.SetNoDelay(true)
		_ = tcpConn.SetWriteBuffer(2 * 1024 * 1024)
		_ = tcpConn.SetReadBuffer(2 * 1024 * 1024)
	}

	tlsConfig := &tls.Config{
		InsecureSkipVerify: true,
	}

	conn := tls.Client(rawConn, tlsConfig)
	if err := conn.Handshake(); err != nil {
		return fmt.Errorf("TLS handshake failed: %w", err)
	}
	defer conn.Close()

	if opts.OnTransferStart != nil {
		opts.OnTransferStart(conn)
	}

	ui.Info("Waiting for sender approval...")

	var headerLen int64
	// Wrap the TLS connection in a large buffered reader. Without this,
	// every read (including tiny 8-byte header reads) triggers a separate
	// TLS record decryption + syscall. Buffering batches network reads.
	bufReader := bufio.NewReaderSize(conn, 2*1024*1024)
	if err := binary.Read(bufReader, binary.BigEndian, &headerLen); err != nil {
		return fmt.Errorf("failed to read header length: %w", err)
	}

	if headerLen > 65536 {
		return fmt.Errorf("header length too large: %d", headerLen)
	}

	headerBytes := make([]byte, headerLen)
	if _, err := io.ReadFull(bufReader, headerBytes); err != nil {
		return fmt.Errorf("failed to read header JSON: %w", err)
	}

	var header FileHeader
	if err := json.Unmarshal(headerBytes, &header); err != nil {
		return fmt.Errorf("failed to unmarshal header: %w", err)
	}

	safeName := utils.SanitizeFilename(header.Name)

	downloadDir := opts.DownloadDir
	if downloadDir == "" {
		downloadDir = "received_files"
	}
	if err := os.MkdirAll(downloadDir, 0755); err != nil {
		return fmt.Errorf("failed to create download directory: %w", err)
	}

	if header.IsArchive {
		ui.Info("Receiving directory: %s (%s)", safeName, byteCountDecimal(header.Size))
	} else {
		ui.Info("Receiving file: %s (%s)", safeName, byteCountDecimal(header.Size))
	}

	var offset int64 = 0
	var outPath string
	var destFile *os.File

	if header.IsArchive {
		destFile, err = os.CreateTemp(downloadDir, "synapse-recv-*.zip")
		if err != nil {
			return fmt.Errorf("failed to create destination file: %w", err)
		}
		offset = 0
	} else {
		finalPath := filepath.Join(downloadDir, safeName)

		if info, err := os.Stat(finalPath); err == nil && !info.IsDir() {
			if info.Size() < header.Size {
				offset = info.Size()
				ui.Info("Found partial file. Resuming from %s...", byteCountDecimal(offset))
				destFile, err = os.OpenFile(finalPath, os.O_WRONLY|os.O_APPEND, 0644)
			} else {
				destFile, err = os.Create(finalPath)
			}
		} else {
			destFile, err = os.Create(finalPath)
			// Pre-allocate the file at the full expected size to prevent fragmentation
			// and incremental disk allocation during the transfer. We seek to (size-1)
			// and write a single zero byte, which causes the OS to reserve blocks.
			if err == nil && header.Size > 0 {
				if _, seekErr := destFile.Seek(header.Size-1, 0); seekErr == nil {
					_, _ = destFile.Write([]byte{0})
					// Seek back to the beginning so subsequent writes start at offset 0
					_, _ = destFile.Seek(0, 0)
				}
			}
		}
	}

	if err != nil {
		return fmt.Errorf("failed to open destination file: %w", err)
	}
	outPath = destFile.Name()

	success := false
	defer func() {
		destFile.Close()
		if header.IsArchive && !success {
			os.Remove(outPath)
		}
	}()

	req := TransferRequest{
		Offset:   offset,
		PeerName: opts.PeerName,
	}
	reqBytes, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("failed to marshal request: %w", err)
	}

	var reqLen int64 = int64(len(reqBytes))
	if err := binary.Write(conn, binary.BigEndian, reqLen); err != nil {
		return fmt.Errorf("failed to write request length: %w", err)
	}
	if _, err := conn.Write(reqBytes); err != nil {
		return fmt.Errorf("failed to send request: %w", err)
	}

	hasher := sha256.New()

	var contentReader io.Reader

	if header.Compression == CompressionZstd {
		hashedReader := io.TeeReader(bufReader, hasher)
		chunked := NewChunkedReader(hashedReader)
		zstdReader, err := zstd.NewReader(chunked)
		if err != nil {
			return fmt.Errorf("failed to create zstd reader: %w", err)
		}
		defer zstdReader.Close()
		contentReader = zstdReader
	} else if header.Compression == CompressionGzip {
		hashedReader := io.TeeReader(bufReader, hasher)
		chunked := NewChunkedReader(hashedReader)
		gzipReader, err := gzip.NewReader(chunked)
		if err != nil {
			return fmt.Errorf("failed to create gzip reader: %w", err)
		}
		defer gzipReader.Close()
		contentReader = gzipReader
	} else if header.Compression == CompressionChunked {
		contentReader = io.TeeReader(NewChunkedReader(bufReader), hasher)
	} else {
		remaining := header.Size - offset
		limitedReader := io.LimitReader(bufReader, remaining)
		hashedReader := io.TeeReader(limitedReader, hasher)
		contentReader = hashedReader
	}

	bar := progressbar.DefaultBytes(
		header.Size-offset,
		"receiving",
	)

	// Build the destination writer with optional progress callback.
	// Wrap destFile in a buffered writer to batch disk writes.
	bufFileWriter := bufio.NewWriterSize(destFile, 256*1024)
	var destWriter io.Writer
	if opts.OnProgress != nil {
		pw := &recvProgressWriter{
			inner:      bufFileWriter,
			total:      header.Size,
			offset:     offset,
			fileName:   safeName,
			peerAddr:   address,
			senderName: opts.SenderName,
			callback:   opts.OnProgress,
		}
		destWriter = pw
	} else {
		destWriter = io.MultiWriter(bufFileWriter, bar)
	}

	buf := make([]byte, 4*1024*1024)
	if _, err := io.CopyBuffer(destWriter, contentReader, buf); err != nil {
		return fmt.Errorf("failed to write file content: %w", err)
	}

	fmt.Println()

	// Flush the buffered file writer before reading checksum
	if err := bufFileWriter.Flush(); err != nil {
		return fmt.Errorf("failed to flush file buffer: %w", err)
	}

	receivedChecksum := make([]byte, 32)
	if _, err := io.ReadFull(bufReader, receivedChecksum); err != nil {
		return fmt.Errorf("failed to read checksum: %w", err)
	}

	calculatedChecksum := hasher.Sum(nil)

	if !bytes.Equal(calculatedChecksum, receivedChecksum) {
		return fmt.Errorf("checksum mismatch! File may be corrupted.\nExpected: %x\nGot:      %x", receivedChecksum, calculatedChecksum)
	}

	ui.Success("Checksum verified successfully.")

	if header.IsArchive {
		ui.Info("Extracting archive...")
		destFile.Close()

		if err := unzip(outPath, downloadDir); err != nil {
			return fmt.Errorf("failed to unzip archive: %w", err)
		}
		os.Remove(outPath)
		ui.Success("Directory received and extracted: %s", filepath.Join(downloadDir, safeName))
	} else {
		ui.Success("File received: %s", filepath.Join(downloadDir, safeName))
	}

	success = true
	if opts.OnComplete != nil {
		opts.OnComplete(safeName)
	}

	return nil
}

func byteCountDecimal(b int64) string {
	const unit = 1000
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := int64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(b)/float64(div), "kMGTPE"[exp])
}

func unzip(src string, dest string) error {
	r, err := zip.OpenReader(src)
	if err != nil {
		return err
	}
	defer r.Close()

	for _, f := range r.File {
		fpath := filepath.Join(dest, f.Name)
		if !strings.HasPrefix(fpath, filepath.Clean(dest)+string(os.PathSeparator)) {
			return fmt.Errorf("illegal file path: %s", fpath)
		}

		if f.FileInfo().IsDir() {
			os.MkdirAll(fpath, os.ModePerm)
			continue
		}

		if err := os.MkdirAll(filepath.Dir(fpath), os.ModePerm); err != nil {
			return err
		}

		outFile, err := os.OpenFile(fpath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, f.Mode())
		if err != nil {
			return err
		}

		rc, err := f.Open()
		if err != nil {
			outFile.Close()
			return err
		}

		_, err = io.Copy(outFile, rc)

		outFile.Close()
		rc.Close()

		if err != nil {
			return err
		}
	}
	return nil
}

// recvProgressWriter wraps a writer and reports progress via callback
type recvProgressWriter struct {
	inner      io.Writer
	written    int64
	total      int64
	offset     int64
	fileName   string
	peerAddr   string
	senderName string
	callback   func(ProgressInfo)
}

func (w *recvProgressWriter) Write(p []byte) (n int, err error) {
	n, err = w.inner.Write(p)
	w.written += int64(n)
	if w.callback != nil && n > 0 {
		w.callback(ProgressInfo{
			BytesSent:  w.written + w.offset,
			TotalBytes: w.total,
			FileName:   w.fileName,
			PeerAddr:   w.peerAddr,
			PeerName:   w.senderName,
		})
	}
	return n, err
}
