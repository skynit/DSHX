package chatquic

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
)

const (
	baseURL        = "https://dshx.invalid"
	tokenHeader    = "Dshx-Endpoint-Token"
	maxToken       = 2048
	maxFrame       = 256 * 1024
	maxHeaders     = 16 * 1024
	openTimeout    = 20 * time.Second
	requestTimeout = 20 * time.Second
)

// Peer 把 QUIC 接到数据通道、端点身份和聊天运行层。回调可能在不同的 goroutine 执行。
type Peer interface {
	SendPacket([]byte) bool
	Sign(binding []byte) string
	Verify(binding []byte, token string) bool
	Ready()
	Frame(value string)
	StillPending(id string) bool
	ReserveImage(id string, metadata string, size int64) string
	Image(id string, metadata string, path string) bool
	ImageSent(id string, delivered bool)
	Finished(reason string)
}

type event struct {
	Frame     json.RawMessage `json:"frame,omitempty"`
	Image     *imageOffer     `json:"image,omitempty"`
	pendingID string
}

// Link 持有一条 HTTP/3 连接。创建数据通道的一方发起请求，另一方通过 /events 发送消息。
type Link struct {
	initiator bool
	peer      Peer
	packets   *packetConn
	ctx       context.Context
	cancel    context.CancelFunc
	start     sync.Once
	closed    atomic.Bool
	ready     atomic.Bool
	out       chan event
	images    imageTransfers
	client    atomic.Pointer[http3.ClientConn]
}

func NewLink(initiator bool, peer Peer) *Link {
	ctx, cancel := context.WithCancel(context.Background())
	return &Link{initiator: initiator, peer: peer, packets: newPacketConn(initiator, peer.SendPacket),
		ctx: ctx, cancel: cancel, out: make(chan event, 128)}
}

func (l *Link) Push(packet []byte) {
	if l.ctx.Err() == nil {
		l.packets.push(packet)
	}
}

func (l *Link) Start() {
	l.start.Do(func() {
		go func() {
			defer l.packets.Close()
			timeout := time.AfterFunc(openTimeout, func() {
				if !l.ready.Load() {
					l.cancel()
				}
			})
			defer timeout.Stop()
			err := l.run()
			l.cancel()
			if !l.closed.Load() {
				if err == nil {
					err = io.EOF
				}
				l.peer.Finished(err.Error())
			}
		}()
	})
}

func (l *Link) Close() { l.closed.Store(true); l.cancel() }

func (l *Link) Send(frame string) bool { return l.SendFrame(frame, "") }

func (l *Link) SendFrame(frame, pendingID string) bool {
	if !l.ready.Load() || l.ctx.Err() != nil || len(frame) == 0 || len(frame) > maxFrame || strings.ContainsAny(frame, "\r\n") || !json.Valid([]byte(frame)) {
		return false
	}
	select {
	case l.out <- event{Frame: json.RawMessage(frame), pendingID: pendingID}:
		return true
	default:
		return false
	}
}

func (l *Link) run() error {
	transport := &quic.Transport{Conn: l.packets}
	defer transport.Close()
	config := &quic.Config{Versions: []quic.Version{quic.Version1}, InitialPacketSize: MaxDatagram,
		DisablePathMTUDiscovery: true, MaxIncomingStreams: 8, MaxIncomingUniStreams: 3,
		KeepAlivePeriod: 8 * time.Second, MaxIdleTimeout: 25 * time.Second}
	tlsConfig := &tls.Config{MinVersion: tls.VersionTLS13, NextProtos: []string{http3.NextProtoH3}}
	if l.initiator {
		// 此连接没有公网域名身份。聊天开始前用 TLS 导出值与双向 token 验证对端。
		tlsConfig.InsecureSkipVerify = true
		conn, err := transport.Dial(l.ctx, l.packets.remote, tlsConfig, config)
		if err != nil {
			return err
		}
		return l.runClient(conn)
	}
	cert, err := temporaryCertificate()
	if err != nil {
		return err
	}
	tlsConfig.Certificates = []tls.Certificate{cert}
	listener, err := transport.Listen(tlsConfig, config)
	if err != nil {
		return err
	}
	defer listener.Close()
	conn, err := listener.Accept(l.ctx)
	if err != nil {
		return err
	}
	return l.runServer(conn)
}

func temporaryCertificate() (tls.Certificate, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, err
	}
	now := time.Now()
	template := &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: now.Add(-time.Minute),
		NotAfter: now.Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, err
}

func connectionBinding(conn *quic.Conn) ([]byte, error) {
	state := conn.ConnectionState()
	if state.Version != quic.Version1 || state.TLS.NegotiatedProtocol != http3.NextProtoH3 || state.Used0RTT {
		return nil, errors.New("unexpected QUIC or HTTP version")
	}
	return state.TLS.ExportKeyingMaterial("EXPORTER-DSHX-CHAT-H3", nil, 32)
}

func (l *Link) watch(conn *quic.Conn) func() bool {
	return context.AfterFunc(l.ctx, func() { _ = conn.CloseWithError(0, "") })
}

func (l *Link) runClient(conn *quic.Conn) error {
	stopWatching := l.watch(conn)
	defer stopWatching()
	defer conn.CloseWithError(0, "")
	binding, err := connectionBinding(conn)
	if err != nil {
		return err
	}
	client := (&http3.Transport{MaxResponseHeaderBytes: maxHeaders}).NewClientConn(conn)
	if err := l.authenticate(client, binding); err != nil {
		return err
	}
	request, err := http.NewRequestWithContext(l.ctx, http.MethodGet, baseURL+"/events", nil)
	if err != nil {
		return err
	}
	response, err := client.RoundTrip(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return errors.New("HTTP/3 events rejected")
	}
	l.client.Store(client)
	l.ready.Store(true)
	l.peer.Ready()
	go l.postFrames(client)
	scanner := bufio.NewScanner(response.Body)
	scanner.Buffer(make([]byte, 4096), maxFrame+1024)
	for scanner.Scan() {
		var incoming event
		if json.Unmarshal(scanner.Bytes(), &incoming) != nil {
			return errors.New("invalid HTTP/3 chat event")
		}
		switch {
		case len(incoming.Frame) > 0 && len(incoming.Frame) <= maxFrame && incoming.Image == nil:
			l.peer.Frame(string(incoming.Frame))
		case len(incoming.Frame) == 0 && incoming.Image != nil:
			if incoming.Image.valid() && validTicket(incoming.Image.Ticket) {
				l.download(client, *incoming.Image)
			}
		default:
			return errors.New("invalid HTTP/3 chat event")
		}
	}
	return scanner.Err()
}

func (l *Link) authenticate(client *http3.ClientConn, binding []byte) error {
	token := l.peer.Sign(binding)
	if !validToken(token) {
		return errors.New("local endpoint token unavailable")
	}
	ctx, cancel := context.WithTimeout(l.ctx, requestTimeout)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+"/authenticate", nil)
	if err != nil {
		return err
	}
	request.Header.Set(tokenHeader, token)
	response, err := client.RoundTrip(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	proof := response.Header.Get(tokenHeader)
	if response.StatusCode != http.StatusNoContent || !validToken(proof) || !l.peer.Verify(binding, proof) {
		return errors.New("remote endpoint authentication failed")
	}
	return nil
}

func validToken(token string) bool {
	return len(token) > 0 && len(token) <= maxToken && strings.IndexFunc(token, func(c rune) bool {
		return !(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.')
	}) < 0
}

func (l *Link) postFrames(client *http3.ClientConn) {
	for {
		select {
		case <-l.ctx.Done():
			return
		case outgoing := <-l.out:
			if outgoing.pendingID != "" && !l.peer.StillPending(outgoing.pendingID) {
				continue
			}
			ctx, cancel := context.WithTimeout(l.ctx, requestTimeout)
			request, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+"/messages", bytes.NewReader(outgoing.Frame))
			if err == nil {
				var response *http.Response
				response, err = client.RoundTrip(request)
				if err == nil {
					response.Body.Close()
					if response.StatusCode != http.StatusNoContent {
						err = errors.New("HTTP/3 message rejected")
					}
				}
			}
			cancel()
			if err != nil {
				l.cancel()
				return
			}
		}
	}
}

func (l *Link) runServer(conn *quic.Conn) error {
	stopWatching := l.watch(conn)
	defer stopWatching()
	defer conn.CloseWithError(0, "")
	binding, err := connectionBinding(conn)
	if err != nil {
		return err
	}
	var authenticated atomic.Bool
	mux := http.NewServeMux()
	mux.HandleFunc("POST /authenticate", func(w http.ResponseWriter, r *http.Request) {
		proof := r.Header.Get(tokenHeader)
		if authenticated.Load() || r.ContentLength != 0 || !validToken(proof) || !l.peer.Verify(binding, proof) {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		token := l.peer.Sign(binding)
		if !validToken(token) || !authenticated.CompareAndSwap(false, true) {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		w.Header().Set(tokenHeader, token)
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("GET /events", func(w http.ResponseWriter, r *http.Request) {
		if !authenticated.Load() || !l.ready.CompareAndSwap(false, true) {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		l.events(w, r)
	})
	mux.HandleFunc("POST /messages", func(w http.ResponseWriter, r *http.Request) {
		if !l.ready.Load() {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(requestTimeout))
		data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxFrame))
		if err != nil || len(data) == 0 || bytes.ContainsAny(data, "\r\n") || !json.Valid(data) {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		l.peer.Frame(string(data))
		w.WriteHeader(http.StatusNoContent)
	})
	protected := func(handler http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if !l.ready.Load() {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			handler(w, r)
		}
	}
	mux.HandleFunc("POST /images/{id}", protected(l.receiveUpload))
	mux.HandleFunc("GET /images/{id}", protected(l.serveDownload))
	mux.HandleFunc("POST /images/{id}/receipt", protected(l.receiveReceipt))
	server := &http3.Server{Handler: mux, MaxHeaderBytes: maxHeaders}
	return server.ServeQUICConn(conn)
}

func (l *Link) events(w http.ResponseWriter, r *http.Request) {
	controller := http.NewResponseController(w)
	w.Header().Set("Content-Type", "application/x-ndjson")
	defer l.cancel()
	l.peer.Ready()
	w.WriteHeader(http.StatusOK)
	if controller.Flush() != nil {
		return
	}
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	for {
		select {
		case <-l.ctx.Done():
			return
		case <-r.Context().Done():
			return
		case outgoing := <-l.out:
			if outgoing.pendingID != "" && !l.peer.StillPending(outgoing.pendingID) {
				continue
			}
			_ = controller.SetWriteDeadline(time.Now().Add(requestTimeout))
			if encoder.Encode(outgoing) != nil || controller.Flush() != nil {
				return
			}
			_ = controller.SetWriteDeadline(time.Time{})
		}
	}
}
