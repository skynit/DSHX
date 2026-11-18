import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { once } from 'node:events';
import { test } from 'node:test';
import { WebSocket } from 'ws';
import { createTunnelPlatform } from './tunnel-platform.mjs';

const directory = '/api/v1/dshx/chat/endpoints';
const register = `${directory}/register`;
const signal = 'cwnas-webrtc-signaling';

function identity(displayName) {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const der = publicKey.export({ type: 'spki', format: 'der' });
  const hash = createHash('sha256').update(der).digest();
  hash[6] = (hash[6] & 15) | 128;
  hash[8] = (hash[8] & 63) | 128;
  const hex = hash.toString('hex');
  return { privateKey, profile: {
    endpointId: `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`,
    publicKey: der.toString('base64').replace(/=+$/, ''), displayName, publicIntroduction: `${displayName} 的 dsh`,
    publication: 'published', acceptance: 'accepting', maxAiReplies: 10,
  } };
}

async function fixture(t) {
  const iceServers = [{ urls: ['stun:test.invalid:3478'] }];
  const server = createTunnelPlatform({ iceServers }).listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  const clients = [];
  t.after(async () => {
    await Promise.all(clients.filter(socket => socket.readyState !== WebSocket.CLOSED).map(async socket => {
      const closed = once(socket, 'close');
      socket.terminate();
      await closed;
    }));
    await new Promise(resolve => server.close(resolve));
  });
  const request = (path, init) => fetch(`${base}${path}`, init);
  const registration = async (peer, changes = {}) => {
    const response = await request(`${register}/challenge`, { method: 'POST' });
    assert.equal(response.status, 200);
    const { challenge } = await response.json();
    const body = JSON.stringify({ ...peer.profile, ...changes });
    const proof = sign('sha256', Buffer.from(`dshx-platform-register/1\n${challenge}\n${body}`), peer.privateKey).toString('base64url');
    return { method: 'PUT', headers: { 'X-DSHX-Challenge': challenge, 'X-DSHX-Signature': proof }, body };
  };
  const publish = async (peer, changes) => {
    const response = await request(register, await registration(peer, changes));
    assert.equal(response.status, 200);
    return response.json();
  };
  const socket = token => {
    const channel = new WebSocket(`${base.replace('http:', 'ws:')}/api/v1/signaling`, { headers: { Authorization: `Bearer ${token}` } });
    clients.push(channel);
    return channel;
  };
  const connect = async (peer, token) => {
    const channel = socket(token);
    await once(channel, 'open');
    const frame = send(channel, 'register', { routes: [{ id: peer.profile.endpointId }] });
    const ack = await receive(channel);
    assert.equal(ack.type, 'register_ok');
    assert.equal(ack.reply_to, frame.message_id);
    assert.deepEqual(ack.payload.ice_servers, iceServers);
    return channel;
  };
  return { request, registration, publish, socket, connect };
}

function send(socket, type, payload, extra) {
  const frame = { protocol: signal, version: 1, message_id: randomUUID(), type, payload, ...extra };
  socket.send(JSON.stringify(frame));
  return frame;
}

async function receive(socket) {
  const [raw] = await once(socket, 'message', { signal: AbortSignal.timeout(2000) });
  return JSON.parse(raw);
}

function target(peer, session = randomUUID()) {
  return { session_id: session, destination: { kind: 'endpoint', id: peer.profile.endpointId } };
}

test('tunnel platform rejects tampering, replay and routing as another endpoint', { timeout: 8000 }, async t => {
  const platform = await fixture(t);
  const peer = identity('端点 A');
  const forged = await platform.registration(peer);
  forged.body = forged.body.replace('端点 A', '篡改');
  assert.equal((await platform.request(register, forged)).status, 401);
  const valid = await platform.registration(peer);
  const registered = await platform.request(register, valid);
  assert.equal(registered.status, 200);
  assert.equal((await platform.request(register, valid)).status, 401);
  const { registrationToken } = await registered.json();
  const socket = platform.socket(registrationToken);
  await once(socket, 'open');
  send(socket, 'register', { routes: [{ id: identity('端点 B').profile.endpointId }] });
  await once(socket, 'close');
  const directoryResponse = await platform.request(directory);
  assert.equal((await directoryResponse.json()).items[0].online, false);
});

test('tunnel platform discovers endpoints and relays only bound session signals', { timeout: 8000 }, async t => {
  const platform = await fixture(t);
  const a = identity('端点 A');
  const b = identity('端点 B');
  const aRow = await platform.publish(a);
  const bRow = await platform.publish(b);
  assert.equal(aRow.endpoint.online, false);
  const [source, destination] = await Promise.all([platform.connect(a, aRow.registrationToken), platform.connect(b, bRow.registrationToken)]);
  const found = await platform.request(`${directory}?query=${encodeURIComponent('端点 B')}`);
  const { items } = await found.json();
  assert.deepEqual(items.map(item => [item.endpointId, item.online]), [[b.profile.endpointId, true]]);
  assert.equal(items[0].publicKey, undefined);
  assert.equal(items[0].maxAiReplies, undefined);
  assert.equal(items[0].acceptance, undefined);
  assert.equal(items[0].accepting, true);
  const to = target(b);
  send(source, 'offer', { description: { type: 'offer', sdp: 'offer sdp' }, ignored: 'not forwarded' }, to);
  const offer = await receive(destination);
  assert.equal(offer.source.id, a.profile.endpointId);
  assert.deepEqual(offer.payload, { description: { type: 'offer', sdp: 'offer sdp' } });
  send(destination, 'answer', { description: { type: 'answer', sdp: 'answer sdp' } }, target(a, to.session_id));
  assert.equal((await receive(source)).type, 'answer');
  send(source, 'candidate', { candidate: 'candidate:1', sdp_mid: '0', sdp_mline_index: 0 }, to);
  assert.equal((await receive(destination)).type, 'candidate');
  send(source, 'message', { text: 'no relay' }, to);
  assert.equal((await receive(source)).error.code, 'invalid_signal');
  send(destination, 'session_close', {}, target(a, to.session_id));
  assert.equal((await receive(source)).type, 'session_close');
  send(source, 'candidate', { candidate: 'candidate:1', sdp_mline_index: 0 }, to);
  assert.equal((await receive(source)).error.code, 'unknown_session');
});

test('tunnel platform rejects an older registration after a newer update', { timeout: 8000 }, async t => {
  const platform = await fixture(t);
  const peer = identity('可更新端点');
  const stale = await platform.registration(peer, { displayName: '旧资料' });
  const current = await platform.registration(peer, { displayName: '新资料' });
  assert.equal((await platform.request(register, current)).status, 200);
  assert.equal((await platform.request(register, stale)).status, 409);
});

test('tunnel platform removes hidden endpoints from discovery and rejects known-ID offers', { timeout: 8000 }, async t => {
  const platform = await fixture(t);
  const a = identity('公开端点');
  const b = identity('隐身端点');
  const aRow = await platform.publish(a);
  const bRow = await platform.publish(b);
  const [source, destination] = await Promise.all([platform.connect(a, aRow.registrationToken), platform.connect(b, bRow.registrationToken)]);
  const closed = once(destination, 'close');
  const hidden = await platform.publish(b, { publication: 'hidden' });
  await closed;
  await platform.connect(b, hidden.registrationToken);
  const found = await platform.request(directory);
  assert.deepEqual((await found.json()).items.map(item => item.endpointId), [a.profile.endpointId]);
  send(source, 'offer', { description: { type: 'offer', sdp: 'sdp' } }, target(b));
  assert.equal((await receive(source)).error.code, 'unavailable');
  assert.equal((await platform.request(`${directory}/${b.profile.endpointId}`, { method: 'DELETE', headers: { Authorization: `Bearer ${aRow.registrationToken}` } })).status, 401);
  assert.equal((await platform.request(`${directory}/${b.profile.endpointId}`, { method: 'DELETE', headers: { Authorization: `Bearer ${hidden.registrationToken}` } })).status, 204);
});
