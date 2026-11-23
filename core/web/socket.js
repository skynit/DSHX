(() => {
  const NativeSocket = window.WebSocket;
  const maxChunk = 12 * 1024;
  class ShareSocket extends EventTarget {
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    get CONNECTING() { return 0; }
    get OPEN() { return 1; }
    get CLOSING() { return 2; }
    get CLOSED() { return 3; }
    #state = 0;
    #buffered = 0;
    #port;
    #handlers = {};
    #binary = 'blob';
    #protocol = '';
    #url;
    get readyState() { return this.#state; }
    get bufferedAmount() { return this.#buffered; }
    get url() { return this.#url; }
    get extensions() { return ''; }
    get protocol() { return this.#protocol; }
    get binaryType() { return this.#binary; }
    set binaryType(value) { if (['blob', 'arraybuffer'].includes(value)) this.#binary = value; }
    constructor(value, protocols = []) {
      super();
      const url = new URL(value, location.href);
      if (url.protocol === 'https:') url.protocol = 'wss:';
      if (url.host !== location.host) return new NativeSocket(value, protocols);
      if (url.protocol !== 'wss:' || url.hash || url.username || url.password) throw new DOMException('WebSocket 地址无效', 'SyntaxError');
      this.#url = url.href;
      const channel = new MessageChannel();
      this.#port = channel.port1;
      this.#port.onmessage = ({ data }) => {
        if (this.#state === 3) return;
        if (data.type === 'socket-ready') {
          this.#protocol = data.protocol || '';
          this.#state = 1;
          this.#emit(new Event('open'));
        } else if (data.type === 'socket-message') {
          const binary = typeof data.bytes === 'string' ? Uint8Array.from(atob(data.bytes), c => c.charCodeAt(0)) : null;
          this.#emit(new MessageEvent('message', { data: binary ? (this.#binary === 'blob' ? new Blob([binary]) : binary.buffer) : data.text,
            origin: url.origin }));
          this.#port.postMessage({ type: 'ack' });
        } else if (data.type === 'socket-sent') {
          this.#buffered = Math.max(0, this.#buffered - data.size);
        } else if (data.type === 'socket-close' || data.type === 'error') {
          this.#state = 3;
          channel.port1.close();
          if (data.type === 'error') this.#emit(new Event('error'));
          this.#emit(new CloseEvent('close', { code: data.code || 1006, reason: data.reason || '', wasClean: data.type === 'socket-close' }));
        }
      };
      navigator.serviceWorker.controller.postMessage({ type: 'socket', url: url.href,
        protocols: typeof protocols === 'string' ? [protocols] : Array.from(protocols) }, [channel.port2]);
    }
    #emit(event) { this.dispatchEvent(event); this.#handlers[event.type]?.call(this, event); }
    get onopen() { return this.#handlers.open ?? null; } set onopen(value) { this.#handlers.open = typeof value === 'function' ? value : null; }
    get onmessage() { return this.#handlers.message ?? null; } set onmessage(value) { this.#handlers.message = typeof value === 'function' ? value : null; }
    get onerror() { return this.#handlers.error ?? null; } set onerror(value) { this.#handlers.error = typeof value === 'function' ? value : null; }
    get onclose() { return this.#handlers.close ?? null; } set onclose(value) { this.#handlers.close = typeof value === 'function' ? value : null; }
    send(value) {
      if (this.#state === 0) throw new DOMException('WebSocket 尚未连接', 'InvalidStateError');
      if (this.#state !== 1) return;
      const bytes = value instanceof ArrayBuffer ? new Uint8Array(value) : ArrayBuffer.isView(value)
        ? new Uint8Array(value.buffer, value.byteOffset, value.byteLength) : null;
      const text = bytes || value instanceof Blob ? null : String(value);
      const size = bytes?.byteLength ?? (value instanceof Blob ? value.size : new TextEncoder().encode(text).length);
      if (size > maxChunk || this.#buffered + size > 128 * 1024) throw new DOMException('WebSocket 消息过大', 'QuotaExceededError');
      this.#buffered += size;
      this.queue = (this.queue || Promise.resolve()).then(async () => {
        if (this.#state === 3) return;
        const binary = bytes ?? (value instanceof Blob ? new Uint8Array(await value.arrayBuffer()) : null);
        this.#port.postMessage({ type: 'socket-send', ...(binary ? { bytes: btoa(String.fromCharCode(...binary)) } : { text }) });
      });
    }
    close(code = 1000, reason = '') {
      if (this.#state >= 2) return;
      this.#state = 2;
      this.#port.postMessage({ type: 'socket-close', code, reason: String(reason).slice(0, 100) });
    }
  }
  window.WebSocket = ShareSocket;
})();
