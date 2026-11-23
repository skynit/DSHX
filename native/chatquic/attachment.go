package chatquic

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"io"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"sync/atomic"
	"time"

	"github.com/quic-go/quic-go/http3"
)

const (
	attachmentTimeout = 2 * time.Minute
	maxAttachment     = 20 * 1024 * 1024
	maxMetadata       = 4096
	attachmentHeader  = "Dshx-Attachment-Message"
)

var attachmentID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

type attachmentOffer struct {
	ID       string `json:"id"`
	Metadata string `json:"metadata"`
	Ticket   string `json:"ticket,omitempty"`
}

func (i attachmentOffer) valid() bool {
	return attachmentID.MatchString(i.ID) && len(i.Metadata) > 0 && len(i.Metadata) <= maxMetadata
}

func validTicket(ticket string) bool {
	return len(ticket) == 22 && attachmentID.MatchString(ticket)
}

type outgoingAttachment struct {
	attachmentOffer
	path    string
	ctx     context.Context
	cancel  context.CancelFunc
	started atomic.Bool
	served  atomic.Bool
}

type attachmentTransfers struct {
	out atomic.Pointer[outgoingAttachment]
	in  atomic.Bool
}

func (l *Link) SendAttachment(id, metadata, path string) bool {
	offer := attachmentOffer{ID: id, Metadata: metadata}
	if !l.ready.Load() || l.ctx.Err() != nil || !offer.valid() || path == "" {
		return false
	}
	if !l.initiator {
		var ticket [16]byte
		if _, err := rand.Read(ticket[:]); err != nil {
			return false
		}
		offer.Ticket = base64.RawURLEncoding.EncodeToString(ticket[:])
	}
	ctx, cancel := context.WithTimeout(l.ctx, attachmentTimeout)
	attachment := &outgoingAttachment{attachmentOffer: offer, path: path, ctx: ctx, cancel: cancel}
	if !l.attachments.out.CompareAndSwap(nil, attachment) {
		cancel()
		return false
	}
	if l.initiator {
		go l.upload(attachment)
	} else {
		select {
		case l.out <- event{Attachment: &attachment.attachmentOffer}:
			context.AfterFunc(ctx, func() { l.finishAttachment(attachment, false) })
		default:
			l.attachments.out.CompareAndSwap(attachment, nil)
			cancel()
			return false
		}
	}
	return true
}

func (l *Link) CancelAttachment() {
	if attachment := l.attachments.out.Load(); attachment != nil {
		attachment.cancel()
	}
}

func (l *Link) finishAttachment(attachment *outgoingAttachment, delivered bool) {
	if l.attachments.out.CompareAndSwap(attachment, nil) {
		attachment.cancel()
		l.peer.AttachmentSent(attachment.ID, delivered)
	}
}

func openAttachment(path string) (*os.File, int64, bool) {
	file, err := os.Open(path)
	if err != nil {
		return nil, 0, false
	}
	stat, err := file.Stat()
	if err != nil || !stat.Mode().IsRegular() || stat.Size() < 1 || stat.Size() > maxAttachment {
		file.Close()
		return nil, 0, false
	}
	return file, stat.Size(), true
}

func (l *Link) upload(attachment *outgoingAttachment) {
	var delivered bool
	defer func() { l.finishAttachment(attachment, delivered) }()
	file, size, ok := openAttachment(attachment.path)
	if !ok {
		return
	}
	defer file.Close()
	request, err := http.NewRequestWithContext(attachment.ctx, http.MethodPost, baseURL+"/attachments/"+attachment.ID, file)
	if err != nil {
		return
	}
	request.ContentLength = size
	request.Header.Set("Content-Type", "application/octet-stream")
	request.Header.Set(attachmentHeader, base64.RawURLEncoding.EncodeToString([]byte(attachment.Metadata)))
	response, err := l.client.Load().RoundTrip(request)
	if err != nil {
		return
	}
	response.Body.Close()
	delivered = response.StatusCode == http.StatusNoContent
}

func (l *Link) receiveUpload(w http.ResponseWriter, r *http.Request) {
	encoded := r.Header.Get(attachmentHeader)
	if len(encoded) > base64.RawURLEncoding.EncodedLen(maxMetadata) {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	metadata, err := base64.RawURLEncoding.DecodeString(encoded)
	offer := attachmentOffer{ID: r.PathValue("id"), Metadata: string(metadata)}
	if err != nil || !offer.valid() || r.ContentLength < 1 || r.ContentLength > maxAttachment {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	if !l.attachments.in.CompareAndSwap(false, true) {
		w.WriteHeader(http.StatusConflict)
		return
	}
	defer l.attachments.in.Store(false)
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(attachmentTimeout))
	if !l.saveAttachment(offer, r.Body, r.ContentLength) {
		w.WriteHeader(http.StatusUnprocessableEntity)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (l *Link) requestedAttachment(r *http.Request) *outgoingAttachment {
	attachment := l.attachments.out.Load()
	if attachment == nil || attachment.ID != r.PathValue("id") || attachment.Ticket != r.URL.Query().Get("transfer") || attachment.ctx.Err() != nil {
		return nil
	}
	return attachment
}

func (l *Link) serveDownload(w http.ResponseWriter, r *http.Request) {
	attachment := l.requestedAttachment(r)
	if attachment == nil || !attachment.started.CompareAndSwap(false, true) {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	file, size, ok := openAttachment(attachment.path)
	if !ok {
		w.WriteHeader(http.StatusNotFound)
		l.finishAttachment(attachment, false)
		return
	}
	defer file.Close()
	controller := http.NewResponseController(w)
	deadline, _ := attachment.ctx.Deadline()
	_ = controller.SetWriteDeadline(deadline)
	stop := context.AfterFunc(attachment.ctx, func() { _ = controller.SetWriteDeadline(time.Now()) })
	defer stop()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	if _, err := io.CopyN(w, file, size); err != nil {
		l.finishAttachment(attachment, false)
		return
	}
	attachment.served.Store(true)
}

func (l *Link) receiveReceipt(w http.ResponseWriter, r *http.Request) {
	attachment := l.requestedAttachment(r)
	if attachment == nil || !attachment.served.Load() || r.ContentLength != 0 {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	l.finishAttachment(attachment, r.URL.Query().Get("saved") == "1")
	w.WriteHeader(http.StatusNoContent)
}

func (l *Link) download(client *http3.ClientConn, offer attachmentOffer) {
	if !l.attachments.in.CompareAndSwap(false, true) {
		return
	}
	go func() {
		url := baseURL + "/attachments/" + offer.ID
		saved := l.downloadBody(client, url, offer)
		l.attachments.in.Store(false)
		status := "0"
		if saved {
			status = "1"
		}
		ctx, cancel := context.WithTimeout(l.ctx, requestTimeout)
		defer cancel()
		receipt, _ := http.NewRequestWithContext(ctx, http.MethodPost, url+"/receipt?transfer="+offer.Ticket+"&saved="+status, nil)
		if response, err := client.RoundTrip(receipt); err == nil {
			response.Body.Close()
		}
	}()
}

func (l *Link) downloadBody(client *http3.ClientConn, url string, offer attachmentOffer) bool {
	ctx, cancel := context.WithTimeout(l.ctx, attachmentTimeout)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, url+"?transfer="+offer.Ticket, nil)
	response, err := client.RoundTrip(request)
	if err != nil {
		return false
	}
	defer response.Body.Close()
	return response.StatusCode == http.StatusOK && l.saveAttachment(offer, response.Body, response.ContentLength)
}

func (l *Link) saveAttachment(offer attachmentOffer, body io.Reader, size int64) bool {
	if size < 1 || size > maxAttachment || l.ctx.Err() != nil {
		return false
	}
	path := l.peer.ReserveAttachment(offer.ID, offer.Metadata, size)
	if path == "" {
		return false
	}
	defer l.peer.AttachmentReceiveFinished(offer.ID)
	defer os.Remove(path)
	file, err := os.OpenFile(path, os.O_WRONLY, 0)
	if err != nil {
		return false
	}
	_, readErr := io.CopyN(file, body, size)
	if readErr == nil {
		var extra [1]byte
		if _, err := io.ReadFull(body, extra[:]); err != io.EOF {
			readErr = io.ErrUnexpectedEOF
		}
	}
	var syncErr error
	if readErr == nil {
		syncErr = file.Sync()
	}
	closeErr := file.Close()
	if readErr != nil || syncErr != nil || closeErr != nil || l.ctx.Err() != nil {
		return false
	}
	return l.peer.Attachment(offer.ID, offer.Metadata, path)
}
