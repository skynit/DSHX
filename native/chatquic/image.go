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
	imageTimeout = 2 * time.Minute
	maxImage     = 10 * 1024 * 1024
	maxMetadata  = 4096
	imageHeader  = "Dshx-Image-Message"
)

var imageID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

type imageOffer struct {
	ID       string `json:"id"`
	Metadata string `json:"metadata"`
	Ticket   string `json:"ticket,omitempty"`
}

func (i imageOffer) valid() bool {
	return imageID.MatchString(i.ID) && len(i.Metadata) > 0 && len(i.Metadata) <= maxMetadata
}

func validTicket(ticket string) bool {
	return len(ticket) == 22 && imageID.MatchString(ticket)
}

type outgoingImage struct {
	imageOffer
	path    string
	ctx     context.Context
	cancel  context.CancelFunc
	started atomic.Bool
	served  atomic.Bool
}

type imageTransfers struct {
	out atomic.Pointer[outgoingImage]
	in  atomic.Bool
}

func (l *Link) SendImage(id, metadata, path string) bool {
	offer := imageOffer{ID: id, Metadata: metadata}
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
	ctx, cancel := context.WithTimeout(l.ctx, imageTimeout)
	image := &outgoingImage{imageOffer: offer, path: path, ctx: ctx, cancel: cancel}
	if !l.images.out.CompareAndSwap(nil, image) {
		cancel()
		return false
	}
	if l.initiator {
		go l.upload(image)
	} else {
		select {
		case l.out <- event{Image: &image.imageOffer}:
			context.AfterFunc(ctx, func() { l.finishImage(image, false) })
		default:
			l.images.out.CompareAndSwap(image, nil)
			cancel()
			return false
		}
	}
	return true
}

func (l *Link) CancelImage() {
	if image := l.images.out.Load(); image != nil {
		image.cancel()
	}
}

func (l *Link) finishImage(image *outgoingImage, delivered bool) {
	if l.images.out.CompareAndSwap(image, nil) {
		image.cancel()
		l.peer.ImageSent(image.ID, delivered)
	}
}

func openImage(path string) (*os.File, int64, bool) {
	file, err := os.Open(path)
	if err != nil {
		return nil, 0, false
	}
	stat, err := file.Stat()
	if err != nil || !stat.Mode().IsRegular() || stat.Size() < 1 || stat.Size() > maxImage {
		file.Close()
		return nil, 0, false
	}
	return file, stat.Size(), true
}

func (l *Link) upload(image *outgoingImage) {
	var delivered bool
	defer func() { l.finishImage(image, delivered) }()
	file, size, ok := openImage(image.path)
	if !ok {
		return
	}
	defer file.Close()
	request, err := http.NewRequestWithContext(image.ctx, http.MethodPost, baseURL+"/images/"+image.ID, file)
	if err != nil {
		return
	}
	request.ContentLength = size
	request.Header.Set("Content-Type", "application/octet-stream")
	request.Header.Set(imageHeader, base64.RawURLEncoding.EncodeToString([]byte(image.Metadata)))
	response, err := l.client.Load().RoundTrip(request)
	if err != nil {
		return
	}
	response.Body.Close()
	delivered = response.StatusCode == http.StatusNoContent
}

func (l *Link) receiveUpload(w http.ResponseWriter, r *http.Request) {
	encoded := r.Header.Get(imageHeader)
	if len(encoded) > base64.RawURLEncoding.EncodedLen(maxMetadata) {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	metadata, err := base64.RawURLEncoding.DecodeString(encoded)
	offer := imageOffer{ID: r.PathValue("id"), Metadata: string(metadata)}
	if err != nil || !offer.valid() || r.ContentLength < 1 || r.ContentLength > maxImage {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	if !l.images.in.CompareAndSwap(false, true) {
		w.WriteHeader(http.StatusConflict)
		return
	}
	defer l.images.in.Store(false)
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(imageTimeout))
	if !l.saveImage(offer, r.Body, r.ContentLength) {
		w.WriteHeader(http.StatusUnprocessableEntity)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (l *Link) requestedImage(r *http.Request) *outgoingImage {
	image := l.images.out.Load()
	if image == nil || image.ID != r.PathValue("id") || image.Ticket != r.URL.Query().Get("transfer") || image.ctx.Err() != nil {
		return nil
	}
	return image
}

func (l *Link) serveDownload(w http.ResponseWriter, r *http.Request) {
	image := l.requestedImage(r)
	if image == nil || !image.started.CompareAndSwap(false, true) {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	file, size, ok := openImage(image.path)
	if !ok {
		w.WriteHeader(http.StatusNotFound)
		l.finishImage(image, false)
		return
	}
	defer file.Close()
	controller := http.NewResponseController(w)
	deadline, _ := image.ctx.Deadline()
	_ = controller.SetWriteDeadline(deadline)
	stop := context.AfterFunc(image.ctx, func() { _ = controller.SetWriteDeadline(time.Now()) })
	defer stop()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	if _, err := io.CopyN(w, file, size); err != nil {
		l.finishImage(image, false)
		return
	}
	image.served.Store(true)
}

func (l *Link) receiveReceipt(w http.ResponseWriter, r *http.Request) {
	image := l.requestedImage(r)
	if image == nil || !image.served.Load() || r.ContentLength != 0 {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	l.finishImage(image, r.URL.Query().Get("saved") == "1")
	w.WriteHeader(http.StatusNoContent)
}

func (l *Link) download(client *http3.ClientConn, offer imageOffer) {
	if !l.images.in.CompareAndSwap(false, true) {
		return
	}
	go func() {
		url := baseURL + "/images/" + offer.ID
		saved := l.downloadBody(client, url, offer)
		l.images.in.Store(false)
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

func (l *Link) downloadBody(client *http3.ClientConn, url string, offer imageOffer) bool {
	ctx, cancel := context.WithTimeout(l.ctx, imageTimeout)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, url+"?transfer="+offer.Ticket, nil)
	response, err := client.RoundTrip(request)
	if err != nil {
		return false
	}
	defer response.Body.Close()
	return response.StatusCode == http.StatusOK && l.saveImage(offer, response.Body, response.ContentLength)
}

func (l *Link) saveImage(offer imageOffer, body io.Reader, size int64) bool {
	if size < 1 || size > maxImage || l.ctx.Err() != nil {
		return false
	}
	path := l.peer.ReserveImage(offer.ID, offer.Metadata, size)
	if path == "" {
		return false
	}
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
	return l.peer.Image(offer.ID, offer.Metadata, path)
}
