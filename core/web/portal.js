const controls = document.querySelector('main');
const directory = document.querySelector('#directory');
const directoryTitle = document.querySelector('#directory-title');
const directoryStatus = document.querySelector('#directory-status');
const endpoints = document.querySelector('#endpoints');
const refresh = document.querySelector('#refresh');
const endpoint = document.querySelector('#endpoint');
const connectionStatus = document.querySelector('#connection-status');
const back = document.querySelector('#back');
const shares = document.querySelector('#shares');
const itemRow = document.querySelector('#item-row');
const viewer = document.querySelector('#viewer');
const appDomain = document.querySelector('meta[name=dshx-app-domain]').content;
const protocol = 'cwnas-webrtc-signaling';
const uuid = /^[\da-f-]{36}$/;
const candidateType = /^(?:a=)?candidate:\S+\s+\d+\s+\S+\s+\d+\s+\S+\s+\d+\s+typ\s+(\w+)(?:\s|$)/i;
const directKinds = ['host', 'srflx', 'prflx'];
const reserved = /^\/__dshx(?:\/|$)/;
const maxChunk = 12 * 1024;
let active;

function row(parent, text, label, action, online) {
  const line = itemRow.content.firstElementChild.cloneNode(true);
  line.querySelector('.item-name').textContent = text;
  const button = line.querySelector('button');
  button.querySelector('span').textContent = label;
  if (online !== undefined) {
    line.querySelector('.item-icon use').setAttribute('href', '#device-icon');
    const badge = line.querySelector('.availability');
    badge.hidden = false;
    badge.textContent = online ? '在线' : '离线';
    badge.classList.toggle('online', online);
    button.disabled = !online;
  }
  button.addEventListener('click', action);
  parent.append(line);
}

async function listEndpoints(cursor, more) {
  if (refresh.disabled) return;
  refresh.disabled = true;
  const moreButton = more?.querySelector('button');
  if (moreButton) moreButton.disabled = true;
  const url = new URL('/api/v1/dshx/chat/endpoints', location.origin);
  if (cursor) url.searchParams.set('cursor', cursor);
  directoryStatus.textContent = '正在读取端点…';
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw Error();
    const page = await response.json();
    const open = page.items.filter(item => item.capabilities?.includes('web-share'));
    if (cursor) more.remove();
    else endpoints.replaceChildren();
    for (const item of open) row(endpoints, item.displayName || item.endpointId, '查看网页', () => {
      history.pushState(null, '', `/web/?endpoint=${encodeURIComponent(item.endpointId)}`);
      showRoute();
    }, Boolean(item.online));
    if (page.nextCursor) {
      const footer = document.createElement('div');
      footer.className = 'more';
      const button = document.createElement('button');
      button.textContent = '加载更多';
      button.addEventListener('click', () => void listEndpoints(page.nextCursor, footer));
      footer.append(button);
      endpoints.append(footer);
    }
    directoryStatus.textContent = endpoints.childElementCount ? '' : '暂无公开网页';
  } catch {
    directoryStatus.textContent = '无法读取公开端点';
  } finally {
    refresh.disabled = false;
    if (moreButton) moreButton.disabled = false;
  }
}

function show(screen) {
  controls.hidden = screen !== 'controls';
  viewer.hidden = screen !== 'viewer';
}

function showRoute() {
  active?.close();
  active = null;
  shares.replaceChildren();
  shares.hidden = true;
  const query = new URLSearchParams(location.search);
  const hasEndpoint = query.has('endpoint');
  directory.hidden = hasEndpoint;
  endpoint.hidden = !hasEndpoint;
  const endpointId = query.get('endpoint');
  if (uuid.test(endpointId)) {
    const share = query.get('share');
    show(share ? 'blank' : 'controls');
    connect(endpointId, share);
  } else {
    show('controls');
    if (hasEndpoint) setConnectionStatus('端点地址无效', 'error');
    else void listEndpoints();
  }
}

function setConnectionStatus(message, tone = 'neutral') {
  connectionStatus.dataset.tone = tone;
  connectionStatus.textContent = message;
}

function connect(endpointId, initialShare) {
  setConnectionStatus('正在建立直连…');
  const connection = new Peer(endpointId, onFrame, message => {
    if (active !== connection) return;
    const restoreFocus = controls.hidden;
    show('controls');
    shares.hidden = false;
    setConnectionStatus(message, 'error');
    if (restoreFocus) back.focus();
  }, message => { if (active === connection) setConnectionStatus(message); });
  active = connection;
  let itemCount = 0;
  let selected;
  function onFrame(frame) {
    if (active !== connection || frame.type !== 'list' || !Array.isArray(frame.items)) return;
    for (const item of frame.items.slice(0, 32)) {
      if (!uuid.test(item.id) || typeof item.name !== 'string' || !safePath(item.path)) continue;
      itemCount++;
      row(shares, item.name, '打开网页', () => {
        const url = new URL(location.href);
        if (url.searchParams.get('share') !== item.id) {
          url.searchParams.set('share', item.id);
          history.pushState(null, '', url);
        }
        if (connection.closed) showRoute();
        else void open(item);
      });
      if (item.id === initialShare) selected = item;
    }
    if (frame.done === false) return;
    if (selected) void open(selected);
    else {
      show('controls');
      shares.hidden = false;
      if (initialShare) setConnectionStatus('找不到指定的网页', 'error');
      else if (itemCount) setConnectionStatus(connection.connectedLabel, connection.attempt.relay ? 'relay' : 'direct');
      else setConnectionStatus('没有可访问的网页');
    }
  }
  async function open(item) {
    shares.hidden = true;
    show('blank');
    try {
      await connection.view(item);
      if (active === connection && !connection.closed) show('viewer');
    } catch {
      if (active !== connection || connection.closed) return;
      connection.disposeView();
      show('controls');
      shares.hidden = false;
      setConnectionStatus('无法初始化网页入口', 'error');
      back.focus();
    }
  }
}

function safePath(value) {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') &&
    !/[\\\x00-\x20]/.test(value) && !reserved.test(new URL(value, location.origin).pathname);
}

class Peer {
  requests = new Map();
  closed = false;

  constructor(endpoint, onFrame, onError, onStatus) {
    this.endpoint = endpoint;
    this.onFrame = onFrame;
    this.onError = onError;
    this.onStatus = onStatus;
    this.socket = new WebSocket(`${location.origin.replace(/^https:/, 'wss:')}/api/v1/dshx/web/signaling`);
    this.socket.onmessage = ({ data }) => {
      this.signals = (this.signals ?? Promise.resolve()).then(async () => {
        const frame = JSON.parse(data);
        try { await this.signal(frame); }
        catch { if (!frame.session_id || this.attempt?.id === frame.session_id) this.fail('网页连接验证失败'); }
      }).catch(() => this.fail('网页信令格式无效'));
    };
    this.socket.onclose = this.socket.onerror = () => this.fail('网页连接已断开');
    this.timer = setTimeout(() => this.fail('无法建立设备直连'), 25_000);
  }

  get connectedLabel() { return this.attempt.relay ? '已通过中继连接' : '已直连'; }

  signalSend(attempt, type, payload) {
    this.socket.send(JSON.stringify({ protocol, version: 1, message_id: crypto.randomUUID(), type, session_id: attempt.id,
      destination: { kind: 'endpoint', id: this.endpoint }, payload }));
  }

  async signal(frame) {
    if (this.closed || frame.protocol !== protocol || frame.version !== 1) return;
    const payload = frame.payload;
    if (frame.type === 'web_ready' && !this.attempt) {
      this.visitor = frame.destination.id;
      this.relayAvailable = payload.relay_available === true;
      const iceServers = payload.ice_servers.flatMap(server => {
        const urls = server.urls.filter(url => /^stuns?:/i.test(url));
        return urls.length ? [{ urls }] : [];
      });
      const attempt = this.attempt = this.newAttempt(false);
      this.setTimer(() => this.connectionFailed(attempt), this.relayAvailable ? 12_000 : 25_000);
      return this.start(attempt, iceServers);
    }
    const attempt = this.attempt;
    if (!attempt || frame.session_id !== attempt.id || frame.destination?.id !== this.visitor) return;
    if (frame.type === 'error' && frame.source?.kind === 'platform') return this.fail('端点当前无法建立网页连接');
    if (frame.type === 'web_relay_ready' && frame.source?.kind === 'platform' && attempt.relay && !attempt.peer) {
      return this.start(attempt, payload.ice_servers);
    }
    const { peer } = attempt;
    if (frame.source?.id !== this.endpoint || !peer) return;
    if (frame.type === 'web_answer' && !peer.remoteDescription) {
      const sdp = payload.description.sdp;
      if (sdp.split(/\r?\n/).some(line => /^a=candidate:/i.test(line) && !this.validCandidate(attempt, line))) throw Error();
      await verifyAnswer(this, attempt, sdp, payload.proof);
      if (!this.current(attempt)) return;
      await peer.setRemoteDescription(payload.description);
      for (const candidate of attempt.remoteCandidates.splice(0)) {
        if (this.current(attempt)) await peer.addIceCandidate(candidate);
      }
    } else if (frame.type === 'web_candidate' && this.validCandidate(attempt, payload.candidate)) {
      const candidate = { candidate: payload.candidate, sdpMid: payload.sdp_mid, sdpMLineIndex: payload.sdp_mline_index };
      if (peer.remoteDescription) await peer.addIceCandidate(candidate);
      else if (attempt.remoteCandidates.length < 128) attempt.remoteCandidates.push(candidate);
    } else if (frame.type === 'web_close') {
      if (payload.connection_failed === true) this.connectionFailed(attempt, false);
      else this.fail('端点已停止网页访问');
    }
  }

  newAttempt(relay) { return { id: crypto.randomUUID(), relay, localCandidates: [], remoteCandidates: [] }; }

  current(attempt) { return !this.closed && this.attempt === attempt; }

  setTimer(callback, timeout) {
    clearTimeout(this.timer);
    this.timer = setTimeout(callback, timeout);
  }

  validCandidate(attempt, candidate) {
    const kind = candidateType.exec(candidate)?.[1].toLowerCase();
    return attempt.relay ? kind === 'relay' : directKinds.includes(kind);
  }

  async start(attempt, iceServers) {
    const peer = attempt.peer = new RTCPeerConnection({ iceServers, iceTransportPolicy: attempt.relay ? 'relay' : 'all' });
    const channel = attempt.channel = peer.createDataChannel('dshx-web/1', { negotiated: true, id: 0, ordered: true, protocol: 'dshx-web/1' });
    peer.onicecandidate = ({ candidate }) => {
      if (!this.current(attempt) || this.socket.readyState !== WebSocket.OPEN || !candidate || !this.validCandidate(attempt, candidate.candidate)) return;
      const data = { candidate: candidate.candidate, sdp_mid: candidate.sdpMid, sdp_mline_index: candidate.sdpMLineIndex };
      if (attempt.offered) this.signalSend(attempt, 'web_candidate', data);
      else if (attempt.localCandidates.length < 128) attempt.localCandidates.push(data);
    };
    peer.onconnectionstatechange = () => {
      if (['failed', 'closed'].includes(peer.connectionState)) this.connectionFailed(attempt);
    };
    channel.binaryType = 'arraybuffer';
    channel.onopen = () => this.checkLink(attempt).catch(() => {
      if (this.current(attempt)) this.fail('无法确认网页连接');
    });
    channel.onclose = () => this.connectionFailed(attempt);
    channel.onmessage = ({ data }) => { if (this.current(attempt)) this.receive(data); };
    const description = await peer.createOffer();
    if (!this.current(attempt)) return;
    await peer.setLocalDescription(description);
    if (!this.current(attempt)) return;
    attempt.offer = description.sdp;
    this.signalSend(attempt, 'web_offer', { description, ...(attempt.relay ? { relay_only: true } : {}) });
    attempt.offered = true;
    for (const candidate of attempt.localCandidates.splice(0)) this.signalSend(attempt, 'web_candidate', candidate);
  }

  connectionFailed(attempt, notifyPeer = true) {
    if (!this.current(attempt)) return;
    if (this.ready) return this.fail('网页连接已断开');
    if (!this.relayAvailable || attempt.relay || !attempt.offered || this.socket.readyState !== WebSocket.OPEN) {
      return this.fail(attempt.relay ? '无法建立中继连接' : '无法建立设备直连');
    }
    // 新会话只重试连接协商。已发送数据请求的连接不走这个分支。
    if (notifyPeer) this.signalSend(attempt, 'web_close', {});
    const relay = this.attempt = this.newAttempt(true);
    attempt.channel?.close();
    attempt.peer?.close();
    this.onStatus('直连未成功，正在尝试中继…');
    this.setTimer(() => this.connectionFailed(relay), 25_000);
    this.signalSend(relay, 'web_relay', {});
  }

  async checkLink(attempt) {
    for (let check = 0; check < 30 && this.current(attempt); check++) {
      const stats = await attempt.peer.getStats();
      if (!this.current(attempt)) return;
      const pair = stats.get([...stats.values()].find(value => value.type === 'transport')?.selectedCandidatePairId);
      const kinds = [stats.get(pair?.localCandidateId)?.candidateType, stats.get(pair?.remoteCandidateId)?.candidateType];
      if (pair?.state === 'succeeded' && (attempt.relay ? kinds.includes('relay') : kinds.every(kind => directKinds.includes(kind)))) {
        this.ready = true;
        this.setTimer(() => this.fail('读取网页列表超时'), 10_000);
        await this.send({ type: 'list' });
        return;
      }
      await sleep(150);
    }
    if (this.current(attempt)) this.fail('无法确认网页连接');
  }

  async send(frame) {
    const channel = this.attempt?.channel;
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
    this.attempt?.channel?.close();
    this.attempt?.peer?.close();
    this.socket.close();
  }
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const unbase64 = text => Uint8Array.from(atob(text.replaceAll('-', '+').replaceAll('_', '/')), char => char.charCodeAt(0));

async function verifyAnswer(peer, attempt, answer, proof) {
  const [keyText, signature] = proof.split('.');
  const keyBytes = unbase64(keyText);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', keyBytes));
  digest[6] = (digest[6] & 15) | 128;
  digest[8] = (digest[8] & 63) | 128;
  const hex = [...digest.slice(0, 16)].map(byte => byte.toString(16).padStart(2, '0')).join('');
  const id = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  if (id !== peer.endpoint) throw Error('端点身份不一致');
  const key = await crypto.subtle.importKey('spki', keyBytes, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
  const transcript = new TextEncoder().encode(`dshx-web/1\n${attempt.id}\n${peer.visitor}\n${peer.endpoint}\n${attempt.offer}\n${answer}`);
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

refresh.addEventListener('click', () => void listEndpoints());
back.addEventListener('click', () => {
  history.replaceState(null, '', '/web/');
  showRoute();
  directoryTitle.focus();
});
showRoute();
addEventListener('popstate', showRoute);
addEventListener('pagehide', () => active?.close());
addEventListener('pageshow', event => { if (event.persisted) showRoute(); });
