const reserved = /^\/__dshx(?:\/|$)/;
const maxChunk = 12 * 1024;
const maxUpload = 128 * 1024 * 1024;
const encode = bytes => btoa(String.fromCharCode(...bytes));
const decode = text => Uint8Array.from(atob(text), char => char.charCodeAt(0));
const denied = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer',
  'transfer-encoding', 'upgrade', 'set-cookie', 'set-cookie2', 'clear-site-data', 'alt-svc', 'content-encoding',
  'content-security-policy-report-only', 'reporting-endpoints', 'report-to', 'nel']);

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

async function bridge() {
  const clients = await self.clients.matchAll({ type: 'window' });
  return clients.find(client => new URL(client.url).pathname === '/__dshx/bridge');
}

self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin || reserved.test(url.pathname)) return;
  event.respondWith(proxy(event.request, url).catch(() => failure('网页请求失败')));
});

self.addEventListener('message', event => {
  if (event.data?.type !== 'socket' || !event.ports[0] || !event.source) return;
  event.waitUntil((async () => {
    const url = new URL(event.data.url, event.source.url);
    const target = await bridge();
    if (!target || url.host !== self.location.host || url.protocol !== 'wss:' || reserved.test(url.pathname)) {
      event.ports[0].postMessage({ type: 'error', message: '不支持该 WebSocket 地址' });
      return;
    }
    target.postMessage({ type: 'request', socket: true, path: url.pathname + url.search, protocols: event.data.protocols }, [event.ports[0]]);
  })().catch(() => event.ports[0]?.postMessage({ type: 'error' })));
});

function failure(message) {
  return new Response(message, { status: 502, headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' } });
}

async function proxy(request, url) {
  const target = await bridge();
  if (!target) return failure('网页连接已关闭，请重新打开');
  const channel = new MessageChannel();
  const port = channel.port1;
  const result = Promise.withResolvers();
  let controller, timer, credit, reader;
  let finished = false, headersSeen = false, pending = false, html = false, first = true;
  const body = new ReadableStream({
    start(value) { controller = value; },
    pull() {
      if (pending && !finished) { pending = false; port.postMessage({ type: 'ack' }); }
    },
    cancel() { stop(null, true); },
  });
  const abort = () => stop('网页请求已取消', true);
  request.signal.addEventListener('abort', abort, { once: true });

  function stop(message, notify = false) {
    if (finished) return;
    finished = true;
    clearTimeout(timer);
    request.signal.removeEventListener('abort', abort);
    if (notify) port.postMessage({ type: 'cancel' });
    port.close();
    credit?.reject(Error('上传已停止'));
    reader?.cancel().catch(() => {});
    if (!headersSeen) result.resolve(failure(message || '网页连接已关闭'));
    else if (message) controller.error(Error(message));
    else if (!notify) controller.close();
  }

  function watch() {
    clearTimeout(timer);
    timer = setTimeout(() => stop('网页请求超时', true), 120_000);
  }

  async function upload() {
    let count = 0;
    reader = request.body.getReader();
    try {
      while (!finished) {
        const { value, done } = await reader.read();
        if (done) { port.postMessage({ type: 'upload-done' }); break; }
        count += value.byteLength;
        if (count > maxUpload) throw Error('上传超过 128 MiB');
        for (let offset = 0; offset < value.length && !finished; offset += maxChunk) {
          credit = Promise.withResolvers();
          port.postMessage({ type: 'upload', bytes: encode(value.subarray(offset, offset + maxChunk)) });
          await credit.promise;
        }
      }
    } catch (error) { stop(error.message, true); }
  }

  port.onmessage = event => {
    if (finished) return;
    const frame = event.data;
    watch();
    try {
      switch (frame.type) {
        case 'upload-ack': credit?.resolve(); break;
        case 'upload-ready': if (request.body && !reader) void upload(); break;
        case 'headers': {
          if (headersSeen) throw Error();
          const headers = new Headers(frame.headers.filter(([name]) => !denied.has(name.toLowerCase())));
          headers.set('Cache-Control', 'no-store');
          headers.set('Referrer-Policy', 'no-referrer');
          headers.set('X-Content-Type-Options', 'nosniff');
          headers.append('Content-Security-Policy', 'worker-src \'none\'; object-src \'none\'');
          html = request.method !== 'HEAD' && headers.get('Content-Type')?.toLowerCase().includes('text/html') &&
            !/^attachment\b/i.test(headers.get('Content-Disposition') || '') && frame.status === 200;
          if (html) headers.delete('Content-Length');
          const noBody = request.method === 'HEAD' || [204, 205, 304].includes(frame.status);
          result.resolve(new Response(noBody ? null : body, { status: frame.status, headers }));
          headersSeen = true;
          break;
        }
        case 'chunk':
          if (!headersSeen || pending) throw Error();
          if (html && first) controller.enqueue(new TextEncoder().encode('<!doctype html><script src="/__dshx/socket.js"></script>'));
          first = false;
          controller.enqueue(decode(frame.bytes));
          pending = true;
          if (controller.desiredSize > 0) { pending = false; port.postMessage({ type: 'ack' }); }
          break;
        case 'done': stop(null); break;
        case 'error': stop(frame.message || '发布端已关闭网页请求'); break;
      }
    } catch { stop('网页响应格式无效', true); }
  };

  watch();
  if (request.signal.aborted) abort();
  else target.postMessage({ type: 'request', method: request.method, path: url.pathname + url.search,
    headers: [...request.headers], upload: request.body !== null }, [channel.port2]);
  return result.promise;
}
