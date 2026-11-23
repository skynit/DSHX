package chatquic

import (
	"context"
	"net"
	"time"
)

const MaxDatagram = 1200

// packetConn 将一条数据通道消息交给 QUIC 作为一个数据报。地址只用于区分双方，不开套接字。
type packetConn struct {
	ctx           context.Context
	cancel        context.CancelFunc
	local, remote *net.UDPAddr
	in            chan []byte
	send          func([]byte) bool
}

func newPacketConn(initiator bool, send func([]byte) bool) *packetConn {
	local := &net.UDPAddr{IP: net.IPv4(192, 0, 2, 1), Port: 443}
	remote := &net.UDPAddr{IP: net.IPv4(192, 0, 2, 2), Port: 443}
	if !initiator {
		local, remote = remote, local
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &packetConn{ctx: ctx, cancel: cancel, local: local, remote: remote, in: make(chan []byte, 128), send: send}
}

func (p *packetConn) push(data []byte) {
	if len(data) == 0 || len(data) > MaxDatagram || p.ctx.Err() != nil {
		return
	}
	select {
	case p.in <- append([]byte(nil), data...):
	default: // 不阻塞 WebRTC 回调，由 QUIC 恢复丢包。
	}
}

func (p *packetConn) ReadFrom(dst []byte) (int, net.Addr, error) {
	select {
	case <-p.ctx.Done():
		return 0, nil, net.ErrClosed
	case data := <-p.in:
		return copy(dst, data), p.remote, nil
	}
}

func (p *packetConn) WriteTo(src []byte, _ net.Addr) (int, error) {
	if p.ctx.Err() != nil {
		return 0, net.ErrClosed
	}
	// 通道忙时丢包，不阻塞 QUIC 发送循环。
	p.send(src)
	return len(src), nil
}

func (p *packetConn) LocalAddr() net.Addr { return p.local }
func (p *packetConn) Close() error        { p.cancel(); return nil }

// quic-go 在关闭时立即中断外部 PacketConn 的读取；这个适配器不可复用。
func (p *packetConn) SetReadDeadline(deadline time.Time) error {
	if !deadline.IsZero() {
		p.cancel()
	}
	return nil
}
func (p *packetConn) SetDeadline(t time.Time) error    { return p.SetReadDeadline(t) }
func (p *packetConn) SetWriteDeadline(time.Time) error { return nil }
