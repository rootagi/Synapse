package transfer

import (
	"encoding/binary"
	"io"
)

const (
	CompressionNone    = "none"
	CompressionGzip    = "gzip"
	CompressionZstd    = "zstd"
	CompressionChunked = "chunked"
)

// FileHeader is the metadata sent before the file content.
type FileHeader struct {
	Name        string `json:"name"`
	Size        int64  `json:"size"`
	IsArchive   bool   `json:"is_archive,omitempty"`  // True if the content is a zip archive (directory transfer)
	Compression string `json:"compression,omitempty"` // "none", "gzip"
}

// TransferRequest is sent by the receiver to the sender to negotiate the transfer.
type TransferRequest struct {
	Offset   int64  `json:"offset"`    // Byte offset to resume from
	PeerName string `json:"peer_name"` // The name of the client receiving the file
}

// ChunkedWriter wraps an io.Writer and writes data in chunks with length headers.
// Format: [Length uint32][Data]. Length 0 indicates EOF.
type ChunkedWriter struct {
	w      io.Writer
	header [4]byte // reusable header buffer — avoids allocation per write
}

func NewChunkedWriter(w io.Writer) *ChunkedWriter {
	return &ChunkedWriter{w: w}
}

func (c *ChunkedWriter) Write(p []byte) (n int, err error) {
	if len(p) == 0 {
		return 0, nil
	}
	// Write the 4-byte length header and the payload as two writes.
	// Since the upstream is always a bufio.Writer, these coalesce into a
	// single flush rather than two syscalls, while completely avoiding
	// the old make([]byte, 4+len(p)) heap allocation on every call.
	binary.BigEndian.PutUint32(c.header[:], uint32(len(p)))
	if _, err := c.w.Write(c.header[:]); err != nil {
		return 0, err
	}
	if _, err := c.w.Write(p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *ChunkedWriter) Close() error {
	// Write 0 length to signal EOF
	binary.BigEndian.PutUint32(c.header[:], 0)
	_, err := c.w.Write(c.header[:])
	return err
}

// ChunkedReader reads data written by ChunkedWriter.
type ChunkedReader struct {
	r         io.Reader
	currChunk int64 // Bytes remaining in current chunk
	eof       bool
}

func NewChunkedReader(r io.Reader) *ChunkedReader {
	return &ChunkedReader{r: r}
}

func (c *ChunkedReader) Read(p []byte) (n int, err error) {
	if c.eof {
		return 0, io.EOF
	}

	if c.currChunk == 0 {
		// Read next chunk length
		var length uint32
		if err := binary.Read(c.r, binary.BigEndian, &length); err != nil {
			return 0, err
		}
		if length == 0 {
			c.eof = true
			return 0, io.EOF
		}
		c.currChunk = int64(length)
	}

	// Read from current chunk
	if int64(len(p)) > c.currChunk {
		p = p[:c.currChunk]
	}

	n, err = c.r.Read(p)
	c.currChunk -= int64(n)
	return n, err
}
