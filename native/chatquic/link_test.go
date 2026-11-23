package chatquic

import (
	"crypto/ed25519"
	"encoding/base64"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type testPeer struct {
	other    *Link
	key      ed25519.PrivateKey
	remote   ed25519.PublicKey
	ready    chan bool
	frames   chan string
	finished chan string
	images   chan string
	receipts chan bool
	dir      string
}

func (p *testPeer) SendPacket(data []byte) bool { p.other.Push(data); return true }
func (p *testPeer) Sign(binding []byte) string {
	return base64.RawURLEncoding.EncodeToString(ed25519.Sign(p.key, binding))
}
func (p *testPeer) Verify(binding []byte, token string) bool {
	signature, err := base64.RawURLEncoding.DecodeString(token)
	return err == nil && ed25519.Verify(p.remote, binding, signature)
}
func (p *testPeer) Ready()                      { p.ready <- true }
func (p *testPeer) Frame(value string)          { p.frames <- value }
func (p *testPeer) Finished(reason string)      { p.finished <- reason }
func (p *testPeer) StillPending(id string) bool { return id != "removed" }
func (p *testPeer) ReserveImage(_, _ string, size int64) string {
	file, err := os.CreateTemp(p.dir, "image-*.part")
	if err != nil {
		return ""
	}
	err = file.Truncate(size)
	file.Close()
	if err != nil {
		os.Remove(file.Name())
		return ""
	}
	return file.Name()
}
func (p *testPeer) Image(_, metadata, path string) bool {
	if metadata == "reject" {
		return false
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return false
	}
	p.images <- metadata + ":" + string(data)
	return true
}
func (p *testPeer) ImageSent(_ string, delivered bool) { p.receipts <- delivered }

func newTestLinks(t *testing.T, mismatched bool) (*Link, *Link, *testPeer, *testPeer) {
	t.Helper()
	newPeer := func() *testPeer {
		_, key, err := ed25519.GenerateKey(nil)
		if err != nil {
			t.Fatal(err)
		}
		return &testPeer{key: key, ready: make(chan bool, 1), frames: make(chan string, 4), finished: make(chan string, 1),
			images: make(chan string, 2), receipts: make(chan bool, 2), dir: t.TempDir()}
	}
	client, server := newPeer(), newPeer()
	client.remote = server.key.Public().(ed25519.PublicKey)
	server.remote = client.key.Public().(ed25519.PublicKey)
	if mismatched {
		client.remote = server.remote
	}
	c, s := NewLink(true, client), NewLink(false, server)
	client.other, server.other = s, c
	t.Cleanup(c.Close)
	t.Cleanup(s.Close)
	s.Start()
	c.Start()
	return c, s, client, server
}

func await[T comparable](t *testing.T, from <-chan T, want T) {
	t.Helper()
	select {
	case got := <-from:
		if got != want {
			t.Fatalf("got %v, want %v", got, want)
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for %v", want)
	}
}

func TestAuthenticatedChat(t *testing.T) {
	c, s, client, server := newTestLinks(t, false)
	await(t, client.ready, true)
	await(t, server.ready, true)
	if !c.Send(`{"text":"你好"}`) || !s.Send(`{"text":"收到了"}`) {
		t.Fatal("authenticated links rejected valid frames")
	}
	await(t, server.frames, `{"text":"你好"}`)
	await(t, client.frames, `{"text":"收到了"}`)
	if !s.SendFrame(`{"text":"stale AI reply"}`, "removed") || !s.Send(`{"text":"human reply"}`) {
		t.Fatal("could not queue subsequent frames")
	}
	await(t, client.frames, `{"text":"human reply"}`)
}

func TestUntrustedResponderCannotStartChat(t *testing.T) {
	c, s, client, _ := newTestLinks(t, true)
	select {
	case <-client.ready:
		t.Fatal("untrusted responder opened chat")
	case <-client.finished:
	case <-time.After(5 * time.Second):
		t.Fatal("untrusted responder did not close the connection")
	}
	if c.Send(`{"text":"secret"}`) || s.Send(`{"text":"secret"}`) {
		t.Fatal("unverified link accepted a chat frame")
	}
}

func TestImagesUseIndependentRequestsAndRequireDeliveryReceipt(t *testing.T) {
	c, s, client, server := newTestLinks(t, false)
	await(t, client.ready, true)
	await(t, server.ready, true)
	file := filepath.Join(t.TempDir(), "image")
	data := strings.Repeat("raw\x00data", 32768)
	if err := os.WriteFile(file, []byte(data), 0600); err != nil {
		t.Fatal(err)
	}
	if !c.SendImage("upload", "visitor", file) || !s.SendImage("download", "endpoint", file) {
		t.Fatal("failed to send images in both directions")
	}
	if !c.Send(`{"text":"still here"}`) || !s.Send(`{"ack":true}`) {
		t.Fatal("chat frames refused during image transfer")
	}
	await(t, server.frames, `{"text":"still here"}`)
	await(t, client.frames, `{"ack":true}`)
	await(t, server.images, "visitor:"+data)
	await(t, client.images, "endpoint:"+data)
	await(t, client.receipts, true)
	await(t, server.receipts, true)

	// 手动重试可以沿用业务编号，但新传输的失败不能被前一次成功覆盖。
	if !s.SendImage("download", "reject", file) {
		t.Fatal("failed to retry the download with the same message ID")
	}
	await(t, server.receipts, false)
	if !s.Send(`{"text":"after a rejected image"}`) {
		t.Fatal("rejecting an image closed chat")
	}
	await(t, client.frames, `{"text":"after a rejected image"}`)
}
