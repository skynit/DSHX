import { createHash, createPublicKey, randomBytes, randomUUID, verify } from 'node:crypto';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { WebSocket, WebSocketServer } from 'ws';

const directory = '/api/v1/dshx/chat/endpoints';
const register = `${directory}/register`;
const signaling = '/api/v1/signaling';
const protocol = 'cwnas-webrtc-signaling';
const routeId = /^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const sessionId = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const maxSignal = 64 * 1024;
const nonce = () => randomBytes(32).toString('base64url');
const validText = (value, max) => typeof value === 'string' && value.length <= max && !value.includes('\0');
const isObject = value => value != null && typeof value === 'object' && !Array.isArray(value);
const endpoint = id => ({ kind: 'endpoint', id });
const bearer = req => req.headers.authorization?.match(/^Bearer ([\w-]{43})$/)?.[1];

function reply(res, status, data) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(data === undefined ? undefined : JSON.stringify(data));
}

function error(res, status, code) { reply(res, status, { error: { code } }); }

async function readBody(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 16 * 1024) throw new Error('注册数据过大。');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function verifyProfile(raw, challenge, proof) {
  const body = JSON.parse(raw);
  if (!routeId.test(body.endpointId) || !validText(body.publicKey, 128) ||
      !validText(body.displayName, 80) || !validText(body.publicIntroduction, 2000) ||
      !['published', 'hidden'].includes(body.publication) || !['accepting', 'paused'].includes(body.acceptance) ||
      !Number.isInteger(body.maxAiReplies) || body.maxAiReplies < 0 || body.maxAiReplies > 1000) return null;
  const der = Buffer.from(body.publicKey, 'base64');
  const key = createPublicKey({ key: der, format: 'der', type: 'spki' });
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails.namedCurve !== 'prime256v1' ||
      !der.equals(key.export({ format: 'der', type: 'spki' }))) return null;
  const digest = createHash('sha256').update(der).digest();
  digest[6] = (digest[6] & 15) | 128;
  digest[8] = (digest[8] & 63) | 128;
  const hex = digest.subarray(0, 16).toString('hex');
  const id = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  const signed = Buffer.concat([Buffer.from(`dshx-platform-register/1\n${challenge}\n`), raw]);
  if (id !== body.endpointId || !verify('sha256', signed, key, Buffer.from(proof, 'base64url'))) return null;
  const { displayName, publicIntroduction, publication, acceptance, maxAiReplies } = body;
  return { endpointId: id, displayName, publicIntroduction, publication, acceptance, maxAiReplies, capabilities: ['text'] };
}

/** 只保存端点状态和信令路由；从不接收或中转聊天正文。 */
export function createTunnelPlatform({ iceServers = [] } = {}) {
  const profiles = new Map();
  const challenges = new Map();
  const tokens = new Map();
  const sessions = new Map();
  const sockets = new WebSocketServer({ noServer: true, maxPayload: maxSignal, perMessageDeflate: false });
  const describe = profile => ({ ...profile.info, online: profile.socket?.readyState === WebSocket.OPEN });

  const service = createServer({ requestTimeout: 10_000, headersTimeout: 5000 }, (req, res) => {
    handleRequest(req, res).catch(() => {
      if (!res.headersSent && !res.destroyed) error(res, 400, 'invalid_request');
    });
  });

  async function handleRequest(req, res) {
    const url = new URL(req.url, 'http://localhost');
    if (req.method === 'POST' && url.pathname === `${register}/challenge`) {
      if (challenges.size >= 4096) return error(res, 429, 'busy');
      const challenge = nonce();
      challenges.set(challenge, Date.now() + 30_000);
      return reply(res, 200, { challenge });
    }
    if (req.method === 'PUT' && url.pathname === register) {
      const challenge = req.headers['x-dshx-challenge'];
      const proof = req.headers['x-dshx-signature'];
      const expires = challenges.get(challenge);
      if (!expires || expires <= Date.now() || !validText(proof, 112)) return error(res, 401, 'invalid_proof');
      challenges.delete(challenge);
      const info = verifyProfile(await readBody(req), challenge, proof);
      if (!info) return error(res, 401, 'invalid_proof');
      const old = profiles.get(info.endpointId);
      if (!old && profiles.size >= 4096) return error(res, 429, 'directory_full');
      if (old) invalidate(old);
      const profile = { info, token: nonce(), expires: Date.now() + 60_000, updated: Date.now(), sessions: new Set() };
      profiles.set(info.endpointId, profile);
      tokens.set(profile.token, profile);
      // 客户端从 HTTPS 平台地址派生同源 WSS 地址，不信任反代传入的 Host。
      return reply(res, 200, { endpoint: describe(profile), registrationToken: profile.token, iceServers });
    }
    if (req.method === 'GET' && url.pathname === directory) {
      const query = (url.searchParams.get('query') ?? '').slice(0, 80).toLowerCase();
      const limit = Number(url.searchParams.get('limit') ?? 50);
      const cursor = url.searchParams.get('cursor') ?? '';
      if (!Number.isInteger(limit) || limit < 1 || limit > 100 || (cursor && !routeId.test(cursor))) return error(res, 400, 'invalid_query');
      const ids = [...profiles.keys()].sort().filter(id => {
        const info = profiles.get(id).info;
        return id > cursor && info.publication === 'published' &&
          [info.endpointId, info.displayName, info.publicIntroduction].some(text => text.toLowerCase().includes(query));
      });
      const items = ids.slice(0, limit).map(id => describe(profiles.get(id)));
      return reply(res, 200, { items, ...(ids.length > limit ? { nextCursor: items.at(-1).endpointId } : {}) });
    }
    if (req.method === 'DELETE' && url.pathname.startsWith(`${directory}/`)) {
      const profile = tokens.get(bearer(req));
      if (!profile || !activeToken(profile) || url.pathname !== `${directory}/${profile.info.endpointId}`) return error(res, 401, 'unauthorized');
      invalidate(profile);
      profiles.delete(profile.info.endpointId);
      return reply(res, 204);
    }
    return error(res, 404, 'not_found');
  }

  function activeToken(profile) { return profile.expires > Date.now() || profile.socket?.readyState === WebSocket.OPEN; }

  function send(socket, frame) {
    if (socket?.readyState !== WebSocket.OPEN) return false;
    if (socket.bufferedAmount > 256 * 1024) { socket.terminate(); return false; }
    socket.send(JSON.stringify(frame));
    return true;
  }

  function platformFrame(profile, type, message, extra) {
    return { protocol, version: 1, type, message_id: randomUUID(), reply_to: message.message_id,
      session_id: message.session_id, source: { kind: 'platform', id: 'dshx' }, destination: endpoint(profile.info.endpointId), ...extra };
  }

  function removeSession(id) {
    const link = sessions.get(id);
    if (!link) return;
    sessions.delete(id);
    link.from.sessions.delete(id);
    link.to.sessions.delete(id);
  }

  function disconnect(profile, socket) {
    if (profile.socket !== socket) return;
    profile.socket = undefined;
    profile.updated = Date.now();
    for (const id of profile.sessions) {
      const { from, to, answered } = sessions.get(id);
      const peer = from === profile ? to : from;
      removeSession(id);
      // 信令掉线不应主动切断已协商的数据通道。未完成的连接由信令通知或客户端超时清理。
      if (!answered) send(peer.socket, { protocol, version: 1, type: 'session_close', message_id: randomUUID(), session_id: id,
        source: endpoint(profile.info.endpointId), destination: endpoint(peer.info.endpointId), payload: {} });
    }
  }

  function invalidate(profile) {
    tokens.delete(profile.token);
    if (!profile.socket) return;
    const socket = profile.socket;
    disconnect(profile, socket);
    socket.close(1000, 'registration_changed');
  }

  function route(profile, message) {
    const id = message.session_id;
    const payload = message.payload;
    const target = message.destination?.id;
    const type = message.type;
    const reject = code => send(profile.socket, platformFrame(profile, 'error', message, { error: { code } }));
    if (!sessionId.test(id) || message.destination?.kind !== 'endpoint' || !routeId.test(target) ||
        profile.info.endpointId === target || (message.source && (message.source.kind !== 'endpoint' || message.source.id !== profile.info.endpointId)) ||
        !isObject(payload)) return reject('invalid_signal');
    const peer = profiles.get(target);
    const bound = sessions.get(id);
    if (type === 'offer') {
      if (!validText(payload.description?.sdp, 60 * 1024) || payload.description.type !== 'offer' || !payload.description.sdp) return reject('invalid_signal');
      if (peer?.info.publication !== 'published' || peer.socket?.readyState !== WebSocket.OPEN) return reject('unavailable');
      // 暂停接收还要允许客户端识别并放行旧会话重连，所以接收策略由端点裁决。
      if (bound || profile.sessions.size >= 64 || peer.sessions.size >= 64) return reject('busy');
      sessions.set(id, { from: profile, to: peer });
      profile.sessions.add(id);
      peer.sessions.add(id);
    } else {
      if (!bound || !(bound.from === profile && bound.to === peer || bound.to === profile && bound.from === peer)) return reject('unknown_session');
      if (type === 'answer') {
        if (bound.to !== profile || bound.answered || payload.description?.type !== 'answer' ||
            !validText(payload.description.sdp, 60 * 1024) || !payload.description.sdp) return reject('invalid_signal');
        bound.answered = true;
      } else if (type === 'candidate') {
        if (!validText(payload.candidate, 4096) || !payload.candidate ||
            (payload.sdp_mid != null && !validText(payload.sdp_mid, 256)) ||
            !Number.isInteger(payload.sdp_mline_index) || payload.sdp_mline_index < 0) return reject('invalid_signal');
      } else if (type === 'session_close') removeSession(id);
      else return reject('invalid_signal');
    }
    const clean = type === 'offer' || type === 'answer' ? { description: { type, sdp: payload.description.sdp } } :
      type === 'candidate' ? { candidate: payload.candidate, sdp_mid: payload.sdp_mid, sdp_mline_index: payload.sdp_mline_index } : {};
    if (!send(peer.socket, { protocol, version: 1, type, message_id: message.message_id, session_id: id,
      source: endpoint(profile.info.endpointId), destination: endpoint(target), payload: clean })) {
      removeSession(id);
      reject('unavailable');
    }
  }

  service.on('upgrade', (req, socket, head) => {
    const profile = tokens.get(bearer(req));
    if (req.url !== signaling || !profile || !activeToken(profile) || profile.pending || profile.socket) {
      socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
      return;
    }
    sockets.handleUpgrade(req, socket, head, ws => {
      profile.pending = ws;
      acceptSocket(profile, ws);
    });
  });

  function acceptSocket(profile, ws) {
    const timer = setTimeout(() => ws.terminate(), 5000);
    let alive = true;
    let count = 0;
    const pulse = setInterval(() => {
      if (!alive) return ws.terminate();
      alive = false;
      ws.ping();
      count = 0;
    }, 10_000);
    ws.on('pong', () => { alive = true; });
    ws.on('error', () => ws.terminate());
    ws.on('close', () => {
      clearTimeout(timer);
      clearInterval(pulse);
      profile.pending = undefined;
      disconnect(profile, ws);
    });
    ws.on('message', (raw, binary) => {
      try {
        if (binary || ++count > 512 || tokens.get(profile.token) !== profile) return ws.terminate();
        const frame = JSON.parse(raw);
        if (frame.protocol !== protocol || frame.version !== 1 || !validText(frame.message_id, 128) || !frame.message_id) return ws.terminate();
        if (profile.socket === ws) return route(profile, frame);
        const routes = frame.payload?.routes;
        if (frame.type !== 'register' || !Array.isArray(routes) || routes.length !== 1 || routes[0]?.id !== profile.info.endpointId) return ws.terminate();
        clearTimeout(timer);
        profile.socket = ws;
        send(ws, platformFrame(profile, 'register_ok', frame, { payload: { connection_id: randomUUID(),
          registered_route_ids: [profile.info.endpointId], ice_servers: iceServers } }));
      } catch { ws.terminate(); }
    });
  }

  const sweep = setInterval(() => {
    const now = Date.now();
    for (const [challenge, expires] of challenges) if (expires <= now) challenges.delete(challenge);
    for (const [token, profile] of tokens) if (!activeToken(profile)) tokens.delete(token);
    for (const [id, profile] of profiles) if (!profile.socket && now - profile.updated > 7 * 24 * 3600_000) profiles.delete(id);
  }, 10_000).unref();
  service.on('close', () => { clearInterval(sweep); sockets.close(); });
  return service;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const iceServers = JSON.parse(process.env.DSHX_TUNNEL_ICE_SERVERS ?? '[]');
  if (!Array.isArray(iceServers)) throw new Error('DSHX_TUNNEL_ICE_SERVERS 必须是 JSON 数组。');
  const port = Number(process.env.DSHX_TUNNEL_PORT ?? 9082);
  createTunnelPlatform({ iceServers }).listen(port, '127.0.0.1', () => console.log(`DSHX 穿透平台监听 127.0.0.1:${port}`));
}
