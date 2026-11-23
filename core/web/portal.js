const directory = document.querySelector('#directory');
const shares = document.querySelector('#shares');
const viewer = document.querySelector('#viewer');
const status = document.querySelector('#status');
const appDomain = document.querySelector('meta[name=dshx-app-domain]').content;
const protocol = 'cwnas-webrtc-signaling';
const uuid = /^[\da-f-]{36}$/;
const direct = /^(?:a=)?candidate:\S+\s+\d+\s+\S+\s+\d+\s+\S+\s+\d+\s+typ\s+(host|srflx|prflx)(?:\s|$)/i;
const reserved = /^\/__dshx(?:\/|$)/;
const maxChunk = 12 * 1024;
let active;

function row(parent, text, label, action, disabled = false) {
  const line = document.createElement('div');
  line.className = 'row';
  const name = document.createElement('span');
  name.textContent = text;
  const button = document.createElement('button');
  button.textContent = label;
  button.disabled = disabled;
  button.addEventListener('click', action);
  line.append(name, button);
  parent.append(line);
}

async function listEndpoints(cursor) {
  const url = new URL('/api/v1/dshx/chat/endpoints', location.origin);
  if (cursor) url.searchParams.set('cursor', cursor);
  status.textContent = '正在读取端点…';
  try {
    const response = await fetch(url);
    if (!response.ok) throw Error();
    const page = await response.json();
    const open = page.items.filter(item => item.capabilities?.includes('web-share'));
    for (const item of open) row(directory, item.displayName || item.endpointId, item.online ? '查看网页' : '离线',
      () => connect(item.endpointId), !item.online);
    if (page.nextCursor) row(directory, '', '加载更多', event => {
      event.currentTarget.parentElement.remove();
      void listEndpoints(page.nextCursor);
    });
    status.textContent = directory.childElementCount ? '' : '暂无公开网页';
  } catch { status.textContent = '无法读取公开端点'; }
}

function connect(endpointId, initialShare) {
  if (!uuid.test(endpointId)) { status.textContent = '端点地址无效'; return; }
  active?.close();
  directory.hidden = true;
  shares.hidden = false;
  shares.replaceChildren();
  viewer.replaceChildren();
  viewer.hidden = true;
  row(shares, '', '返回端点', () => {
    active?.close();
    active = null;
    shares.hidden = true;
    directory.hidden = false;
    viewer.hidden = true;
    history.replaceState(null, '', '/web/');
    if (!directory.childElementCount) void listEndpoints();
    else status.textContent = '';
  });
  status.textContent = '正在建立直连…';
  const connection = new Peer(endpointId, onFrame, message => {
    if (active !== connection) return;
    connection.disposeView();
    viewer.hidden = true;
    status.textContent = message;
  });
  active = connection;
  let itemCount = 0;
  function onFrame(frame) {
    if (active !== connection || frame.type !== 'list' || !Array.isArray(frame.items)) return;
    for (const item of frame.items.slice(0, 32)) {
      if (!uuid.test(item.id) || typeof item.name !== 'string' || !safePath(item.path)) continue;
      itemCount++;
      row(shares, item.name, '打开网页', () => void open(item));
      if (item.id === initialShare) void open(item);
    }
    if (frame.done !== false) status.textContent = itemCount ? '已直连' : '没有可访问的网页';
  }
  async function open(item) {
    status.textContent = '正在打开网页…';
    try {
      await connection.view(item);
      viewer.hidden = false;
      status.textContent = '已直连';
      history.replaceState(null, '', `/web/?endpoint=${encodeURIComponent(endpointId)}&share=${encodeURIComponent(item.id)}`);
    } catch { if (active === connection) status.textContent = '无法初始化网页入口'; }
  }
}

function safePath(value) {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') &&
    !/[\\\x00-\x20]/.test(value) && !reserved.test(new URL(value, location.origin).pathname);
}

class Peer {
  requests = new Map();
  session = crypto.randomUUID();
  remoteCandidates = [];
  localCandidates = [];
  closed = false;

  constructor(endpoint, onFrame, onError) {
    this.endpoint = endpoint;
    this.onFrame = onFrame;
    this.onError = onError;
    this.socket = new WebSocket(`${location.origin.replace(/^https:/, 'wss:')}/api/v1/dshx/web/signaling`);
    this.socket.onmessage = ({ data }) => { this.signals = (this.signals ?? Promise.resolve()).then(() => this.signal(JSON.parse(data))).catch(() => this.fail('直连验证失败')); };
    this.socket.onclose = this.socket.onerror = () => this.fail('网页连接已断开');
    this.timer = setTimeout(() => this.fail('无法建立设备直连'), 25_000);
  }

  signalSend(type, payload) {
    this.socket.send(JSON.stringify({ protocol, version: 1, message_id: crypto.randomUUID(), type, session_id: this.session,
      destination: { kind: 'endpoint', id: this.endpoint }, payload }));
  }

  async signal(frame) {
    if (this.closed || frame.protocol !== protocol || frame.version !== 1) return;
    const payload = frame.payload;
    if (frame.type === 'web_ready' && !this.peer) {
      this.visitor = frame.destination.id;
      const iceServers = payload.ice_servers.flatMap(server => {
        const urls = server.urls.filter(url => /^stuns?:/i.test(url));
        return urls.length ? [{ urls }] : [];
      });
      this.peer = new RTCPeerConnection({ iceServers });
      this.channel = this.peer.createDataChannel('dshx-web/1', { negotiated: true, id: 0, ordered: true, protocol: 'dshx-web/1' });
      this.peer.onicecandidate = ({ candidate }) => {
        if (!candidate || !direct.test(candidate.candidate)) return;
        const data = { candidate: candidate.candidate, sdp_mid: candidate.sdpMid, sdp_mline_index: candidate.sdpMLineIndex };
        if (!this.offered) this.localCandidates.push(data);
        else this.signalSend('web_candidate', data);
      };
      this.peer.onconnectionstatechange = () => {
        if (['failed', 'closed'].includes(this.peer.connectionState)) this.fail('网页直连已断开');
      };
      this.channel.binaryType = 'arraybuffer';
      this.channel.onopen = () => this.checkDirect().catch(() => this.fail('无法确认设备直连'));
      this.channel.onclose = () => this.fail('网页共享已关闭');
      this.channel.onmessage = ({ data }) => this.receive(data);
      const description = await this.peer.createOffer();
      await this.peer.setLocalDescription(description);
      this.offer = description.sdp;
      this.signalSend('web_offer', { description });
      this.offered = true;
      for (const candidate of this.localCandidates.splice(0)) this.signalSend('web_candidate', candidate);
      return;
    }
    if (frame.session_id !== this.session || frame.destination?.id !== this.visitor) return;
    if (frame.type === 'error' && frame.source?.kind === 'platform') return this.fail('端点当前无法建立网页连接');
    if (frame.source?.id !== this.endpoint || !this.peer) return;
    if (frame.type === 'web_answer' && !this.peer.remoteDescription) {
      const sdp = payload.description.sdp;
      if (sdp.split(/\r?\n/).some(line => line.startsWith('a=candidate:') && !direct.test(line))) throw Error();
      await verifyAnswer(this, sdp, payload.proof);
      await this.peer.setRemoteDescription(payload.description);
      for (const candidate of this.remoteCandidates.splice(0)) await this.peer.addIceCandidate(candidate);
    } else if (frame.type === 'web_candidate' && direct.test(payload.candidate)) {
      const candidate = { candidate: payload.candidate, sdpMid: payload.sdp_mid, sdpMLineIndex: payload.sdp_mline_index };
      if (this.peer.remoteDescription) await this.peer.addIceCandidate(candidate);
      else if (this.remoteCandidates.length < 128) this.remoteCandidates.push(candidate);
    } else if (frame.type === 'web_close') this.fail('端点已停止网页访问');
  }

  async checkDirect() {
    for (let attempt = 0; attempt < 30 && !this.closed; attempt++) {
      const stats = await this.peer.getStats();
      const pair = stats.get([...stats.values()].find(value => value.type === 'transport')?.selectedCandidatePairId);
      const kinds = [stats.get(pair?.localCandidateId)?.candidateType, stats.get(pair?.remoteCandidateId)?.candidateType];
      if (kinds.includes('relay')) break;
      if (pair?.state === 'succeeded' && kinds.every(kind => ['host', 'srflx', 'prflx'].includes(kind))) {
        this.ready = true;
        clearTimeout(this.timer);
        this.timer = setTimeout(() => this.fail('读取网页列表超时'), 10_000);
        await this.send({ type: 'list' });
        return;
      }
      await sleep(150);
    }
    if (!this.closed) this.fail('无法确认设备直连');
  }

  async send(frame) {
    const channel = this.channel;
    while (!this.closed && channel?.bufferedAmount > 128 * 1024) await sleep(15);
    if (!this.ready || this.closed || channel?.readyState !== 'open') throw Error('网页连接已断开');
    channel.send(new TextEncoder().encode(JSON.stringify(frame)));
  }

  receive(data) {
    try {
      if (!this.ready || !(data instanceof ArrayBuffer) || data.byteLength > 32 * 1024) return;
      const frame = JSON.parse(new TextDecoder().decode(data));
      if (frame.type === 'list' && frame.done !== false) clearTimeout(this.timer);
      const request = this.requests.get(frame.id);
      if (!request) { this.onFrame(frame); return; }
      request.port.postMessage(frame);
      if (['done', 'error', 'socket-close'].includes(frame.type)) this.requests.delete(frame.id);
    } catch { this.fail('网页响应格式无效'); }
  }

  async view(item) {
    this.disposeView();
    const viewId = crypto.randomUUID().replaceAll('-', '');
    const appOrigin = `https://s-${viewId}.${appDomain}${location.port ? `:${location.port}` : ''}`;
    const iframe = document.createElement('iframe');
    iframe.title = item.name;
    // 独立应用来源不持有信令权限；保留表单、脚本、同来源请求和下载。
    iframe.sandbox = 'allow-scripts allow-same-origin allow-forms allow-downloads';
    iframe.referrerPolicy = 'no-referrer';
    const key = crypto.randomUUID();
    const messages = new MessageChannel();
    const started = Promise.withResolvers();
    const timer = setTimeout(() => started.reject(Error('网页入口初始化超时')), 10_000);
    const boot = event => {
      if (event.source !== iframe.contentWindow || event.origin !== appOrigin || event.data?.key !== key || event.data.type !== 'boot') return;
      removeEventListener('message', boot);
      iframe.contentWindow.postMessage({ type: 'bind', key, path: item.path }, appOrigin, [messages.port2]);
    };
    addEventListener('message', boot);
    this.viewClose = () => {
      clearTimeout(timer);
      removeEventListener('message', boot);
      iframe.remove();
      messages.port1.close();
      started.reject(Error('网页入口已关闭'));
    };
    messages.port1.onmessage = event => {
      if (event.data?.type === 'ready') started.resolve();
      if (event.data?.type === 'failed') started.reject(Error('网页入口初始化失败'));
      if (event.data?.type === 'request' && event.ports[0]) this.forward(item.id, event.data, event.ports[0]);
    };
    iframe.src = `${appOrigin}/__dshx/bridge#${key}`;
    viewer.replaceChildren(iframe);
    try { await started.promise; } finally { clearTimeout(timer); }
  }

  forward(share, request, port) {
    if (!safePath(request.path) || this.requests.size >= 24) {
      port.postMessage({ type: 'error', message: '网页请求路径无效或连接繁忙' });
      return;
    }
    const id = crypto.randomUUID();
    this.requests.set(id, { port });
    port.onmessage = ({ data }) => {
      if (!this.requests.has(id) || !['ack', 'upload', 'upload-done', 'cancel', 'socket-send', 'socket-close'].includes(data?.type)) return;
      if (typeof data.bytes === 'string' && data.bytes.length > maxChunk * 2) return;
      void this.send({ ...data, id }).catch(() => this.fail('网页连接已断开'));
      if (data.type === 'cancel') this.requests.delete(id);
    };
    void this.send({ ...request, type: request.socket ? 'socket' : 'open', id, share }).catch(() => this.fail('网页连接已断开'));
  }

  disposeView() {
    this.viewClose?.();
    this.viewClose = null;
    for (const [id, { port }] of this.requests) {
      port.postMessage({ type: 'error', message: '网页访问已关闭' });
      port.close();
      if (!this.closed) void this.send({ type: 'cancel', id }).catch(() => {});
    }
    this.requests.clear();
  }

  fail(message) { if (!this.closed) { this.close(); this.onError(message); } }

  close() {
    if (this.closed) return;
    this.closed = true;
    clearTimeout(this.timer);
    this.disposeView();
    this.channel?.close();
    this.peer?.close();
    this.socket.close();
  }
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const unbase64 = text => Uint8Array.from(atob(text.replaceAll('-', '+').replaceAll('_', '/')), char => char.charCodeAt(0));

async function verifyAnswer(peer, answer, proof) {
  const [keyText, signature] = proof.split('.');
  const keyBytes = unbase64(keyText);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', keyBytes));
  digest[6] = (digest[6] & 15) | 128;
  digest[8] = (digest[8] & 63) | 128;
  const hex = [...digest.slice(0, 16)].map(byte => byte.toString(16).padStart(2, '0')).join('');
  const id = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  if (id !== peer.endpoint) throw Error('端点身份不一致');
  const key = await crypto.subtle.importKey('spki', keyBytes, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
  const transcript = new TextEncoder().encode(`dshx-web/1\n${peer.session}\n${peer.visitor}\n${peer.endpoint}\n${peer.offer}\n${answer}`);
  if (!await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, derToRaw(unbase64(signature)), transcript)) throw Error('端点签名无效');
}

function derToRaw(signature) {
  if (signature[0] !== 48 || signature[1] !== signature.length - 2) throw Error('签名格式无效');
  const result = new Uint8Array(64);
  let offset = 2;
  for (let index = 0; index < 2; index++) {
    if (signature[offset++] !== 2) throw Error('签名格式无效');
    const length = signature[offset++];
    const end = offset + length;
    if (length < 1 || length > 33 || end > signature.length || (length === 33 && signature[offset] !== 0)) throw Error('签名格式无效');
    const item = signature.subarray(end - Math.min(32, length), end);
    result.set(item, index * 32 + 32 - item.length);
    offset = end;
  }
  if (offset !== signature.length) throw Error('签名格式无效');
  return result;
}

const query = new URLSearchParams(location.search);
if (query.has('endpoint')) connect(query.get('endpoint'), query.get('share'));
else void listEndpoints();
addEventListener('pagehide', () => active?.close());
