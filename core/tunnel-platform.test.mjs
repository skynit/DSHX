import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer as httpsServer } from 'node:https';
import os from 'node:os';
import path from 'node:path';
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

async function fixture(t, options = {}) {
  const iceServers = [{ urls: ['stun:test.invalid:3478'] }];
  const server = createTunnelPlatform({ iceServers, ...options }).listen(0, '127.0.0.1');
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
  const connect = async (peer, token, checkServers = servers => assert.deepEqual(servers, iceServers)) => {
    const channel = socket(token);
    await once(channel, 'open');
    const frame = send(channel, 'register', { routes: [{ id: peer.profile.endpointId }] });
    const ack = await receive(channel);
    assert.equal(ack.type, 'register_ok');
    assert.equal(ack.reply_to, frame.message_id);
    checkServers(ack.payload.ice_servers);
    return channel;
  };
  const browser = () => {
    const channel = new WebSocket(`${base.replace('http:', 'ws:')}/api/v1/dshx/web/signaling`, {
      origin: 'https://web.example.test', headers: { Host: 'web.example.test' },
    });
    clients.push(channel);
    return channel;
  };
  return { request, registration, publish, socket, connect, browser, server };
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

test('browser guests only signal supported public web shares and never enter the chat directory', { timeout: 8000 }, async t => {
  const platform = await fixture(t, { webOrigin: 'https://web.example.test', appDomain: 'apps.example.test' });
  const publisher = identity('网页发布端');
  const old = identity('旧版本');
  const row = await platform.publish(publisher);
  const older = await platform.publish(old);
  const [destination] = await Promise.all([platform.connect(publisher, row.registrationToken), platform.connect(old, older.registrationToken)]);
  const guest = platform.browser();
  const welcome = await receive(guest);
  const visitor = { profile: { endpointId: welcome.destination.id } };
  assert.equal(welcome.type, 'web_ready');
  assert.deepEqual(welcome.payload.ice_servers, [{ urls: ['stun:test.invalid:3478'] }]);
  const offer = { description: { type: 'offer', sdp: 'offer' } };
  send(guest, 'offer', offer, target(publisher));
  assert.equal((await receive(guest)).error.code, 'invalid_signal');
  send(guest, 'web_offer', offer, target(old));
  assert.equal((await receive(guest)).error.code, 'unavailable');
  send(guest, 'web_offer', offer, target(publisher));
  assert.equal((await receive(guest)).error.code, 'unavailable');
  // 以同一个信令连接上的响应为同步点，确认状态已更新且没有重新注册。
  async function publishWebStatus(enabled) {
    send(destination, 'web_status', { enabled });
    send(destination, 'web_close', {}, target(visitor));
    assert.equal((await receive(destination)).error.code, 'unknown_session');
  }
  await publishWebStatus(true);
  const request = target(publisher);
  send(guest, 'web_offer', offer, request);
  const routed = await receive(destination);
  assert.equal(routed.type, 'web_offer');
  assert.equal(routed.source.id, visitor.profile.endpointId);
  const answer = { description: { type: 'answer', sdp: 'answer' }, proof: 'cHVibGlj.c2lnbmF0dXJl' };
  send(destination, 'answer', answer, target(visitor, request.session_id));
  assert.equal((await receive(destination)).error.code, 'unknown_session');
  send(destination, 'web_answer', answer, target(visitor, request.session_id));
  assert.deepEqual((await receive(guest)).payload, answer);
  send(guest, 'web_candidate', { candidate: 'candidate:1 1 udp 2122260223 192.0.2.6 4000 typ relay', sdp_mid: '0', sdp_mline_index: 0 }, request);
  assert.equal((await receive(guest)).error.code, 'invalid_signal');
  const { items } = await (await platform.request(directory)).json();
  assert.equal(items.length, 2);
  assert.deepEqual(items.find(item => item.endpointId === publisher.profile.endpointId).capabilities, ['text', 'web-share']);
  await publishWebStatus(false);
  send(guest, 'web_offer', offer, target(publisher));
  assert.equal((await receive(guest)).error.code, 'unavailable');
  const { items: updated } = await (await platform.request(directory)).json();
  assert.deepEqual(updated.find(item => item.endpointId === publisher.profile.endpointId).capabilities, ['text']);
});

test('real Chromium loads and writes a shared app through Service Worker and a verified direct channel', {
  skip: !process.env.DSHX_PLAYWRIGHT_MODULE, timeout: 45_000,
}, async t => {
  const certDir = mkdtempSync(path.join(os.tmpdir(), 'dshx-web-test-'));
  t.after(() => rmSync(certDir, { recursive: true, force: true }));
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=portal.test',
    '-keyout', path.join(certDir, 'key.pem'), '-out', path.join(certDir, 'cert.pem')], { stdio: 'ignore' });
  const tls = httpsServer({ key: readFileSync(path.join(certDir, 'key.pem')), cert: readFileSync(path.join(certDir, 'cert.pem')) });
  tls.listen(0, '127.0.0.1');
  await once(tls, 'listening');
  const origin = `https://portal.test:${tls.address().port}`;
  const platform = await fixture(t, { webOrigin: origin, appDomain: 'apps.test', iceServers: [] });
  tls.on('request', (req, res) => platform.server.emit('request', req, res));
  tls.on('upgrade', (...args) => platform.server.emit('upgrade', ...args));
  t.after(() => { tls.closeAllConnections(); tls.close(); });
  const publisher = identity('真实浏览器测试');
  const registration = await platform.publish(publisher, { webShare: true });
  const publisherSocket = await platform.connect(publisher, registration.registrationToken, servers => assert.deepEqual(servers, []));
  const { chromium } = await import(process.env.DSHX_PLAYWRIGHT_MODULE);
  const browser = await chromium.launch({ executablePath: process.env.DSHX_CHROME_PATH, headless: true, args: [
    '--ignore-certificate-errors', '--disable-features=WebRtcHideLocalIpsWithMdns', '--no-proxy-server',
    '--host-resolver-rules=MAP portal.test 127.0.0.1, MAP *.apps.test 127.0.0.1',
  ] });
  t.after(() => browser.close());
  const context = await browser.newContext({ acceptDownloads: true });
  const publishing = await context.newPage();
  await publishing.goto('about:blank');
  const share = randomUUID();
  await publishing.evaluate(({ share }) => {
    const encoder = new TextEncoder();
    const peer = new RTCPeerConnection({ iceServers: [] });
    const channel = peer.createDataChannel('dshx-web/1', { negotiated: true, id: 0, ordered: true, protocol: 'dshx-web/1' });
    channel.binaryType = 'arraybuffer';
    const requests = new Map();
    const reply = value => channel.send(encoder.encode(JSON.stringify(value)));
    const report = (request, status, headers, body) => {
      reply({ type: 'headers', id: request.id, status, headers });
      if (body == null) { reply({ type: 'done', id: request.id }); requests.delete(request.id); }
      else reply({ type: 'chunk', id: request.id, bytes: btoa(body) });
    };
    channel.onmessage = ({ data }) => {
      const request = JSON.parse(new TextDecoder().decode(data));
      const pending = requests.get(request.id);
      switch (request.type) {
        case 'list': reply({ type: 'list', items: [{ id: share, name: '测试网站', path: '/' }] }); break;
        case 'open':
          requests.set(request.id, { ...request, body: '' });
          if (request.share !== share) { reply({ type: 'error', id: request.id }); return; }
          if (request.upload) reply({ type: 'upload-ready', id: request.id });
          else if (request.path === '/data') report(request, 200, [['Content-Type', 'text/plain']], 'fixtures work');
          else if (request.path === '/redirect') report(request, 302, [['Location', '/data']], null);
          else if (request.path === '/file') report(request, 200, [['Content-Type', 'text/plain'],
            ['Content-Disposition', 'attachment; filename="fixture.txt"']], 'download');
          else if (request.path === '/range') report(request, 206, [['Content-Type', 'text/plain'], ['Content-Range', 'bytes 1-3/4']], 'BCD');
          else report(request, 200, [['Content-Type', 'text/html']],
            '<!doctype html><h1>Shared application</h1><a href="/file">Download</a><a href="/redirect">Redirect</a>');
          break;
        case 'upload': pending.body += atob(request.bytes); reply({ type: 'upload-ack', id: request.id }); break;
        case 'upload-done': report(request, 200, [['Content-Type', 'text/plain']], pending.body); break;
        case 'socket': reply({ type: 'socket-ready', id: request.id, protocol: 'echo' }); break;
        case 'socket-send': reply({ type: 'socket-message', id: request.id, text: request.text }); break;
        case 'socket-close': reply({ type: 'socket-close', id: request.id, code: 1000 }); break;
        case 'ack': if (pending) { reply({ type: 'done', id: request.id }); requests.delete(request.id); } break;
      }
    };
    window.publisher = { peer };
  }, { share });
  let incoming = Promise.resolve();
  publisherSocket.on('message', raw => {
    incoming = incoming.then(async () => {
      const frame = JSON.parse(raw);
      const recipient = { session_id: frame.session_id, destination: frame.source };
      if (frame.type === 'web_offer') {
        const answer = await publishing.evaluate(async offer => {
          const { peer } = window.publisher;
          await peer.setRemoteDescription({ type: 'offer', sdp: offer });
          await peer.setLocalDescription(await peer.createAnswer());
          if (peer.iceGatheringState !== 'complete') await new Promise(resolve => {
            const changed = () => {
              if (peer.iceGatheringState !== 'complete') return;
              peer.removeEventListener('icegatheringstatechange', changed);
              resolve();
            };
            peer.addEventListener('icegatheringstatechange', changed);
          });
          return peer.localDescription.toJSON();
        }, frame.payload.description.sdp);
        const text = `dshx-web/1\n${frame.session_id}\n${frame.source.id}\n${publisher.profile.endpointId}\n${frame.payload.description.sdp}\n${answer.sdp}`;
        const proof = `${Buffer.from(publisher.profile.publicKey, 'base64').toString('base64url')}.${sign('sha256', Buffer.from(text), publisher.privateKey).toString('base64url')}`;
        send(publisherSocket, 'web_answer', { description: answer, proof }, recipient);
      }
      if (frame.type === 'web_candidate') await publishing.evaluate(candidate => window.publisher.peer.addIceCandidate(candidate), {
        candidate: frame.payload.candidate, sdpMid: frame.payload.sdp_mid, sdpMLineIndex: frame.payload.sdp_mline_index,
      });
    }).catch(error => t.diagnostic(error.stack));
  });
  const visitor = await context.newPage();
  visitor.on('pageerror', error => t.diagnostic(error.stack));
  await visitor.goto(`${origin}/web/?endpoint=${publisher.profile.endpointId}&share=${share}`);
  const app = visitor.frameLocator('#viewer > iframe').frameLocator('iframe');
  try { await app.getByRole('heading', { name: 'Shared application' }).waitFor({ timeout: 30_000 }); }
  catch (error) { t.diagnostic(`访客状态：${await visitor.locator('#status').textContent()}`); throw error; }
  const result = await app.locator('body').evaluate(async () => {
    const plain = await fetch('/data').then(r => r.text());
    const upload = await fetch('/submit', { method: 'POST', body: 'x'.repeat(20_000) }).then(r => r.text());
    const range = await fetch('/range', { headers: { Range: 'bytes=1-3' } });
    const echo = await new Promise((resolve, reject) => {
      const ws = new WebSocket('/echo', 'echo');
      ws.onerror = () => reject(Error('WebSocket failed'));
      ws.onopen = () => ws.send('ping');
      ws.onmessage = event => { resolve(event.data); ws.close(); };
      setTimeout(() => reject(Error('WebSocket timed out')), 5000);
    });
    return { plain, upload: upload.length, echo, range: range.status, portion: await range.text() };
  });
  assert.deepEqual(result, { plain: 'fixtures work', upload: 20_000, echo: 'ping', range: 206, portion: 'BCD' });
  const download = visitor.waitForEvent('download', { timeout: 5000 });
  await app.getByRole('link', { name: 'Download' }).click();
  assert.equal((await download).suggestedFilename(), 'fixture.txt');
  await app.getByRole('link', { name: 'Redirect' }).click();
  await app.getByText('fixtures work').waitFor({ timeout: 5000 });
  publisherSocket.close();
  await visitor.getByText('端点已停止网页访问').waitFor({ timeout: 5000 });
});

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

test('tunnel platform issues endpoint-specific temporary TURN credentials without exposing the secret', { timeout: 8000 }, async t => {
  const secret = 'test-only-shared-secret-with-32-characters';
  const urls = ['turn:turn.test.invalid:3478?transport=udp', 'turn:turn.test.invalid:3478?transport=tcp'];
  const platform = await fixture(t, { turn: { urls, secret, ttlSeconds: 120 } });
  const a = identity('中继端点 A'), b = identity('中继端点 B');
  const started = Math.floor(Date.now() / 1000);
  const row = await platform.publish(a);
  const checkServers = servers => {
    assert.deepEqual(servers[0], { urls: ['stun:test.invalid:3478'] });
    assert.equal(servers.length, 2);
    const server = servers[1];
    const [expires, endpointId] = server.username.split(':');
    assert.equal(endpointId, a.profile.endpointId);
    assert.ok(Number(expires) >= started + 120 && Number(expires) <= Math.floor(Date.now() / 1000) + 120);
    assert.deepEqual(server.urls, urls);
    return server;
  };
  const server = checkServers(row.iceServers);
  // Node 的 WebCrypto 使用独立接口验证 coturn REST 凭据格式。
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-1' }, false, ['verify']);
  assert.ok(await crypto.subtle.verify('HMAC', key, Buffer.from(server.credential, 'base64'), Buffer.from(server.username)));
  assert.ok(!JSON.stringify(row).includes(secret));
  const other = await platform.publish(b);
  assert.notEqual(other.iceServers[1].username, server.username);
  assert.notEqual(other.iceServers[1].credential, server.credential);
  await platform.connect(a, row.registrationToken, checkServers);
  const listing = await (await platform.request(directory)).text();
  assert.ok(!listing.includes(server.credential) && !listing.includes(secret));
  assert.equal((await platform.request(`${directory}/ice-servers`)).status, 404);
});

test('tunnel platform renews TURN credentials on the existing signaling connection', { timeout: 15000 }, async t => {
  let now = 1795000000000;
  t.mock.method(Date, 'now', () => now);
  const platform = await fixture(t, { turn: {
    urls: ['turn:turn.test.invalid:3478?transport=tcp'], secret: 'test-only-shared-secret-with-32-characters', ttlSeconds: 60,
  } });
  const peer = identity('持续在线端点');
  const row = await platform.publish(peer);
  const socket = platform.socket(row.registrationToken);
  await once(socket, 'open');
  const registration = send(socket, 'register', { routes: [{ id: peer.profile.endpointId }] });
  const initial = await receive(socket);
  const first = initial.payload.ice_servers[1];
  now += 31_000;
  const [raw] = await once(socket, 'message', { signal: AbortSignal.timeout(12_000) });
  const refresh = JSON.parse(raw);
  assert.equal(refresh.type, 'register_ok');
  assert.equal(refresh.reply_to, registration.message_id);
  assert.equal(refresh.payload.connection_id, initial.payload.connection_id);
  assert.deepEqual(refresh.payload.registered_route_ids, [peer.profile.endpointId]);
  const fresh = refresh.payload.ice_servers[1];
  assert.equal(fresh.username, `${Math.floor(now / 1000) + 60}:${peer.profile.endpointId}`);
  assert.notEqual(fresh.credential, first.credential);
  // 刷新没有注销或重新注册端点，原信令仍可路由。
  send(socket, 'offer', { description: { type: 'offer', sdp: 'offer' } }, target(identity('离线目标')));
  assert.equal((await receive(socket)).error.code, 'unavailable');
});

test('tunnel platform rejects incomplete or unbounded TURN configuration', () => {
  const valid = { urls: ['turn:turn.test.invalid:3478'], secret: 'test-only-shared-secret-with-32-characters' };
  for (const change of [{ urls: [] }, { urls: ['https://invalid'] }, { secret: 'short' }, { ttlSeconds: 0 }, { ttlSeconds: 86401 }, { ttlSeconds: 1.5 }]) {
    assert.throws(() => createTunnelPlatform({ turn: { ...valid, ...change } }), /TURN 配置/);
  }
});
