// Run on Android with DSHX_CORE pointing at the installed, verified core.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdtemp, open, writeFile, readFile, readdir, rm, link, stat, mkdir, symlink, rename, chmod, realpath } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { createServer as createHttpServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const core = process.env.DSHX_CORE;
assert.ok(core, 'DSHX_CORE must name the installed core');
const require = createRequire(join(core, 'lib/node_modules/package.json'));
const native = require(join(core, 'native.node'));
const { runTool, inFileTool } = await import(pathToFileURL(join(core, 'policy.mjs')));
const { PolicyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
const { callPhone, phoneContent } = await import(pathToFileURL(join(core, 'phone.mjs')));
const load = name => import(pathToFileURL(require.resolve(name)));
const directory = await mkdtemp(join(tmpdir(), 'dshx-native-test-'));
after(() => rm(directory, { recursive: true, force: true }));
const workspace = await mkdtemp(join(process.env.DSHX_WORKSPACE, 'dshx-test-'));
after(() => rm(workspace, { recursive: true, force: true }));

test('Android assistant persona updates the next assembly including complete scoped presets and literal braces', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { createScope } = await load('@deepseek-ai/dsh-scope');
  const { default: SystemPrompt, renderPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const dir = await mkdtemp(join(directory, 'assistant-persona-'));
  const previous = process.env.DSHX_ASSISTANT_DIR;
  process.env.DSHX_ASSISTANT_DIR = dir;
  const ctx = new Context();
  try {
    await ctx.plugin(SystemPrompt, { personaPrefix: 'upstream persona' });
    const original = await ctx.systemPrompt.assemble();
    assert.match(renderPrompt(original), /upstream persona/);
    assert.equal(original.sections.some(section => section.name === 'android:assistant'), false);
    const settings = { version: 1, name: '小禾', identity: '手机助手', prompt: '保留字面 {{name}}，使用中文' };
    await writeFile(join(dir, 'settings.json'), JSON.stringify(settings));
    const current = await ctx.systemPrompt.assemble();
    assert.match(renderPrompt(current), /小禾/);
    assert.match(renderPrompt(current), /字面 \{\{name\}\}/);
    assert.equal(renderPrompt(original).includes('小禾'), false, 'accepted/history snapshots stay unchanged');
    const key = {};
    const scope = createScope(ctx, key);
    try {
      await scope.ctx.plugin(await load('@deepseek-ai/dsh-persona'), { prefix: 'complete preset', complete: true });
      const complete = await ctx.systemPrompt.assemble({ scope: key });
      assert.deepEqual(complete.sections.map(section => section.name), ['deployment:persona-prefix', 'android:assistant']);
      assert.match(renderPrompt(complete), /complete preset.*小禾/s);
    } finally { await scope.dispose(); }
    await writeFile(join(dir, 'settings.json'), JSON.stringify({ ...settings, name: '新的名字' }));
    assert.match(renderPrompt(await ctx.systemPrompt.assemble()), /新的名字/);
    await writeFile(join(dir, 'settings.json'), JSON.stringify({ version: 1, name: '', identity: '', prompt: '' }));
    assert.deepEqual(await ctx.systemPrompt.assemble(), original);
    await writeFile(join(dir, 'settings.json'), JSON.stringify({ ...settings, name: 'x'.repeat(81) }));
    await assert.rejects(ctx.systemPrompt.assemble(), /格式或长度无效/);
    await writeFile(join(dir, 'settings.json'), '{broken');
    await assert.rejects(ctx.systemPrompt.assemble(), /无法读取/);
  } finally {
    if (previous === undefined) delete process.env.DSHX_ASSISTANT_DIR;
    else process.env.DSHX_ASSISTANT_DIR = previous;
    await ctx.fiber.dispose();
  }
});

test('Android assistant usage records concurrent snapshots once and preserves partial failed usage without content', async () => {
  const { usageStream } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const dir = await mkdtemp(join(directory, 'assistant-usage-'));
  const collect = async stream => { const chunks = []; for await (const chunk of stream) chunks.push(chunk); return chunks; };
  const options = { provider: 'fixture', model: 'single', sessionId: 'test-session', messages: [{ text: 'private prompt' }], apiKey: 'private-key' };
  const usage = { inputTokens: 10, outputTokens: 6, cacheReadTokens: 4, cacheWriteTokens: 2, reasoningTokens: 3, totalTokens: 22 };
  const good = [{ type: 'usage', usage }, { type: 'usage', usage }, { type: 'finish', reason: { kind: 'stop' } }];
  const bad = [{ type: 'usage', usage: { outputTokens: 7, inputTokens: -1, extra: 'private response' } },
    { type: 'finish', reason: { kind: 'error', failure: { message: 'private error' } } }];
  const run = (chunks, extra) => collect(usageStream({ ...options, ...extra }, async function* () {
    for (const chunk of chunks) { await delay(1); yield chunk; }
  }, dir));
  assert.deepEqual(await Promise.all([run(good), run(bad, { purpose: 'compaction' })]), [good, bad]);
  const text = await readFile(join(dir, 'usage.jsonl'), 'utf8');
  assert.doesNotMatch(text, /private|extra|apiKey|messages/);
  const rows = text.trim().split('\n').map(JSON.parse);
  const starts = rows.filter(row => row.event === 'start');
  assert.equal(starts.length, 2);
  assert.deepEqual(starts.map(row => row.purpose), ['other', 'compaction']);
  for (const start of starts) assert.equal(rows.filter(row => row.id === start.id && row.event === 'finish').length, 1);
  assert.deepEqual(rows.filter(row => row.event === 'usage').map(row => row.usage), [usage, { outputTokens: 7 }]);
  assert.deepEqual(rows.filter(row => row.event === 'finish').map(row => row.outcome).sort(), ['failed', 'success']);
});

test('Android assistant usage distinguishes missing zero cancelled thrown and early closed calls', async () => {
  const { usageStream } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const dir = await mkdtemp(join(directory, 'assistant-outcomes-'));
  const collect = async stream => { for await (const _ of stream) {} };
  const options = { provider: 'fixture', model: 'single' };
  await collect(usageStream(options, async function* () { yield { type: 'finish', reason: { kind: 'stop' } }; }, dir));
  await collect(usageStream(options, async function* () {
    yield { type: 'usage', usage: { inputTokens: 0, outputTokens: 0 } };
    yield { type: 'finish', reason: { kind: 'max-tokens' } };
  }, dir));
  await collect(usageStream(options, async function* () { yield { type: 'finish', reason: { kind: 'aborted' } }; }, dir));
  await assert.rejects(collect(usageStream(options, async function* () {
    yield { type: 'usage', usage: { inputTokens: 2, outputTokens: 1 } };
    throw new Error('fixture network failure');
  }, dir)), /fixture network failure/);
  let closed = false;
  for await (const _ of usageStream(options, async function* () {
    try { yield { type: 'usage', usage: { inputTokens: 3, outputTokens: 2 } }; }
    finally { closed = true; }
  }, dir)) break;
  assert.equal(closed, true);
  const rows = (await readFile(join(dir, 'usage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
  assert.deepEqual(rows.filter(row => row.event === 'finish').map(row => row.outcome), ['success', 'success', 'cancelled', 'failed', 'incomplete']);
  const starts = rows.filter(row => row.event === 'start');
  assert.equal(rows.some(row => row.id === starts[0].id && row.event === 'usage'), false);
  assert.deepEqual(rows.find(row => row.id === starts[1].id && row.event === 'usage').usage, { inputTokens: 0, outputTokens: 0 });
  assert.deepEqual(rows.find(row => row.id === starts[3].id && row.event === 'usage').usage, { inputTokens: 2, outputTokens: 1 });
});

test('Android assistant final usage requires a provider terminal report even when zeros match', async () => {
  const { usageStream, modelFetch, reportProviderUsage } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const dir = await mkdtemp(join(directory, 'assistant-final-'));
  for (const final of [false, true]) {
    for await (const _ of usageStream({ provider: 'fixture', model: 'single' }, async function* () {
      await modelFetch('data:application/json,{}');
      reportProviderUsage({ inputTokens: 2, outputTokens: 0 });
      if (final) reportProviderUsage({ inputTokens: 2, outputTokens: 0 }, true);
      yield { type: 'usage', usage: { inputTokens: 2, outputTokens: 0 } };
      yield { type: 'finish', reason: { kind: final ? 'stop' : 'aborted' } };
    }, dir, true)) {}
  }
  const rows = (await readFile(join(dir, 'usage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
  assert.deepEqual(rows.filter(row => row.event === 'usage').map(row => row.usageFinal), [false, false, true]);
  assert.deepEqual(rows.filter(row => row.event === 'finish').map(row => row.usageFinal), [false, true]);
  assert.ok(rows.filter(row => row.event === 'usage').every(row => row.usage.outputTokens === 0 && row.usage.totalTokens === undefined));
});

test('Android assistant actual LLM boundary records retries prepared title and compaction separately', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: LlmRuntime, LlmAdapter, markAgentLoopRequest } = await load('@deepseek-ai/dsh-llm');
  const { executeSessionTitleLlm } = await load('@deepseek-ai/dsh-session-title-llm');
  const { default: BasicCompactionEngine } = await load('@deepseek-ai/dsh-compaction-basic');
  const dir = await mkdtemp(join(directory, 'assistant-llm-'));
  const previous = process.env.DSHX_ASSISTANT_DIR;
  process.env.DSHX_ASSISTANT_DIR = dir;
  const ctx = new Context();
  let calls = 0;
  class Adapter extends LlmAdapter {
    async *stream() {
      calls++;
      if (calls === 1) throw new Error('fixture first attempt');
      yield { type: 'text-delta', index: 0, text: 'fixture title or summary' };
      yield { type: 'usage', usage: { inputTokens: 10, outputTokens: 4 } };
      yield { type: 'finish', reason: { kind: 'stop' } };
    }
  }
  try {
    await ctx.plugin(LlmRuntime);
    ctx.llm.registerAdapter(['fixture'], new Adapter());
    const options = markAgentLoopRequest({ provider: 'fixture', model: 'single', messages: [], sessionId: 'same-session' });
    const collect = async stream => { const chunks = []; for await (const chunk of stream) chunks.push(chunk); return chunks; };
    assert.equal((await collect(ctx.llm.stream(options))).at(-1).reason.kind, 'error');
    await collect(ctx.llm.stream(options));
    const sessionEvents = [];
    await executeSessionTitleLlm(ctx, { maxInputBytes: 1000, maxOutputTokens: 64, timeoutMs: 1000 }, {
      session: { id: 'same-session', append: (...args) => sessionEvents.push(args) }, route: { provider: 'fixture', model: 'single' },
      signal: new AbortController().signal
    }, 'fixture-title', { system: 'title system', input: 'title input', messageSeqs: [1], selectReasoningEffort: () => undefined });
    assert.equal(sessionEvents[0][0], 'session/title-llm-request');
    const compaction = new BasicCompactionEngine(ctx, { auto: false });
    const summary = await compaction.summarize({ system: '', messages: [] }, {
      options, session: { id: 'same-session', requestHeader: () => undefined, toolHistory: () => [] }
    }, new AbortController().signal);
    assert.equal(summary.summary[0].text, 'fixture title or summary');
    await collect(ctx.llm.stream({ ...options, purpose: 'future-auxiliary' }));
    // Translator-style calls omit purpose and are not main agent-loop requests.
    await collect(ctx.llm.stream({ ...options }));
    const prepared = await ctx.llm.prepareCall({ provider: 'fixture', model: 'single' });
    await collect(prepared.stream({ ...options, ...prepared.config }));
    class InvalidMetadata extends Adapter {
      async prepareCall() { throw new Error('fixture metadata failure'); }
    }
    ctx.llm.registerAdapter(['bad-metadata'], new InvalidMetadata());
    assert.equal((await collect(ctx.llm.stream({ ...options, provider: 'unregistered' }))).at(-1).reason.kind, 'error');
    assert.equal((await collect(ctx.llm.stream({ ...options, provider: 'bad-metadata' }))).at(-1).reason.kind, 'error');
    await assert.rejects(ctx.llm.prepareCall({ provider: 'bad-metadata', model: 'single' }), /metadata failure/);
    const rows = (await readFile(join(dir, 'usage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
    const starts = rows.filter(row => row.event === 'start');
    assert.equal(calls, 7);
    assert.equal(starts.length, calls);
    assert.ok(starts.every(row => row.dispatch === 'adapter-attempt'));
    assert.ok(starts.every(row => /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(row.id)));
    assert.deepEqual(starts.map(row => row.purpose), ['conversation', 'conversation', 'session-title', 'compaction', 'other', 'other', 'other']);
    assert.equal(new Set(starts.map(row => row.id)).size, calls);
    assert.deepEqual(rows.filter(row => row.event === 'finish').map(row => row.outcome), ['failed', 'success', 'success', 'success', 'success', 'success', 'success']);
  } finally {
    if (previous === undefined) delete process.env.DSHX_ASSISTANT_DIR;
    else process.env.DSHX_ASSISTANT_DIR = previous;
    await ctx.fiber.dispose();
  }
});

// These fixtures exercise the installed provider and pi-ai parsers over real
// loopback HTTP. They never read credentials or contact a paid model endpoint.
async function assistantServer(t, respond) {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: LlmRuntime } = await load('@deepseek-ai/dsh-llm');
  const dir = await mkdtemp(join(process.env.DSHX_WORKSPACE, 'assistant-provider-'));
  const previous = process.env.DSHX_ASSISTANT_DIR;
  process.env.DSHX_ASSISTANT_DIR = dir;
  const ctx = new Context();
  const errors = [];
  const requests = [];
  const server = createHttpServer(async (request, response) => {
    try {
      const buffers = [];
      for await (const data of request) buffers.push(data);
      const body = Buffer.concat(buffers);
      requests.push({ path: request.url, body });
      await respond(request, response, body);
    } catch (error) { errors.push(error); response.destroy(); }
  });
  t.after(async () => {
    await ctx.fiber.dispose();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    if (previous === undefined) delete process.env.DSHX_ASSISTANT_DIR;
    else process.env.DSHX_ASSISTANT_DIR = previous;
    await rm(dir, { recursive: true, force: true });
    assert.deepEqual(errors, []);
  });
  await ctx.plugin(LlmRuntime);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return {
    ctx, dir, requests, url: `http://127.0.0.1:${server.address().port}/v1`,
    async rows() {
      try { return (await readFile(join(dir, 'usage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse); }
      catch (error) { if (error.code === 'ENOENT') return []; throw error; }
    },
    async collect(stream) { const chunks = []; for await (const chunk of stream) chunks.push(chunk); return chunks; }
  };
}

function assistantEvents(response, events, end = true) {
  response.writeHead(200, { 'content-type': 'text/event-stream' });
  for (const event of events) response.write(`${event.type ? `event: ${event.type}\n` : ''}data: ${JSON.stringify(event)}\n\n`);
  if (end) response.end();
}

async function assistantWait(check) {
  const until = Date.now() + 5000;
  while (!await check()) {
    assert.ok(Date.now() < until, 'provider fixture did not report before timeout');
    await delay(10);
  }
}

function assistantMessages(usage = { input_tokens: 11, output_tokens: 0, cache_read_input_tokens: 3 }) {
  return [
    { type: 'message_start', message: { id: 'fixture-message', model: 'single', usage } },
    { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } },
    { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'fixture response' } },
    { type: 'content_block_stop', index: 0 },
    { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 5 } },
    { type: 'message_stop' }
  ];
}

async function assistantDeepSeek(fixture, extra = {}) {
  const { DeepSeekAdapter, resolveAdapterOptions } = await load('@deepseek-ai/dsh-llm-deepseek');
  const connection = resolveAdapterOptions({
    baseURL: fixture.url, models: [{ id: 'single', inputModalities: ['text', 'image'] }],
    streamIdleTimeoutMs: 2000
  });
  const adapter = new DeepSeekAdapter({
    options: () => connection,
    resolveAuth: async () => ({ headers: { 'x-api-key': 'fixture-private-key' } }),
    resolveUserId: () => 'fixture-user',
    prepareExtensions: async () => ({ fields: {}, accept: async () => {} }),
    ...extra
  });
  fixture.ctx.llm.registerAdapter(['deepseek-fixture'], adapter);
  return { provider: 'deepseek-fixture', model: 'single', messages: [], sessionId: 'deepseek-session' };
}

async function assistantPi(fixture, api, provider = api) {
  const { PiAiAdapter } = await load('@deepseek-ai/dsh-llm-pi-ai');
  const protocol = await import(pathToFileURL(join(core, `lib/node_modules/@earendil-works/pi-ai/dist/api/${api}.js`)));
  const model = {
    id: 'single', name: 'fixture', provider, api,
    baseUrl: api === 'anthropic-messages' ? fixture.url.replace(/\/v1$/, '') : fixture.url,
    input: ['text'], reasoning: false, contextWindow: 32000, maxTokens: 128,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }
  };
  const piProvider = {
    id: provider, name: 'fixture', getModels: () => [model],
    stream: protocol.stream, streamSimple: protocol.streamSimple,
    auth: { apiKey: { resolve: async () => ({ auth: { apiKey: 'fixture-private-key' } }) } }
  };
  const profiles = new Map([[provider, {
    piProvider, modelErrors: new Map(), configuredMaxTokens: new Map(),
    streamIdleTimeoutMs: 2000, requestImagePixelBudget: 4194304, requestImageMaxBytes: 1048576
  }]]);
  fixture.ctx.llm.registerAdapter([provider], new PiAiAdapter({
    profiles: () => profiles, resolveApiKey: async () => 'fixture-private-key'
  }));
  return { provider, model: 'single', messages: [], sessionId: `${provider}-session` };
}

test('Android assistant real DeepSeek preserves message_start and partial delta usage on disconnect and cancellation', async t => {
  const fixture = await assistantServer(t, (request, response, body) => {
    const mode = JSON.parse(body).messages[0].content[0].text;
    const events = assistantMessages();
    if (mode === 'disconnect') assistantEvents(response, [events[0], { type: 'message_delta', delta: {}, usage: { cache_creation_input_tokens: 0 } }]);
    else if (mode === 'cancel') assistantEvents(response, [events[0]], false);
    else assistantEvents(response, events);
  });
  const options = await assistantDeepSeek(fixture);
  const messages = text => [{ role: 'user', content: [{ type: 'text', text }] }];
  const success = await fixture.collect(fixture.ctx.llm.stream({ ...options, messages: messages('success') }));
  assert.equal(success.at(-1).reason.kind, 'stop');
  const broken = await fixture.collect(fixture.ctx.llm.stream({ ...options, messages: messages('disconnect') }));
  assert.equal(broken.at(-1).reason.kind, 'error');
  const abort = new AbortController();
  const pending = fixture.collect(fixture.ctx.llm.stream({ ...options, messages: messages('cancel'), signal: abort.signal }));
  await assistantWait(async () => (await fixture.rows()).filter(row => row.event === 'start').length === 3 && (await fixture.rows()).at(-1).event === 'usage');
  abort.abort();
  await pending;
  const rows = await fixture.rows();
  const starts = rows.filter(row => row.event === 'start');
  assert.equal(starts.length, fixture.requests.length);
  assert.ok(starts.every(row => row.dispatch === 'http-request' && row.purpose === 'other'));
  assert.deepEqual(rows.filter(row => row.event === 'finish').map(row => row.outcome), ['success', 'failed', 'cancelled']);
  assert.deepEqual(rows.filter(row => row.event === 'finish').map(row => row.usageFinal), [true, false, false]);
  assert.equal(rows.find(row => row.event === 'usage').usageFinal, false);
  assert.deepEqual(rows.filter(row => row.id === starts[1].id && row.event === 'usage').map(row => row.usage), [
    { inputTokens: 11, outputTokens: 0, cacheReadTokens: 3 },
    { inputTokens: 11, outputTokens: 0, cacheReadTokens: 3, cacheWriteTokens: 0 }
  ]);
  assert.ok(rows.filter(row => row.event === 'usage').every(row => row.usageSource === 'provider' && row.usage.totalTokens === undefined));
  assert.doesNotMatch(JSON.stringify(rows), /fixture-private-key|fixture response|messages|content/);
});

test('Android assistant real DeepSeek stale files retry records both sends and excludes uploads credentials and metadata', async t => {
  let uploads = 0;
  let sends = 0;
  const fixture = await assistantServer(t, async (request, response, body) => {
    if (request.url === '/v1/models' || request.url === '/v1/credentials/refresh') {
      response.end('{}');
      return;
    }
    if (request.url === '/v1/files') {
      uploads++;
      const form = await new Response(body, { headers: { 'content-type': request.headers['content-type'] } }).formData();
      const file = form.get('file');
      response.setHeader('content-type', 'application/json');
      response.end(JSON.stringify({
        id: `file-fixture-${uploads}`, type: 'file', mime_type: file.type,
        size_bytes: file.size, filename: file.name, created_at: new Date().toISOString()
      }));
      return;
    }
    assert.equal(request.url, '/v1/messages');
    sends++;
    const payload = JSON.parse(body);
    const image = payload.messages[0].content.find(block => block.type === 'image');
    assert.equal(image.source.file_id, `file-fixture-${sends}`);
    if (sends === 1) {
      response.writeHead(400, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ error: { type: 'invalid_request_error', message: 'file_id file-fixture-1 expired' } }));
    } else assistantEvents(response, assistantMessages());
  });
  const { default: Attachments } = await load('@deepseek-ai/dsh-attachment-local');
  await fixture.ctx.plugin(Attachments, { dshHome: join(fixture.dir, 'image-home'), normalizedImageMaxPixels: 4096 });
  const sharp = require('sharp');
  const data = await sharp({ create: { width: 8, height: 8, channels: 3, background: '#abcdef' } }).png().toBuffer();
  const [attachment] = await fixture.ctx.attachments.saveImages([{ data, mediaType: 'image/png', name: 'fixture.png' }]);
  const { DeepSeekFileStore, DeepSeekUploadIndex, resolveAdapterOptions } = await load('@deepseek-ai/dsh-llm-deepseek');
  const files = new DeepSeekFileStore({ index: new DeepSeekUploadIndex(join(fixture.dir, 'uploads.json')) });
  const options = await assistantDeepSeek(fixture, {
    connect: async () => {
      await fetch(`${fixture.url}/models`);
      return resolveAdapterOptions({ baseURL: fixture.url, models: [{ id: 'single', inputModalities: ['text', 'image'] }], streamIdleTimeoutMs: 2000 });
    },
    resolveAuth: async () => {
      await fetch(`${fixture.url}/credentials/refresh`);
      return { headers: { 'x-api-key': 'fixture-private-key' } };
    },
    resolveFiles: () => files,
    resolveAttachments: () => fixture.ctx.attachments
  });
  const chunks = await fixture.collect(fixture.ctx.llm.stream({ ...options, messages: [{ role: 'user', content: [{ type: 'image', attachment }] }] }));
  assert.equal(chunks.at(-1).reason.kind, 'stop', JSON.stringify(chunks.at(-1)));
  assert.equal(sends, 2);
  assert.equal(uploads, 2);
  assert.equal(fixture.requests.length, 6);
  const rows = await fixture.rows();
  const starts = rows.filter(row => row.event === 'start');
  assert.equal(starts.length, 2);
  assert.ok(starts.every(row => row.dispatch === 'http-request'));
  assert.notEqual(starts[0].id, starts[1].id);
  assert.deepEqual(rows.filter(row => row.event === 'finish').map(row => row.outcome), ['failed', 'success']);
  assert.equal(rows.filter(row => row.id === starts[0].id && row.event === 'usage').length, 0);
  assert.equal(rows.findIndex(row => row.event === 'finish' && row.id === starts[0].id) < rows.indexOf(starts[1]), true);
});

test('Android assistant real DeepSeek preflight failure never creates a send record', async t => {
  const fixture = await assistantServer(t, () => assert.fail('preflight must not send a model request'));
  const options = await assistantDeepSeek(fixture, { prepareExtensions: async () => { throw new Error('fixture extension failure'); } });
  const chunks = await fixture.collect(fixture.ctx.llm.stream(options));
  assert.equal(chunks.at(-1).reason.kind, 'error');
  assert.deepEqual(await fixture.rows(), []);
  assert.deepEqual(fixture.requests, []);
});

test('Android assistant real piAI completions distinguishes absent zero partial choice usage and disconnect', async t => {
  const fixture = await assistantServer(t, (request, response, body) => {
    const mode = JSON.parse(body).messages[0].content;
    const first = { id: 'fixture', choices: [{ index: 0, delta: { role: 'assistant', content: 'fixture response' }, finish_reason: null }] };
    const stop = { id: 'fixture', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] };
    const raw = { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0, prompt_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 }, completion_tokens_details: { reasoning_tokens: 0 } };
    if (mode === 'zero') assistantEvents(response, [first, { ...stop, usage: raw }]);
    else if (mode === 'partial') assistantEvents(response, [first, { ...stop, usage: { prompt_tokens: 7 } }]);
    else if (mode === 'choice') assistantEvents(response, [first, { id: 'fixture', choices: [{ ...stop.choices[0], usage: { completion_tokens: 0 } }] }]);
    else if (mode === 'disconnect') assistantEvents(response, [{ ...first, usage: { prompt_tokens: 13, prompt_cache_hit_tokens: 4 } }]);
    else if (mode === 'fail') { response.writeHead(503); response.end('{"error":{"message":"fixture failure"}}'); }
    else assistantEvents(response, [first, stop]);
  });
  const options = await assistantPi(fixture, 'openai-completions');
  for (const mode of ['absent', 'zero', 'partial', 'choice', 'disconnect', 'fail']) {
    const chunks = await fixture.collect(fixture.ctx.llm.stream({ ...options, messages: [{ role: 'user', content: [{ type: 'text', text: mode }] }] }));
    assert.equal(chunks.at(-1).reason.kind, ['disconnect', 'fail'].includes(mode) ? 'error' : 'stop', mode);
    if (['absent', 'partial', 'choice', 'disconnect', 'fail'].includes(mode)) assert.equal(chunks.some(chunk => chunk.type === 'usage'), false, mode);
    for (const chunk of chunks.filter(chunk => chunk.type === 'usage')) {
      assert.ok(Number.isSafeInteger(chunk.usage.inputTokens) && Number.isSafeInteger(chunk.usage.outputTokens));
    }
  }
  const rows = await fixture.rows();
  const starts = rows.filter(row => row.event === 'start');
  assert.equal(starts.length, 6);
  assert.equal(starts.length, fixture.requests.length);
  const usage = start => rows.filter(row => row.id === start.id && row.event === 'usage').map(row => row.usage);
  assert.deepEqual(starts.map(usage), [[], [{ inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, reasoningTokens: 0, totalTokens: 0 }],
    [{ inputTokens: 7 }], [{ outputTokens: 0 }], [{ inputTokens: 9, cacheReadTokens: 4 }], []]);
  assert.ok(starts.every(row => row.dispatch === 'http-request'));
  assert.ok(rows.filter(row => row.event === 'usage').every(row => row.usageSource === 'provider'));
});

test('Android assistant real piAI responses and anthropic preserve only provider fields on success and failure', async t => {
  let mode;
  const fixture = await assistantServer(t, (request, response) => {
    if (mode === 'fail') { response.writeHead(503); response.end('{"error":{"message":"fixture failure"}}'); return; }
    if (new URL(request.url, 'http://fixture').pathname === '/v1/messages') {
      const events = assistantMessages(mode.startsWith('absent') ? {} : { input_tokens: 0, cache_read_input_tokens: 0 });
      events[4].usage = mode.startsWith('absent') ? {} : { output_tokens: 0 };
      assistantEvents(response, mode.endsWith('disconnect') ? [events[0]] : events);
    } else {
      const usage = mode.startsWith('absent') ? undefined : { input_tokens: 0, output_tokens: 0, total_tokens: 0 };
      const item = { id: 'fixture-item', type: 'message', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text: 'fixture response', annotations: [] }] };
      assistantEvents(response, [
        { type: 'response.created', response: { id: 'fixture-response', status: 'in_progress', ...(mode.endsWith('disconnect') ? { usage } : {}) } },
        ...(mode.endsWith('disconnect') ? [] : [
          { type: 'response.output_item.added', output_index: 0, item: { ...item, content: [] } },
          { type: 'response.content_part.added', item_id: item.id, output_index: 0, content_index: 0, part: { type: 'output_text', text: '', annotations: [] } },
          { type: 'response.output_text.delta', item_id: item.id, output_index: 0, content_index: 0, delta: 'fixture response' },
          { type: 'response.output_item.done', output_index: 0, item },
          { type: 'response.completed', response: { id: 'fixture-response', status: 'completed', output: [item], ...(usage === undefined ? {} : { usage }) } }
        ])
      ]);
    }
  });
  for (const api of ['openai-responses', 'anthropic-messages']) {
    const options = await assistantPi(fixture, api);
    for (mode of ['absent', 'zero', 'disconnect', 'absent-disconnect', 'fail']) {
      const before = (await fixture.rows()).length;
      const chunks = await fixture.collect(fixture.ctx.llm.stream(options));
      assert.equal(chunks.at(-1).reason.kind, mode.endsWith('disconnect') || mode === 'fail' ? 'error' : 'stop', `${api}/${mode}, paths=${fixture.requests.map(request => request.path)}: ${JSON.stringify(chunks.at(-1))}`);
      const rows = (await fixture.rows()).slice(before);
      const usages = rows.filter(row => row.event === 'usage');
      if (mode.startsWith('absent') || mode === 'fail') {
        assert.deepEqual(usages, []);
        assert.equal(chunks.some(chunk => chunk.type === 'usage'), false);
      }
      else {
        assert.ok(usages.length > 0, `${api}/${mode}`);
        const usage = usages.at(-1).usage;
        if (api === 'openai-responses') assert.deepEqual(usage, { inputTokens: 0, outputTokens: 0, totalTokens: 0 });
        else assert.deepEqual(usage, { inputTokens: 0, cacheReadTokens: 0, ...(mode === 'disconnect' ? {} : { outputTokens: 0 }) });
      }
      assert.equal(rows[0].dispatch, 'http-request');
    }
  }
  assert.equal((await fixture.rows()).filter(row => row.event === 'start').length, fixture.requests.length);
});

test('Android assistant real piAI Google catalog protocol remains an execution attempt with unknown usage', async t => {
  const fixture = await assistantServer(t, (request, response) => assistantEvents(response, [{
    candidates: [{ content: { role: 'model', parts: [{ text: 'fixture response' }] }, finishReason: 'STOP' }],
    usageMetadata: { promptTokenCount: 0, candidatesTokenCount: 0, totalTokenCount: 0 }
  }]));
  const options = await assistantPi(fixture, 'google-generative-ai');
  const chunks = await fixture.collect(fixture.ctx.llm.stream({ ...options, messages: [{ role: 'user', content: [{ type: 'text', text: 'fixture' }] }] }));
  assert.equal(chunks.at(-1).reason.kind, 'stop', JSON.stringify(chunks.at(-1)));
  assert.equal(chunks.some(chunk => chunk.type === 'usage'), false);
  const rows = await fixture.rows();
  assert.deepEqual(rows.map(row => row.event), ['start', 'finish']);
  assert.equal(rows[0].dispatch, 'adapter-attempt');
  assert.equal(rows[1].outcome, 'success');
  assert.equal(fixture.requests.length, 1);
});

test('Android assistant real provider concurrent sends keep route session purpose and cumulative cache normalization isolated', async t => {
  const fixture = await assistantServer(t, async (request, response, body) => {
    const payload = JSON.parse(body);
    if (request.url === '/v1/messages') {
      await delay(20);
      assistantEvents(response, assistantMessages({ input_tokens: 31, output_tokens: 0 }));
      return;
    }
    const name = payload.messages[0].content;
    const input = name === 'title' ? 17 : 23;
    const first = { choices: [{ index: 0, delta: { content: 'fixture response' } }] };
    response.writeHead(200, { 'content-type': 'text/event-stream' });
    response.write(`data: ${JSON.stringify({ ...first, usage: { prompt_tokens: input } })}\n\n`);
    await delay(name === 'title' ? 40 : 10);
    response.end(`data: ${JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { completion_tokens: 2, prompt_tokens_details: { cached_tokens: 3 } } })}\n\n`);
  });
  const { markAgentLoopRequest } = await load('@deepseek-ai/dsh-llm');
  const deepseek = markAgentLoopRequest(await assistantDeepSeek(fixture));
  const pi = await assistantPi(fixture, 'openai-completions');
  const prepared = await fixture.ctx.llm.prepareCall({ provider: pi.provider, model: pi.model });
  const request = (text, purpose) => ({ ...pi, sessionId: `${text}-session`, purpose, messages: [{ role: 'user', content: [{ type: 'text', text }] }] });
  await Promise.all([
    fixture.collect(fixture.ctx.llm.stream(deepseek)),
    fixture.collect(fixture.ctx.llm.stream(request('title', 'session-title'))),
    fixture.collect(prepared.stream({ ...request('translator', undefined), ...prepared.config }))
  ]);
  const rows = await fixture.rows();
  const starts = rows.filter(row => row.event === 'start');
  assert.equal(starts.length, 3);
  assert.equal(fixture.requests.length, 3);
  for (const start of starts) {
    const own = rows.filter(row => row.id === start.id);
    assert.equal(own.at(-1).outcome, 'success');
    const usage = own.filter(row => row.event === 'usage').at(-1).usage;
    if (start.provider === deepseek.provider) {
      assert.equal(start.sessionId, 'deepseek-session');
      assert.equal(start.purpose, 'conversation');
      assert.deepEqual(usage, { inputTokens: 31, outputTokens: 5 });
    } else {
      const title = start.sessionId === 'title-session';
      assert.equal(start.purpose, title ? 'session-title' : 'other');
      assert.deepEqual(usage, { inputTokens: (title ? 17 : 23) - 3, outputTokens: 2, cacheReadTokens: 3 });
    }
  }
});

test('Android assistant real piAI contradictory cache usage stays unknown instead of producing zero input', async t => {
  const fixture = await assistantServer(t, (request, response) => assistantEvents(response, [
    { choices: [{ index: 0, delta: { content: 'fixture response' } }], usage: { prompt_tokens: 2, completion_tokens: 0 } },
    { choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens_details: { cached_tokens: 4 } } }
  ]));
  const options = await assistantPi(fixture, 'openai-completions');
  const chunks = await fixture.collect(fixture.ctx.llm.stream(options));
  assert.equal(chunks.at(-1).reason.kind, 'stop');
  assert.equal(chunks.some(chunk => chunk.type === 'usage'), false);
  const usages = (await fixture.rows()).filter(row => row.event === 'usage');
  assert.equal(usages[0].usage.inputTokens, 2);
  assert.deepEqual(usages.at(-1).usage, { outputTokens: 0, cacheReadTokens: 4 });
  assert.equal(usages.at(-1).usageAnomaly, 'cache-exceeds-input');
});

test('Android assistant real providers preserve input-only usage before SIGKILL without exposing partial business chunks', async t => {
  const fixture = await assistantServer(t, (request, response) => {
    const event = request.url === '/v1/messages'
      ? assistantMessages({ input_tokens: 12, cache_read_input_tokens: 3 })[0]
      : { choices: [], usage: { prompt_tokens: 15, prompt_tokens_details: { cached_tokens: 3 } } };
    assistantEvents(response, [event], false);
  });
  for (const kind of ['deepseek', 'pi']) {
    const dir = join(fixture.dir, kind);
    await mkdir(dir);
    const source = `import { createRequire } from 'node:module';
      import { join } from 'node:path';
      import { pathToFileURL } from 'node:url';
      const core = ${JSON.stringify(core)};
      const require = createRequire(join(core, 'lib/node_modules/package.json'));
      const load = name => import(pathToFileURL(require.resolve(name)));
      const { Context } = await load('@deepseek-ai/cordis');
      const { default: LlmRuntime } = await load('@deepseek-ai/dsh-llm');
      const ctx = new Context(); await ctx.plugin(LlmRuntime);
      const fixture = {ctx, url:${JSON.stringify(fixture.url)}, dir:${JSON.stringify(dir)}};
      const options = await (${kind === 'deepseek' ? assistantDeepSeek.toString() : assistantPi.toString()})(fixture${kind === 'pi' ? ", 'openai-completions'" : ''});
      for await (const chunk of ctx.llm.stream(options)) {
        if (chunk.type === 'usage') throw new Error('partial usage escaped into business stream');
      }`;
    const child = spawn(process.execPath, ['--input-type=module', '-e', source], {
      env: { ...process.env, DSHX_ASSISTANT_DIR: dir }, stdio: ['ignore', 'pipe', 'pipe']
    });
    let errors = '';
    child.stderr.on('data', data => { errors += data; });
    try {
      await assistantWait(async () => {
        assert.equal(child.exitCode, null, errors);
        try { return (await readFile(join(dir, 'usage.jsonl'), 'utf8')).includes('"event":"usage"'); }
        catch (error) { if (error.code === 'ENOENT') return false; throw error; }
      });
    } finally {
      const closed = new Promise(resolve => child.once('close', resolve));
      child.kill('SIGKILL');
      await closed;
    }
    assert.equal(errors, '');
    const rows = (await readFile(join(dir, 'usage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
    assert.deepEqual(rows.map(row => row.event), ['start', 'usage']);
    assert.equal(rows[0].dispatch, 'http-request');
    assert.deepEqual(rows[1].usage, { inputTokens: 12, cacheReadTokens: 3 });
    assert.equal(rows[1].usageSource, 'provider');
  }
});

test('Android assistant crash preserves reported usage and separates a torn record on restart', async () => {
  const dir = await mkdtemp(join(directory, 'assistant-crash-'));
  const module = pathToFileURL(join(core, 'assistant.mjs')).href;
  const source = `import { usageStream } from ${JSON.stringify(module)};
    for await (const chunk of usageStream({provider:'fixture',model:'single'}, async function* () {
      yield {type:'usage',usage:{inputTokens:12,outputTokens:3}};
      await new Promise(() => {});
    }, ${JSON.stringify(dir)})) { process.stdout.write('ready\\n'); setInterval(() => {}, 1000); }`;
  const child = spawn(process.execPath, ['--input-type=module', '-e', source], { stdio: ['ignore', 'pipe', 'pipe'] });
  let errors = '';
  child.stderr.on('data', data => { errors += data; });
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`child did not report usage: ${errors}`)), 5000);
      child.stdout.once('data', () => { clearTimeout(timer); resolve(); });
      child.once('error', error => { clearTimeout(timer); reject(error); });
      child.once('exit', code => { clearTimeout(timer); reject(new Error(`child exited ${code}: ${errors}`)); });
    });
  } finally {
    const exited = new Promise(resolve => child.once('close', resolve));
    child.kill('SIGKILL');
    await exited;
  }
  let rows = (await readFile(join(dir, 'usage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
  assert.deepEqual(rows.map(row => row.event), ['start', 'usage']);
  assert.deepEqual(rows[1].usage, { inputTokens: 12, outputTokens: 3 });
  const file = await open(join(dir, 'usage.jsonl'), 'a');
  await file.write('{torn'); await file.close();
  const { usageStream } = await import(module);
  for await (const _ of usageStream({ provider: 'fixture', model: 'single' }, async function* () {
    yield { type: 'finish', reason: { kind: 'stop' } };
  }, dir)) {}
  const lines = (await readFile(join(dir, 'usage.jsonl'), 'utf8')).trim().split('\n');
  assert.equal(lines[2], '{torn');
  assert.equal(JSON.parse(lines[3]).event, 'start');
  assert.equal(JSON.parse(lines[4]).outcome, 'success');
});

test('Android assistant refuses an unrecorded attempt when durable storage fails', async () => {
  const { usageStream } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const file = join(directory, 'assistant-not-directory');
  await writeFile(file, 'keep');
  let called = false;
  await assert.rejects(async () => {
    for await (const _ of usageStream({ provider: 'fixture', model: 'single' }, async function* () {
      called = true;
    }, file)) {}
  });
  assert.equal(called, false);
  assert.equal(await readFile(file, 'utf8'), 'keep');
  for (const closeFails of [false, true]) {
    const dir = await mkdtemp(join(directory, 'assistant-finish-storage-'));
    let closed = false;
    const stream = usageStream({ provider: 'fixture', model: 'single' }, async function* () {
      try { yield { type: 'text-delta', index: 0, text: 'started' }; }
      finally {
        closed = true;
        if (closeFails) throw new Error('fixture cleanup failure');
      }
    }, dir);
    await stream.next();
    const ledger = join(dir, 'usage.jsonl');
    await rm(ledger);
    await mkdir(ledger);
    await assert.rejects(stream.return(), error => error.code === 'EISDIR');
    assert.equal(closed, true, 'storage failure must not skip provider cleanup');
  }
});

test('Android Git runtime creates commits and clones local and HTTP repositories', { timeout: 45000 }, async () => {
  const { packageManager } = await import(pathToFileURL(join(core, 'plugins.mjs')));
  const env = { ...process.env, ...packageManager(core).env };
  const git = join(core, 'bin/git');
  const run = (...args) => {
    const result = spawnSync(git, args, { env, encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 0, result.error?.message ?? result.stderr);
    return result.stdout;
  };
  assert.match(run('--version'), /^git version /);
  assert.equal(run('--exec-path').trim(), join(core, 'libexec/git-core'));
  const dir = await mkdtemp(join(directory, 'git-runtime-'));
  const source = join(dir, 'source'), remote = join(dir, 'remote.git'), target = join(dir, 'clone');
  run('init', source);
  await writeFile(join(source, 'note.txt'), 'DSHX bundled Git\n');
  run('-C', source, 'add', 'note.txt');
  run('-C', source, '-c', 'user.name=DSHX Test', '-c', 'user.email=test@example.invalid', 'commit', '-m', 'runtime check');
  run('clone', '--bare', source, remote);
  run('-C', remote, 'update-server-info');
  assert.equal(run('ls-remote', remote, 'HEAD').trim().split(/\s/)[0], run('-C', source, 'rev-parse', 'HEAD').trim());
  const server = createHttpServer(async (req, res) => {
    const path = new URL(req.url, 'http://localhost').pathname;
    if (path.includes('..')) { res.writeHead(400); res.end(); return; }
    try {
      const data = await readFile(join(remote, path));
      res.writeHead(200, { 'Content-Type': 'application/octet-stream' }); res.end(data);
    } catch { res.writeHead(404); res.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    await new Promise((resolve, reject) => {
      const child = spawn(git, ['clone', `http://127.0.0.1:${server.address().port}/`, target], { env });
      let output = '';
      child.stdout.on('data', chunk => { output += chunk; });
      child.stderr.on('data', chunk => { output += chunk; });
      const timeout = setTimeout(() => child.kill('SIGKILL'), 20000);
      child.once('error', error => { clearTimeout(timeout); reject(error); });
      child.once('exit', code => { clearTimeout(timeout); code === 0 ? resolve() : reject(new Error(output)); });
    });
    assert.equal(await readFile(join(target, 'note.txt'), 'utf8'), 'DSHX bundled Git\n');
    assert.equal(run('-C', target, 'rev-parse', 'HEAD'), run('-C', source, 'rev-parse', 'HEAD'));
    await stat(join(core, 'libexec/git-core/git-remote-https'));
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});

async function runtimeCommand(name, args, options = {}) {
  const { status = 0, input, ...spawnOptions } = options;
  const child = spawn(name.includes('/') ? name : join(core, 'bin', name), args, {
    env: { ...process.env, PATH: `${join(core, 'bin')}:/system/bin` }, ...spawnOptions,
    stdio: ['pipe', 'pipe', 'pipe']
  });
  let stdout = '', stderr = '';
  child.stdout.on('data', chunk => { stdout += chunk; });
  child.stderr.on('data', chunk => { stderr += chunk; });
  child.stdin.end(input);
  const timer = setTimeout(() => child.kill('SIGKILL'), 30000);
  try {
    const code = await new Promise((resolve, reject) => {
      child.once('error', reject); child.once('exit', resolve);
    });
    assert.equal(code, status, `${name}: ${stdout}\n${stderr}`);
    return stdout;
  } finally { clearTimeout(timer); }
}

test('Android runtime tools process JSON databases GNU commands archives and HTTP', { timeout: 60000 }, async () => {
  const dir = await mkdtemp(join(directory, 'runtime tools '));
  assert.equal((await runtimeCommand('jq', ['-r', '.items | sort | join(",")'], { input: '{"items":["乙","甲"]}' })).trim(), '乙,甲');
  const database = join(dir, 'records.db');
  assert.equal((await runtimeCommand('sqlite3', [database, "create table note(text); insert into note values('本地数据库'); select text from note;"])).trim(), '本地数据库');
  const original = join(dir, 'note.txt');
  await writeFile(original, '旧内容\n');
  await runtimeCommand('patch', [original], { input: '--- note.txt\n+++ note.txt\n@@ -1 +1 @@\n-旧内容\n+新内容\n' });
  assert.equal(await readFile(original, 'utf8'), '新内容\n');
  const changed = join(dir, 'changed.txt'); await writeFile(changed, '另一份内容\n');
  assert.match(await runtimeCommand('diff', ['-u', original, changed], { status: 1 }), /另一份内容/);
  assert.equal((await runtimeCommand('sort', ['-u'], { input: 'b\na\nb\n' })).trim(), 'a\nb');
  assert.match(await runtimeCommand('find', [dir, '-name', '*.txt']), /note\.txt/);
  assert.equal((await runtimeCommand('xargs', ['-0', 'printf', '<%s>'], { input: '含 空格\0第二项\0' })).trim(), '<含 空格><第二项>');
  assert.match(await runtimeCommand('sha256sum', [original]), /^[0-9a-f]{64} /);
  assert.equal(await runtimeCommand('stdbuf', ['-oL', 'printf', 'buffer ready']), 'buffer ready');
  await runtimeCommand('gzip', ['-k', original]);
  assert.equal(await runtimeCommand('zcat', [original + '.gz']), '新内容\n');
  await rm(original);
  await runtimeCommand('gunzip', [original + '.gz']);
  await runtimeCommand('tar', ['-czf', join(dir, 'notes.tar.gz'), '-C', dir, 'note.txt']);
  const tarOutput = join(dir, 'tar output'); await mkdir(tarOutput);
  await runtimeCommand('tar', ['-xzf', join(dir, 'notes.tar.gz'), '-C', tarOutput]);
  assert.equal(await readFile(join(tarOutput, 'note.txt'), 'utf8'), '新内容\n');
  await runtimeCommand('zip', ['-q', join(dir, 'notes.zip'), 'note.txt'], { cwd: dir });
  const zipOutput = join(dir, 'zip output'); await mkdir(zipOutput);
  await runtimeCommand('unzip', ['-q', join(dir, 'notes.zip'), '-d', zipOutput]);
  assert.equal(await readFile(join(zipOutput, 'note.txt'), 'utf8'), '新内容\n');
  assert.equal((await runtimeCommand('zipinfo', ['-1', join(dir, 'notes.zip')])).trim(), 'note.txt');
  const server = createHttpServer((req, res) => { res.setHeader('Content-Type', 'application/json'); res.end('{"ok":true}'); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    assert.equal(await runtimeCommand('curl', ['--fail', '--silent', '--show-error', `http://127.0.0.1:${server.address().port}/`]), '{"ok":true}');
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  assert.ok(process.env.DSHX_TEST_TLS_CERT && process.env.DSHX_TEST_TLS_KEY, 'Provide the temporary localhost TLS certificate');
  const tls = createHttpsServer({ cert: await readFile(process.env.DSHX_TEST_TLS_CERT), key: await readFile(process.env.DSHX_TEST_TLS_KEY) },
    (req, res) => res.end('tls ready'));
  await new Promise(resolve => tls.listen(0, '127.0.0.1', resolve));
  try {
    const url = `https://127.0.0.1:${tls.address().port}/`;
    assert.equal(await runtimeCommand('curl', ['--fail', '--silent', '--show-error', '--cacert', process.env.DSHX_TEST_TLS_CERT, url]), 'tls ready');
    await runtimeCommand('curl', ['--silent', '--show-error', url], { status: 60 });
    assert.equal((await runtimeCommand('python3', ['-c', 'import ssl,urllib.request,sys; print(urllib.request.urlopen(sys.argv[1], context=ssl.create_default_context(cafile=sys.argv[2])).read().decode())',
      url, process.env.DSHX_TEST_TLS_CERT])).trim(), 'tls ready');
  } finally { tls.closeAllConnections(); await new Promise(resolve => tls.close(resolve)); }
});

test('Android runtime Python loads standard extensions creates venv and installs an offline wheel', { timeout: 90000 }, async () => {
  const dir = await mkdtemp(join(directory, 'python tools '));
  const code = 'import ssl,sqlite3,ctypes,bz2,lzma,zlib,hashlib,dbm.gnu,multiprocessing; print("stdlib ready")';
  assert.equal((await runtimeCommand('python3', ['-c', code])).trim(), 'stdlib ready');
  assert.match(await runtimeCommand('pip', ['--version']), /^pip /);
  const script = join(dir, 'script');
  await writeFile(script, '#!/usr/bin/env python3\nprint("shebang ready")\n', { mode: 0o700 });
  assert.equal((await runtimeCommand('bash', ['-c', '"$1"', 'test', script])).trim(), 'shebang ready');
  const venv = join(dir, 'venv');
  await runtimeCommand('python3', ['-m', 'venv', venv]);
  const python = join(venv, 'bin/python');
  const wheel = join(dir, 'dshx_test-1.0-py3-none-any.whl');
  await runtimeCommand('python3', ['-c', 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],"w"); '
    + 'z.writestr("dshx_test.py","value = 42\\n"); '
    + 'z.writestr("dshx_test-1.0.dist-info/METADATA","Metadata-Version: 2.1\\nName: dshx-test\\nVersion: 1.0\\n"); '
    + 'z.writestr("dshx_test-1.0.dist-info/WHEEL","Wheel-Version: 1.0\\nGenerator: dshx-test\\nRoot-Is-Purelib: true\\nTag: py3-none-any\\n"); '
    + 'z.writestr("dshx_test-1.0.dist-info/RECORD",""); z.close()', wheel]);
  await runtimeCommand(python, ['-m', 'pip', 'install', '--no-index', '--disable-pip-version-check', wheel]);
  assert.equal((await runtimeCommand(python, ['-c', 'import sys,dshx_test; assert sys.prefix != sys.base_prefix; print(dshx_test.value)'])).trim(), '42');
  assert.equal((await runtimeCommand('bash', ['-c', '"$1/bin/pip" --version', 'test', venv])).includes(venv), true);
  await runtimeCommand('python3', ['-c', 'import importlib.util; assert importlib.util.find_spec("dshx_test") is None']);
});

test('Android package commands install local packages and preserve script arguments shell overrides and exit codes', { timeout: 60000 }, async () => {
  const dir = await mkdtemp(join(directory, 'package commands '));
  const source = join(dir, 'local package');
  await mkdir(source);
  await writeFile(join(source, 'package.json'), JSON.stringify({ name: 'dshx-command-test', version: '1.0.0', main: 'index.cjs' }));
  await writeFile(join(source, 'index.cjs'), 'module.exports = "local package ready";');
  for (const name of ['npm', 'pnpm']) {
    const cwd = join(dir, name);
    await mkdir(cwd);
    await writeFile(join(cwd, 'package.json'), JSON.stringify({ private: true, scripts: {
      probe: 'node probe.cjs', fail: 'node -e "process.exit(7)"', nested: 'npm --version && pnpm --version'
    } }));
    await writeFile(join(cwd, 'probe.cjs'), 'console.log(JSON.stringify(process.argv.slice(2)));');
    const env = { ...process.env, HOME: dir, PREFIX: join(dir, 'unused prefix'),
      PATH: `${join(core, 'bin')}:/system/bin`, npm_config_cache: join(dir, 'cache'),
      npm_config_store_dir: join(dir, 'store'), npm_config_update_notifier: 'false' };
    delete env.npm_config_script_shell;
    const run = (args, extra = {}) => spawnSync(name, args, { cwd, env: { ...env, ...extra }, encoding: 'utf8', timeout: 15000 });
    const success = result => {
      assert.ifError(result.error);
      assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
      return result.stdout.trim();
    };
    const version = JSON.parse(await readFile(join(core, 'lib/node_modules', name, 'package.json'), 'utf8')).version;
    assert.equal(success(run(['--version'])), version);
    const args = ['含 空格', '"引号"', '$HOME;$(exit 9)', ''];
    const probe = success(run(['run', 'probe', ...(name === 'npm' ? ['--'] : []), ...args]));
    assert.ok(probe.includes(JSON.stringify(args)), probe);
    const failure = run(['run', 'fail']);
    assert.ifError(failure.error);
    assert.equal(failure.status, 7, `${failure.stdout}\n${failure.stderr}`);
    success(run(['run', 'nested']));
    assert.equal(success(run(['config', 'get', 'script-shell'], { npm_config_script_shell: '/system/bin/sh' })), '/system/bin/sh');
    success(run([name === 'npm' ? 'install' : 'add', '--offline', '--ignore-scripts',
      ...(name === 'npm' ? ['--no-audit', '--no-fund'] : []), source]));
    assert.equal(createRequire(join(cwd, 'package.json'))('dshx-command-test'), 'local package ready');
  }
});

test('Android package runners execute a local command with an env shebang', { timeout: 60000 }, async () => {
  const dir = await mkdtemp(join(directory, 'package runners '));
  const source = join(dir, 'package'); await mkdir(source);
  await writeFile(join(source, 'package.json'), JSON.stringify({
    name: 'dshx-runner-test', version: '1.0.0', bin: { 'dshx-runner-test': 'cli.cjs' }
  }));
  await writeFile(join(source, 'cli.cjs'), '#!/usr/bin/env node\nconsole.log(JSON.stringify(process.argv.slice(2)));\n', { mode: 0o700 });
  const archive = join(dir, 'package.tgz');
  await runtimeCommand('tar', ['-czf', archive, '-C', dir, 'package']);
  const env = { ...process.env, HOME: dir, PATH: `${join(core, 'bin')}:/system/bin`,
    npm_config_cache: join(dir, 'npm cache'), npm_config_store_dir: join(dir, 'pnpm store'),
    npm_config_update_notifier: 'false', npm_config_script_shell: join(core, 'bin/bash'), CI: 'true' };
  const args = ['含 空格', '$HOME;$(exit 9)', ''];
  for (const name of ['npx', 'pnpx']) {
    const result = await runtimeCommand(name, [...(name === 'npx' ? ['--yes', '--offline'] : []),
      `--package=file:${archive}`, 'dshx-runner-test', ...args], { cwd: dir, env });
    assert.ok(result.includes(JSON.stringify(args)), result);
  }
});

test('Android runtime SSH creates keys honors user config transfers files and synchronizes through SSH', { timeout: 90000 }, async () => {
  assert.ok(process.env.DSHX_TEST_SSH_PORT, 'Provide the temporary loopback SSH test service');
  const dir = await mkdtemp(join(directory, 'ssh tools '));
  const home = join(dir, 'home'); await mkdir(home);
  const env = { ...process.env, HOME: home, PATH: `${join(core, 'bin')}:/system/bin` };
  await runtimeCommand('ssh-keygen', ['-q', '-t', 'ed25519', '-N', ''], { env });
  await stat(join(home, '.ssh/id_ed25519.pub'));
  const config = ['Host dshx-runtime-test', '    HostName 127.0.0.1', `    Port ${process.env.DSHX_TEST_SSH_PORT}`, '    ConnectTimeout 5',
    `    User ${process.env.DSHX_TEST_SSH_USER}`, `    IdentityFile "${process.env.DSHX_TEST_SSH_KEY}"`,
    '    BatchMode yes', '    StrictHostKeyChecking yes', `    UserKnownHostsFile "${process.env.DSHX_TEST_SSH_HOSTS}"`].join('\n') + '\n';
  await writeFile(join(home, '.ssh/config'), config, { mode: 0o600 });
  const settings = await runtimeCommand('ssh', ['-G', 'dshx-runtime-test'], { env });
  assert.match(settings, /hostname 127\.0\.0\.1/);
  assert.ok(settings.includes(`userknownhostsfile ${process.env.DSHX_TEST_SSH_HOSTS}`), settings);
  assert.match(await runtimeCommand('ssh-keyscan', ['-T', '5', '-t', 'ed25519', '-p', process.env.DSHX_TEST_SSH_PORT, '127.0.0.1'], { env }), /ssh-ed25519 /);
  assert.equal((await runtimeCommand('ssh', ['dshx-runtime-test', 'printf remote-ready'], { env })).trim(), 'remote-ready');
  await runtimeCommand('ssh', ['dshx-runtime-test', 'exit 7'], { env, status: 7 });
  const source = join(dir, 'source.txt'); await writeFile(source, 'SSH 文件传输\n');
  const remote = process.env.DSHX_TEST_SSH_ROOT;
  await runtimeCommand('scp', [source, `dshx-runtime-test:${remote}/remote/scp.txt`], { env });
  const download = join(dir, 'download.txt');
  await runtimeCommand('sftp', ['-b', '-', 'dshx-runtime-test'], {
    env, input: `get ${remote}/remote/scp.txt "${download}"\nput "${source}" ${remote}/remote/sftp.txt\n`
  });
  assert.equal(await readFile(download, 'utf8'), 'SSH 文件传输\n');
  const local = join(dir, 'sync'); await mkdir(local); await writeFile(join(local, 'note.txt'), '同步内容\n');
  await runtimeCommand('rsync', ['-r', local + '/', `dshx-runtime-test:${remote}/remote/sync/`], { env });
  const received = join(dir, 'received'); await mkdir(received);
  await runtimeCommand('rsync', ['-r', `dshx-runtime-test:${remote}/remote/sync/`, received + '/'], { env });
  assert.equal(await readFile(join(received, 'note.txt'), 'utf8'), '同步内容\n');
  const socket = join(dir, 'agent.sock');
  const agent = spawn(join(core, 'bin/ssh-agent'), ['-D', '-a', socket], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = ''; agent.stderr.on('data', chunk => { output += chunk; });
  try {
    for (let count = 0; count < 100; count++) {
      try { await stat(socket); break; } catch { await delay(20); }
    }
    assert.equal(agent.exitCode, null, output);
    const agentEnv = { ...env, SSH_AUTH_SOCK: socket };
    await runtimeCommand('ssh-add', [join(home, '.ssh/id_ed25519')], { env: agentEnv });
    assert.match(await runtimeCommand('ssh-add', ['-l'], { env: agentEnv }), /ED25519/);
  } finally {
    if (agent.exitCode === null) { agent.kill('SIGTERM'); await new Promise(resolve => agent.once('exit', resolve)); }
  }
});

test('Android plugin GitHub installation preserves Git sources while scripts stay disabled', async () => {
  const { runPnpm, packageManager } = await import(pathToFileURL(join(core, 'plugins.mjs')));
  const runtime = await mkdtemp(join(directory, 'plugin-github-'));
  const bin = join(runtime, 'lib/node_modules/pnpm/bin');
  await mkdir(bin, { recursive: true });
  const captured = join(runtime, 'arguments.json');
  await writeFile(join(bin, 'pnpm.cjs'), `require('node:fs').writeFileSync(${JSON.stringify(captured)}, JSON.stringify(process.argv.slice(2)));`);
  for (const source of ['github:Yuuyuko-uu/dsh-speak-up', 'github:owner/repo#main',
    'github:owner/repo#feature/phone', 'github:owner/repo#semver:^1', 'github:owner/repo#' + 'a'.repeat(40),
    'https://github.com/owner/repo', 'git+https://github.com/owner/repo.git',
    'https://github.com/owner/repo/archive/main.tar.gz']) {
    assert.equal(await runPnpm(runtime, ['add', source]), 0);
    const args = JSON.parse(await readFile(captured, 'utf8'));
    assert.deepEqual(args.slice(0, 2), ['add', source]);
    for (const flag of ['--config.ignore-scripts=true', '--config.ignore-pnpmfile=true', '--save-exact']) {
      assert.ok(args.includes(flag), flag);
    }
  }
  const env = packageManager(core).env;
  assert.equal(env.GIT_EXEC_PATH, join(core, 'libexec/git-core'));
  assert.equal(env.GIT_TEMPLATE_DIR, join(core, 'share/git-core/templates'));
  assert.equal(env.GIT_SSL_CAINFO, join(core, 'etc/tls/cert.pem'));
  assert.ok(env.PATH.startsWith(join(core, 'bin') + ':'));
  await rm(captured);
  for (const source of ['gitlab:owner/repo', 'git@github.com:owner/repo.git',
    'https://example.com/plugin.tgz', 'link:/tmp/plugin']) {
    await assert.rejects(runPnpm(runtime, ['add', source]), /尚未适配/);
  }
  await assert.rejects(stat(captured), { code: 'ENOENT' });
});

test('Android plugin content checks follow selected transitive packages without scanning stale store entries', async () => {
  const { checkPlugins } = await import(pathToFileURL(join(core, 'plugins.mjs')));
  const modules = join(await mkdtemp(join(directory, 'plugin-tree-')), 'node_modules');
  const selected = join(modules, '.pnpm/selected/node_modules');
  const dependency = join(modules, '.pnpm/dependency/node_modules/dependency');
  const stale = join(modules, '.pnpm/stale/node_modules/stale');
  await mkdir(join(selected, 'selected'), { recursive: true });
  await mkdir(dependency, { recursive: true });
  await mkdir(stale, { recursive: true });
  await writeFile(join(selected, 'selected/package.json'), '{"name":"selected"}');
  await writeFile(join(dependency, 'package.json'), '{"name":"dependency"}');
  await writeFile(join(dependency, 'code.wasm'), Buffer.from([0, 97, 115, 109]));
  await writeFile(join(stale, 'unsupported.node'), 'unselected leftover');
  await symlink(join(selected, 'selected'), join(modules, 'selected'));
  await symlink(dependency, join(selected, 'dependency'));
  await symlink(join(selected, 'selected'), join(dependency, 'loop'));
  await checkPlugins(modules);
  const binary = join(dependency, 'hidden-native');
  await writeFile(binary, Buffer.from([127, 69, 76, 70]));
  await assert.rejects(checkPlugins(modules), /原生二进制/);
  await rm(binary);
  const external = await mkdtemp(join(directory, 'outside-plugin-'));
  await writeFile(join(external, 'keep.txt'), 'keep');
  await symlink(external, join(dependency, 'outside'));
  await assert.rejects(checkPlugins(modules), /安装目录之外/);
  assert.equal(await readFile(join(external, 'keep.txt'), 'utf8'), 'keep');
});

test('Android plugin manager installs disables enables and removes a local bundle while scripts and hooks stay off', async () => {
  const { packageManager } = await import(pathToFileURL(join(core, 'plugins.mjs')));
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: PluginManager } = await load('@deepseek-ai/dsh-plugin-manager');
  const dir = await mkdtemp(join(directory, 'plugin-profile-'));
  const source = await mkdtemp(join(directory, 'plugin-source-'));
  const marker = join(dir, 'must-not-run');
  const script = `require('node:fs').writeFileSync(${JSON.stringify(marker)}, 'ran')`;
  await writeFile(join(source, 'package.json'), JSON.stringify({
    name: 'dshx-test-bundle', version: '1.0.0',
    dsh: { bundle: { patch: './cordis.patch.yml' } },
    scripts: { postinstall: `node -e ${JSON.stringify(script)}` }
  }));
  await writeFile(join(source, 'cordis.patch.yml'), '[]\n');
  await writeFile(join(dir, 'package.json'), JSON.stringify({ private: true, dsh: { profile: { bundles: [] } } }));
  await writeFile(join(dir, 'cordis.patch.yml'), '[]\n');
  await writeFile(join(dir, '.npmrc'), 'ignore-scripts=false\nignore-pnpmfile=false\n');
  await writeFile(join(dir, '.pnpmfile.cjs'), `${script}; module.exports = {};`);
  const invocation = packageManager(core);
  const ctx = new Context();
  ctx.provide('loader', { entries: () => [] });
  ctx.provide('profileContext', {
    name: 'test', dir, patchPath: join(dir, 'cordis.patch.yml'),
    installAnchor: require.resolve('@deepseek-ai/dsh/package.json'), cwd: dir, home: dir,
    startedBundles: [], overlays: [],
    packageManager: { ...invocation, env: { ...invocation.env, npm_config_offline: 'true' } }
  });
  try {
    await ctx.plugin(PluginManager, { fallbackRegistries: [] });
    const manager = ctx.pluginManager;
    assert.equal((await manager.inspect(`file:${source}`)).status, 'accepted');
    const original = await readFile(join(dir, 'package.json'), 'utf8');
    const approved = await manager.installBundle(`file:${source}`, { approvedBuilds: ['example'] });
    assert.equal(approved.application, 'failed');
    assert.match(approved.error.diagnostic, /始终禁用构建脚本/);
    assert.equal(await readFile(join(dir, 'package.json'), 'utf8'), original);
    const installed = await manager.installBundle(`file:${source}`, { requestId: 'local-bundle' });
    assert.equal(installed.application, 'restart-required', JSON.stringify(installed));
    assert.equal((await manager.listBundles()).find(row => row.name === 'dshx-test-bundle').enabled, true);
    assert.equal((await manager.setBundleEnabled('dshx-test-bundle', false)).application, 'restart-required');
    assert.equal((await manager.listBundles()).find(row => row.name === 'dshx-test-bundle').enabled, false);
    assert.equal((await manager.setBundleEnabled('dshx-test-bundle', true)).application, 'restart-required');
    await assert.rejects(stat(marker), { code: 'ENOENT' });
    const before = await readFile(join(dir, 'package.json'), 'utf8');
    const failed = await manager.installBundle('link:unsupported');
    assert.equal(failed.application, 'failed');
    assert.equal(await readFile(join(dir, 'package.json'), 'utf8'), before);
    const removed = await manager.removeBundle('dshx-test-bundle');
    assert.equal(removed.application, 'restart-required', JSON.stringify(removed));
    assert.equal((await manager.listBundles()).some(row => row.name === 'dshx-test-bundle'), false);
    assert.equal((await manager.cancelInstall('unknown')).status, 'not-running');
    await assert.rejects(stat(marker), { code: 'ENOENT' });
    const saved = await readFile(join(dir, 'package.json'), 'utf8');
    const lock = await readFile(join(dir, 'pnpm-lock.yaml'), 'utf8');
    // Exercise the real manager's rollback after its runtime adapter rejects activation.
    ctx.provide('hmr', {
      runExclusive: operation => operation(),
      reconcileProfile: async () => {
        const manifest = JSON.parse(await readFile(join(dir, 'package.json'), 'utf8'));
        if (manifest.dsh.profile.bundles.includes('dshx-test-bundle')) throw new Error('fixture activation failed');
        return { warnings: [], pending: [] };
      }
    });
    const broken = await manager.installBundle(`file:${source}`);
    assert.equal(broken.application, 'failed', JSON.stringify(broken));
    assert.match(broken.error.diagnostic, /fixture activation failed/);
    assert.equal(await readFile(join(dir, 'package.json'), 'utf8'), saved);
    assert.equal(await readFile(join(dir, 'pnpm-lock.yaml'), 'utf8'), lock);
  } finally { await ctx.fiber.dispose(); }
});

// A loopback registry exercises pnpm's metadata, integrity and tarball paths
// without relying on a mutable public package or spending model tokens.
async function pluginRegistry(packages, stalled) {
  function tar(files) {
    const blocks = [];
    for (const [name, content] of Object.entries(files)) {
      const data = Buffer.from(content);
      const header = Buffer.alloc(512);
      header.write(`package/${name}`);
      header.write('0000644\0', 100);
      header.write('0000000\0', 108); header.write('0000000\0', 116);
      header.write(data.length.toString(8).padStart(11, '0') + '\0', 124);
      header.write('00000000000\0', 136); header.fill(32, 148, 156);
      header.write('0', 156); header.write('ustar\0', 257); header.write('00', 263);
      header.write([...header].reduce((sum, byte) => sum + byte, 0).toString(8).padStart(6, '0') + '\0 ', 148);
      blocks.push(header, data, Buffer.alloc((512 - data.length % 512) % 512));
    }
    return gzipSync(Buffer.concat([...blocks, Buffer.alloc(1024)]));
  }
  const archives = new Map(Object.entries(packages).map(([name, files]) => [name, tar(files)]));
  const requests = [];
  let received, disconnected;
  const started = new Promise(resolve => { received = resolve; });
  const closed = new Promise(resolve => { disconnected = resolve; });
  const server = createHttpServer((req, res) => {
    requests.push(req.url);
    const name = decodeURIComponent(req.url.slice(1).replace(/\.tgz$/, ''));
    const archive = archives.get(name);
    if (!archive) { res.writeHead(404); res.end('{}'); return; }
    if (req.url.endsWith('.tgz')) {
      res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': archive.length });
      if (name === stalled) {
        res.write(archive.subarray(0, 10));
        res.once('close', disconnected); received();
      } else res.end(archive);
    } else {
      const manifest = JSON.parse(packages[name]['package.json']);
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ ...manifest, 'dist-tags': { latest: '1.0.0' }, versions: {
        '1.0.0': { ...manifest, dist: {
          tarball: `http://127.0.0.1:${server.address().port}/${name}.tgz`,
          integrity: 'sha512-' + createHash('sha512').update(archive).digest('base64')
        } }
      } }));
    }
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return {
    url: `http://127.0.0.1:${server.address().port}/`, requests, started, closed,
    close: () => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); }
  };
}

test('Android plugin registry downloads reject native dependencies and cancellation stops the actual download', { timeout: 90000 }, async () => {
  const { packageManager } = await import(pathToFileURL(join(core, 'plugins.mjs')));
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: PluginManager } = await load('@deepseek-ai/dsh-plugin-manager');
  const bundle = (name, extra = {}, files = {}) => ({
    'package.json': JSON.stringify({ name, version: '1.0.0', dsh: { bundle: { patch: './cordis.patch.yml' } }, ...extra }),
    'cordis.patch.yml': '[]\n', ...files
  });
  const registry = await pluginRegistry({
    'dshx-registry-good': bundle('dshx-registry-good'),
    'dshx-registry-slow': bundle('dshx-registry-slow'),
    'dshx-registry-native': bundle('dshx-registry-native', { dependencies: { 'dshx-native-dependency': '1.0.0' } }),
    'dshx-native-dependency': { 'package.json': '{"name":"dshx-native-dependency","version":"1.0.0"}', 'addon.node': 'unsupported' },
    'dshx-registry-plain': { 'package.json': '{"name":"dshx-registry-plain","version":"1.0.0"}' },
    'dshx-registry-bad-patch': bundle('dshx-registry-bad-patch', {}, { 'cordis.patch.yml': '[unclosed' })
  }, 'dshx-registry-slow');
  const dir = await mkdtemp(join(directory, 'plugin-registry-'));
  await writeFile(join(dir, 'package.json'), '{"private":true,"dsh":{"profile":{"bundles":[]}}}');
  await writeFile(join(dir, 'cordis.patch.yml'), '[]\n');
  // A fresh store proves each run actually downloads, rather than reusing the
  // same content-addressed archive from an earlier test invocation.
  await writeFile(join(dir, '.npmrc'), `store-dir=${join(dir, 'store')}\n`);
  const invocation = packageManager(core);
  const ctx = new Context();
  ctx.provide('loader', { entries: () => [] });
  ctx.provide('profileContext', {
    name: 'registry', dir, patchPath: join(dir, 'cordis.patch.yml'), cwd: dir, home: dir,
    installAnchor: require.resolve('@deepseek-ai/dsh/package.json'), startedBundles: [], overlays: [],
    packageManager: { ...invocation, env: { ...invocation.env, npm_config_offline: 'false' } }
  });
  try {
    await ctx.plugin(PluginManager, { registry: registry.url, fallbackRegistries: [] });
    const manager = ctx.pluginManager;
    const inspection = await manager.inspect('dshx-registry-good');
    assert.equal(inspection.status, 'accepted', JSON.stringify(inspection));
    const installed = await manager.installBundle('dshx-registry-good');
    assert.equal(installed.application, 'restart-required', JSON.stringify(installed));
    assert.ok(registry.requests.includes('/dshx-registry-good.tgz'));
    assert.equal(JSON.parse(await readFile(join(dir, 'package.json'), 'utf8')).dependencies['dshx-registry-good'], '1.0.0');
    const saved = await Promise.all(['package.json', 'pnpm-lock.yaml', 'cordis.patch.yml'].map(file => readFile(join(dir, file), 'utf8')));
    const unchanged = async () => assert.deepEqual(await Promise.all(['package.json', 'pnpm-lock.yaml', 'cordis.patch.yml'].map(file => readFile(join(dir, file), 'utf8'))), saved);
    for (const [name, reason] of [['dshx-registry-native', /原生依赖/], ['dshx-registry-plain', /not.*bundle/i], ['dshx-registry-bad-patch', /parse|YAML|flow|expected/i]]) {
      const failed = await manager.installBundle(name);
      assert.equal(failed.application, 'failed', JSON.stringify(failed));
      assert.match(failed.error.diagnostic ?? failed.error.code, reason);
      await unchanged();
    }
    const pending = manager.installBundle('dshx-registry-slow', { requestId: 'slow-download' });
    const waitForDownload = async (event, label) => {
      let timer;
      try { await Promise.race([event, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(label)), 25000); })]); }
      finally { clearTimeout(timer); }
    };
    await waitForDownload(registry.started, 'tarball download did not start');
    assert.equal((await manager.cancelInstall('slow-download')).status, 'cancelled');
    const cancelled = await pending;
    assert.equal(cancelled.application, 'cancelled', JSON.stringify(cancelled));
    await waitForDownload(registry.closed, 'cancelled pnpm kept the download connection open');
    await unchanged();
    assert.equal((await manager.cancelInstall('slow-download')).status, 'not-running');
    assert.equal((await manager.listBundles()).find(row => row.name === 'dshx-registry-good').enabled, true);
    const removed = await manager.removeBundle('dshx-registry-good');
    assert.equal(removed.application, 'restart-required', JSON.stringify(removed));
  } finally { await ctx.fiber.dispose(); await registry.close(); }
});

test('Android plugin GitHub manager checks the repository with Git and preserves its source', { timeout: 45000 }, async () => {
  const { packageManager } = await import(pathToFileURL(join(core, 'plugins.mjs')));
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: PluginManager } = await load('@deepseek-ai/dsh-plugin-manager');
  const registry = await pluginRegistry({
    'dshx-github-bundle': {
      'package.json': JSON.stringify({ name: 'dshx-github-bundle', version: '1.0.0', dsh: { bundle: { patch: './cordis.patch.yml' } } }),
      'cordis.patch.yml': '[]\n'
    }
  });
  const dir = await mkdtemp(join(directory, 'plugin-github-profile-'));
  const runtime = join(dir, 'runtime');
  const bin = join(runtime, 'lib/node_modules/pnpm/bin');
  await mkdir(bin, { recursive: true });
  await writeFile(join(runtime, 'plugins.mjs'), await readFile(join(core, 'plugins.mjs')));
  const source = 'github:example/dshx-github-bundle';
  const captured = join(dir, 'download-source');
  // 用本地压缩包替代外网下载，保留真实 Git 连接检查及 pnpm 安装流程。
  await writeFile(join(bin, 'pnpm.cjs'), `
    const { spawn } = require('node:child_process');
    const { writeFileSync } = require('node:fs');
    const args = process.argv.slice(2);
    if (args[0] === 'add') {
      if (args[1] !== ${JSON.stringify(source)}) throw new Error('GitHub source was changed');
      writeFileSync(${JSON.stringify(captured)}, args[1]);
      args[1] = ${JSON.stringify(registry.url + 'dshx-github-bundle.tgz')};
    }
    const child = spawn(process.execPath, [${JSON.stringify(join(core, 'lib/node_modules/pnpm/bin/pnpm.cjs'))}, ...args], { stdio: 'inherit' });
    child.once('error', error => { console.error(error.message); process.exitCode = 1; });
    child.once('exit', code => { process.exitCode = code ?? 1; });
    for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP']) process.on(signal, () => child.kill(signal));
  `);
  await writeFile(join(dir, 'package.json'), '{"private":true,"dsh":{"profile":{"bundles":[]}}}');
  await writeFile(join(dir, 'cordis.patch.yml'), '[]\n');
  await writeFile(join(dir, '.npmrc'), `store-dir=${join(dir, 'store')}\n`);
  const gitEnv = { ...process.env, ...packageManager(core).env };
  const repo = join(dir, 'dshx-github-bundle');
  const initialized = spawnSync(join(core, 'bin/git'), ['init', repo], { env: gitEnv, encoding: 'utf8' });
  assert.equal(initialized.status, 0, initialized.stderr);
  const config = join(dir, 'git.config');
  await writeFile(config, `[url "${pathToFileURL(dir + '/').href}"]\n    insteadOf = https://github.com/example/\n`);
  const invocation = packageManager(runtime);
  const ctx = new Context();
  ctx.provide('loader', { entries: () => [] });
  ctx.provide('profileContext', {
    name: 'github', dir, patchPath: join(dir, 'cordis.patch.yml'), cwd: dir, home: dir,
    installAnchor: require.resolve('@deepseek-ai/dsh/package.json'), startedBundles: [], overlays: [],
    packageManager: { ...invocation, env: { ...invocation.env, ...packageManager(core).env, GIT_CONFIG_GLOBAL: config } }
  });
  try {
    await ctx.plugin(PluginManager, { registry: registry.url, fallbackRegistries: [] });
    const installed = await ctx.pluginManager.installBundle('github:example/dshx-github-bundle');
    assert.equal(installed.application, 'restart-required', JSON.stringify(installed));
    assert.equal(await readFile(captured, 'utf8'), source);
    assert.ok(registry.requests.includes('/dshx-github-bundle.tgz'));
    assert.equal((await ctx.pluginManager.listBundles()).find(row => row.name === 'dshx-github-bundle').enabled, true);
    const logs = await readdir(join(dir, '.plugin-manager/logs'));
    assert.equal(logs.some(name => name.startsWith('github-connection-')), true, 'GitHub installs must use the bundled Git preflight');
  } finally { await ctx.fiber.dispose(); await registry.close(); }
});

test('Android plugin actual loader starts stops reloads and restores a failing bundle', { timeout: 90000 }, async () => {
  const dir = await mkdtemp(join(directory, 'plugin-live-'));
  const script = `
    import assert from 'node:assert/strict';
    import { createRequire } from 'node:module';
    import { pathToFileURL } from 'node:url';
    import { join } from 'node:path';
    import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
    const core = process.env.DSHX_CORE;
    const dir = ${JSON.stringify(dir)};
    const require = createRequire(join(core, 'lib/node_modules/package.json'));
    const load = name => import(pathToFileURL(require.resolve(name)));
    const { packageManager } = await import(pathToFileURL(join(core, 'plugins.mjs')));
    const { runProfile } = await load('@deepseek-ai/dsh/profile-boot');
    const { loadProfileDirectory } = await load('@deepseek-ai/dsh-app-boot');
    const { createLaunchEnvironmentSnapshot } = await load('@deepseek-ai/dsh-launch-environment');
    const profile = join(dir, 'profile'); mkdirSync(profile);
    const marker = join(dir, 'lifecycle');
    const makeBundle = (name, code) => {
      const source = join(dir, name); mkdirSync(source);
      writeFileSync(join(source, 'package.json'), JSON.stringify({ name, version: '1.0.0', type: 'module', main: './index.mjs', dsh: { bundle: { patch: './cordis.patch.yml' } } }));
      writeFileSync(join(source, 'index.mjs'), code);
      writeFileSync(join(source, 'cordis.patch.yml'), JSON.stringify([{ insert: [{ id: name, name }] }]));
      return 'file:' + source;
    };
    const good = makeBundle('dshx-live-good',
      'import { appendFileSync } from "node:fs"; export function apply(ctx) { appendFileSync(' + JSON.stringify(marker) + ', "start\\\\n"); ctx.effect(() => () => appendFileSync(' + JSON.stringify(marker) + ', "stop\\\\n")); }');
    const broken = makeBundle('dshx-live-broken', 'export function apply() { throw new Error("actual activation failed"); }');
    writeFileSync(join(profile, 'package.json'), '{"private":true,"dsh":{"profile":{"bundles":[]}}}');
    writeFileSync(join(profile, 'cordis.patch.yml'), JSON.stringify([{ insert: [
      { id: 'timer', name: pathToFileURL(require.resolve('@deepseek-ai/cordis-plugin-timer')).href },
      { id: 'hmr', name: pathToFileURL(require.resolve('@deepseek-ai/dsh-hmr')).href, config: { root: [] } },
      { id: 'plugin-manager', name: pathToFileURL(require.resolve('@deepseek-ai/dsh-plugin-manager')).href }
    ] }]));
    const anchor = require.resolve('@deepseek-ai/dsh/package.json');
    const invocation = packageManager(core);
    const app = await runProfile({ environment: createLaunchEnvironmentSnapshot([]), profile: 'live-test', patchFiles: [], args: [],
      resolvedProfile: { profile: loadProfileDirectory('dsh', profile, anchor), installAnchor: anchor },
      packageManager: { ...invocation, env: { ...invocation.env, npm_config_offline: 'true' } }
    });
    try {
      const { ctx } = app;
      assert.ok(ctx.get('hmr'), 'real HMR must be mounted');
      const manager = ctx.pluginManager;
      const installed = await manager.installBundle(good);
      assert.equal(installed.application, 'applied', JSON.stringify(installed));
      assert.equal(readFileSync(marker, 'utf8'), 'start\\n');
      assert.equal((await manager.setBundleEnabled('dshx-live-good', false)).application, 'applied');
      assert.equal(readFileSync(marker, 'utf8'), 'start\\nstop\\n');
      assert.equal((await manager.setBundleEnabled('dshx-live-good', true)).application, 'applied');
      assert.equal(readFileSync(marker, 'utf8'), 'start\\nstop\\nstart\\n');
      const plugin = (await manager.listPlugins()).find(row => row.moduleName === 'dshx-live-good');
      assert.ok(plugin && !plugin.readOnlyReason, JSON.stringify(plugin));
      assert.equal((await manager.setPluginEnabled(plugin.entryId, false)).application, 'applied');
      assert.equal(readFileSync(marker, 'utf8'), 'start\\nstop\\nstart\\nstop\\n');
      assert.equal((await manager.setPluginEnabled(plugin.entryId, true)).application, 'applied');
      assert.equal(readFileSync(marker, 'utf8'), 'start\\nstop\\nstart\\nstop\\nstart\\n');
      const files = ['package.json', 'pnpm-lock.yaml', 'cordis.patch.yml'];
      const saved = files.map(file => readFileSync(join(profile, file), 'utf8'));
      const failed = await manager.installBundle(broken);
      assert.equal(failed.application, 'failed', JSON.stringify(failed));
      assert.match(failed.error.diagnostic, /actual activation failed/);
      assert.deepEqual(files.map(file => readFileSync(join(profile, file), 'utf8')), saved);
      assert.equal((await manager.listBundles()).find(row => row.name === 'dshx-live-good').enabled, true);
      const removed = await manager.removeBundle('dshx-live-good');
      assert.ok(['applied', 'restart-required'].includes(removed.application), JSON.stringify(removed));
      if (removed.application === 'restart-required') assert.equal(removed.restartReason, 'module-comparison', JSON.stringify(removed));
      assert.equal(readFileSync(marker, 'utf8').split('start\\n').length - 1, readFileSync(marker, 'utf8').split('stop\\n').length - 1);
      assert.equal((await manager.listBundles()).some(row => row.name === 'dshx-live-good'), false);
      console.log('Actual plugin lifecycle and activation rollback passed');
    } finally { await app.shutdown.shutdown(0); }
  `;
  const child = spawn(process.execPath, ['--expose-internals', '--import', join(core, 'compat.mjs'), '--input-type=module', '-e', script], {
    env: { ...process.env, DSH_HOME: join(dir, 'home') }, stdio: ['ignore', 'pipe', 'pipe']
  });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; }); child.stderr.on('data', chunk => { output += chunk; });
  const timeout = setTimeout(() => child.kill('SIGKILL'), 80000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    assert.equal(code, 0, output);
    assert.match(output, /Actual plugin lifecycle and activation rollback passed/);
  } finally { clearTimeout(timeout); if (child.exitCode === null) child.kill('SIGKILL'); }
});

// The fixture imitates the native wire protocol. Kotlin's real authority
// and native-only mode changes are exercised separately in CoreTest.kt.
async function policyServer({ phone } = {}) {
  const sockets = new Set();
  const sessions = new Map();
  const requests = [];
  let revision = 1;
  const server = createServer(socket => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => {});
    socket.setEncoding('utf8');
    let buffer = '';
    socket.on('data', chunk => {
      buffer += chunk;
      let newline;
      while ((newline = buffer.indexOf('\n')) !== -1) {
        const request = JSON.parse(buffer.slice(0, newline));
        buffer = buffer.slice(newline + 1);
        requests.push(request);
        if (request.version !== undefined && (request.token !== 'test-token' || request.instance !== 'test-instance')) {
          socket.destroy(); return;
        }
        if (request.method === 'register') {
          sessions.set(request.session, { id: request.session, mode: request.parent ? sessions.get(request.parent).mode :
            request.session === 'read-session' ? 'read-only' : request.session === 'full-session' ? 'full' : 'workspace-write' });
          revision++;
        }
        if (request.method === 'end') { sessions.delete(request.session); revision++; }
        if (request.method === 'mode' && request.session === 'lost-session') { socket.destroy(); return; }
        const respond = (response = {}) => {
          const ok = response.ok ?? true;
          socket.write(JSON.stringify({ id: request.id ?? 0, ok, ...(ok ? { state: {
            instance: 'test-instance', connected: true, revision, sessions: [...sessions.values()]
          } } : {}), ...response }) + '\n');
        };
        if (phone && ['phone', 'phone-cancel'].includes(request.method)) {
          phone(request, respond);
          continue;
        }
        const ok = request.method !== 'mode' || sessions.has(request.session) && request.session !== 'denied-session';
        respond(ok ? {} : { ok: false, error: 'unknown session' });
      }
    });
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return {
    config: { port: server.address().port, instance: 'test-instance', token: 'test-token' },
    requests,
    close: async () => { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); }
  };
}

test('phone actions share one request usage without double counting and link subsequent result input', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools, defineTool } = await load('@deepseek-ai/dsh-tools');
  const { default: LlmRuntime, LlmAdapter, markAgentLoopRequest } = await load('@deepseek-ai/dsh-llm');
  const { trackActions, forgetActions } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
  const dir = await mkdtemp(join(directory, 'action-shared-'));
  const previousDirectory = process.env.DSHX_ASSISTANT_DIR;
  const previousMode = policyHost.cachedMode;
  process.env.DSHX_ASSISTANT_DIR = dir;
  policyHost.cachedMode = () => 'full';
  const ctx = new Context();
  const usage = { inputTokens: 20, outputTokens: 8, cacheReadTokens: 3, reasoningTokens: 2 };
  class Adapter extends LlmAdapter {
    async *stream(options) {
      if (!options.messages.length) for (const [index, name] of ['phone_observe', 'phone_screenshot'].entries()) {
        const id = `call-${index}`;
        yield { type: 'tool-call-delta', index, id: '', name, argumentsDelta: '' };
        yield { type: 'tool-call-delta', index, id, name, argumentsDelta: '{"private":"never log"}' };
        yield { type: 'block-end', index, block: { type: 'tool-call', id, name, arguments: '{}' } };
      }
      yield { type: 'usage', usage };
      yield { type: 'usage', usage };
      yield { type: 'finish', reason: { kind: options.messages.length ? 'stop' : 'tool-calls' } };
    }
  }
  try {
    await ctx.plugin(SystemPrompt);
    await ctx.plugin(Tools);
    await ctx.plugin(LlmRuntime);
    trackActions(ctx);
    ctx.llm.registerAdapter(['fixture'], new Adapter());
    for (const name of ['phone_observe', 'phone_screenshot']) ctx.tools.register(defineTool({
      name, description: 'fixture', parameters: {},
      output: { schema: { type: 'object', additionalProperties: true }, render: () => [{ type: 'text', text: 'private result' }] },
      execute: async () => name === 'phone_observe'
        ? { ok: true, execution_state: 'completed', nodes: [{ text: 'private screen' }], truncated: true }
        : { ok: true, image: { bytes: 120, width: 12, height: 8 } },
    }));
    const promptBefore = await ctx.systemPrompt.assemble();
    const options = { provider: 'fixture', model: 'single', sessionId: 'shared-session', messages: [] };
    for await (const _ of ctx.llm.stream(markAgentLoopRequest(options))) {}
    const agent = { session: { id: options.sessionId } };
    for (const [index, name] of ['phone_observe', 'phone_screenshot'].entries()) {
      const result = await ctx.tools.execute({ name, callId: `call-${index}`, arguments: {}, agent, signal: new AbortController().signal });
      assert.equal(result.isError, false, JSON.stringify(result));
    }
    for await (const _ of ctx.llm.stream(markAgentLoopRequest({ ...options,
      messages: ['call-0', 'call-1'].map(toolCallId => ({ role: 'tool', toolCallId, content: [{ type: 'text', text: 'private tool content' }] })),
    }))) {}
    const text = await readFile(join(dir, 'actions.jsonl'), 'utf8');
    assert.doesNotMatch(text, /private|never log|arguments|content/);
    const rows = text.trim().split('\n').map(JSON.parse);
    const plans = rows.filter(row => row.event === 'action-plan');
    const ends = rows.filter(row => row.event === 'action-finish');
    assert.equal(plans.length, 2, 'delta and complete block must not duplicate the plan');
    assert.equal(ends.length, 2);
    assert.equal(new Set(ends.map(row => row.requestId)).size, 1);
    assert.equal(new Set(ends.map(row => row.id)).size, 2);
    assert.deepEqual(ends.map(row => row.source), ['component-tree', 'screenshot']);
    assert.ok(ends.every(row => row.requestActionCount === 2 && row.tokenScope === 'model-request' && row.usageStatus === 'reported' && row.usageSource === 'adapter'));
    assert.ok(ends.every(row => row.dispatchReached && row.durationMs >= 0 && row.outcome === 'success'));
    assert.deepEqual(ends.map(row => row.requestUsage), [usage, usage]);
    assert.ok(ends.every(row => row.usageFinal === true));
    assert.equal(ends[0].nodeCount, 1);
    assert.equal(ends[0].treeTruncated, true);
    assert.deepEqual([ends[1].bytes, ends[1].width, ends[1].height], [120, 12, 8]);
    const requests = (await readFile(join(dir, 'usage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
    const starts = requests.filter(row => row.event === 'start');
    assert.equal(starts.length, 2);
    assert.equal(ends[0].requestId, starts[0].id);
    assert.deepEqual(starts[1].toolResultCallIds, ['call-0', 'call-1']);
    assert.equal(requests.filter(row => row.event === 'usage').length, 2);
    assert.deepEqual(await ctx.systemPrompt.assemble(), promptBefore, 'action statistics must not update model prompts');
    assert.deepEqual(options.messages, [], 'accounting must not inject metadata into model context');
  } finally {
    forgetActions('shared-session', dir);
    policyHost.cachedMode = previousMode;
    if (previousDirectory === undefined) delete process.env.DSHX_ASSISTANT_DIR;
    else process.env.DSHX_ASSISTANT_DIR = previousDirectory;
    await ctx.fiber.dispose();
  }
});

test('phone actions link real provider usage to the successful HTTP request after a failed send', async t => {
  const { trackActions, forgetActions } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const { markAgentLoopRequest } = await load('@deepseek-ai/dsh-llm');
  let sends = 0;
  const fixture = await assistantServer(t, (_request, response) => {
    if (++sends === 1) { response.writeHead(503); response.end('{"error":{"message":"private failure"}}'); return; }
    assistantEvents(response, [
      { type: 'message_start', message: { id: 'fixture-message', model: 'single', usage: { input_tokens: 11, cache_read_input_tokens: 3 } } },
      { type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'provider-call', name: 'phone_observe', input: {} } },
      { type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{}' } },
      { type: 'content_block_stop', index: 0 },
      { type: 'message_delta', delta: { stop_reason: 'tool_use' }, usage: { output_tokens: 5 } },
      { type: 'message_stop' },
    ]);
  });
  const options = markAgentLoopRequest(await assistantDeepSeek(fixture));
  t.after(() => forgetActions(options.sessionId, fixture.dir));
  const handlers = new Map();
  trackActions({ on: (name, handler) => handlers.set(name, handler), effect() {} }, fixture.dir);
  assert.equal((await fixture.collect(fixture.ctx.llm.stream(options))).at(-1).reason.kind, 'error');
  assert.equal((await fixture.collect(fixture.ctx.llm.stream(options))).at(-1).reason.kind, 'tool-calls');
  const exec = { token: Symbol(), callId: 'provider-call', name: 'phone_observe', agent: { session: { id: options.sessionId } } };
  handlers.get('tools/pre-execute')(exec, () => {});
  handlers.get('tools/result')(exec, { isError: false, value: { ok: true, nodes: [] } });
  const rows = (await readFile(join(fixture.dir, 'actions.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
  const end = rows.find(row => row.event === 'action-finish');
  const requests = await fixture.rows();
  const starts = requests.filter(row => row.event === 'start');
  assert.equal(starts.length, 2);
  assert.ok(starts.every(row => row.dispatch === 'http-request'));
  assert.equal(end.requestId, starts[1].id);
  assert.equal(end.usageSource, 'provider');
  assert.deepEqual(end.requestUsage, { inputTokens: 11, cacheReadTokens: 3, outputTokens: 5 });
  assert.equal(end.requestActionCount, 1);
  assert.doesNotMatch(await readFile(join(fixture.dir, 'actions.jsonl'), 'utf8'), /private|fixture-private-key/);
});

test('phone actions isolate concurrent sessions retries missing usage and late manual calls', async () => {
  const { usageStream, trackActions, forgetActions } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const dir = await mkdtemp(join(directory, 'action-sessions-'));
  const handlers = new Map();
  trackActions({ on: (name, handler) => handlers.set(name, handler), effect() {} }, dir);
  const stream = async (sessionId, usage, outcome = 'tool-calls') => {
    for await (const _ of usageStream({ provider: 'fixture', model: 'single', sessionId, purpose: 'conversation' }, async function* () {
      await delay(1);
      yield { type: 'block-end', block: { type: 'tool-call', id: 'reused-call', name: 'phone_click', arguments: '{"private":"input"}' } };
      if (usage) yield { type: 'usage', usage };
      yield { type: 'finish', reason: { kind: outcome } };
    }, dir)) {}
  };
  const result = (sessionId, nested = false) => {
    const exec = { token: Symbol(), callId: nested ? 'child-call' : 'reused-call', rootCallId: 'reused-call',
      name: nested ? 'read' : 'phone_click', agent: { session: { id: sessionId } } };
    handlers.get('tools/pre-execute')(exec, () => {});
    handlers.get('tools/result')(exec, { isError: false, value: { ok: true } });
  };
  try {
    await Promise.all([stream('one', { inputTokens: 0, outputTokens: 0 }), stream('two', { outputTokens: 7 })]);
    result('one'); result('two'); result('two', true);
    await stream('one', undefined); result('one');
    await stream('one', { inputTokens: 99 }, 'error'); result('one');
    forgetActions('two', dir); result('two');
    const rows = (await readFile(join(dir, 'actions.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
    const ends = rows.filter(row => row.event === 'action-finish');
    assert.deepEqual(ends.map(row => row.requestUsage), [{ inputTokens: 0, outputTokens: 0 }, { outputTokens: 7 }, { outputTokens: 7 }, null, null, null]);
    assert.deepEqual(ends.map(row => row.usageStatus), ['reported', 'partial', 'partial', 'unknown', 'unknown', 'unknown']);
    assert.notEqual(ends[0].requestId, ends[1].requestId);
    assert.equal(ends[1].requestId, ends[2].requestId, 'nested tools inherit the root decision request');
    assert.equal(ends[2].tool, 'read');
    assert.equal(ends[2].rootCallId, 'reused-call');
    assert.notEqual(ends[0].requestId, ends[3].requestId);
    assert.equal(ends[4].requestId, null, 'failed attempts must not own a later action');
    assert.equal(ends[5].requestId, null, 'idle/disposal must clear old plans');
    const plans = rows.filter(row => row.event === 'action-plan');
    assert.equal(plans.at(-1).requestOutcome, 'failed');
    assert.deepEqual(plans.at(-1).requestUsage, { inputTokens: 99 });
  } finally { forgetActions('one', dir); forgetActions('two', dir); }
});

test('phone actions record native failure unknown cancellation and final policy rejection without replay', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools, defineTool } = await load('@deepseek-ai/dsh-tools');
  const { trackActions } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
  const original = policyHost.cachedMode;
  policyHost.cachedMode = () => 'full';
  const ctx = new Context();
  const dir = await mkdtemp(join(directory, 'action-outcomes-'));
  let bodies = 0;
  try {
    await ctx.plugin(SystemPrompt);
    await ctx.plugin(Tools);
    trackActions(ctx, dir);
    ctx.tools.register(defineTool({
      name: 'phone_click', description: 'fixture', parameters: {},
      output: { schema: { type: 'object', additionalProperties: true }, render: () => [] },
      execute: async (_args, exec) => {
        bodies++;
        if (exec.callId === 'throw') throw new Error('private failure details');
        return exec.callId === 'unknown' ? { ok: false, execution_state: 'unknown', code: 'cancelled' }
          : exec.callId === 'native' ? { ok: false, execution_state: 'not_started', code: 'stale_snapshot' } : { ok: true };
      },
    }));
    ctx.tools.guard(exec => exec.callId === 'denied' ? 'private guard reason' : undefined);
    ctx.on('tools/pre-execute', (exec, next) => exec.callId === 'policy-cancel' ? { kind: 'cancel' } : next());
    ctx.on('tools/post-execute', (exec, _result, next) => exec.callId === 'post'
      ? { kind: 'block', feedback: [{ type: 'text', text: 'private post reason' }] } : next());
    for (const callId of ['native', 'unknown', 'throw', 'denied', 'cancelled', 'policy-cancel', 'post']) {
      const controller = new AbortController();
      if (callId === 'cancelled') controller.abort();
      const result = await ctx.tools.execute({ name: 'phone_click', callId, arguments: {}, signal: controller.signal });
      if (callId === 'policy-cancel') {
        assert.equal(controller.signal.aborted, false);
        assert.equal(result.error?.info?.code, 'ABORTED_BEFORE_DISPATCH');
      }
      if (callId === 'post') {
        assert.equal(result.isError, true);
        assert.deepEqual(result.content, [{ type: 'text', text: 'private post reason' }]);
        assert.equal(result.error.message, 'private post reason');
      }
    }
    const text = await readFile(join(dir, 'actions.jsonl'), 'utf8');
    assert.doesNotMatch(text, /private/);
    const rows = text.trim().split('\n').map(JSON.parse);
    const ends = rows.filter(row => row.event === 'action-finish');
    assert.equal(ends.length, 7);
    assert.deepEqual(ends.map(row => row.outcome), ['failed', 'unknown', 'failed', 'failed', 'cancelled', 'cancelled', 'failed']);
    assert.equal(ends[0].code, 'stale_snapshot');
    assert.equal(ends[1].executionState, 'unknown');
    assert.equal(ends[3].dispatchReached, false);
    assert.equal(ends[4].dispatchReached, false);
    assert.equal(ends[4].code, 'ABORTED_BEFORE_DISPATCH');
    assert.equal(ends[5].dispatchReached, false);
    assert.equal(ends[5].code, 'ABORTED_BEFORE_DISPATCH');
    assert.ok(ends.every(row => row.requestId === null && row.requestUsage === null));
    assert.equal(bodies, 4, 'recording must never replay a tool');
    assert.equal(rows.filter(row => row.event === 'action-start').length, 7);
    assert.equal((await stat(join(dir, 'actions.jsonl'))).mode & 0o777, 0o600);
  } finally { policyHost.cachedMode = original; await ctx.fiber.dispose(); }
});

test('phone actions record queued cancellations from the real agent scheduler once outside model context', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools, defineTool } = await load('@deepseek-ai/dsh-tools');
  const { default: LlmRuntime, LlmAdapter, createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const { default: Agents } = await load('@deepseek-ai/dsh-agent');
  const { default: Sessions } = await load('@deepseek-ai/dsh-session');
  const { default: Projections } = await load('@deepseek-ai/dsh-session-projection');
  const { default: AgentLoop } = await load('@deepseek-ai/dsh-agent-loop');
  const { trackActions, forgetActions } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
  const dir = await mkdtemp(join(directory, 'action-queued-'));
  const previousDirectory = process.env.DSHX_ASSISTANT_DIR;
  const previousMode = policyHost.cachedMode;
  process.env.DSHX_ASSISTANT_DIR = dir;
  policyHost.cachedMode = () => 'full';
  const ctx = new Context();
  let bodies = 0;
  class Adapter extends LlmAdapter {
    async *stream() {
      for (let index = 0; index < 2; index++) {
        yield { type: 'tool-call-delta', index, id: '', name: 'phone_click', argumentsDelta: '' };
        yield { type: 'tool-call-delta', index, id: `queued-${index}`, argumentsDelta: '{}' };
      }
      yield { type: 'usage', usage: { inputTokens: 9, outputTokens: 4 } };
      yield { type: 'finish', reason: { kind: 'tool-calls' } };
    }
  }
  try {
    for (const plugin of [SystemPrompt, Tools, LlmRuntime, Agents, Sessions, Projections]) await ctx.plugin(plugin);
    await ctx.plugin(AgentLoop, { agents: [], maxParallelToolCalls: 1 });
    trackActions(ctx);
    ctx.llm.registerAdapter(['fixture'], new Adapter());
    ctx.tools.register(defineTool({
      name: 'phone_click', description: 'fixture', parameters: {},
      output: { schema: { type: 'object', additionalProperties: true }, render: () => [] },
      execute: async (_args, exec) => { bodies++; exec.agent.cancel({ kind: 'user' }); return { ok: true }; },
    }));
    const events = [];
    ctx.on('session/event', (session, event) => { if (session.id === 'queued-session') events.push(event); });
    const agent = await ctx.agentLoop.create('queued-session', { provider: 'fixture', model: 'single' });
    agent.followup(createUserMessage({ content: [{ type: 'text', text: 'private request' }], source: { kind: 'user' } }));
    await agent.whenIdle();
    const results = events.filter(event => event.type === 'tool/result');
    assert.equal(results.length, 2, `the scheduler must resolve both model calls: ${JSON.stringify(events)}`);
    assert.equal(results[1].data.error.code, 'ABORTED_BEFORE_DISPATCH');
    // Re-publishing the same skipped event cannot create another accounting row.
    ctx.emit('session/event', agent.session, results[1]);
    const rows = (await readFile(join(dir, 'actions.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
    const plans = rows.filter(row => row.event === 'action-plan');
    const ends = rows.filter(row => row.event === 'action-finish');
    assert.equal(bodies, 1);
    assert.equal(plans.length, 2);
    assert.equal(ends.length, 2);
    assert.deepEqual(ends.map(row => row.callId), ['queued-0', 'queued-1']);
    assert.ok(ends.every(row => row.outcome === 'cancelled' && row.requestActionCount === 2));
    assert.equal(ends[1].executionState, 'not_started');
    assert.equal(ends[1].dispatchReached, false);
    assert.deepEqual(ends[1].requestUsage, { inputTokens: 9, outputTokens: 4 });
    assert.equal(ends[0].requestId, ends[1].requestId);
    assert.doesNotMatch(JSON.stringify(events), /requestUsage|requestActionCount|tokenScope|durationMs|action-finish/);
  } finally {
    forgetActions('queued-session', dir);
    policyHost.cachedMode = previousMode;
    if (previousDirectory === undefined) delete process.env.DSHX_ASSISTANT_DIR;
    else process.env.DSHX_ASSISTANT_DIR = previousDirectory;
    await ctx.fiber.dispose();
  }
});

test('phone bridge real agent bounds stopped planning allows an explanation and clears the next turn', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools, defineTool } = await load('@deepseek-ai/dsh-tools');
  const { default: LlmRuntime, LlmAdapter, createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const { default: Agents } = await load('@deepseek-ai/dsh-agent');
  const { default: Sessions } = await load('@deepseek-ai/dsh-session');
  const { default: Projections } = await load('@deepseek-ai/dsh-session-projection');
  const { default: AgentLoop } = await load('@deepseek-ai/dsh-agent-loop');
  const { trackPhone } = await import(pathToFileURL(join(core, 'phone.mjs')));
  const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
  const previousMode = policyHost.cachedMode;
  policyHost.cachedMode = () => 'full';
  const ctx = new Context();
  let requests = 0;
  let sends = 0;
  let explain = false;
  const explanationStarted = Promise.withResolvers();
  const finishExplanation = Promise.withResolvers();
  class Adapter extends LlmAdapter {
    async *stream(options) {
      requests++;
      if (requests > 6) throw new Error('fixture exceeded model request budget');
      assert.doesNotMatch(JSON.stringify(options.messages), /needsObserve|failures|explained|usageFinal/);
      if (explain && requests % 2 === 0) {
        if (requests === 4) { explanationStarted.resolve(); await finishExplanation.promise; }
        yield { type: 'text-delta', index: 0, text: '手机开关已关闭，请启用后重新操作。' };
        yield { type: 'finish', reason: { kind: 'stop' } };
      } else {
        yield { type: 'tool-call-delta', index: 0, id: `stop-${requests}`, name: 'phone_observe', argumentsDelta: '{}' };
        yield { type: 'finish', reason: { kind: 'tool-calls' } };
      }
    }
  }
  try {
    for (const plugin of [SystemPrompt, Tools, LlmRuntime, Agents, Sessions, Projections]) await ctx.plugin(plugin);
    await ctx.plugin(AgentLoop, { agents: [], maxParallelToolCalls: 1 });
    trackPhone(ctx);
    ctx.llm.registerAdapter(['fixture'], new Adapter());
    ctx.tools.register(defineTool({
      name: 'phone_observe', description: 'fixture', parameters: {},
      output: { schema: { type: 'object', additionalProperties: true }, render: (_args, value) => phoneContent(value) },
      execute: (args, exec) => callPhone(ctx, { phone: async () => { sends++; return { ok: false, code: 'disabled', execution_state: 'not_started' }; } }, 'observe', args, exec),
    }));
    const agent = await ctx.agentLoop.create('stop-session', { provider: 'fixture', model: 'single' });
    const prompt = (text = '打开手机应用') => agent.followup(createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } }));
    prompt();
    await agent.whenIdle();
    assert.equal(requests, 2, 'an insistent model gets at most one explanation request');
    assert.equal(sends, 1, 'no native call may be dispatched after disabled');
    explain = true;
    const events = [];
    const statuses = [];
    ctx.on('session/event', (session, event) => { if (session.id === agent.session.id) events.push(event); });
    ctx.on('agent/status', ({ agent: current, status }) => { if (current === agent) statuses.push(status); });
    prompt();
    await Promise.race([explanationStarted.promise, agent.whenIdle().then(() => assert.fail('agent ended before explanation'))]);
    assert.deepEqual(statuses, ['running']);
    const queuedText = '请重新检查手机开关';
    prompt(queuedText);
    finishExplanation.resolve();
    await agent.whenIdle();
    assert.equal(requests, 6, 'a queued user turn must get its own phone check and explanation');
    assert.equal(sends, 3);
    assert.deepEqual(statuses, ['running', 'idle'], 'queued turns must reset state without an intervening idle');
    assert.ok(events.some(event => event.type === 'user/message' && event.data.content.some(part => part.text === queuedText)),
      'the queued user message must be committed rather than consumed by a rejected step');
    assert.ok(events.some(event => JSON.stringify(event).includes('手机开关已关闭')));
    assert.doesNotMatch(JSON.stringify(events), /needsObserve|failures|explained|usageFinal/);
  } finally { finishExplanation.resolve(); policyHost.cachedMode = previousMode; await ctx.fiber.dispose(); }
});

test('phone window guard finishes only when the agent is idle, never between model requests', async () => {
  const { apply } = await import(pathToFileURL(join(core, 'guard.mjs')));
  const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
  const original = policyHost.finishPhone;
  const finished = [];
  const handlers = new Map();
  policyHost.finishPhone = async session => { finished.push(session); };
  try {
    await apply({
      agents: { list: () => [] }, tools: { guard() {} }, systemPrompt: { section() {} },
      on: (event, handler) => { handlers.set(event, handler); }, effect() {}, get() {},
    });
    const agent = { session: { id: 'window-session' } };
    const config = { provider: 'fixture', model: 'fixture' };
    assert.equal(await handlers.get('agent/request')({ agent }, async () => config), config);
    handlers.get('agent/status')({ agent, status: 'running' });
    assert.deepEqual(finished, []);
    handlers.get('agent/status')({ agent, status: 'idle' });
    assert.deepEqual(finished, ['window-session']);
  } finally { policyHost.finishPhone = original; }
});

test('phone window PolicyHost finishes the window without ending the live conversation', async () => {
  const fixture = await policyServer();
  const host = new PolicyHost(fixture.config);
  try {
    await host.register('full-session', 'Window');
    await host.finishPhone('full-session');
    assert.equal(await host.mode('full-session'), 'full');
    const request = fixture.requests.find(row => row.method === 'phone-finish');
    assert.deepEqual(request, { id: request.id, method: 'phone-finish', session: 'full-session' });
    assert.equal(fixture.requests.some(row => row.method === 'end'), false);
  } finally { host.close(); await fixture.close(); }
});

test('phone bridge compact tree preserves exceptions credentials and the native result', () => {
  const value = { ok: true, snapshot: 'snapshot-1', nodes: [{
    id: 'node-1', text: '文字', actions: [], enabled: false, bounds: [0, 1, 2, 3],
    editable: false, focused: false, checkable: false, checked: false, selected: false,
    description: '', stateDescription: '', clickTarget: '', selectionStart: -1, selectionEnd: -1,
    textTruncated: false,
  }, { id: 'node-2', actions: ['input'], enabled: true, editable: true, textTruncated: true,
    text: '部分文字', selectionStart: 0, selectionEnd: 2, description: '备注', clickTarget: 'node-3' }] };
  const before = structuredClone(value);
  const content = JSON.parse(phoneContent(value)[0].text);
  assert.deepEqual(content.nodes[0], { id: 'node-1', text: '文字', actions: [], enabled: false, bounds: [0, 1, 2, 3] });
  assert.deepEqual(content.nodes[1], value.nodes[1]);
  assert.equal(content.snapshot, value.snapshot);
  assert.deepEqual(value, before, 'compact rendering must not mutate the native receipt');
});

test('phone bridge failure state isolates agents requires observation and resets at idle', async () => {
  const { trackPhone } = await import(pathToFileURL(join(core, 'phone.mjs')));
  const handlers = new Map();
  trackPhone({ on: (event, handler) => handlers.set(event, handler) });
  const agent = { session: { id: 'failure-session' } };
  const other = { session: { id: 'other-session' } };
  let sends = 0;
  const host = { phone: async action => {
    sends++;
    return action === 'click' ? { ok: false, code: 'stale_snapshot', execution_state: 'not_started' } : { ok: true };
  } };
  const call = (action, who = agent) => callPhone({}, host, action, {}, { agent: who });
  assert.equal((await call('click')).code, 'stale_snapshot');
  assert.equal((await call('click')).code, 'observation_required');
  assert.equal(sends, 1, 'a failed action cannot be blindly replayed');
  await call('observe');
  assert.equal((await call('click')).code, 'stale_snapshot');
  assert.equal((await call('observe')).code, 'phone_stopped');
  assert.equal(sends, 3, 'observations must not reset a loop of stale actions');
  assert.deepEqual(await call('observe', other), { ok: true });
  const step = handlers.get('agent/pre-step');
  assert.equal(step({ agent }, () => 'explanation'), 'explanation');
  assert.deepEqual(step({ agent }, () => assert.fail('unbounded follow-up')), { kind: 'reject' });
  handlers.get('agent/status')({ agent, status: 'idle' });
  assert.deepEqual(await call('observe'), { ok: true });
  for (const code of ['disabled', 'accessibility_offline', 'locked', 'screen_off']) {
    handlers.get('agent/disposed')({ agent });
    await callPhone({}, { phone: async () => ({ ok: false, code }) }, 'observe', {}, { agent });
    assert.equal((await call('status')).code, 'phone_stopped');
  }
  handlers.get('agent/status')({ agent, status: 'idle' });
  await callPhone({}, { phone: async () => ({ ok: true, enabled: false }) }, 'status', {}, { agent });
  assert.equal((await call('observe')).code, 'phone_stopped');
});

test('phone bridge callPhone uses the executing session and signal instead of argument or agent lookalikes', async () => {
  const controller = new AbortController();
  const other = new AbortController();
  other.abort();
  const args = { snapshot: 'snapshot-1', node: 'node-1', session: 'argument-session' };
  const exec = { signal: controller.signal, agent: { id: 'agent-id', signal: other.signal, session: { id: 'current-session' } } };
  const result = { accepted: true, snapshot: 'snapshot-2' };
  const calls = [];
  const host = { phone: async (...values) => { calls.push(values); return result; } };
  const ctx = { get: () => assert.fail('non-image actions must not resolve model or attachment services') };
  assert.equal(await callPhone(ctx, host, 'click', args, exec), result);
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0], ['click', args, 'current-session', controller.signal]);
  assert.equal(calls[0][1], args);
  assert.equal(calls[0][3], controller.signal);
  assert.deepEqual(phoneContent(result), [{ type: 'text', text: JSON.stringify(result) }]);
});

test('phone bridge callPhone rejects pre-cancellation before any lookup or host send', async () => {
  const controller = new AbortController();
  const reason = new Error('fixture phone call already cancelled');
  controller.abort(reason);
  const ctx = { get: () => assert.fail('pre-cancellation must not resolve services') };
  const host = { phone: () => assert.fail('pre-cancellation must not send a phone request') };
  for (const action of ['status', 'screenshot']) {
    await assert.rejects(callPhone(ctx, host, action, {}, {
      signal: controller.signal, agent: { session: { id: 'current-session' } }
    }), error => error === reason);
  }
});

test('phone bridge callPhone discards a host result when cancellation arrives during the call', async () => {
  const controller = new AbortController();
  const reason = new Error('fixture phone call cancelled in flight');
  let calls = 0;
  const host = { phone: async () => { calls++; controller.abort(reason); return { accepted: true }; } };
  await assert.rejects(callPhone({}, host, 'back', {}, {
    signal: controller.signal, agent: { session: { id: 'current-session' } }
  }), error => error === reason);
  assert.equal(calls, 1);
});

test('phone bridge screenshot refuses text-only or undeclared image support before fetching a screen', async () => {
  const signal = new AbortController().signal;
  for (const inputModalities of [['text'], undefined]) {
    const resolutions = [];
    let captures = 0;
    let saves = 0;
    const services = {
      llm: { resolveModelInfo: async (...args) => { resolutions.push(args); return { inputModalities }; } },
      attachments: { saveImage: async () => { saves++; assert.fail('unsupported models must not save screenshots'); } }
    };
    const host = { phone: async () => { captures++; assert.fail('unsupported models must not fetch screenshots'); } };
    await assert.rejects(callPhone({ get: name => services[name] }, host, 'screenshot', {}, {
      signal, agent: {
        options: { provider: 'default-provider', model: 'default-image-model' },
        session: { id: 'current-session', requestHeader: () => ({ config: { provider: 'routed-provider', model: 'routed-text-model' } }) }
      }
    }), /未声明支持看图/);
    assert.deepEqual(resolutions, [['routed-provider', 'routed-text-model', signal]]);
    assert.equal(captures, 0);
    assert.equal(saves, 0);
  }
});

test('phone bridge screenshot model and attachment failures stop after three calls counted once each', async () => {
  const { trackPhone } = await import(pathToFileURL(join(core, 'phone.mjs')));
  const handlers = new Map();
  trackPhone({ on: (event, handler) => handlers.set(event, handler) });
  const step = handlers.get('agent/pre-step');
  for (const phase of ['model', 'attachment']) {
    const agent = { session: { id: `image-${phase}` }, options: { provider: 'fixture', model: 'image-model' } };
    let resolutions = 0;
    let captures = 0;
    let saves = 0;
    const attachmentError = new Error('fixture attachment save failed');
    const services = {
      llm: { resolveModelInfo: async () => {
        resolutions++;
        return { inputModalities: phase === 'model' ? ['text'] : ['image'] };
      } },
      attachments: { saveImage: async () => { saves++; throw attachmentError; } },
    };
    const host = { phone: async () => { captures++; return { ok: true, data: 'YWJj' }; } };
    const call = () => callPhone({ get: name => services[name] }, host, 'screenshot', {}, { agent });
    for (let attempt = 0; attempt < 3; attempt++) {
      assert.equal(step({ agent, turn: 1 }, () => 'enter'), 'enter');
      await assert.rejects(call(), error => phase === 'model' ? /未声明支持看图/.test(error.message) : error === attachmentError);
    }
    assert.equal((await call()).code, 'phone_stopped', phase);
    assert.equal(resolutions, 3, 'each of the three failures must count exactly once');
    assert.equal(captures, phase === 'model' ? 0 : 3);
    assert.equal(saves, phase === 'model' ? 0 : 3);
    assert.equal(step({ agent, turn: 1 }, () => 'explanation'), 'explanation');
    assert.deepEqual(step({ agent, turn: 1 }, () => assert.fail('unbounded screenshot retry')), { kind: 'reject' });
    assert.equal(step({ agent, turn: 2 }, () => 'new turn'), 'new turn');
    await assert.rejects(call(), error => phase === 'model' ? /未声明支持看图/.test(error.message) : error === attachmentError);
    assert.equal(resolutions, 4, 'a new turn must reset the screenshot failure budget');
    handlers.get('agent/disposed')({ agent });
  }
});

test('phone bridge screenshot saves a real image attachment and keeps base64 out of text content', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: Attachments } = await load('@deepseek-ai/dsh-attachment-local');
  const sharp = require('sharp');
  const ctx = new Context();
  const signal = new AbortController().signal;
  try {
    await ctx.plugin(Attachments, { dshHome: join(directory, 'phone-image-home') });
    const data = await sharp({ create: { width: 8, height: 6, channels: 3, background: '#2a64b4' } }).jpeg().toBuffer();
    const encoded = data.toString('base64');
    const resolutions = [];
    const saves = [];
    const services = {
      llm: { resolveModelInfo: async (...args) => { resolutions.push(args); return { inputModalities: ['text', 'image'] }; } },
      attachments: { saveImage: async input => { saves.push(input); return ctx.attachments.saveImage(input); } }
    };
    const calls = [];
    const details = { width: 8, height: 6, mediaType: 'image/jpeg' };
    const host = { phone: async (...args) => { calls.push(args); return { ...details, data: encoded }; } };
    const exec = { signal, agent: { session: { id: 'image-session', requestHeader: () => undefined }, options: { provider: 'fixture', model: 'image-model' } } };
    const value = await callPhone({ get: name => services[name] }, host, 'screenshot', {}, exec);
    assert.deepEqual(resolutions, [['fixture', 'image-model', signal]]);
    assert.deepEqual(calls, [['screenshot', {}, 'image-session', signal]]);
    assert.deepEqual(saves, [{ data, mediaType: 'image/jpeg', name: 'phone-screen.jpg' }]);
    assert.equal(Object.hasOwn(value, 'data'), false);
    assert.ok(value.image.attachmentId);
    const stored = await ctx.attachments.readImage(value.image);
    const metadata = await sharp(stored.data).metadata();
    assert.equal(metadata.width, 8);
    assert.equal(metadata.height, 6);
    const content = phoneContent(value);
    assert.deepEqual(content, [{ type: 'text', text: JSON.stringify(details) }, { type: 'image', attachment: value.image }]);
    assert.equal(content[1].attachment, value.image);
    assert.equal(JSON.stringify(content).includes(encoded), false);
    assert.equal(Object.hasOwn(JSON.parse(content[0].text), 'image'), false);
    // Valid base64 containing non-image bytes must still fail the real store.
    await assert.rejects(callPhone({ get: name => services[name] }, {
      phone: async () => ({ data: Buffer.from('not a JPEG image').toString('base64') })
    }, 'screenshot', {}, exec));
  } finally { await ctx.fiber.dispose(); }
});

test('phone bridge screenshot delegates to a prepared image model without changing a text-only main model', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: LlmRuntime, LlmAdapter } = await load('@deepseek-ai/dsh-llm');
  const { default: Attachments } = await load('@deepseek-ai/dsh-attachment-local');
  const sharp = require('sharp');
  const ctx = new Context(), requests = [], resolutions = [];
  const dir = await mkdtemp(join(directory, 'phone-recognition-'));
  const previous = process.env.DSHX_ASSISTANT_DIR;
  process.env.DSHX_ASSISTANT_DIR = dir;
  const data = await sharp({ create: { width: 8, height: 6, channels: 3, background: '#73716d' } }).jpeg().toBuffer();
  class Adapter extends LlmAdapter {
    async resolveModel(provider, id) {
      resolutions.push(id);
      return { provider, id, name: id, inputModalities: id === 'vision' ? ['text', 'image'] : ['text'] };
    }
    async *stream(request) {
      requests.push(request);
      const image = request.messages[0].content.find(part => part.type === 'image').attachment;
      const stored = await ctx.attachments.readImage(image);
      const metadata = await sharp(stored.data).metadata();
      assert.equal(metadata.width, 8); assert.equal(metadata.height, 6);
      yield { type: 'text-delta', index: 0, text: '画面中央有“保存”按钮，中心坐标 (4, 3)。' };
      yield { type: 'usage', usage: { inputTokens: 32, outputTokens: 18 } };
      yield { type: 'finish', reason: { kind: 'stop' } };
    }
  }
  try {
    await ctx.plugin(LlmRuntime);
    await ctx.plugin(Attachments, { dshHome: dir });
    ctx.llm.registerAdapter(['fixture'], new Adapter());
    const options = { provider: 'fixture', model: 'main-text' };
    const exec = { signal: new AbortController().signal, agent: { session: { id: 'screen-session' }, options } };
    let config = { enabled: true, route: { provider: 'fixture', model: 'vision' } }, captures = 0;
    const host = {
      smallModel: async (session, signal) => { assert.equal(session, 'screen-session'); assert.equal(signal, exec.signal); return config; },
      phone: async () => { captures++; return { ok: true, screenshot: 'one-use-native-id', width: 8, height: 6, expiresInMs: 10000, data: data.toString('base64') }; },
    };
    const result = await callPhone(ctx, host, 'screenshot', {}, exec);
    assert.deepEqual(result.recognition, { provider: 'fixture', model: 'vision', text: '画面中央有“保存”按钮，中心坐标 (4, 3)。' });
    assert.equal(result.screenshot, 'one-use-native-id');
    assert.equal(result.width, 8); assert.equal(result.height, 6);
    assert.ok(result.expiresInMs >= 0 && result.expiresInMs <= 10000);
    assert.equal(Object.hasOwn(result, 'image'), false);
    assert.equal(Object.hasOwn(result, 'data'), false);
    assert.equal(phoneContent(result).some(part => part.type === 'image'), false);
    assert.deepEqual(options, { provider: 'fixture', model: 'main-text' });
    assert.deepEqual(resolutions, ['vision', 'vision']);
    assert.equal(requests.length, 1); assert.deepEqual(requests[0].tools, []);
    assert.equal(requests[0].purpose, 'screenshot-recognition');
    assert.equal(requests[0].sessionId, 'screen-session');
    assert.match(requests[0].system, /不能执行/);
    assert.equal(JSON.stringify(requests[0].messages).includes('main-text'), false);
    const rows = (await readFile(join(dir, 'usage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
    assert.equal(rows.find(row => row.event === 'start').purpose, 'screenshot-recognition');
    assert.deepEqual(rows.find(row => row.event === 'usage').usage, { inputTokens: 32, outputTokens: 18 });
    assert.equal(rows.filter(row => row.event === 'finish').length, 1);
    assert.doesNotMatch(JSON.stringify(rows), /保存|one-use-native-id/);
    // Both kinds of conversation can use the screenshot route; disabling it is immediate.
    options.model = 'vision';
    config = { enabled: false };
    const direct = await callPhone(ctx, host, 'screenshot', {}, exec);
    assert.ok(direct.image); assert.equal(direct.recognition, undefined);
    assert.equal(requests.length, 1);
    config = { enabled: true, route: { provider: 'fixture', model: 'local-text' } };
    const fallback = await callPhone(ctx, host, 'screenshot', {}, exec);
    assert.ok(fallback.image); assert.match(fallback.recognitionFallback, /不支持图片/);
    options.model = 'main-text';
    await assert.rejects(callPhone(ctx, host, 'screenshot', {}, exec), /未声明支持看图/);
    assert.equal(captures, 3, 'unsupported models must be rejected before capturing');
  } finally {
    await ctx.fiber.dispose();
    if (previous === undefined) delete process.env.DSHX_ASSISTANT_DIR; else process.env.DSHX_ASSISTANT_DIR = previous;
  }
});

test('phone bridge screenshot recognition rejects broken streams and cancellation without a second capture or model fallback', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: Attachments } = await load('@deepseek-ai/dsh-attachment-local');
  const sharp = require('sharp');
  const ctx = new Context();
  const data = await sharp({ create: { width: 8, height: 6, channels: 3, background: '#73716d' } }).jpeg().toBuffer();
  try {
    await ctx.plugin(Attachments, { dshHome: join(directory, 'phone-recognition-failures') });
    for (const mode of ['empty', 'partial', 'overflow', 'tool', 'error', 'cancel', 'metadata']) {
      const controller = new AbortController();
      let captures = 0, streams = 0, resolutions = 0;
      const info = { provider: 'fixture', id: 'vision', inputModalities: ['image'] };
      const services = { attachments: ctx.attachments, llm: {
        resolveModelInfo: async () => { resolutions++; return info; },
        prepareCall: async (config, signal, refine) => {
          refine(config, mode === 'metadata' ? { ...info, id: 'changed' } : info);
          return { config, stream: async function* (request) {
            streams++;
            if (mode === 'error') throw new Error('fixture recognition failed');
            if (mode === 'cancel') {
              controller.abort(new Error('fixture cancelled'));
              await new Promise(() => {}); // adapter ignores the signal
            }
            if (mode === 'tool') yield { type: 'tool-call-delta', name: 'phone_tap' };
            if (mode === 'overflow') yield { type: 'text-delta', text: 'a'.repeat(16385) };
            if (mode === 'partial') yield { type: 'text-delta', text: 'incomplete' };
            if (mode === 'empty') yield { type: 'finish', reason: { kind: 'stop' } };
          } };
        },
      } };
      const host = {
        smallModel: async () => ({ enabled: true, route: { provider: 'fixture', model: 'vision' } }),
        phone: async () => { captures++; return { ok: true, width: 8, height: 6, data: data.toString('base64') }; },
      };
      await assert.rejects(callPhone({ get: name => services[name] }, host, 'screenshot', {}, {
        signal: controller.signal, agent: { session: { id: `recognition-${mode}` }, options: { provider: 'fixture', model: 'main-image' } },
      }), error => /截图|fixture/.test(error.message), mode);
      assert.equal(captures, mode === 'metadata' ? 0 : 1, mode);
      assert.equal(streams, mode === 'metadata' ? 0 : 1, mode);
      assert.equal(resolutions, 1, 'recognition failure must not resolve the main model for a retry');
    }
  } finally { await ctx.fiber.dispose(); }
});

test('phone bridge screenshot recognition consumes coordinate lifetime without renewing native credentials', async () => {
  const controller = new AbortController();
  const info = { provider: 'fixture', id: 'vision', inputModalities: ['image'] };
  const services = {
    attachments: { saveImage: async () => ({ attachmentId: 'fixture' }) },
    llm: { resolveModelInfo: async () => info, prepareCall: async (config, signal, refine) => {
      refine(config, info);
      return { config, stream: async function* () {
        await delay(10);
        yield { type: 'text-delta', text: '识别内容' };
        yield { type: 'finish', reason: { kind: 'stop' } };
      } };
    } },
  };
  const result = await callPhone({ get: name => services[name] }, {
    smallModel: async () => ({ enabled: true, route: { provider: 'fixture', model: 'vision' } }),
    phone: async () => ({ ok: true, width: 8, height: 6, screenshot: 'native-id', expiresInMs: 1, data: 'YWJj' }),
  }, 'screenshot', {}, { signal: controller.signal, agent: { session: { id: 'expired-screen' } } });
  assert.equal(result.expiresInMs, 0);
  assert.equal(result.screenshot, 'native-id');
  assert.match(result.coordinateNotice, /已过期/);
});

test('phone bridge screenshot rejects malformed or oversized base64 before saving an attachment', async () => {
  let captures = 0;
  let saves = 0;
  const services = {
    llm: { resolveModelInfo: async () => ({ inputModalities: ['image'] }) },
    attachments: { saveImage: async () => { saves++; assert.fail('invalid screenshot data must not reach the attachment store'); } }
  };
  const invalid = [undefined, null, 123, '', '!not-base64!', 'data:image/jpeg;base64,YQ==', 'YQ\n==', 'Y===', 'Y=Q=', 'A'.repeat(3 * 1024 * 1024 + 1)];
  for (const data of invalid) {
    const exec = { signal: new AbortController().signal, agent: { session: { id: 'image-session' }, options: { provider: 'fixture', model: 'image-model' } } };
    const host = { phone: async () => { captures++; return { data }; } };
    await assert.rejects(callPhone({ get: name => services[name] }, host, 'screenshot', {}, exec), /手机截图数据无效/);
  }
  assert.equal(captures, invalid.length);
  assert.equal(saves, 0);
});

test('phone bridge real plugin registers all 28 tools with validated schemas and native content rendering', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools } = await load('@deepseek-ai/dsh-tools');
  const Phone = await import(pathToFileURL(join(core, 'phone.mjs')));
  const ctx = new Context();
  const parameters = {
    phone_status: [], phone_apps: ['query', 'userOnly'], phone_open: ['package'], phone_observe: [],
    phone_click: ['snapshot', 'node'], phone_input: ['snapshot', 'node', 'text'],
    phone_scroll: ['snapshot', 'node', 'direction'], phone_back: [], phone_home: [], phone_screenshot: [],
    phone_device: [], phone_key: ['name'], phone_tap: ['screenshot', 'x', 'y'], phone_swipe: ['screenshot', 'x', 'y', 'endX', 'endY', 'ms'],
    phone_clipboard: ['mode', 'text'], phone_read_file: ['path'], phone_export: ['path', 'name'],
    phone_notify: ['title', 'text'], phone_toast: ['text'], phone_ask: ['question', 'options'], phone_vibrate: ['ms'],
    phone_share: ['text', 'path'], phone_open_url: ['url'], phone_overlay: ['text'], phone_location: ['fresh'],
    phone_sensors: [], phone_sensor: ['name'], phone_torch: ['on']
  };
  try {
    await ctx.plugin(SystemPrompt);
    await ctx.plugin(Tools);
    await ctx.plugin(Phone);
    assert.deepEqual([...ctx.tools.view().visible.keys()].sort(), Object.keys(parameters).sort());
    for (const [name, fields] of Object.entries(parameters)) {
      const tool = ctx.tools.get(name);
      assert.ok(tool, name);
      assert.ok(tool.description.length > 0, name);
      assert.equal(tool.parameters.type, 'object', name);
      assert.deepEqual(Object.keys(tool.parameters.properties).sort(), [...fields].sort(), name);
      const definition = Phone.phoneTools.find(row => row.name === name);
      const required = Object.entries(definition.parameters).filter(([, value]) => value.required).map(([key]) => key);
      assert.deepEqual([...(tool.parameters.required ?? [])].sort(), required.sort(), name);
      for (const field of fields) {
        assert.equal(tool.parameters.properties[field].type, definition.parameters[field].type, `${name}.${field}`);
        assert.ok(tool.parameters.properties[field].description.length > 0, `${name}.${field}`);
      }
      assert.deepEqual(tool.output.schema, { type: 'object', additionalProperties: true });
      const args = Object.fromEntries(fields.map(field => [field, field === 'direction' ? 'forward' : field === 'text' ? '' : 'fixture']));
      assert.equal(tool.isConcurrencySafe(args), false, name);
      assert.deepEqual(tool.output.render({}, { accepted: true }), [{ type: 'text', text: '{"accepted":true}' }]);
      if (required.length) await assert.rejects(tool.execute({}, {}), { code: 'INVALID_ARGS' });
    }
    assert.equal(Object.keys(parameters).length, 28);
    for (const [name, args] of [
      ['phone_tap', { screenshot: 's', x: 1.5, y: 0 }],
      ['phone_torch', { on: 'true' }],
      ['phone_ask', { question: 'q', options: [false] }],
      ['phone_location', { fresh: 'true' }],
      ['phone_key', { name: 'unknown' }],
    ]) await assert.rejects(ctx.tools.get(name).execute(args, {}), { code: 'INVALID_ARGS' });
    const scroll = ctx.tools.get('phone_scroll');
    assert.deepEqual(scroll.parameters.properties.direction.enum, ['forward', 'backward']);
    await assert.rejects(scroll.execute({ snapshot: 'snapshot-1', node: 'node-1', direction: 'left' }, {}), { code: 'INVALID_ARGS' });
    await assert.rejects(ctx.tools.get('phone_input').execute({ snapshot: 'snapshot-1', node: 'node-1', text: 123 }, {}), { code: 'INVALID_ARGS' });
    const image = { attachmentId: '0'.repeat(64), mediaType: 'image/jpeg', bytes: 100, width: 8, height: 6 };
    assert.deepEqual(ctx.tools.get('phone_screenshot').output.render({}, { width: 8, height: 6, image }), [
      { type: 'text', text: '{"width":8,"height":6}' }, { type: 'image', attachment: image }
    ]);
    const prompt = await ctx.systemPrompt.assemble();
    assert.equal(prompt.sections.filter(section => section.name === 'android:phone').length, 1);
    const phonePrompt = prompt.sections.find(section => section.name === 'android:phone').text;
    assert.match(phonePrompt, /组件树为第一选择/);
    assert.match(phonePrompt, /不得把截图作为每步操作前后的固定流程/);
    assert.match(ctx.tools.get('phone_observe').description, /首选/);
    assert.match(ctx.tools.get('phone_screenshot').description, /低优先级视觉后备/);
  } finally { await ctx.fiber.dispose(); }
});

test('phone bridge PolicyHost rejects missing sessions and pre-cancellation without sending a request', async () => {
  const local = new PolicyHost();
  await assert.rejects(local.phone('status', {}, 'full-session'), /需要当前原生会话连接/);
  const fixture = await policyServer();
  const host = new PolicyHost(fixture.config);
  try {
    for (const session of [undefined, '', 123]) await assert.rejects(host.phone('status', {}, session), /需要当前原生会话连接/);
    const controller = new AbortController();
    const reason = new Error('fixture native phone already cancelled');
    controller.abort(reason);
    await assert.rejects(host.phone('status', {}, 'full-session', controller.signal), error => error === reason);
    assert.deepEqual(fixture.requests, []);
    assert.equal(host.sequence, 0);
    assert.equal(host.pending.size, 0);
    assert.equal(await host.register('full-session', 'Full'), 'full');
  } finally { host.close(); await fixture.close(); }
});

test('phone bridge ask has a 65 second transport deadline and cancellation still consumes its late reply', async () => {
  let delayed;
  const fixture = await policyServer({ phone: (request, respond) => {
    if (request.method === 'phone-cancel') respond({ result: { cancelled: true } });
    else delayed = { request, respond };
  } });
  const host = new PolicyHost(fixture.config);
  const controller = new AbortController();
  try {
    await host.register('full-session', 'Full');
    const pending = host.phone('ask', { question: 'q', options: ['a'] }, 'full-session', controller.signal).catch(error => error);
    await waitFor(() => delayed !== undefined, 'ask request absent');
    assert.equal(host.pending.get(delayed.request.id).timer._idleTimeout, 65000);
    controller.abort();
    assert.match((await pending).message, /手机操作已取消/);
    await waitFor(() => fixture.requests.some(row => row.method === 'phone-cancel'), 'cancel request absent');
    await host.mode('full-session');
    delayed.respond({ result: { ok: false, code: 'cancelled' } });
    assert.equal(await host.mode('full-session'), 'full');
    assert.equal(host.pending.size, 0);
    assert.equal(host.connected, true);
  } finally { host.close(); await fixture.close(); }
});

test('phone bridge export cancellation warns about an incomplete target before its late receipt', async () => {
  let delayed;
  const fixture = await policyServer({ phone: (request, respond) => {
    if (request.method === 'phone-cancel') respond({ result: { cancelled: true } });
    else delayed = { request, respond };
  } });
  const host = new PolicyHost(fixture.config); const controller = new AbortController();
  try {
    await host.register('full-session', 'Full');
    const pending = host.phone('export', { path: 'workspace:a.txt' }, 'full-session', controller.signal).catch(error => error);
    await waitFor(() => delayed !== undefined, 'export request absent');
    controller.abort();
    assert.match((await pending).message, /授权目录可能残留不完整文件/);
    await waitFor(() => fixture.requests.some(row => row.method === 'phone-cancel'), 'cancel request absent');
    await host.mode('full-session'); delayed.respond({ result: { ok: false, code: 'cancelled' } });
    await host.mode('full-session'); assert.equal(host.pending.size, 0);
  } finally { host.close(); await fixture.close(); }
});

test('phone bridge PolicyHost returns result and isolates phone errors from other sessions', async () => {
  const result = { snapshot: 'snapshot-1', nodes: [{ id: 'node-1', text: '测试控件' }] };
  const fixture = await policyServer({ phone: (request, respond) => {
    if (request.action === 'click') respond({ ok: false, error: 'fixture stale phone snapshot' });
    else if (request.action === 'input') respond({ ok: false });
    else respond({ result });
  } });
  const host = new PolicyHost(fixture.config);
  try {
    await host.register('full-session', 'Full');
    await host.register('write-session', 'Write');
    assert.deepEqual(await host.phone('observe', {}, 'full-session'), result);
    const request = fixture.requests.find(request => request.method === 'phone');
    assert.deepEqual(request, { id: request.id, method: 'phone', action: 'observe', args: {}, session: 'full-session' });
    assert.ok(Number.isSafeInteger(request.id) && request.id > 0);
    await assert.rejects(host.phone('click', { snapshot: 'old', node: 'node-1' }, 'full-session'), /fixture stale phone snapshot/);
    assert.equal(host.pending.size, 0);
    assert.equal(await host.mode('write-session'), 'workspace-write');
    await assert.rejects(host.phone('input', {}, 'full-session'), /手机操作失败，请重新观察界面/);
    assert.equal(host.cachedMode('full-session'), 'full');
    assert.equal(host.connected, true);
    assert.deepEqual(await host.phone('observe', {}, 'full-session'), result);
    assert.equal(host.pending.size, 0);
  } finally { host.close(); await fixture.close(); }
});

for (const ok of [true, false]) {
  test(`phone bridge cancellation sends phone-cancel and consumes a late ${ok ? 'result' : 'error'}`, { timeout: 15_000 }, async () => {
    let delayed;
    const fixture = await policyServer({ phone: (request, respond) => {
      if (request.method === 'phone-cancel') respond({ result: { cancelled: true } });
      else if (request.action === 'click') delayed = { request, respond };
      else respond({ result: { enabled: true } });
    } });
    const host = new PolicyHost(fixture.config);
    const controller = new AbortController();
    try {
      await host.register('full-session', 'Full');
      await host.register('write-session', 'Write');
      const pending = host.phone('click', { snapshot: 'snapshot-1', node: 'node-1' }, 'full-session', controller.signal)
        .then(value => ({ value }), error => ({ error }));
      await waitFor(() => delayed !== undefined, 'phone bridge did not send the phone request');
      controller.abort();
      const cancelled = await pending;
      assert.ok(cancelled.error instanceof Error, 'the phone request must reject on cancellation');
      assert.match(cancelled.error.message, /手机操作已取消.*不能自动重试/);
      await waitFor(() => fixture.requests.some(request => request.method === 'phone-cancel'), 'phone bridge did not send phone-cancel');
      const cancel = fixture.requests.find(request => request.method === 'phone-cancel');
      assert.deepEqual(cancel, { id: cancel.id, method: 'phone-cancel', session: 'full-session', call: delayed.request.id });
      assert.ok(cancel.id > delayed.request.id);
      // A subsequent query is a wire-order barrier for the cancel response.
      assert.equal(await host.mode('write-session'), 'workspace-write');
      assert.equal(host.pending.has(cancel.id), false);
      assert.equal(host.pending.has(delayed.request.id), true, 'the cancelled request must remain recognizable until its result arrives');
      assert.equal(host.pending.size, 1);
      delayed.respond(ok ? { result: { accepted: true } } : { ok: false, error: 'fixture late cancellation' });
      assert.equal(await host.mode('write-session'), 'workspace-write');
      assert.equal(host.pending.size, 0, 'a late result must consume the original pending request');
      assert.equal(host.connected, true);
      assert.equal(host.cachedMode('full-session'), 'full');
      assert.deepEqual(await host.phone('status', {}, 'full-session'), { enabled: true });
      assert.equal(fixture.requests.filter(request => request.method === 'phone' && request.action === 'click').length, 1);
      assert.equal(fixture.requests.filter(request => request.method === 'phone-cancel').length, 1);
      assert.equal(host.pending.size, 0);
    } finally { host.close(); await fixture.close(); }
  });
}

if (process.env.DSHX_PHONE_DEVICE_AUDIT === '1') {
  test('phone bridge device performs native settings search and image attachment audit', { timeout: 120_000 }, async t => {
    const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
    // compat owns the authenticated singleton; close it after cleanup so Node can exit.
    t.after(() => policyHost.close());
    const output = process.env.DSHX_PHONE_AUDIT_OUTPUT;
    assert.ok(output, 'DSHX_PHONE_AUDIT_OUTPUT must name a writable JSON report path');
    const session = 'phone-device-audit';
    const report = { status: 'running', checks: {} };
    const codeOf = error => typeof error?.code === 'string' && /^[A-Za-z0-9_]{1,64}$/.test(error.code) ? error.code : 'test_failed';
    const check = async (name, run) => {
      report.checks[name] = { status: 'running' };
      await writeFile(output, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
      try {
        const value = await run();
        report.checks[name] = { status: 'passed' };
        await writeFile(output, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
        return value;
      } catch (error) {
        report.checks[name] = { status: 'failed', code: codeOf(error) };
        throw error;
      }
    };
    let ctx;
    let sharp;
    let registered = false;
    let searchOpened = false;
    let restoreNeeded = false;
    let originalText;
    let latest;
    let failure;
    const phone = async (action, args = {}, signal = t.signal) => {
      latest = undefined;
      const value = await policyHost.phone(action, args, session, signal);
      if (value?.ok !== true) {
        const code = codeOf(value);
        throw Object.assign(new Error(`phone bridge device ${action} failed: ${code}`), { code });
      }
      assert.equal(value.execution_state, 'completed', `native ${action} did not complete`);
      if (['open', 'click', 'input', 'scroll', 'back', 'home'].includes(action)) {
        assert.equal(value.accepted, true, `Android did not accept ${action}`);
      }
      return value;
    };
    const observe = async (ready, signal = t.signal) => {
      const deadline = Date.now() + 2000;
      let lastCode = 'page_not_ready';
      for (let attempt = 0; attempt < 5 && Date.now() < deadline; attempt++) {
        signal.throwIfAborted();
        try {
          const frame = await phone('observe', {}, AbortSignal.any([signal, AbortSignal.timeout(Math.max(1, deadline - Date.now()))]));
          assert.ok(typeof frame.snapshot === 'string' && frame.snapshot.length > 0, 'observe must return a snapshot');
          assert.ok(Array.isArray(frame.nodes), 'observe must return nodes');
          assert.ok(frame.nodes.every(node => typeof node.id === 'string' && Array.isArray(node.actions)), 'node ids must be strings with declared actions');
          if (ready(frame)) { latest = frame; return frame; }
          lastCode = 'page_not_ready';
        } catch (error) {
          if (!['no_window', 'window_changed', 'stale_snapshot'].includes(error.code)) throw error;
          lastCode = error.code;
        }
        if (attempt < 4 && Date.now() < deadline) await delay(Math.min(200, deadline - Date.now()), undefined, { signal });
      }
      throw Object.assign(new Error(`phone bridge device observe did not settle: ${lastCode}`), { code: lastCode });
    };
    const act = async (action, node, extra = {}, signal = t.signal) => {
      const frame = latest;
      assert.ok(frame && frame.nodes.includes(node), 'action must use the latest observation');
      const id = action === 'click' ? node.clickTarget : node.id;
      assert.ok(typeof id === 'string' && id.length > 0, 'args.node must be a nonempty string');
      const target = frame.nodes.find(candidate => candidate.id === id);
      assert.ok(target?.actions.includes(action === 'scroll' ? extra.direction : action), 'target must declare the requested action');
      return phone(action, { snapshot: frame.snapshot, node: id, ...extra }, signal);
    };
    const settingsPage = frame => frame.package === 'com.android.settings';
    const searchPage = frame => ['com.android.settings', 'com.android.settings.intelligence', 'com.google.android.settings.intelligence'].includes(frame.package);
    const searchLabel = node => /搜索|search/i.test(`${node.text ?? ''} ${node.description ?? ''}`);
    const searchButton = frame => frame.nodes.find(node => searchLabel(node) &&
      frame.nodes.some(target => target.id === node.clickTarget && target.actions.includes('click')));
    const searchInput = frame => {
      const inputs = frame.nodes.filter(node => node.editable === true && node.actions.includes('input') &&
        typeof node.text === 'string' && node.textTruncated === false);
      return inputs.find(searchLabel) ?? (inputs.length === 1 ? inputs[0] : undefined);
    };
    try {
      await check('session', async () => {
        assert.equal(policyHost.configured, true, 'Instrumentation must configure DSHX_POLICY_*');
        assert.equal(policyHost.connected, true, 'compat must authenticate the singleton before the test');
        const mode = await policyHost.register(session, '手机桥设备验收');
        registered = true;
        assert.equal(mode, 'full');
      });
      await check('attachments', async () => {
        const { Context } = await load('@deepseek-ai/cordis');
        const { default: Attachments } = await load('@deepseek-ai/dsh-attachment-local');
        sharp = require('sharp');
        ctx = new Context();
        await ctx.plugin(Attachments, { dshHome: await mkdtemp(join(directory, 'phone-device-images-')) });
      });
      await check('status', async () => {
        const status = await phone('status');
        assert.equal(status.ready, true, 'enable phone control and accessibility, unlock the screen and keep it awake');
        assert.equal(status.screenshotSupported, true, 'the device audit requires Android API 30 or newer');
        assert.equal(status.screenshotReady, true);
      });
      await check('apps', async () => {
        const value = await phone('apps');
        assert.ok(value.apps.some(app => app.package === 'com.android.settings'), 'Settings must appear in the native app catalog');
      });
      await check('open_settings', async () => {
        await phone('open', { package: 'com.android.settings' });
        await delay(500, undefined, { signal: t.signal });
      });
      let frame = await check('observe_settings', () => observe(value => settingsPage(value) &&
        value.nodes.some(node => node.editable === false && node.actions.length === 0 && typeof node.text === 'string' && node.text.trim().length > 0)));
      const scroll = frame.nodes.find(node => node.actions.includes('forward')) ?? frame.nodes.find(node => node.actions.includes('backward'));
      if (scroll) {
        await check('scroll', async () => {
          const previous = frame.snapshot;
          await act('scroll', scroll, { direction: scroll.actions.includes('forward') ? 'forward' : 'backward' });
          await delay(1000, undefined, { signal: t.signal });
          frame = await observe(value => settingsPage(value) && value.snapshot !== previous && Boolean(searchButton(value)));
        });
      } else report.checks.scroll = { status: 'skipped', reason: 'no_scroll_node' };
      await check('click_search', async () => {
        frame = await observe(value => settingsPage(value) && Boolean(searchButton(value)));
        await act('click', searchButton(frame));
        searchOpened = true;
      });
      await check('input_chinese', async () => {
        // Wait for the search activity and keyboard transition before creating the action snapshot.
        await delay(1500, undefined, { signal: t.signal });
        frame = await observe(value => searchPage(value) && Boolean(searchInput(value)));
        const input = searchInput(frame);
        originalText = input.text;
        // Restore once with a fresh observation even if the input receipt is uncertain.
        restoreNeeded = true;
        await act('input', input, { text: '手机桥测试' });
        frame = await observe(value => searchPage(value) && searchInput(value)?.text === '手机桥测试');
      });
      await check('screenshot', async () => {
        await delay(400, undefined, { signal: t.signal });
        // One native screenshot request only; throttling and all other errors fail honestly.
        const screen = await check('native_screenshot', () => phone('screenshot'));
        assert.equal(screen.mime, 'image/jpeg');
        assert.ok(Number.isInteger(screen.width) && screen.width > 0 && screen.width <= 1280);
        assert.ok(Number.isInteger(screen.height) && screen.height > 0 && screen.height <= 1280);
        assert.ok(typeof screen.data === 'string' && screen.data.length > 0 && screen.data.length <= 3 * 1024 * 1024, 'screenshot must contain bounded base64');
        const data = Buffer.from(screen.data, 'base64');
        assert.ok(data.length > 0, 'JPEG must not be empty');
        await writeFile(join(directory, 'device-screen.jpg'), data);
        const metadata = await check('decode_screenshot', () => sharp(data).metadata());
        assert.equal(metadata.format, 'jpeg');
        assert.equal(metadata.width, screen.width);
        assert.equal(metadata.height, screen.height);
        const image = await check('save_screenshot_attachment', () => ctx.attachments.saveImage({ data, mediaType: 'image/jpeg', name: 'phone-device-audit.jpg' }));
        const stored = await ctx.attachments.readImage(image);
        assert.ok(stored.data.length > 0, 'the actual attachment store must verify the screenshot');
        const { data: encoded, ...details } = screen;
        const content = phoneContent({ ...details, image });
        assert.ok(content.some(part => part.type === 'image' && part.attachment === image), 'screenshot must render native image content');
        assert.equal(JSON.stringify(content).includes(encoded), false, 'rendered content must not contain base64');
        assert.equal(Object.hasOwn(JSON.parse(content[0].text), 'data'), false);
      });
    } catch (error) { failure = error; }
    finally {
      const cleanup = async (name, run) => {
        try { await check(name, run); }
        catch (error) { failure ??= error; }
      };
      if (restoreNeeded) await cleanup('restore_search', async () => {
        const signal = AbortSignal.timeout(12_000);
        const frame = await observe(value => searchPage(value) && Boolean(searchInput(value)), signal);
        await act('input', searchInput(frame), { text: originalText }, signal);
        await observe(value => searchPage(value) && searchInput(value)?.text === originalText, signal);
      });
      if (registered && searchOpened) await cleanup('back', () => phone('back', {}, AbortSignal.timeout(10_000)));
      if (registered) {
        await cleanup('home', () => phone('home', {}, AbortSignal.timeout(10_000)));
        await cleanup('end_session', () => policyHost.end(session));
      }
      if (ctx) await cleanup('dispose_attachments', () => ctx.fiber.dispose());
      report.status = failure ? 'failed' : 'passed';
      // Only fixed step names, validation states and error codes leave the test.
      await writeFile(output, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
    }
    if (failure) throw failure;
  });
}

test('phone bridge authenticated image workers share no extra native connection', { timeout: 25_000 }, async () => {
  const fixture = await policyServer();
  const sample = join(directory, 'phone-preload-image.jpg');
  await writeFile(sample, Buffer.from('/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCAAGAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDj6KKK+5Plj//Z', 'base64'));
  const script = `
    import assert from 'node:assert/strict';
    import { createRequire } from 'node:module';
    import { join } from 'node:path';
    import { readFile } from 'node:fs/promises';
    import { pathToFileURL } from 'node:url';
    const core = process.env.DSHX_CORE;
    const require = createRequire(join(core, 'lib/node_modules/package.json'));
    const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
    assert.equal(policyHost.connected, true);
    const sharp = require('sharp');
    const metadata = await sharp(await readFile(${JSON.stringify(sample)})).metadata();
    assert.equal(metadata.width, 8);
    assert.equal(metadata.height, 6);
    assert.equal(policyHost.connected, true);
    assert.equal(await policyHost.register('full-session', 'image worker check'), 'full');
    assert.equal(await policyHost.mode('full-session'), 'full');
    policyHost.close();
    console.log('authenticated image workers completed');
  `;
  const launcher = join(directory, 'phone-image-workers.mjs');
  await writeFile(launcher, script);
  const child = spawn(process.execPath, ['--expose-internals', '--import', join(core, 'compat.mjs'), launcher], {
    env: { ...process.env, DSHX_POLICY_PORT: String(fixture.config.port), DSHX_POLICY_INSTANCE: fixture.config.instance, DSHX_POLICY_TOKEN: fixture.config.token },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; });
  child.stderr.on('data', chunk => { output += chunk; });
  const timeout = setTimeout(() => child.kill('SIGKILL'), 15_000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    assert.equal(code, 0, output || 'authenticated image worker did not finish');
    assert.match(output, /authenticated image workers completed/);
    assert.equal(fixture.requests.filter(request => request.version === 1).length, 1, 'only the main thread may authenticate the native connection');
  } finally { clearTimeout(timeout); if (child.exitCode === null) child.kill('SIGKILL'); await fixture.close(); }
});

test('native policy client rejects partial configuration and bad authentication', async () => {
  const incomplete = new PolicyHost({ instance: 'test-instance' });
  await assert.rejects(incomplete.connect(), /原生工具权限连接中断/);
  assert.equal(incomplete.cachedMode('any'), 'unavailable');
  const fixture = await policyServer();
  const wrong = new PolicyHost({ ...fixture.config, token: 'wrong-token' });
  try {
    await assert.rejects(wrong.connect(), /原生工具权限连接中断/);
    assert.equal(wrong.cachedMode('any'), 'unavailable');
  } finally { wrong.close(); await fixture.close(); }
});

test('unknown or ended session queries stay closed without breaking other sessions', async () => {
  const fixture = await policyServer();
  const host = new PolicyHost(fixture.config);
  try {
    assert.equal(await host.register('read-session', 'Read'), 'read-only');
    assert.equal(await host.register('write-session', 'Write'), 'workspace-write');
    assert.equal(await host.mode(undefined), 'unavailable');
    await assert.rejects(host.mode('unknown'), /查询被拒绝/);
    assert.equal(await host.mode('read-session'), 'read-only');
    await host.end('read-session');
    await assert.rejects(host.mode('read-session'), /查询被拒绝/);
    assert.equal(await host.mode('write-session'), 'workspace-write');
    await fixture.close();
    await assert.rejects(host.mode('write-session'), /原生工具权限连接中断/);
    assert.equal(host.cachedMode('write-session'), 'unavailable');
  } finally { host.close(); if (fixture) await fixture.close(); }
});

test('configured native modes constrain phone bridge catalogs, final execution and nested file writes', async () => {
  const phoneRequests = [];
  const fixture = await policyServer({ phone: (request, respond) => {
    phoneRequests.push(request);
    respond({ result: { snapshot: 'catalog-snapshot', nodes: [] } });
  } });
  const script = `
    import assert from 'node:assert/strict';
    import { createRequire } from 'node:module';
    import { pathToFileURL } from 'node:url';
    import { join } from 'node:path';
    const core = process.env.DSHX_CORE;
    const require = createRequire(join(core, 'lib/node_modules/package.json'));
    const load = name => import(pathToFileURL(require.resolve(name)));
    const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
    assert.equal(policyHost.connected, true, 'the real preload must authenticate before official module initialization');
    const { runTool } = await import(pathToFileURL(join(core, 'policy.mjs')));
    const { Context } = await load('@deepseek-ai/cordis');
    const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
    const { default: Tools } = await load('@deepseek-ai/dsh-tools');
    const { default: Agents } = await load('@deepseek-ai/dsh-agent');
    const Guard = await import(pathToFileURL(join(core, 'guard.mjs')));
    const Phone = await import(pathToFileURL(join(core, 'phone.mjs')));
    const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
    const ctx = new Context();
    const full = { mode: 'danger-full-access', workspaceRoot: process.env.DSHX_WORKSPACE };
    ctx.provide('sandboxPolicy', { defaultMode: 'danger-full-access', resolve: () => full });
    try {
      await policyHost.register('read-session', 'Read');
      await policyHost.register('write-session', 'Write');
      await policyHost.register('full-session', 'Full');
      await ctx.plugin(SystemPrompt); await ctx.plugin(Tools); await ctx.plugin(Agents);
      await ctx.plugin(Phone);
      await ctx.plugin(AndroidFiles, { cwd: process.env.DSHX_WORKSPACE });
      let writes = 0;
      const commandNames = ['bash', 'bash_isolated', 'terminal_open', 'terminal_send', 'terminal_read', 'terminal_signal', 'terminal_close', 'terminal_list'];
      for (const name of ['read', 'write', 'edit', 'glob', 'grep', ...commandNames]) ctx.tools.register({
        name, description: name, parameters: { type: 'object', properties: {} },
        output: { schema: { type: 'string' }, render: (_, value) => [{ type: 'text', text: value }] },
        execute: async () => { writes++; return 'done'; }
      });
      const read = { id: 'read-session', session: { id: 'read-session', header: {} } };
      const write = { id: 'write-session', session: { id: 'write-session', header: { parentSession: 'inactive-history-parent' } } };
      const fullAgent = { id: 'full-session', session: { id: 'full-session', header: {} } };
      const unknown = { id: 'unknown-session', session: { id: 'unknown-session' } };
      const phoneNames = [...Phone.phoneToolNames].sort();
      ctx.agents.enter(read); ctx.agents.enter(write);
      await ctx.plugin(Guard);
      const childAgent = { id: 'child-session', session: { id: 'child-session', header: {} } };
      const detachChild = ctx.agents.enter(childAgent, read);
      let announced = 0;
      ctx.on('agent/created', () => { announced++; });
      await ctx.agents.announce(childAgent, 'create');
      assert.equal(announced, 1, 'policy registration must not bail out of official creation listeners');
      assert.equal(policyHost.cachedMode(childAgent.id), 'read-only', 'live child inherits its creator mode');
      for (const catalog of [agent => ctx.tools.schemas(agent), agent => ctx.tools.sdkSchemas(agent), agent => ctx.tools.wireSchemas(agent).schemas]) {
        assert.deepEqual(catalog(read).map(t => t.name).sort(), ['glob', 'grep', 'read']);
        assert.deepEqual(catalog(childAgent).map(t => t.name).sort(), ['glob', 'grep', 'read']);
        assert.deepEqual(catalog(write).map(t => t.name).sort(), ['edit', 'glob', 'grep', 'read', 'write']);
        assert.deepEqual(catalog(fullAgent).map(t => t.name).sort(), ['edit', 'glob', 'grep', 'read', 'write', ...commandNames, ...phoneNames].sort());
        assert.deepEqual(catalog(unknown), []);
        assert.deepEqual(catalog(undefined), []);
      }
      const phoneCall = (agent, name = 'phone_observe', args = {}) => ctx.tools.execute({
        agent, name, arguments: args, signal: new AbortController().signal, callId: 'phone-bridge-catalog-test'
      });
      for (const agent of [read, childAgent, write, unknown, undefined]) {
        const result = await phoneCall(agent);
        assert.equal(result.isError, true, JSON.stringify(result));
        assert.match(JSON.stringify(result), /当前会话尚未连接/);
      }
      const observed = await phoneCall(fullAgent);
      assert.equal(observed.isError, false, JSON.stringify(observed));
      assert.deepEqual(observed.value, { snapshot: 'catalog-snapshot', nodes: [] });
      assert.deepEqual(observed.content, [{ type: 'text', text: JSON.stringify(observed.value) }]);
      const escalated = await phoneCall(fullAgent, 'phone_observe', { sandbox_permissions: 'danger-full-access' });
      assert.equal(escalated.isError, true);
      assert.match(JSON.stringify(escalated), /不支持单次沙箱提权参数/);
      const call = agent => ctx.tools.execute({ agent, name: 'write', arguments: {}, signal: new AbortController().signal, callId: 'native-policy-test' });
      await call(read); assert.equal(writes, 0);
      await call(write); assert.equal(writes, 1);
      await runTool({ name: 'read', agent: read }, async () => {
        const target = await ctx.fs.resolve('native-readonly-must-not-exist.txt');
        await assert.rejects(ctx.fs.writeText(target, 'blocked', undefined, undefined, full), { code: 'FS_SANDBOX_DENIED' });
        await assert.rejects(runTool({ name: 'write', agent: write }, async () => { throw new Error('must not execute'); }), { code: 'FS_SANDBOX_DENIED' });
      });
      for (const name of commandNames) {
        await runTool({ name: 'read', agent: read }, () => assert.rejects(runTool({ name, agent: fullAgent }, () => assert.fail('nested command escaped read-only')), { code: 'FS_SANDBOX_DENIED' }));
      }
      detachChild();
      const deadline = Date.now() + 1000;
      while (policyHost.cachedMode(childAgent.id) !== 'unavailable' && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 10));
      assert.equal(policyHost.cachedMode(childAgent.id), 'unavailable');
      await policyHost.end(read.id);
      assert.deepEqual(ctx.tools.schemas(read), []);
      await call(read); assert.equal(writes, 1);
      await call(write); assert.equal(writes, 2);
      const denied = { id: 'denied-session', session: { id: 'denied-session' } };
      await policyHost.register(denied.id, 'Query denied');
      assert.equal(policyHost.cachedMode(denied.id), 'workspace-write');
      const rejection = await call(denied);
      assert.match(JSON.stringify(rejection), /查询被拒绝/); assert.equal(writes, 2);
      await call(write); assert.equal(writes, 3, 'a rejected query must not stop another session');
      assert.equal(policyHost.cachedMode(fullAgent.id), 'full');
      assert.ok(ctx.tools.schemas(fullAgent).some(t => t.name === 'bash'));
      assert.ok(!ctx.tools.schemas(write).some(t => t.name === 'bash'));
      for (const name of commandNames) {
        for (const agent of [read, write, unknown]) {
          const blocked = await ctx.tools.execute({ agent, name, arguments: {}, signal: new AbortController().signal, callId: 'command-policy-test' });
          assert.equal(blocked.isError, true, name);
        }
        const background = await ctx.tools.execute({ agent: fullAgent, name, arguments: { run_in_background: true }, signal: new AbortController().signal, callId: 'background-denied' });
        assert.equal(background.isError, true, name);
      }
      const command = agent => ctx.tools.execute({ agent, name: 'bash', arguments: {}, signal: new AbortController().signal, callId: 'full-policy-test' });
      await command(read); assert.equal(writes, 3);
      await command(write); assert.equal(writes, 3);
      await command(fullAgent); assert.equal(writes, 4);
      writes--;
      const lost = { id: 'lost-session', session: { id: 'lost-session' } };
      await policyHost.register(lost.id, 'Disconnect during execution');
      assert.equal(policyHost.cachedMode(lost.id), 'workspace-write');
      const disconnected = await call(lost);
      assert.match(JSON.stringify(disconnected), /原生工具权限连接中断/); assert.equal(writes, 3);
      assert.deepEqual(ctx.tools.schemas(write), []);
      assert.deepEqual(ctx.tools.schemas(fullAgent), []);
      assert.equal((await phoneCall(fullAgent)).isError, true);
      await call(write); assert.equal(writes, 3);
    } finally { policyHost.close(); await ctx.fiber.dispose(); }
  `;
  const child = spawn(process.execPath, ['--expose-internals', '--import', join(core, 'compat.mjs'), '--input-type=module', '-e', script], { env: { ...process.env,
    DSHX_POLICY_PORT: String(fixture.config.port), DSHX_POLICY_INSTANCE: fixture.config.instance, DSHX_POLICY_TOKEN: fixture.config.token
  }, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', data => { output += data; }); child.stderr.on('data', data => { output += data; });
  const timer = setTimeout(() => child.kill('SIGKILL'), 20000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    assert.equal(code, 0, output);
    assert.equal(phoneRequests.length, 1, 'only the admitted Full call may reach the phone host');
    assert.deepEqual(phoneRequests[0], { id: phoneRequests[0].id, method: 'phone', action: 'observe', args: {}, session: 'full-session' });
  } finally { clearTimeout(timer); child.kill(); await fixture.close(); }
});

test('official bash and external files work in default Full despite legacy web restrictions', async () => {
  const fixture = await policyServer();
  const target = join(workspace, 'official-bash-proof.txt');
  const script = `
    import assert from 'node:assert/strict';
    import { readFile, stat } from 'node:fs/promises';
    import { createRequire } from 'node:module';
    import { join } from 'node:path';
    import { pathToFileURL } from 'node:url';
    const core = process.env.DSHX_CORE;
    const require = createRequire(join(core, 'lib/node_modules/package.json'));
    const load = name => import(pathToFileURL(require.resolve(name)));
    const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
    const { Context } = await load('@deepseek-ai/cordis');
    const ctx = new Context();
    const { default: SandboxPolicy } = await load('@deepseek-ai/dsh-sandbox-policy');
    const sandboxPolicy = { defaultMode: 'danger-full-access', workspaceRoot: ${JSON.stringify(workspace)},
      overrideOf: () => 'read-only' };
    sandboxPolicy.resolve = request => SandboxPolicy.prototype.resolve.call(sandboxPolicy, request);
    assert.equal(sandboxPolicy.resolve({ session: { id: 'old-session', header: { cwd: ${JSON.stringify(workspace)} } }, mode: 'read-only' }).mode, 'danger-full-access');
    let confined = 0;
    ctx.provide('sandboxPolicy', sandboxPolicy);
    ctx.provide('sandbox', { wrap: async () => { confined++; throw new Error('Android confinement unavailable'); } });
    ctx.provide('workingDirectory', { ensure: async () => ${JSON.stringify(workspace)} });
    try {
      for (const name of ['system-prompt', 'tools', 'subprocess-local', 'shell-env', 'bash-sandbox', 'tool-bash', 'tool-fs']) {
        const plugin = await load('@deepseek-ai/dsh-' + name);
        await ctx.plugin(plugin.default ?? plugin);
      }
      const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
      await ctx.plugin(AndroidFiles, { cwd: ${JSON.stringify(workspace)} });
      await policyHost.register('full-session', 'Full');
      await policyHost.register('write-session', 'Write');
      const call = session => ctx.tools.execute({
        name: 'bash_isolated', agent: { id: session, session: { id: session, header: { id: session } } },
        arguments: { description: 'Verify official Android bash', command: ${JSON.stringify("printf 'OFFICIAL_BASH_OK' > '" + target + "'; cat '" + target + "'")} },
        callId: 'official-bash-proof', signal: new AbortController().signal
      });
      await call('write-session');
      await assert.rejects(stat(${JSON.stringify(target)}), { code: 'ENOENT' });
      const result = await call('full-session');
      assert.match(JSON.stringify(result), /OFFICIAL_BASH_OK/);
      assert.equal(await readFile(${JSON.stringify(target)}, 'utf8'), 'OFFICIAL_BASH_OK');
      assert.equal(confined, 0);
      const outside = ${JSON.stringify(join(directory, 'full-outside-workspace.txt'))};
      const agent = { id: 'full-session', session: { id: 'full-session', header: { cwd: ${JSON.stringify(workspace)} } } };
      const write = await ctx.tools.execute({ name: 'write', agent, arguments: { file_path: outside, content: 'FULL_FILE_OK' }, callId: 'full-external-file', signal: new AbortController().signal });
      assert.equal(await readFile(outside, 'utf8'), 'FULL_FILE_OK', JSON.stringify(write));
      const read = await ctx.tools.execute({ name: 'read', agent, arguments: { file_path: outside }, callId: 'full-external-read', signal: new AbortController().signal });
      assert.match(JSON.stringify(read), /FULL_FILE_OK/);
    } finally { policyHost.close(); await ctx.fiber.dispose(); }
  `;
  const child = spawn(process.execPath, ['--expose-internals', '--import', join(core, 'compat.mjs'), '--input-type=module', '-e', script], {
    env: { ...process.env, DSHX_POLICY_PORT: String(fixture.config.port), DSHX_POLICY_INSTANCE: fixture.config.instance, DSHX_POLICY_TOKEN: fixture.config.token },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let output = '';
  child.stdout.on('data', data => { output += data; }); child.stderr.on('data', data => { output += data; });
  const timer = setTimeout(() => child.kill('SIGKILL'), 20000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    assert.equal(code, 0, output);
  } finally { clearTimeout(timer); child.kill(); await fixture.close(); }
});

test('trust recovery rebuilds executable profiles while preserving model settings and user data', async () => {
  const root = join(directory, 'recovery');
  const home = join(root, 'home');
  const dsh = join(home, '.dsh');
  const profile = join(dsh, 'profiles/web');
  await mkdir(profile, { recursive: true });
  for (const name of ['sessions', 'attachments', 'storages']) {
    await mkdir(join(dsh, name)); await writeFile(join(dsh, name, 'keep'), name);
  }
  await writeFile(join(dsh, '.credentials.yaml'), 'credential-retained');
  await writeFile(join(home, '.bashrc'), 'exit 99');
  await writeFile(join(profile, 'package.json'), '{"dependencies":{"arbitrary":"file:../../"}}');
  await writeFile(join(profile, 'cordis.patch.yml'), JSON.stringify([
    { id: 'malicious', name: 'file:arbitrary.mjs', config: {} },
    { id: 'llm-pi-ai', name: '@deepseek-ai/dsh-llm-pi-ai', config: { providers: { example: { baseURL: 'https://example.test/v1' } }, dangerous: true } },
    { id: 'agent-default-model', name: '@deepseek-ai/dsh-agent-default-model', config: { provider: 'example', model: 'test', dangerous: true } },
    { id: 'system-prompt', config: { personaPrefix: '你是我的手机助手', personaSuffix: '回答简洁', dangerous: true } },
    { id: 'preset-standard', name: '@deepseek-ai/dsh-agent-preset', config: { id: 'standard', plugins: [
      { id: 'persona', name: '@deepseek-ai/dsh-persona', config: { prefix: '你叫小石', suffix: '用中文回答', complete: false, dangerous: true } },
      { id: 'compaction', name: 'cordis:group', group: true, config: [
        { id: 'compaction-basic', name: '@deepseek-ai/dsh-compaction-basic', config: { headroomTokens: 1024, maxTokens: 512, summarizationProvider: 'local', summarizationModel: 'small', dangerous: true } },
      ] },
      { id: 'tool-bash', name: 'file:arbitrary.mjs', config: {} },
    ] } },
    { id: 'preset-minimal', config: { id: 'minimal', plugins: [
      { id: 'persona', config: { prefix: { __jsExpr: '(ctx.reviewFlag = true, "unsafe")' } } },
      { id: 'compaction-basic', config: { headroomTokens: -1, summarizationModel: 'missing-provider' } },
    ] } },
    { id: 'llm-deepseek', config: { maxTokens: 512, thinking: 'disabled', reasoningEffort: 'off', apiKeyEnv: 'DEEPSEEK_API_KEY', dangerous: true } },
  ]));
  const result = spawnSync(process.execPath, [join(core, 'recover.mjs')], { env: { ...process.env, HOME: home, DSH_HOME: dsh }, timeout: 30_000, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  const manifest = JSON.parse(await readFile(join(profile, 'package.json'), 'utf8'));
  assert.deepEqual(manifest.dependencies, {});
  const patch = require('yaml').parse(await readFile(join(profile, 'cordis.patch.yml'), 'utf8'));
  assert.deepEqual(patch.map(row => row.id), ['llm-pi-ai', 'agent-default-model', 'system-prompt', 'preset-standard', 'preset-minimal', 'llm-deepseek']);
  assert.equal(patch[0].config.providers.example.baseURL, 'https://example.test/v1');
  assert.equal(patch[1].config.model, 'test');
  assert.equal(patch[1].config.dangerous, undefined);
  assert.equal(patch[2].config.personaPrefix, '你是我的手机助手');
  assert.equal(patch[2].config.dangerous, undefined);
  const plugins = patch[3].config.plugins;
  assert.equal(plugins.find(row => row.id === 'persona').config.prefix, '你叫小石');
  assert.equal(plugins.find(row => row.id === 'persona').config.dangerous, undefined);
  assert.equal(plugins.find(row => row.id === 'tool-bash').name, '@deepseek-ai/dsh-tool-bash');
  assert.equal(plugins.find(row => row.id === 'tool-bash').disabled, 'process.platform === \'win32\'');
  const compact = plugins.find(row => row.id === 'compaction').config.find(row => row.id === 'compaction-basic');
  assert.equal(compact.config.summarizationModel, 'small');
  assert.equal(compact.config.headroomTokens, 1024);
  assert.equal(compact.config.dangerous, undefined);
  assert.ok((await readFile(join(profile, 'cordis.patch.yml'), 'utf8')).includes('!!js'));
  assert.ok(!(await readFile(join(profile, 'cordis.patch.yml'), 'utf8')).includes('__jsExpr'));
  const minimal = patch[4].config.plugins;
  assert.equal(minimal.find(row => row.id === 'persona').config.prefix, 'You are a helpful software engineer assistant.');
  assert.equal(minimal.find(row => row.id === 'compaction').config.find(row => row.id === 'compaction-basic').config, undefined);
  assert.equal(patch[5].config.maxTokens, 512);
  assert.equal(patch[5].config.apiKeyEnv, 'DEEPSEEK_API_KEY');
  assert.equal(patch[5].config.dangerous, undefined);
  for (const name of ['sessions', 'attachments', 'storages']) assert.equal(await readFile(join(dsh, name, 'keep'), 'utf8'), name);
  assert.equal(await readFile(join(dsh, '.credentials.yaml'), 'utf8'), 'credential-retained');
  await assert.rejects(stat(join(home, '.bashrc')), { code: 'ENOENT' });
  const backup = (await import('node:fs/promises')).readdir;
  assert.equal((await backup(join(root, 'recovery'))).length, 1);
  const again = spawnSync(process.execPath, [join(core, 'recover.mjs')], { env: { ...process.env, HOME: home, DSH_HOME: dsh }, timeout: 30_000, encoding: 'utf8' });
  assert.equal(again.status, 0, again.stderr);
  const repeated = require('yaml').parse(await readFile(join(profile, 'cordis.patch.yml'), 'utf8'));
  assert.deepEqual(repeated, patch, 'cold recovery must retain the same preferences on every restart');
  await writeFile(join(profile, 'cordis.patch.yml'), JSON.stringify([
    { id: 'llm-pi-ai', config: { providers: { injected: { apiKey: { __jsExpr: 'ctx.reviewFlag = true' } } } } },
  ]));
  const injected = spawnSync(process.execPath, [join(core, 'recover.mjs')], { env: { ...process.env, HOME: home, DSH_HOME: dsh }, timeout: 30_000, encoding: 'utf8' });
  assert.equal(injected.status, 0, injected.stderr);
  assert.ok(!(await readFile(join(profile, 'cordis.patch.yml'), 'utf8')).includes('__jsExpr'));
});

test('real upstream profile composition disables the original files and mounts the Android provider', async () => {
  const { composeEntries, loadOverlayPatches } = await load('@deepseek-ai/dsh-app-boot');
  const androidPatch = join(directory, 'android.patch.yml');
  await writeFile(androidPatch, (await readFile(join(core, 'android.patch.yml'), 'utf8')).replaceAll('@CORE@', core));
  const patches = [
    join(core, 'lib/node_modules/@deepseek-ai/dsh-base/cordis.patch.yml'),
    join(core, 'lib/node_modules/@deepseek-ai/dsh-web-app/cordis.patch.yml'),
    androidPatch
  ].map(path => loadOverlayPatches('dshx-test', path));
  const warnings = [];
  const entries = composeEntries(patches, message => warnings.push(message));
  assert.deepEqual(warnings, [], 'Android boot patches must not be skipped');
  assert.equal(entries.find(row => row.id === 'fs-sandbox')?.disabled, true);
  assert.equal(entries.find(row => row.id === 'office-to-pdf')?.disabled, true);
  assert.equal(entries.find(row => row.id === 'dshx-android-files')?.name, `file:${core}/files.mjs`);
  for (const [id, name] of [['dshx-terminal-registry', 'terminal'], ['dshx-terminal-bash', 'terminal-bash'],
    ['dshx-persistent-bash', 'tool-bash-persistent'], ['dshx-isolated-bash', 'tool-bash'], ['dshx-terminal-tools', 'tool-terminal']]) {
    assert.equal(entries.find(row => row.id === id)?.name, '@deepseek-ai/dsh-' + name);
  }
  assert.ok(entries.findIndex(row => row.id === 'dshx-android-guard') < entries.findIndex(row => row.id === 'dshx-persistent-bash'));
  const { interpolate } = await load('@deepseek-ai/cordis-plugin-loader');
  const minimal = composeEntries([loadOverlayPatches('dshx-test', join(core, 'lib/node_modules/@deepseek-ai/dsh-web-app/presets/minimal.patch.yml'))], assert.fail);
  const shell = minimal[0].config.plugins.find(row => row.id === 'persistent-shell');
  assert.equal(interpolate({}, shell.disabled), true, 'minimal must inherit the Android default shell');
  const sdk = composeEntries([
    loadOverlayPatches('dshx-test', join(core, 'lib/node_modules/@deepseek-ai/dsh-base/cordis.patch.yml')),
    loadOverlayPatches('dshx-test', join(core, 'lib/node_modules/@deepseek-ai/dsh-sdk-app/cordis.patch.yml')),
  ], message => warnings.push(message));
  assert.deepEqual(warnings, []);
  assert.equal(sdk.find(row => row.id === 'skill-office')?.disabled, true);
});

test('Android slim runtime omits LibreOffice and shares the pinned schema dependency', async () => {
  const modules = join(core, 'lib/node_modules');
  const packages = await readdir(join(modules, '@deepseek-ai'));
  assert.equal(packages.some(name => name === 'libreoffice-kit' || name.startsWith('libreoffice-kit-')), false);
  const shared = require.resolve('zod');
  let consumers = 0;
  for (const name of packages) {
    const path = join(modules, '@deepseek-ai', name, 'package.json');
    const metadata = JSON.parse(await readFile(path, 'utf8'));
    if (metadata.dependencies?.zod !== '4.4.3') continue;
    const local = createRequire(path);
    assert.equal(local.resolve('zod'), shared, `${name} must use the shared schema package`);
    assert.equal(local('zod').z.string().parse('手机桥'), '手机桥');
    consumers++;
  }
  assert.ok(consumers >= 50, 'check every pinned upstream consumer');
  assert.equal(require('zod/package.json').version, '4.4.3');
  assert.match(new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric' }).format(new Date('2026-10-22T00:00:00Z')), /2026/);
  const { DatabaseSync } = await import('node:sqlite');
  const db = new DatabaseSync(':memory:');
  try { assert.equal(db.prepare('SELECT 1 AS value').get().value, 1); }
  finally { db.close(); }
});

test('Android model catalogs expose only available tools and preserve known history names', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools } = await load('@deepseek-ai/dsh-tools');
  for (const mode of ['native', 'ptc']) {
    const ctx = new Context();
    try {
      await ctx.plugin(SystemPrompt);
      await ctx.plugin(Tools, { mode });
      for (const name of ['read', 'write', 'edit', 'glob', 'grep', 'bash', 'web_fetch', 'test_effect']) {
        ctx.tools.register({ name, description: name,
          parameters: { type: 'object', properties: {} },
          output: { schema: { type: 'string' }, render: (_, value) => [{ type: 'text', text: value }] },
          execute: async () => 'test result' });
      }
      const expected = ['edit', 'glob', 'grep', 'read', 'write'];
      for (const agent of [undefined, { id: 'catalog-child' }]) {
        assert.deepEqual(ctx.tools.schemas(agent).map(tool => tool.name).sort(), expected);
        const wire = ctx.tools.wireSchemas(agent);
        assert.deepEqual(wire.schemas.map(tool => tool.name).sort(), expected);
        assert.ok(wire.knownNames.includes('bash'), 'saved tool history remains a known name');
        assert.deepEqual(ctx.tools.sdkSchemas(agent).map(tool => tool.name).sort(), expected);
        assert.equal(ctx.tools.modeFor(agent), 'native', 'unavailable program execution must not hide available file tools');
      }
    } finally { await ctx.fiber.dispose(); }
  }
});

test('official instruction discovery can inspect parent markers and host configuration before a model request', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { loadBaselineInstructions } = await load('@deepseek-ai/dsh-agent-instructions');
  const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
  const ctx = new Context();
  ctx.provide('sandboxPolicy', { defaultMode: 'workspace-write', resolve: () => ({ mode: 'workspace-write', workspaceRoot: workspace }) });
  try {
    await ctx.plugin(AndroidFiles, { cwd: workspace });
    const seen = [];
    const fs = new Proxy(ctx.fs, { get(target, key) {
      if (key === 'resolve') return async (path, options) => {
        seen.push(path);
        try { return await target.resolve(path, options); }
        catch (error) { error.message += ` (instruction probe: ${path})`; throw error; }
      };
      const value = Reflect.get(target, key);
      return typeof value === 'function' ? value.bind(target) : value;
    } });
    await loadBaselineInstructions({ cwd: workspace, maxBytes: 4096 }, fs);
    assert.ok(seen.some(path => !path.startsWith(process.env.DSHX_WORKSPACE + '/')), 'upstream discovery must inspect host paths');
  } finally { await ctx.fiber.dispose(); }
});

test('Android file policy enforces physical workspace paths even under Full and stale aliases', () => runTool({ name: 'read' }, async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
  const ctx = new Context();
  const full = { mode: 'danger-full-access', workspaceRoot: workspace };
  // This test deliberately supplies the widest upstream policy. The Android
  // boundary must remain effective even if the web page records that choice.
  ctx.provide('sandboxPolicy', { defaultMode: 'workspace-write', resolve: () => full });
  try {
    await ctx.plugin(AndroidFiles, { cwd: workspace });
    const target = await ctx.fs.resolve('allowed.txt');
    await ctx.fs.writeText(target, 'allowed', { kind: 'createIfAbsent' }, undefined, full);
    assert.equal(await ctx.fs.readText(target), 'allowed');
    await assert.rejects(ctx.fs.resolve(join(directory, 'outside.txt')), { code: 'FS_SANDBOX_DENIED' });
    await assert.rejects(ctx.fs.resolve('../'.repeat(20) + 'system/build.prop'), { code: 'FS_SANDBOX_DENIED' });
    await symlink(directory, join(workspace, 'escape'));
    await assert.rejects(ctx.fs.resolve('escape/new.txt'), { code: 'FS_SANDBOX_DENIED' });
    await assert.rejects(ctx.fs.resolve('escape/../new.txt'), { code: 'FS_SANDBOX_DENIED' });
    await symlink(join(directory, 'not-created'), join(workspace, 'dangling'));
    await assert.rejects(ctx.fs.resolve('dangling/new.txt'), { code: 'FS_SANDBOX_DENIED' });
    await symlink(join(workspace, 'allowed.txt'), join(workspace, 'alias'));
    const alias = await ctx.fs.resolve('alias');
    await rm(join(workspace, 'alias'));
    await symlink(join(directory, 'outside.txt'), join(workspace, 'alias'));
    // The provider uses the checked canonical target, not a newly followed
    // model spelling. Changing the alias cannot redirect this read/write.
    assert.equal(await ctx.fs.readText(alias), 'allowed');
    await ctx.fs.writeText(alias, 'still inside', undefined, undefined, full);
    await assert.rejects(stat(join(directory, 'outside.txt')), { code: 'ENOENT' });
    const parent = join(workspace, 'parent');
    await mkdir(parent);
    const pending = await ctx.fs.resolve('parent/new.txt');
    await rename(parent, parent + '-old');
    await symlink(directory, parent);
    await assert.rejects(ctx.fs.writeText(pending, 'blocked', undefined, undefined, full), { code: 'FS_SANDBOX_DENIED' });
    await assert.rejects(ctx.fs.writeText(target, 'blocked', undefined, undefined, { mode: 'read-only', workspaceRoot: workspace }), { code: 'FS_SANDBOX_DENIED' });
  } finally { await ctx.fiber.dispose(); }
}));

test('concurrent model reads stay bounded while host configuration reads remain available', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
  const ctx = new Context();
  ctx.provide('sandboxPolicy', { defaultMode: 'workspace-write', resolve: () => ({ mode: 'workspace-write', workspaceRoot: workspace }) });
  const path = join(directory, 'host-config.txt');
  await writeFile(path, 'HOST_CONFIG_ONLY');
  try {
    await ctx.plugin(AndroidFiles, { cwd: workspace });
    await Promise.all([
      runTool({ name: 'read' }, async () => {
        await delay(15);
        assert.equal(inFileTool(), true);
        await assert.rejects(ctx.fs.resolve(path), { code: 'FS_SANDBOX_DENIED' });
        await runTool({ name: 'nested' }, () => assert.rejects(ctx.fs.resolve(path), { code: 'FS_SANDBOX_DENIED' }));
        await assert.rejects(runTool({ name: 'edit' }, async () => { throw new Error('scope cleanup'); }), /scope cleanup/);
        assert.equal(inFileTool(), true);
      }),
      (async () => {
        await delay(5);
        assert.equal(inFileTool(), false);
        assert.equal(await ctx.fs.readText(await ctx.fs.resolve(path)), 'HOST_CONFIG_ONLY');
      })()
    ]);
    assert.equal(inFileTool(), false);
    const outside = await ctx.fs.resolve(path);
    await assert.rejects(ctx.fs.writeText(outside, 'blocked'), { code: 'FS_SANDBOX_DENIED' });
    assert.equal(await readFile(path, 'utf8'), 'HOST_CONFIG_ONLY');
  } finally { await ctx.fiber.dispose(); }
});

test('official file provider creates, reads, edits and rejects stale writes on Android', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: LocalFileSystem } = await load('@deepseek-ai/dsh-fs-local');
  const ctx = new Context();
  try {
    await ctx.plugin(LocalFileSystem, { cwd: directory });
    const target = await ctx.fs.resolve('中文 文件.txt');
    const created = await ctx.fs.writeText(target, 'first\n中文内容\n', { kind: 'createIfAbsent' });
    await assert.rejects(ctx.fs.writeText(target, 'overwrite', { kind: 'createIfAbsent' }), { code: 'FS_NOT_OBSERVED' });
    const read = await ctx.fs.readText(target);
    assert.equal(read, 'first\n中文内容\n');
    const edited = await ctx.fs.editText(target, { oldString: 'first', newString: 'second', replaceAll: false }, { version: created.version });
    assert.equal(edited.after, 'second\n中文内容\n');
    await assert.rejects(ctx.fs.writeText(target, 'stale', { kind: 'replaceIfVersion', version: created.version }), { code: 'FS_STALE_VERSION' });
    assert.equal(await ctx.fs.readText(target), edited.after);
    const binary = await ctx.fs.resolve('binary.dat');
    await writeFile(binary.targetKey, Buffer.from([0, 1, 2]));
    await assert.rejects(ctx.fs.readText(binary), { code: 'FS_NOT_TEXT' });
    const controller = new AbortController();
    controller.abort();
    await assert.rejects(ctx.fs.writeText(target, 'cancelled', undefined, controller.signal), { code: 'FS_ABORTED' });
    assert.equal(await ctx.fs.readText(target), edited.after);
  } finally { await ctx.fiber.dispose(); }
});

test('official model file tools work through final dispatch and refuse outside paths and escalation', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools } = await load('@deepseek-ai/dsh-tools');
  const FileTools = await load('@deepseek-ai/dsh-tool-fs');
  const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
  const ctx = new Context();
  ctx.provide('sandboxPolicy', { defaultMode: 'workspace-write', resolve: () => ({ mode: 'workspace-write', workspaceRoot: workspace }) });
  ctx.provide('workingDirectory', { ensure: async () => workspace });
  const signal = new AbortController().signal;
  const call = (name, args, agent) => ctx.tools.execute({ name, arguments: args, signal, callId: `file-test-${name}`, ...(agent ? { agent } : {}) });
  try {
    await ctx.plugin(SystemPrompt);
    await ctx.plugin(Tools);
    await ctx.plugin(AndroidFiles, { cwd: workspace });
    await ctx.plugin(FileTools, { readImage: false });
    const path = join(workspace, 'model.txt');
    const created = await call('write', { file_path: path, content: 'model-written' });
    assert.notEqual(created.isError, true, JSON.stringify(created));
    assert.equal(await readFile(path, 'utf8'), 'model-written');
    const read = await call('read', { file_path: path });
    assert.match(JSON.stringify(read), /model-written/);
    const edited = await call('edit', { file_path: path, old_string: 'model-written', new_string: 'model-edited' });
    assert.notEqual(edited.isError, true, JSON.stringify(edited));
    assert.equal(await readFile(path, 'utf8'), 'model-edited');
    const outside = join(directory, 'model-outside.txt');
    const denied = await call('write', { file_path: outside, content: 'blocked' });
    assert.match(JSON.stringify(denied), /Android 文件与搜索仅允许访问/);
    await assert.rejects(stat(outside), { code: 'ENOENT' });
    const escalated = await call('write', { file_path: path, content: 'blocked', sandbox_permissions: 'danger-full-access', justification: 'test' });
    assert.match(JSON.stringify(escalated), /不能通过网页权限设置扩大/);
    assert.equal(await readFile(path, 'utf8'), 'model-edited');
    const child = await call('read', { file_path: join(directory, 'secret.txt') }, { id: 'child-file-test', session: {} });
    assert.match(JSON.stringify(child), /Android 文件与搜索仅允许访问/);
  } finally { await ctx.fiber.dispose(); }
});

test('official search runs the Android binary with Unicode, no-match and invalid-pattern outcomes', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: LocalSubprocess } = await load('@deepseek-ai/dsh-subprocess-local');
  const { runRipgrep, buildGlobCommand, buildGrepCommand, parseGrepMatches, resolveRgPath } = await load('@deepseek-ai/dsh-tool-fs-search');
  const ctx = new Context();
  const root = join(workspace, 'search');
  await mkdir(root);
  await writeFile(join(root, '中文 文件.txt'), '第一行\n测试内容\n');
  const signal = new AbortController().signal;
  const run = (name, argv, callSignal = signal) => runRipgrep(ctx, { signal: callSignal }, name, argv, 1024 * 1024, 1000, 4096);
  try {
    await ctx.plugin(LocalSubprocess);
    assert.equal(await realpath(await resolveRgPath()), await realpath(join(core, 'bin/rg')));
    const found = await run('grep', buildGrepCommand({ pattern: '测试内容', path: root }));
    assert.equal(found.noMatches, false);
    const matches = parseGrepMatches(found.stdout);
    assert.equal(matches.length, 1);
    assert.equal(matches[0].lineNumber, 2);
    assert.equal(matches[0].line, '测试内容');
    const files = await run('glob', buildGlobCommand({ pattern: '**/*.txt', path: root }));
    assert.match(files.stdout, /中文 文件\.txt/);
    assert.equal((await run('grep', buildGrepCommand({ pattern: 'absent-text', path: root }))).noMatches, true);
    await assert.rejects(run('grep', buildGrepCommand({ pattern: '[', path: root })), { code: 'SEARCH_INVALID_PATTERN' });
    await symlink(directory, join(root, 'escape'));
    await symlink(join(directory, 'not-created'), join(root, 'dangling'));
    for (const path of [directory, join(root, 'escape'), join(root, 'dangling')]) {
      await assert.rejects(run('grep', buildGrepCommand({ pattern: '.', path })), /Android 文件与搜索仅允许访问/);
    }
    await writeFile(join(directory, 'secret.txt'), 'OUTSIDE_SECRET');
    await symlink(directory, join(root, 'outside-link'));
    const hidden = await run('grep', buildGrepCommand({ pattern: 'OUTSIDE_SECRET', path: root }));
    assert.equal(hidden.noMatches, true);
    const controller = new AbortController();
    controller.abort();
    await assert.rejects(run('grep', buildGrepCommand({ pattern: '.', path: root }), controller.signal), { code: 'SEARCH_ABORTED' });
  } finally { await ctx.fiber.dispose(); }
});

test('current session attachments are verified and readable without granting writes or other sessions', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { Session } = await load('@deepseek-ai/dsh-session');
  const { default: Attachments } = await load('@deepseek-ai/dsh-attachment-local');
  const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
  const ctx = new Context();
  const full = { mode: 'danger-full-access', workspaceRoot: workspace };
  ctx.provide('sandboxPolicy', { defaultMode: 'workspace-write', resolve: () => full });
  try {
    await ctx.plugin(Attachments, { dshHome: join(directory, 'attachment-home') });
    await ctx.plugin(AndroidFiles, { cwd: workspace });
    const ref = await ctx.attachments.saveFile({ data: Buffer.from('附加文件：只读\n'), name: '资料.txt' });
    const other = await ctx.attachments.saveFile({ data: Buffer.from('OTHER_SESSION'), name: 'other.txt' });
    const path = ctx.attachments.fileHostPath(ref);
    const otherPath = ctx.attachments.fileHostPath(other);
    const session = Session.create('attachment-session');
    const event = session.append('user/message', { id: 'attachment-message', role: 'user', source: { kind: 'user' },
      content: [{ type: 'file', attachment: ref }] }, { surfaceOp: 'append' });
    const agent = { id: session.id, ctx, session };
    const empty = { id: 'empty-session', ctx, session: Session.create('empty-session') };
    await runTool({ name: 'read', agent }, async () => {
      const target = await ctx.fs.resolve(path);
      assert.equal(await ctx.fs.readText(target), '附加文件：只读\n');
      await assert.rejects(ctx.fs.writeText(target, 'blocked', undefined, undefined, full), { code: 'FS_SANDBOX_DENIED' });
      await assert.rejects(ctx.fs.resolve(otherPath), { code: 'FS_SANDBOX_DENIED' });
      await assert.rejects(ctx.fs.resolve(join(path, '..')), { code: 'FS_SANDBOX_DENIED' });
      await runTool({ name: 'read', agent: empty }, () => assert.rejects(ctx.fs.resolve(path), { code: 'FS_SANDBOX_DENIED' }));
      assert.equal(await ctx.fs.readText(target), '附加文件：只读\n');
    });
    for (const name of ['read', 'write', 'edit', 'glob', 'grep']) {
      await runTool({ name, ...(name === 'read' ? {} : { agent }) }, () => assert.rejects(ctx.fs.resolve(path), { code: 'FS_SANDBOX_DENIED' }));
    }
    const restored = Session.create('attachment-restored', [event]);
    await runTool({ name: 'read', agent: { id: restored.id, ctx, session: restored } }, async () => {
      assert.equal(await ctx.fs.readText(await ctx.fs.resolve(path)), '附加文件：只读\n');
    });
    await chmod(path, 0o600);
    await writeFile(path, 'CORRUPTED_ATTACHMENT');
    await runTool({ name: 'read', agent }, () => assert.rejects(ctx.fs.resolve(path), { code: 'ATTACHMENT_CORRUPT' }));
    await rm(path);
    await symlink(otherPath, path);
    await runTool({ name: 'read', agent }, () => assert.rejects(ctx.fs.resolve(path), { code: 'FS_SANDBOX_DENIED' }));
  } finally { await ctx.fiber.dispose(); }
});

test('official image store normalizes, transforms and verifies images with Android sharp', async () => {
  const sharp = require('sharp');
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: Attachments } = await load('@deepseek-ai/dsh-attachment-local');
  const ctx = new Context();
  try {
    await ctx.plugin(Attachments, { dshHome: join(directory, 'image-home'), normalizedImageMaxPixels: 4096 });
    const source = await sharp({ create: { width: 120, height: 80, channels: 3, background: { r: 42, g: 100, b: 180 } } }).png().toBuffer();
    const [ref] = await ctx.attachments.saveImages([{ data: source, mediaType: 'image/png', name: '图片.png' }]);
    assert.ok(ref.width * ref.height <= 4096);
    const stored = await ctx.attachments.readImage(ref);
    const metadata = await sharp(stored.data).metadata();
    assert.equal(metadata.width, ref.width);
    assert.equal(metadata.height, ref.height);
    assert.equal(metadata.space, 'srgb');
    const variant = await ctx.attachments.readImageRequest(ref, { width: 24, height: 16, maxBytes: 8192 });
    const transformed = await sharp(variant.data).metadata();
    assert.ok(transformed.width <= 24 && transformed.height <= 16);
    const repeat = await ctx.attachments.readImageRequest(ref, { width: 24, height: 16, maxBytes: 8192 });
    assert.deepEqual(repeat.data, variant.data);
    await assert.rejects(ctx.attachments.saveImages([{ data: Buffer.from('not an image'), mediaType: 'image/png' }]));
    await assert.rejects(ctx.attachments.saveImages([{ data: source, mediaType: 'image/jpeg' }]));
    await chmod(ctx.attachments.imageHostPath(ref), 0o600);
    await writeFile(ctx.attachments.imageHostPath(ref), 'CORRUPTED_IMAGE');
    await assert.rejects(ctx.attachments.readImage(ref), { code: 'ATTACHMENT_CORRUPT' });
  } finally { await ctx.fiber.dispose(); }
});

async function waitFor(check, message) {
  const end = Date.now() + 10_000;
  while (Date.now() < end) {
    if (await check()) return;
    await delay(25);
  }
  assert.fail(message);
}

test('Android terminal persistent and isolated commands retain separate state and clean up', { timeout: 90_000 }, async () => {
  const fixture = await policyServer();
  const script = `
    import assert from 'node:assert/strict';
    import { createRequire } from 'node:module';
    import { join } from 'node:path';
    import { pathToFileURL } from 'node:url';
    import { mkdir, writeFile, readFile, stat } from 'node:fs/promises';
    const core = process.env.DSHX_CORE;
    const require = createRequire(join(core, 'lib/node_modules/package.json'));
    const load = name => import(pathToFileURL(require.resolve(name)));
    const { Context } = await load('@deepseek-ai/cordis');
    const { createScope } = await load('@deepseek-ai/dsh-scope');
    const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
    const ctx = new Context();
    const cwd = process.env.DSHX_WORKSPACE;
    ctx.provide('workingDirectory', { ensure: async () => cwd });
    ctx.provide('sandboxPolicy', { defaultMode: 'danger-full-access', resolve: () => ({ mode: 'danger-full-access', workspaceRoot: cwd }) });
    ctx.provide('sessionProjections', { stateOf: () => undefined });
    ctx.provide('sandbox', { wrap: async () => { throw new Error('Full must use the local executor'); } });
    const owners = [];
    const owner = id => {
      const scope = createScope(ctx, {});
      const agent = { id, ctx: scope.ctx, session: { id, header: { id, cwd } } };
      const detach = ctx.agents.enter(agent, owners[0]?.agent);
      owners.push({ agent, scope, detach });
      return agent;
    };
    try {
      for (const name of ['system-prompt', 'tools', 'agent', 'subprocess-local', 'shell-env', 'bash-sandbox', 'terminal', 'terminal-bash']) {
        const plugin = await load('@deepseek-ai/dsh-' + name);
        await ctx.plugin(plugin.default ?? plugin);
      }
      ctx.provide('profileContext', { name: 'terminal-profile', dir: join(cwd, 'profile') });
      ctx.shellEnv.register({ name: 'terminal-fixture', variables: { DSH_WEB_URL: { description: 'Web URL fixture' } }, resolve: () => ({ DSH_WEB_URL: 'http://127.0.0.1:3210/' }) });
      await policyHost.register('full-session', 'Full');
      const main = owner('full-session');
      await ctx.plugin(await import(pathToFileURL(join(core, 'guard.mjs'))));
      await ctx.plugin(await load('@deepseek-ai/dsh-tool-bash-persistent'), { timeoutMs: 1000 });
      await ctx.plugin(await load('@deepseek-ai/dsh-tool-bash'), { enableRunInBackground: false });
      await ctx.plugin(await load('@deepseek-ai/dsh-tool-terminal'), { enableRunInBackground: false });
      assert.equal(ctx.terminals.list(main).length, 1, 'default shell must exist before the first tool call');
      const original = ctx.terminals.list(main)[0];
      const call = async (name, args, agent = main, signal = new AbortController().signal) => {
        const result = await ctx.tools.execute({ name, arguments: args, agent, signal, callId: 'terminal-check' });
        assert.equal(result.isError, false, JSON.stringify(result));
        return result.value;
      };
      const bash = command => call('bash', { command });
      const initialEnv = await bash('printf "%s|%s|%s|%s|%s" "$DSH_HOME" "$DSH_PROFILE" "$DSH_PROFILE_DIR" "$DSH_WEB_URL" "$DSH_SESSION_ID"');
      const shellEnv = ctx.shellEnv.collect({ agent: main });
      assert.ok(initialEnv.includes([shellEnv.DSH_HOME, 'terminal-profile', join(cwd, 'profile'), 'http://127.0.0.1:3210/', main.id].join('|')), initialEnv);
      const subdir = join(cwd, 'terminal-project');
      const venv = join(cwd, 'terminal-venv');
      await mkdir(subdir, { recursive: true });
      await mkdir(join(venv, 'bin'), { recursive: true });
      await writeFile(join(venv, 'bin/activate'), 'export VIRTUAL_ENV=' + JSON.stringify(venv) + '; export PATH="$VIRTUAL_ENV/bin:$PATH"');
      await bash('cd ' + JSON.stringify(subdir) + '; export TERMINAL_PROOF=retained; source ' + JSON.stringify(join(venv, 'bin/activate')));
      assert.match(await bash('printf "%s|%s|%s|%s" "$PWD" "$TERMINAL_PROOF" "$VIRTUAL_ENV" "$PATH"'), new RegExp('terminal-project\\\\|retained\\\\|.*terminal-venv\\\\|.*terminal-venv/bin'));
      assert.match(await bash('false'), /exit code 1/);
      assert.match(await bash('printf after-failure'), /after-failure/);
      const parallel = await Promise.all([bash('export TERMINAL_ORDER=first'), bash('printf "order=%s" "$TERMINAL_ORDER"')]);
      assert.match(parallel[1], /order=first/);
      assert.equal(ctx.terminals.list(main)[0].sessionId, original.sessionId);
      for (let i = 0; i < 2; i++) {
        const result = await call('bash_isolated', { description: 'Check isolated shell', command: 'printf "isolated:%s:%s" "$PWD" "\${TERMINAL_PROOF-unset}"; export TERMINAL_PROOF=isolated; cd /', workdir: cwd });
        assert.equal(result.exitCode, 0);
        assert.match(result.stdout.text, /isolated:.*:unset/);
      }
      assert.match(await bash('printf "still=%s" "$TERMINAL_PROOF"'), /still=retained/);
      const isolated = await call('terminal_open', { type: 'shell', name: 'worker' });
      await call('terminal_send', { sessionId: isolated.sessionId, text: 'export TERMINAL_PROOF=worker' });
      const worker = await call('terminal_send', { sessionId: isolated.sessionId, text: 'printf "worker=%s" "$TERMINAL_PROOF"' });
      assert.match(worker.viewport, /worker=worker/);
      assert.match(await bash('printf "main=%s" "$TERMINAL_PROOF"'), /main=retained/);
      const second = owner('child-session');
      await policyHost.register(second.id, 'Child', main.id);
      await ctx.agents.announce(second, 'startup');
      assert.equal(ctx.terminals.list(second).length, 1, 'created hook must warm each new owner');
      assert.match(await call('bash', { command: 'printf "child=%s" "\${TERMINAL_PROOF-unset}"' }, second), /child=unset/);
      const foreign = await ctx.tools.execute({ name: 'terminal_send', agent: second, arguments: { sessionId: isolated.sessionId, text: 'echo forbidden' }, signal: new AbortController().signal, callId: 'foreign' });
      assert.equal(foreign.isError, true);
      assert.match(JSON.stringify(foreign), /another agent/);
      await call('terminal_close', { sessionId: isolated.sessionId });
      await call('terminal_close', { sessionId: original.sessionId });
      assert.match(await bash('printf "reset=%s" "\${TERMINAL_PROOF-unset}"'), /reset=unset/);
      const timedOut = await bash('sleep 10');
      assert.match(timedOut, /Command timed out/);
      assert.match(timedOut, /shell was reset/);
      assert.match(await bash('printf after-timeout'), /after-timeout/);
      const abort = new AbortController();
      const running = ctx.tools.execute({ name: 'bash', arguments: { command: 'sleep 10' }, agent: main, signal: abort.signal, callId: 'terminal-cancel' });
      setTimeout(() => abort.abort(new Error('test cancellation')), 150);
      const cancelled = await running;
      assert.equal(cancelled.isError, true);
      assert.match(JSON.stringify(cancelled), /ABORTED/);
      assert.match(await bash('printf after-cancel'), /after-cancel/);
      await bash('sleep 30 & echo $! > ' + JSON.stringify(join(cwd, 'terminal-child.pid')));
      const childPid = Number(await readFile(join(cwd, 'terminal-child.pid'), 'utf8'));
      const pids = ctx.terminals.list(main).map(row => row.pid);
      await owners[0].scope.dispose();
      assert.deepEqual(ctx.terminals.list(main), []);
      for (const pid of pids) await assert.rejects(stat('/proc/' + pid + '/stat'), { code: 'ENOENT' });
      try { assert.match(await readFile('/proc/' + childPid + '/stat', 'utf8'), /\\) [ZXx] /); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
    } finally {
      for (const entry of owners.reverse()) { await entry.scope.dispose(); entry.detach(); }
      await ctx.fiber.dispose();
      policyHost.close();
    }
  `;
  const child = spawn(process.execPath, ['--expose-internals', '--import', join(core, 'compat.mjs'), '--input-type=module', '-e', script], {
    env: { ...process.env, DSHX_POLICY_PORT: String(fixture.config.port), DSHX_POLICY_INSTANCE: fixture.config.instance, DSHX_POLICY_TOKEN: fixture.config.token },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let output = '';
  child.stdout.on('data', data => { output += data; }); child.stderr.on('data', data => { output += data; });
  const timer = setTimeout(() => child.kill('SIGKILL'), 80_000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    assert.equal(code, 0, output);
  } finally { clearTimeout(timer); child.kill(); await fixture.close(); }
});

test('official terminal writes, resizes and terminates real Android PTYs for five cycles', { timeout: 90_000 }, async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: LocalSubprocess } = await load('@deepseek-ai/dsh-subprocess-local');
  const ctx = new Context();
  try {
    await ctx.plugin(LocalSubprocess);
    for (let cycle = 0; cycle < 5; cycle++) {
      const terminal = await ctx.subprocess.spawnTerminal({
        argv: [join(core, 'bin/bash'), '--noprofile', '--norc', '-i'],
        cwd: directory, rows: 24, cols: 80, terminalType: 'xterm-256color', graceMs: 1000,
        env: { TERMINFO: join(core, 'share/terminfo'), PS1: 'dshx-test> ' }
      });
      let output = '';
      terminal.output.on('data', chunk => { output += chunk.toString('utf8'); });
      try {
        await waitFor(() => output.includes('dshx-test> '), 'interactive bash did not become ready');
        assert.equal((await terminal.inspectForeground())?.processGroupId, terminal.pid);
        await terminal.resize(100, 35);
        await terminal.write("printf '\\u4e2d\\u6587:%s:%s\\n' \"$COLUMNS\" \"$LINES\"\r");
        await waitFor(() => output.includes('中文:100:35'), 'PTY input or dimensions were not applied');
        await terminal.write("sleep 30 & printf 'CHILD:%s\\n' \"$!\"; wait\r");
        await waitFor(() => /CHILD:\d+/.test(output), 'terminal did not create the cleanup fixture');
        const child = Number(output.match(/CHILD:(\d+)/)[1]);
        await stat(`/proc/${child}/stat`);
        await terminal.terminate();
        await terminal.done;
        await terminal.terminate();
        await assert.rejects(readFile(`/proc/${terminal.pid}/stat`), { code: 'ENOENT' });
        // A killed job may briefly remain as a reparented zombie. It cannot
        // execute; a live or stopped survivor must still fail this check.
        await waitFor(async () => {
          try { return /\) [ZXx] /.test(await readFile(`/proc/${child}/stat`, 'utf8')); }
          catch (error) { if (error.code === 'ENOENT') return true; throw error; }
        }, 'a terminal background job survived termination');
      } finally { await terminal.terminate(); }
    }
  } finally { await ctx.fiber.dispose(); }
});

test('uses Android Bionic Node with the pinned major version', () => {
  assert.equal(process.platform, 'android');
  assert.equal(Number(process.versions.node.split('.')[0]), 24);
});

test('native locking rejects a second live file descriptor', async () => {
  const file = join(directory, 'lock-a');
  const first = await open(file, 'w');
  const second = await open(file, 'r+');
  try {
    native.tryLockExclusive(first.fd);
    assert.throws(() => native.tryLockExclusive(second.fd), { code: 'EAGAIN' });
  } finally { await first.close(); await second.close(); }
});

test('closing the holder releases the kernel lock', async () => {
  const file = join(directory, 'lock-b');
  const first = await open(file, 'w');
  native.tryLockExclusive(first.fd);
  await first.close();
  const second = await open(file, 'r+');
  try { native.tryLockExclusive(second.fd); } finally { await second.close(); }
});

test('another process cannot acquire an owned session lock', async () => {
  const file = join(directory, 'lock-c');
  const holder = await open(file, 'w');
  native.tryLockExclusive(holder.fd);
  try {
    const child = spawnSync(process.execPath, ['-e', `
      const fs = require('node:fs');
      const n = require(process.argv[1]);
      const fd = fs.openSync(process.argv[2], 'r+');
      try { n.tryLockExclusive(fd); process.exitCode = 2; }
      catch (e) { process.exitCode = e.code === 'EAGAIN' ? 0 : 3; }
      finally { fs.closeSync(fd); }
    `, join(core, 'native.node'), file], { encoding: 'utf8' });
    assert.equal(child.status, 0, child.stderr);
  } finally { await holder.close(); }
});

test('invalid lock arguments fail visibly', () => {
  assert.throws(() => native.tryLockExclusive(-1), TypeError);
});

test('atomic publication preserves a pre-existing destination', async () => {
  const source = join(directory, 'publish-source');
  const target = join(directory, 'publish-target');
  await writeFile(source, 'new');
  await writeFile(target, 'old');
  assert.throws(() => native.publish(source, target), { code: 'EEXIST' });
  assert.equal(await readFile(target, 'utf8'), 'old');
  assert.equal(await readFile(source, 'utf8'), 'new');
});

test('atomic publication moves a complete staged file', async () => {
  const source = join(directory, 'publish-new');
  const target = join(directory, 'published');
  await writeFile(source, 'complete data');
  native.publish(source, target);
  assert.equal(await readFile(target, 'utf8'), 'complete data');
  await assert.rejects(stat(source), { code: 'ENOENT' });
});

test('compatibility link leaves the source intact and refuses overwrite', async () => {
  await import(pathToFileURL(join(core, 'compat.mjs')));
  const source = join(directory, 'link-source');
  const target = join(directory, 'link-target');
  await writeFile(source, 'persistent session');
  await link(source, target);
  assert.equal(await readFile(source, 'utf8'), 'persistent session');
  assert.equal(await readFile(target, 'utf8'), 'persistent session');
  await assert.rejects(link(source, target), { code: 'EEXIST' });
  await chmod(source, 0o400);
  const readonly = join(directory, 'link-readonly-target');
  await link(source, readonly);
  assert.equal(await readFile(readonly, 'utf8'), 'persistent session');
  assert.equal((await stat(readonly)).mode & 0o777, 0o400);
  await assert.rejects(link(source, readonly), { code: 'EEXIST' });
  assert.equal(await readFile(source, 'utf8'), 'persistent session');
});

test('official flock entry actually uses the Android kernel binding', async () => {
  const file = join(directory, 'official-lock');
  const { tryLockExclusive } = await import(pathToFileURL(require.resolve('@deepseek-ai/node-addon-system/flock')));
  const a = await open(file, 'w');
  const b = await open(file, 'r+');
  try {
    await tryLockExclusive(a.fd);
    await assert.rejects(tryLockExclusive(b.fd), { code: 'EAGAIN' });
  } finally { await a.close(); await b.close(); }
});

test('official loader receives real Node internals on Android', () => {
  const api = require('node-addon-require-builtin');
  assert.equal(typeof api.requireBuiltin('internal/modules/esm/loader').getOrInitializeCascadedLoader, 'function');
});

for (const scenario of ['direct', 'later allow listener', 'removed plugin guard', 'subagent']) {
  test(`final Android tool refusal covers ${scenario}`, async () => {
    const { Context } = await load('@deepseek-ai/cordis');
    const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
    const { default: Tools } = await load('@deepseek-ai/dsh-tools');
    const ctx = new Context();
    let calls = 0;
    try {
      await ctx.plugin(SystemPrompt);
      await ctx.plugin(Tools);
      ctx.tools.register({
        name: 'test_effect', description: 'test only',
        parameters: { type: 'object', properties: {} },
        output: { schema: { type: 'string' }, render: (_, value) => [{ type: 'text', text: value }] },
        execute: async () => { calls++; return 'unexpected execution'; }
      });
      if (scenario === 'later allow listener') ctx.on('tools/pre-execute', async () => ({ kind: 'allow' }), { prepend: true });
      if (scenario === 'removed plugin guard') { const remove = ctx.tools.guard(() => 'temporary'); remove(); }
      const result = await ctx.tools.execute({
        name: 'test_effect', callId: 'android-test', arguments: {}, signal: new AbortController().signal,
        ...(scenario === 'subagent' ? { agent: { id: 'child-test' } } : {})
      });
      assert.equal(calls, 0);
      assert.match(JSON.stringify(result), /其他模型工具尚未开放/);
    } finally { await ctx.fiber.dispose(); }
  });
}

test('voice local routing calibrates examples and leaves unknown requests unmatched', async () => {
  const { localQuestion } = await import(pathToFileURL(join(core, 'voice.mjs')));
  const { createRouter } = await import(pathToFileURL(join(core, 'local-route.mjs')));
  const router = createRouter({
    routes: [{ name: 'greeting', examples: ['你好', '您好', '晚上好'] }],
    calibration: [{ text: '你好呀', route: 'greeting' }, { text: '你好，帮我设计一整套系统', route: null }],
  });
  assert.equal(router.match('你好').route, 'greeting');
  assert.equal(router.match('你好，帮我设计一整套系统').route, null);
  assert.equal(router.match('量子力学证明题').route, null);
  assert.ok(router.match('你好').threshold > 0, 'thresholds come from labelled calibration examples');
  assert.throws(() => createRouter({ routes: [{ name: 'empty', examples: ['你好'] }], calibration: [] }), /校准/);
  const tied = createRouter({
    routes: [{ name: 'one', examples: ['你好'] }, { name: 'two', examples: ['你好'] }],
    calibration: [{ text: '你好呀', route: 'one' }, { text: '你好啊', route: 'two' }, { text: '未知', route: null }],
  });
  assert.equal(tied.match('你好').route, null, 'nearly tied categories must remain unmatched');
  const unsupported = createRouter({ routes: [{ name: 'one', examples: ['你好'] }],
    calibration: [{ text: '未知', route: 'one' }, { text: '再见', route: null }] });
  assert.equal(unsupported.match('你好').route, null, 'disable a route with no positive calibration support');
  for (const text of ['你好', '您好！', '你叫什么', '什么是光合作用', '本地回答：把你的第二点展开说说']) {
    assert.equal(localQuestion(text), true, text);
  }
  for (const text of ['今天的天气', '本地回答：今天的天气', '打开微信', 'Open WeChat', '帮我设计一个分布式数据库',
    '你好，帮我规划一个完整的系统', '本地回答：删除目录', '随机内容 xyz987', '什么是光合作用，并搜索最新论文']) {
    assert.equal(localQuestion(text), false, text);
  }
});

test('voice local input keeps model replies checkpoints and instructions without rewriting the session', async () => {
  const { localInput } = await import(pathToFileURL(join(core, 'voice.mjs')));
  const { createUserMessage, createAssistantMessage } = await load('@deepseek-ai/dsh-llm');
  const user = text => createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } });
  const reply = createAssistantMessage({ content: [{ type: 'text', text: '第二点是节省流量。' },
    { type: 'reasoning', text: 'private reasoning' }], source: { provider: 'fixture', model: 'remote' } });
  const summary = { ...user('校验词 KEEP_1023，任务是解释本地模型。'), source: { kind: 'compact-checkpoint' } };
  const instructions = { ...user('回答简洁，使用中文。'), source: { kind: 'agent-instructions', baseline: true } };
  const messages = [summary, instructions, user('解释一下本地模型'), reply, user('把你的第二点展开说说')];
  const before = JSON.stringify(messages);
  const input = localInput(messages, true);
  assert.ok(input.some(message => message.role === 'assistant' && message.text === '第二点是节省流量。'));
  assert.ok(input.some(message => message.role === 'context' && message.kind === 'summary' && message.text.includes('KEEP_1023')));
  assert.ok(input.some(message => message.role === 'context' && message.kind === 'instructions'));
  assert.equal(input.at(-1).text, '把你的第二点展开说说');
  assert.doesNotMatch(JSON.stringify(input), /private reasoning/);
  assert.equal(JSON.stringify(messages), before);
  assert.equal(localInput([user('把你的第二点展开说说')], true), null, 'a dependent task needs the previous complete turn');
  assert.deepEqual(localInput([user('没有回答的问题'), user('你好')]), [{ role: 'user', text: '你好' }]);
  assert.equal(localInput([user('问题'), { ...reply, content: [{ type: 'text', text: '🙂'.repeat(1800) }] },
    user('把你的第二点展开说说')], true), null, 'do not cut away the referenced answer');
  assert.equal(localInput([user('x'.repeat(1801))]), null, 'the current task cannot be silently truncated');
  assert.equal(localInput([{ role: 'user', source: { kind: 'runtime-context' }, content: [{ type: 'text', text: 'hidden' }] }]), null);
  assert.equal(localInput([user('问题'), reply]), null, 'the final conversational message must be a user task');
  const toolReply = { ...reply, content: [...reply.content, { type: 'tool-call', id: 'call', name: 'search', arguments: {} }] };
  assert.equal(localInput([user('问题'), toolReply, user('把你的第二点展开说说')], true), null,
    'a tool-dependent answer is not a complete text-only turn');
  assert.equal(localInput([user('图片'), { ...user('你好'), content: [{ type: 'text', text: '你好' }, { type: 'image' }] }]), null);
  const latest = { ...instructions, content: [{ type: 'text', text: '新的基准要求' }] };
  const increment = { ...instructions, source: { kind: 'agent-instructions', baseline: false },
    content: [{ type: 'text', text: '补充要求' }] };
  assert.deepEqual(localInput([instructions, latest, increment, user('你好')]).filter(m => m.role === 'context').map(m => m.text),
    ['新的基准要求', '补充要求']);
});

test('voice local routing uses the real loop history restores selection and separates accounting', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools, defineTool } = await load('@deepseek-ai/dsh-tools');
  const { default: LlmRuntime, LlmAdapter, createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const { default: Agents, installModelSelection } = await load('@deepseek-ai/dsh-agent');
  const { default: Sessions } = await load('@deepseek-ai/dsh-session');
  const { default: Projections } = await load('@deepseek-ai/dsh-session-projection');
  const { installModelSelectionProjection } = await import(new URL('./types/model-selection-projection.js',
    pathToFileURL(require.resolve('@deepseek-ai/dsh-api-session-controller'))));
  const { default: AgentLoop } = await load('@deepseek-ai/dsh-agent-loop');
  const { voiceSession, localAdapter, localQuestion } = await import(pathToFileURL(join(core, 'voice.mjs')));
  const { usageStream } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const ctx = new Context();
  const dir = await mkdtemp(join(directory, 'voice-accounting-'));
  const previous = process.env.DSHX_ASSISTANT_DIR;
  process.env.DSHX_ASSISTANT_DIR = dir;
  let voice, disposeSelection;
  let remote = 0;
  let local = 0;
  let remoteText = '远程任务回答';
  let toolsLeft = 0;
  let remoteInput;
  const events = [];
  const original = { provider: 'fixture', model: 'remote' };
  const selection = { current: original };
  class Remote extends LlmAdapter {
    async *stream(options) {
      remote++; remoteInput = options.messages;
      yield { type: 'reasoning-delta', index: 0, text: '不能播报的思考' };
      if (toolsLeft-- > 0) {
        yield { type: 'text-delta', index: 1, text: '我先检查。' };
        yield { type: 'tool-call-delta', index: 2, id: 'voice-tool', name: 'read', argumentsDelta: '{"private":"工具参数"}' };
        yield { type: 'finish', reason: { kind: 'tool-calls' } };
        return;
      }
      yield { type: 'text-delta', index: 1, text: remoteText.slice(0, 2) };
      yield { type: 'text-delta', index: 1, text: remoteText.slice(2) };
      yield { type: 'finish', reason: { kind: 'stop' } };
    }
  }
  try {
    for (const plugin of [SystemPrompt, Tools, LlmRuntime, Agents, Sessions, Projections]) await ctx.plugin(plugin);
    installModelSelectionProjection(ctx);
    await ctx.plugin(AgentLoop, { agents: [] });
    ctx.llm.registerAdapter(['fixture'], new Remote());
    ctx.llm.registerAdapter(['dshx-local'], localAdapter(LlmAdapter, { query: async (method, data) => {
      local++; assert.equal(method, 'local-model');
      assert.equal(data.session, 'voice-session');
      assert.equal(data.messages.at(-1).text, local === 1 ? '你好' : '本地回答：把你的第二点展开说说');
      assert.ok(data.messages.every(message => ['user', 'assistant'].includes(message.role)));
      if (local === 2) assert.ok(data.messages.some(message => message.role === 'assistant' && message.text === '你好，我是小橙。'));
      selection.current = undefined; // Controller consumes a selection after its durable header.
      return { text: '你好，我是小橙。', inputTokens: 17, outputTokens: 8 };
    } }));
    ctx.on('llm/stream', (options, stream) => usageStream(options, stream));
    const agent = await ctx.agentLoop.create('voice-session', original);
    disposeSelection = installModelSelection(agent.ctx, selection);
    ctx.sessionController = {
      agents: {
        selectionFor: () => selection,
        selectForNextRequest(agent, selected) { agent.session.append('model/selection', selected); selection.current = selected; },
      },
      create: async () => ({ sessionId: agent.session.id }),
      commands: {
        resolveAgent: async () => agent,
        prompt: async (request, source) => { agent.followup(createUserMessage({ content: request.content, source })); return { accepted: true }; },
      },
    };
    ctx.on('session/event', (_session, event) => events.push(event));
    voice = voiceSession(ctx);
    assert.equal(localQuestion('你好'), true);
    assert.equal(localQuestion('本地回答：今天的天气'), false);
    assert.equal(localQuestion('搜索文件'), false);
    assert.equal(localQuestion('什么是光合作用'), true);
    const missing = await voice.call('prompt', { interaction: 'missing-history', session: agent.session.id,
      text: '把你的第二点展开说说', local: true });
    await agent.whenIdle();
    assert.equal(missing.local, false);
    assert.equal(local, 0); assert.equal(remote, 1);
    const localEvents = [];
    const result = await voice.call('prompt', { interaction: 'first', text: '你好', local: true }, event => localEvents.push(event));
    await agent.whenIdle();
    assert.equal(result.text, '你好，我是小橙。');
    assert.equal(result.session, agent.session.id);
    assert.equal(result.local, true);
    assert.deepEqual(localEvents, ['', result.text].map(text => ({ session: agent.session.id, text })));
    assert.equal(result.streamedText, result.text);
    assert.equal(local, 1); assert.equal(remote, 1);
    assert.equal(selection.current, original, 'restore even after the API consumed our temporary selection');
    assert.ok(events.some(event => event.type === 'model/selection' && event.data.provider === original.provider),
      'persist the original route before a local header can become the resumed session default');
    assert.equal(agent.session.requestHeader().config.provider, 'dshx-local');
    const replay = new Context();
    try {
      await replay.plugin(Projections);
      installModelSelectionProjection(replay);
      assert.deepEqual(replay.sessionProjections.stateOf(agent.session, 'modelSelection').pending, original,
        'a fresh projection rebuilds the original route from committed events without the execution cache');
      assert.deepEqual(replay.sessionProjections.snapshot(agent.session, ['modelSelection']).values.modelSelection.next, original);
    } finally { await replay.fiber.dispose(); }
    assert.ok(events.some(event => event.type === 'user/message' && event.data.source.rpcId));
    await voice.call('prompt', { interaction: 'followup', session: agent.session.id, text: '本地回答：把你的第二点展开说说', local: true });
    await agent.whenIdle();
    assert.equal(local, 2);
    remoteText = '完整回答'.repeat(500);
    const streamed = [], from = events.length;
    const followup = await voice.call('prompt', { interaction: 'second', session: agent.session.id, text: '搜索文件', local: true }, event => {
      assert.equal(events.slice(from).some(entry => entry.type === 'assistant/message'), false,
        '真实运行器必须在完整助手消息提交前发布片段');
      streamed.push(event);
    });
    await agent.whenIdle();
    assert.deepEqual(streamed, ['', remoteText.slice(0, 2), remoteText.slice(2)].map(text => ({ session: agent.session.id, text })));
    assert.equal(followup.text, remoteText);
    assert.equal(followup.streamedText, streamed.map(event => event.text).join(''));
    assert.equal(followup.local, false); assert.equal(remote, 2);
    assert.equal(agent.session.requestHeader().config.provider, 'fixture');
    const ledger = (await readFile(join(dir, 'local-usage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
    assert.deepEqual(ledger.map(row => row.event), ['start', 'finish', 'start', 'finish']);
    assert.deepEqual(ledger[1].usage, { inputTokens: 17, outputTokens: 8 });
    assert.equal(ledger[1].outcome, 'success');
    const cloudLedger = await readFile(join(dir, 'usage.jsonl'), 'utf8');
    assert.doesNotMatch(cloudLedger, /dshx-local/);
    assert.doesNotMatch(JSON.stringify(ledger), /你好|搜索文件|小橙/);
    const tooLong = await voice.call('prompt', { interaction: 'long-history', session: agent.session.id,
      text: '把你的第二点展开说说', local: true });
    await agent.whenIdle();
    assert.equal(tooLong.local, false);
    assert.equal(local, 2); assert.equal(remote, 3, 'do not truncate the required previous answer');
    let inject = true;
    const stopInject = ctx.on('agent/request', ({ agent }, next) => {
      if (inject) {
        inject = false;
        agent.session.append('user/message', createUserMessage({ content: [{ type: 'text', text: '长指令'.repeat(700) }],
          source: { kind: 'agent-instructions', baseline: true } }), { surfaceOp: 'append' });
      }
      return next();
    });
    const start = events.length;
    remoteText = '原模型承接同一轮输入。';
    try {
      const overflow = await voice.call('prompt', { interaction: 'late-context', session: agent.session.id, text: '你好', local: true });
      await agent.whenIdle();
      assert.equal(overflow.local, false);
      assert.equal(overflow.text, remoteText);
      assert.equal(local, 2, 'reject oversized final context before invoking native inference');
      assert.equal(remote, 4, 'retry exactly once on the original model');
      assert.equal(selection.current, original);
      assert.ok(remoteInput.some(message => message.source?.kind === 'agent-instructions' &&
        message.content.some(part => part.type === 'text' && part.text === '长指令'.repeat(700))),
      'the original model receives the complete instruction that could not fit locally');
      assert.equal(events.slice(start).filter(e => e.type === 'user/message' && e.data.source?.kind === 'user').length, 1,
        'a fallback must not enqueue or persist the same user message twice');
      const attempts = (await readFile(join(dir, 'local-usage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
      assert.equal(attempts.at(-1).outcome, 'failed');
      assert.equal(attempts.at(-1).usage, null);
    } finally { stopInject(); }
    let toolCalls = 0;
    // Android 只向模型开放已适配的工具名；复用 read，执行体仍是无文件操作的测试函数。
    ctx.tools.register(defineTool({
      name: 'read', description: '语音增量测试工具', parameters: { private: { type: 'string' } },
      output: { schema: { type: 'object', additionalProperties: true }, render: () => [] },
      execute: async args => { assert.equal(args.private, '工具参数'); toolCalls++; return { ok: true }; },
    }));
    assert.ok(ctx.tools.schemas(agent).some(tool => tool.name === 'read'), '测试工具必须通过平台策略并出现在模型可见列表中');
    toolsLeft = 1; remoteText = '检查结果正常。';
    const steps = [];
    const toolStart = events.length;
    const toolAnswer = await voice.call('prompt', { interaction: 'tool-turn', session: agent.session.id, text: '搜索文件' },
      event => steps.push(event));
    await agent.whenIdle();
    const results = events.slice(toolStart).filter(event => event.type === 'tool/result');
    assert.equal(results.length, 1);
    assert.notEqual(results[0].data.isError, true, JSON.stringify(results[0].data));
    assert.equal(toolCalls, 1);
    assert.equal(toolAnswer.text, remoteText, '最终正式回答保留现有最后一条无工具消息的语义');
    assert.equal(toolAnswer.streamedText, '我先检查。' + remoteText);
    assert.equal(toolAnswer.streamedText, steps.map(event => event.text).join(''), '实际推送记录必须跨步骤累加');
    assert.equal(toolAnswer.text.startsWith(toolAnswer.streamedText), false, '存在前导说明时接收端不能补读整篇');
    assert.doesNotMatch(toolAnswer.streamedText, /思考|工具参数|private/);
  } finally {
    voice?.close(); disposeSelection?.(); await ctx.fiber.dispose();
    if (previous === undefined) delete process.env.DSHX_ASSISTANT_DIR; else process.env.DSHX_ASSISTANT_DIR = previous;
  }
});

for (const target of [{ provider: 'dshx-local', model: 'custom' }, { provider: 'fixture', model: 'small-online' }]) {
  test(`voice custom split uses ${target.model} in the real loop and restores the original model`, async () => {
    const { Context } = await load('@deepseek-ai/cordis');
    const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
    const { default: Tools } = await load('@deepseek-ai/dsh-tools');
    const { default: LlmRuntime, LlmAdapter, createUserMessage } = await load('@deepseek-ai/dsh-llm');
    const { default: Agents, installModelSelection } = await load('@deepseek-ai/dsh-agent');
    const { default: Sessions } = await load('@deepseek-ai/dsh-session');
    const { default: Projections } = await load('@deepseek-ai/dsh-session-projection');
    const { default: AgentLoop } = await load('@deepseek-ai/dsh-agent-loop');
    const { voiceSession, localAdapter } = await import(pathToFileURL(join(core, 'voice.mjs')));
    const ctx = new Context(), calls = [], events = [];
    const original = { provider: 'fixture', model: 'main' };
    const selection = { current: original };
    class Remote extends LlmAdapter {
      async *stream(options) {
        calls.push({ provider: options.provider, model: options.model });
        selection.current = undefined;
        yield { type: 'text-delta', index: 0, text: options.model };
        yield { type: 'finish', reason: { kind: 'stop' } };
      }
    }
    let voice, dispose;
    try {
      for (const plugin of [SystemPrompt, Tools, LlmRuntime, Agents, Sessions, Projections]) await ctx.plugin(plugin);
      await ctx.plugin(AgentLoop, { agents: [] });
      ctx.llm.registerAdapter(['fixture'], new Remote());
      ctx.llm.registerAdapter(['dshx-local'], localAdapter(LlmAdapter, { async query(method, data) {
        assert.equal(method, 'local-model');
        assert.equal(data.model, 'custom');
        assert.equal(data.messages.at(-1).text, '你好');
        calls.push({ provider: 'dshx-local', model: data.model });
        selection.current = undefined;
        return { text: 'custom', inputTokens: 12, outputTokens: 2 };
      } }));
      const agent = await ctx.agentLoop.create('custom-split', original);
      dispose = installModelSelection(agent.ctx, selection);
      ctx.sessionController = {
        agents: {
          selectionFor: () => selection,
          selectForNextRequest(agent, selected) { agent.session.append('model/selection', selected); selection.current = selected; },
        },
        commands: {
          resolveAgent: async () => agent,
          prompt: async (request, source) => { agent.followup(createUserMessage({ content: request.content, source })); },
        },
      };
      ctx.on('session/event', (_session, entry) => events.push(entry));
      voice = voiceSession(ctx);
      const result = await voice.call('prompt', { interaction: 'custom-task', session: agent.session.id, text: '你好', local: true, smallModel: target });
      await agent.whenIdle();
      assert.equal(result.text, target.model);
      assert.equal(result.local, target.provider === 'dshx-local');
      assert.deepEqual(calls, [target]);
      assert.equal(selection.current, original);
      assert.deepEqual(events.filter(entry => entry.type === 'model/selection').map(entry => entry.data), [original],
        'only the original model is persisted as the next choice, even within the same provider');
      await voice.call('prompt', { interaction: 'complex-task', session: agent.session.id, text: '搜索文件', local: true, smallModel: target });
      await agent.whenIdle();
      assert.deepEqual(calls, [target, original]);
      selection.current = original;
      await voice.call('prompt', { interaction: 'disabled-task', session: agent.session.id, text: '你好', local: false, smallModel: target });
      await agent.whenIdle();
      assert.deepEqual(calls, [target, original, original]);
      for (const invalid of [{ provider: 'dshx-local', model: 'unknown' }, { provider: '', model: 'small' },
        { provider: 'fixture', model: 'small', apiKey: 'must-not-cross' }, { provider: 'fixture', model: 'bad\u0000model' }]) {
        await assert.rejects(voice.call('prompt', { interaction: 'invalid-task', session: agent.session.id, text: '你好', local: true, smallModel: invalid }), /配置无效/);
      }
      assert.equal(calls.length, 3);
    } finally { voice?.close(); dispose?.(); await ctx.fiber.dispose(); }
  });
}

for (const target of [{ provider: 'dshx-local', model: 'qwen3-0.6b' },
  { provider: 'dshx-local', model: 'custom' }, { provider: 'fixture', model: 'small-online' }]) {
  test(`simple tasks route typed input through ${target.model} and keep the main model`, async () => {
    const { Context } = await load('@deepseek-ai/cordis');
    const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
    const { default: Tools } = await load('@deepseek-ai/dsh-tools');
    const { default: LlmRuntime, LlmAdapter, createUserMessage } = await load('@deepseek-ai/dsh-llm');
    const { default: Agents, installModelSelection } = await load('@deepseek-ai/dsh-agent');
    const { default: Sessions } = await load('@deepseek-ai/dsh-session');
    const { default: Projections } = await load('@deepseek-ai/dsh-session-projection');
    const { default: AgentLoop } = await load('@deepseek-ai/dsh-agent-loop');
    const { voiceSession, localAdapter } = await import(pathToFileURL(join(core, 'voice.mjs')));
    const ctx = new Context(), calls = [], events = [];
    const original = { provider: 'fixture', model: 'main' }, selection = { current: original };
    let enabled = true, fail = false, voice, dispose, gate, started;
    const wait = async signal => {
      if (!gate) return;
      started.resolve();
      await Promise.race([gate.promise, new Promise((_, reject) => {
        signal.addEventListener('abort', () => reject(signal.reason), { once: true });
      })]);
    };
    class Remote extends LlmAdapter {
      async *stream(options) {
        calls.push({ provider: options.provider, model: options.model });
        selection.current = undefined;
        await wait(options.signal);
        if (fail) throw new Error('fixture failure');
        yield { type: 'text-delta', index: 0, text: '第二点是节省流量。' };
        yield { type: 'finish', reason: { kind: 'stop' } };
      }
    }
    try {
      for (const plugin of [SystemPrompt, Tools, LlmRuntime, Agents, Sessions, Projections]) await ctx.plugin(plugin);
      await ctx.plugin(AgentLoop, { agents: [] });
      ctx.llm.registerAdapter(['fixture'], new Remote());
      ctx.llm.registerAdapter(['dshx-local'], localAdapter(LlmAdapter, { async query(method, data, signal) {
        assert.equal(method, 'local-model');
        calls.push({ provider: 'dshx-local', model: data.model ?? 'qwen3-0.6b' });
        selection.current = undefined;
        await wait(signal);
        if (fail) throw new Error('fixture failure');
        return { text: '第二点是节省流量。', inputTokens: 12, outputTokens: 8 };
      } }));
      const agent = await ctx.agentLoop.create('typed-simple', original);
      dispose = installModelSelection(agent.ctx, selection);
      ctx.sessionController = { agents: {
        selectionFor: () => selection,
        selectForNextRequest(agent, selected) { agent.session.append('model/selection', selected); selection.current = selected; },
      } };
      ctx.on('session/event', (_session, entry) => events.push(entry));
      voice = voiceSession(ctx, { configured: true, async smallModel(session, signal) {
        assert.equal(session, agent.session.id); assert.equal(signal.aborted, false);
        return { enabled, route: { provider: 'fixture', model: 'image-only' }, textRoute: target };
      } });
      const send = async (text, content = [{ type: 'text', text }], source = { kind: 'user' }) => {
        agent.followup(createUserMessage({ content, source }));
        await agent.whenIdle();
      };
      await send('你好');
      assert.deepEqual(calls, [target], 'typed input uses the configured text route, not the screenshot route');
      assert.equal(selection.current, original);
      await send('把你的第二点展开说说');
      assert.deepEqual(calls, [target, target]);
      assert.equal(selection.current, original);
      await send('搜索文件');
      assert.deepEqual(calls.at(-1), original);
      selection.current = original; enabled = false;
      await send('你好');
      assert.deepEqual(calls.at(-1), original);
      selection.current = original; enabled = true;
      await send('你好', [{ type: 'text', text: '你好' }, { type: 'image', attachment: { attachmentId: 'sha256:' + 'a'.repeat(64), mimeType: 'image/png', size: 5 } }]);
      assert.deepEqual(calls.at(-1), original, 'attachments remain with the main model');
      selection.current = original;
      await send('你好', undefined, { kind: 'plugin:background' });
      assert.deepEqual(calls.at(-1), original, 'background messages are not simple user tasks');
      selection.current = original; fail = true;
      await send('你好');
      assert.deepEqual(calls.at(-1), target);
      assert.equal(selection.current, original, 'failure restores the main model');
      fail = false; gate = Promise.withResolvers(); started = Promise.withResolvers();
      const cancelled = send('你好');
      await started.promise;
      agent.cancel({ kind: 'user' });
      await cancelled;
      assert.equal(selection.current, original, 'cancellation restores the main model');
      gate = Promise.withResolvers(); started = Promise.withResolvers();
      const changed = send('你好');
      await started.promise;
      const manual = { provider: 'fixture', model: 'manual' };
      ctx.sessionController.agents.selectForNextRequest(agent, manual);
      gate.resolve(); await changed;
      assert.equal(selection.current, manual, 'an explicit user selection wins over temporary routing');
      gate = undefined; selection.current = original;
      const beforeBatch = calls.length;
      agent.inbox.append('next-step', createUserMessage({ content: [{ type: 'text', text: '你好' }], source: { kind: 'user' } }));
      await send('搜索文件');
      assert.equal(calls.length, beforeBatch + 1);
      assert.deepEqual(calls.at(-1), original, 'a greeting cannot route a batch containing a complex request');
      assert.ok(events.filter(entry => entry.type === 'model/selection').every(entry => ['main', 'manual'].includes(entry.data.model)));
      assert.equal(events.filter(entry => entry.type === 'user/message' && entry.data.source?.kind === 'user').length, 10,
        'each typed message is recorded once');
    } finally { voice?.close(); dispose?.(); await ctx.fiber.dispose(); }
  });
}

test('voice host sends incremental frames before one final response and discards late callbacks', async t => {
  const host = new PolicyHost(), frames = [], reply = Promise.withResolvers();
  let publish;
  host.connected = true;
  host.socket = { write(value) { frames.push(JSON.parse(value)); return false; }, destroy() {} };
  t.after(() => host.close());
  host.voice = async (action, data, onEvent) => {
    assert.equal(action, 'prompt'); assert.equal(data.interaction, 'stream');
    publish = onEvent;
    onEvent({ session: 'session', text: '第一段' });
    onEvent({ session: 'session', text: '第二段🙂' });
    return reply.promise;
  };
  host.receive({ voice: 'request-1', action: 'prompt', data: { interaction: 'stream', text: '你好' } });
  await waitFor(() => frames.length === 2, '语音增量必须先于最终结果到达');
  assert.deepEqual(frames, ['第一段', '第二段🙂'].map(text => ({ voice: 'request-1', event: { session: 'session', text } })));
  assert.equal(host.failure, undefined, 'write 返回 false 不能误判为连接失败');
  assert.throws(() => host.receive({ voice: 'request-1', action: 'prompt' }), /重复/);
  const result = { session: 'session', text: '第一段第二段🙂', local: false };
  reply.resolve(result);
  await waitFor(() => host.voiceCalls.size === 0, '最终结果后必须清理请求');
  assert.deepEqual(frames[2], { voice: 'request-1', ok: true, result });
  publish({ session: 'session', text: '已完成后到达' });
  assert.equal(frames.length, 3);
  host.voice = async () => { throw new Error('private provider body'); };
  host.receive({ voice: 'request-error', action: 'prompt' });
  await waitFor(() => frames.length === 4, '缺少失败终帧');
  assert.equal(frames[3].ok, false);
  assert.doesNotMatch(JSON.stringify(frames[3]), /private|provider body/);
  host.voice = async action => { assert.equal(action, 'cancel'); return { cancelled: true }; };
  host.receive({ voice: 'request-cancel', action: 'cancel', data: { interaction: 'stream' } });
  await waitFor(() => frames.length === 5, '缺少取消回执');
  assert.deepEqual(frames[4], { voice: 'request-cancel', ok: true, result: { cancelled: true } });
});

test('voice host cleans up real voice runs on synchronous asynchronous send failure and disconnect', async t => {
  for (const mode of ['throw', 'callback', 'disconnect', 'final-throw', 'final-callback']) {
    await t.test(mode, async () => {
      const f = await voiceStreamFixture(), host = new PolicyHost(), frames = [];
      host.connected = true; host.voice = f.voice.call;
      let writeResult, destroyed = 0;
      host.socket = { write(value, callback) {
        const frame = JSON.parse(value);
        if (mode === 'throw' && frame.event?.text || mode === 'final-throw' && frame.ok) throw new Error('socket write failed');
        writeResult = callback; frames.push(frame); return true;
      }, destroy() { destroyed++; } };
      try {
        host.receive({ voice: 'send-failure', action: 'prompt', data: {
          session: f.session.id, interaction: 'stream', text: '你好', local: true,
        } });
        await f.admitted.promise;
        f.start(); f.chunk({ type: 'text-delta', index: 0, text: '第一段' });
        const final = mode.startsWith('final-');
        if (final) {
          f.end();
          await waitFor(() => host.voiceCalls.size === 0, '发送终帧后必须释放请求');
          if (mode === 'final-callback') writeResult(new Error('final write failed'));
        }
        if (mode === 'callback') writeResult(new Error('async write failed'));
        if (mode === 'disconnect') host.close();
        await waitFor(() => host.failure && (final || f.cancellations() === 1), '发送失败或断连必须清理当前语音轮次');
        assert.equal(f.cancellations(), final ? 0 : 1, '发送已完成结果失败时不能取消后续工作');
        assert.equal(host.connected, false);
        assert.ok(host.failure); assert.equal(host.voiceCalls.size, 0);
        assert.equal(f.selection.current, f.original);
        assert.equal(destroyed, 1);
        const count = frames.length;
        f.chunk({ type: 'text-delta', index: 0, text: '迟到片段' }, 1);
        f.end(); await delay(0);
        assert.equal(frames.length, count, '连接失败后不能发送增量或成功终帧');
      } finally { host.close(); f.voice.close(); }
    });
  }
});

async function voiceStreamFixture() {
  const { voiceSession } = await import(pathToFileURL(join(core, 'voice.mjs')));
  const handlers = new Map(), admitted = Promise.withResolvers();
  const signal = new AbortController(), session = { id: 'stream-session' };
  const original = { provider: 'original', model: 'one' }, selection = { current: original };
  let cancellations = 0;
  const agent = { status: 'idle', session, inbox: { nextTurn: [], nextStep: [], hasPending: false,
    remove(id) { this.nextTurn = this.nextTurn.filter(message => message.id !== id); } },
    cancel() { cancellations++; signal.abort(); } };
  const emit = (name, ...args) => handlers.get(name)?.(...args);
  const voice = voiceSession({
    on(name, handler) { handlers.set(name, handler); return () => handlers.delete(name); },
    sessionController: {
      agents: { selectionFor: () => selection, selectForNextRequest(_agent, value) { selection.current = value; } },
      commands: {
        resolveAgent: async () => agent,
        prompt: async (request, source) => {
          emit('agent/inbox/claimed', { agent, message: { role: 'user', content: request.content, source }, turn: 7 });
          emit('agent/request', { agent, turn: 7, signal: signal.signal }, () => {});
          admitted.resolve(source);
        },
      },
    },
  });
  const frame = (value, who = agent) => emit('agent/assistant-stream', { agent: who, frame: value });
  const start = (id = 'attempt-1', turn = 7) => frame({ type: 'start', attemptId: id, revision: 1, turn, step: 1 });
  const chunk = (value, index = 0, id = 'attempt-1') => frame({ type: 'chunk', attemptId: id, revision: index + 2, index, time: Date.now(), chunk: value });
  const end = (reason = { kind: 'completed' }, text = '最终回答') => {
    emit('session/event', session, { type: 'assistant/message', data: { turn: 7, message: { content: [{ type: 'text', text }] } } });
    emit('session/event', session, { type: 'turn/end', data: { turn: 7, reason } });
  };
  return { voice, handlers, admitted, agent, session, signal, original, selection, emit, frame, start, chunk, end,
    cancellations: () => cancellations,
    prompt: (onEvent, interaction = 'stream') => voice.call('prompt', { session: session.id, interaction, text: '你好', local: true }, onEvent) };
}

test('voice incremental output filters real stream frames by agent turn attempt and text channel', async () => {
  const f = await voiceStreamFixture(), events = [];
  try {
    const result = f.prompt(event => events.push(event));
    await f.admitted.promise;
    const text = { type: 'text-delta', index: 0, text: '你好🙂' };
    f.chunk(text); // 没有本轮 start 的片段不能进入播报。
    f.start('other-turn', 8); f.chunk(text, 0, 'other-turn');
    f.frame({ type: 'start', attemptId: 'other-agent', turn: 7 }, { ...f.agent });
    f.chunk(text, 0, 'other-agent');
    f.start();
    f.chunk({ type: 'reasoning-delta', index: 0, text: '思考' }, 0);
    f.chunk({ type: 'tool-call-delta', index: 1, id: 'tool', argumentsDelta: '{"secret":1}' }, 1);
    f.chunk({ type: 'block-end', index: 1, block: { type: 'text', text: '整块快照' } }, 2);
    f.chunk({ type: 'text-delta', index: 2, text: '' }, 3);
    f.chunk(text, 4); f.chunk(text, 4);
    assert.deepEqual(events, ['', '你好🙂'].map(text => ({ session: f.session.id, text })));
    f.frame({ type: 'end', attemptId: 'attempt-1', index: 5, outcome: { kind: 'committed' } });
    f.chunk(text, 5);
    f.start('attempt-2'); f.chunk(text, 6); f.chunk({ ...text, text: '，继续' }, 0, 'attempt-2');
    f.end({ kind: 'completed' }, '最终回答'.repeat(1500));
    f.chunk(text, 1, 'attempt-2');
    assert.deepEqual(await result, { session: f.session.id, text: '最终回答'.repeat(1500).slice(0, 4000), local: true,
      streamedText: '你好🙂，继续' });
    assert.deepEqual(events.map(event => event.text), ['', '你好🙂', '，继续']);
    assert.equal(f.selection.current, f.original);
  } finally { f.voice.close(); }
  assert.equal(f.handlers.size, 0);
});

test('voice incremental output stops on cancellation failure takeover abort and callback errors', async t => {
  for (const mode of ['cancel', 'failure', 'takeover', 'abort', 'callback', 'async-callback', 'close']) {
    await t.test(mode, async () => {
      const f = await voiceStreamFixture(), events = [];
      const result = f.prompt(event => {
        if (!event.text) return;
        events.push(event);
        if (mode === 'callback') throw new Error('回调失败');
        if (mode === 'async-callback') return Promise.reject(new Error('回调失败'));
      });
      result.catch(() => {});
      try {
        await f.admitted.promise;
        f.start(); f.chunk({ type: 'text-delta', index: 0, text: '首段' });
        if (mode === 'cancel') {
          await f.voice.call('cancel', { interaction: 'old' });
          assert.equal(f.cancellations(), 0);
          await f.voice.call('cancel', { interaction: 'stream' });
        } else if (mode === 'failure') f.end({ kind: 'error' });
        else if (mode === 'takeover') f.emit('agent/inbox/claimed', { agent: f.agent, message: { source: { kind: 'user', rpcId: 'typed' } }, turn: 8 });
        else if (mode === 'abort') f.signal.abort();
        else if (mode === 'close') f.voice.close();
        if (mode === 'async-callback') await Promise.resolve();
        f.chunk({ type: 'text-delta', index: 0, text: '迟到片段' }, 1);
        if (mode === 'abort') f.end({ kind: 'cancelled' });
        await assert.rejects(result);
        assert.deepEqual(events.map(event => event.text), ['首段']);
        assert.equal(f.selection.current, f.original);
        assert.equal(f.cancellations(), ['cancel', 'callback', 'async-callback', 'close'].includes(mode) ? 1 : 0);
      } finally { f.voice.close(); }
    });
  }
});

test('voice incremental output keeps the Unicode speech limit and optional callback compatibility', async () => {
  const f = await voiceStreamFixture(), events = [], text = '🙂'.repeat(4001);
  try {
    const first = f.prompt(event => events.push(event));
    await f.admitted.promise; f.start();
    f.chunk({ type: 'text-delta', index: 0, text });
    f.chunk({ type: 'text-delta', index: 0, text: '超过播报范围' }, 1);
    f.end({ kind: 'completed' }, text);
    const result = await first;
    assert.equal(events.length, 2); assert.equal([...events[1].text].length, 4000);
    assert.equal(result.text, events[1].text); assert.equal(result.streamedText, result.text);
    const second = f.prompt();
    await waitFor(() => f.selection.current !== f.original, '下一轮未进入执行');
    f.start('next-attempt'); f.chunk({ type: 'text-delta', index: 0, text: '未注册回调' }, 0, 'next-attempt');
    f.end();
    assert.deepEqual(await second, { session: f.session.id, text: '最终回答', local: true });
    assert.equal(events.length, 2, '上一轮回调不能接收下一轮文字');
  } finally { f.voice.close(); }
});

test('voice incremental output supplements a missing final suffix and preserves the final step budget', async t => {
  for (const mode of ['snapshot', 'partial', 'tools']) {
    await t.test(mode, async () => {
      const f = await voiceStreamFixture(), events = [], answer = '最终回答🙂';
      const result = f.prompt(event => events.push(event));
      try {
        await f.admitted.promise; f.start();
        if (mode === 'partial') f.chunk({ type: 'text-delta', index: 0, text: '最终' });
        if (mode === 'tools') {
          f.chunk({ type: 'text-delta', index: 0, text: '前'.repeat(5000) });
          f.frame({ type: 'end', attemptId: 'attempt-1', index: 1, outcome: { kind: 'committed' } });
          f.start('final-step');
          f.chunk({ type: 'text-delta', index: 0, text: answer }, 0, 'final-step');
        }
        f.end({ kind: 'completed' }, answer);
        const final = await result;
        assert.equal(final.text, answer);
        assert.equal(final.streamedText, events.map(event => event.text).join(''));
        assert.equal(final.streamedText, (mode === 'tools' ? '前'.repeat(4000) : '') + answer);
        assert.ok(events.every(event => [...event.text].length <= 4000));
        assert.equal(events.filter(event => event.text === answer).length, mode === 'partial' ? 0 : 1,
          '最终回答已发出时不能整篇重播');
      } finally { f.voice.close(); }
    });
  }
});

test('voice incremental output bounds a whole tool turn at 16000 and reserves the final 4000', async () => {
  const f = await voiceStreamFixture(), events = [], answer = '🙂'.repeat(4000);
  const result = f.prompt(event => events.push(event));
  try {
    await f.admitted.promise;
    for (let index = 0; index < 6; index++) {
      const id = `tool-${index}`;
      f.start(id); f.chunk({ type: 'text-delta', index: 0, text: '前'.repeat(5000) }, 0, id);
      f.frame({ type: 'end', attemptId: id, index: 1, outcome: { kind: 'committed' } });
    }
    assert.equal([...events.map(event => event.text).join('')].length, 12000);
    f.start('final'); f.chunk({ type: 'text-delta', index: 0, text: answer }, 0, 'final');
    assert.equal(events.length, 4, '达到实时限额时等待最终确认后再发送预留文字');
    f.end({ kind: 'completed' }, answer);
    const final = await result;
    assert.equal([...final.streamedText].length, 16000);
    assert.equal(final.streamedText, events.map(event => event.text).join(''));
    assert.equal(final.streamedText, '前'.repeat(12000) + answer);
    assert.equal(final.text, answer);
    assert.ok(events.every(event => [...event.text].length <= 4000));
  } finally { f.voice.close(); }
});

test('voice ready event preserves a newly created session before model admission and cancellation', async () => {
  const { voiceSession } = await import(pathToFileURL(join(core, 'voice.mjs')));
  const created = Promise.withResolvers(), resolved = Promise.withResolvers(), events = [];
  let prompts = 0;
  const voice = voiceSession({
    on() { return () => {}; },
    sessionController: {
      create: () => created.promise,
      commands: { resolveAgent: () => resolved.promise, prompt: () => { prompts++; } },
    },
  });
  const result = voice.call('prompt', { interaction: 'new-session', text: '你好' }, event => events.push(event));
  result.catch(() => {});
  try {
    assert.deepEqual(events, []);
    created.resolve({ sessionId: 'new-session:1.a_b' });
    await waitFor(() => events.length === 1, '模型准备前必须发布会话编号');
    assert.deepEqual(events, [{ session: 'new-session:1.a_b', text: '' }]);
    let acknowledged = false;
    const cancellation = voice.call('cancel', { interaction: 'new-session' }).then(value => { acknowledged = true; return value; });
    await Promise.resolve();
    assert.equal(acknowledged, false, '取消回执必须等待正在进行的准备工作清理');
    resolved.resolve({ inbox: { nextTurn: [], nextStep: [] } });
    await cancellation; await assert.rejects(result, /取消/);
    assert.equal(prompts, 0);
    assert.equal(events.length, 1);
  } finally { created.resolve({ sessionId: 'unused' }); resolved.resolve({}); voice.close(); }
});

test('voice cancellation admits an interrupting prompt received before cancel acknowledgement after the old agent is idle', async () => {
  const f = await voiceStreamFixture(), idle = Promise.withResolvers();
  f.agent.whenIdle = () => idle.promise;
  const result = f.prompt(); result.catch(() => {});
  try {
    await f.admitted.promise;
    await assert.rejects(f.prompt(undefined, 'busy'), /上一轮语音对话尚未结束/, '正常执行中的轮次仍拒绝并发输入');
    let acknowledged = false;
    const cancellation = f.voice.call('cancel', { interaction: 'stream' }).then(value => { acknowledged = true; return value; });
    const next = f.prompt(undefined, 'next');
    await Promise.resolve();
    assert.equal(f.cancellations(), 1); assert.equal(acknowledged, false);
    assert.equal(f.selection.current, f.original, '旧运行器未空闲时新 prompt 必须等待');
    idle.resolve();
    assert.deepEqual(await cancellation, { cancelled: true });
    await assert.rejects(result, /取消/);
    await waitFor(() => f.selection.current !== f.original, '取消回执之后新轮次应能进入执行');
    f.end();
    assert.equal((await next).text, '最终回答');
  } finally { idle.resolve(); f.voice.close(); }
});

test('voice cancellation stops a waiting interruption before it can enter the old agent', async () => {
  const f = await voiceStreamFixture(), idle = Promise.withResolvers();
  f.agent.whenIdle = () => idle.promise;
  const first = f.prompt(); first.catch(() => {});
  try {
    await f.admitted.promise;
    const stopped = f.voice.call('cancel', { interaction: 'stream' });
    const skipped = f.prompt(undefined, 'skipped'); skipped.catch(() => {});
    await f.voice.call('cancel', { interaction: 'skipped' });
    await assert.rejects(skipped, /取消/);
    const latest = f.prompt(undefined, 'latest');
    idle.resolve(); await stopped; await assert.rejects(first, /取消/);
    await waitFor(() => f.selection.current !== f.original, '最后一个问题必须在旧轮次收尾后进入执行');
    f.end(); assert.equal((await latest).text, '最终回答');
    assert.equal(f.cancellations(), 1, '取消等待中的问题不能重复取消运行器');
  } finally { idle.resolve(); f.voice.close(); }
});

test('voice cancellation removes only its pending message and cannot cancel another running task', async () => {
  const { voiceSession } = await import(pathToFileURL(join(core, 'voice.mjs')));
  const handlers = new Map();
  const admission = Promise.withResolvers();
  const admitted = Promise.withResolvers();
  const pending = [{ id: 'typed', source: { kind: 'user', rpcId: 'typed' } }];
  let cancellations = 0;
  const agent = { status: 'idle', inbox: { nextTurn: [], nextStep: [], hasPending: false, remove(id) {
    this.nextTurn = this.nextTurn.filter(message => message.id !== id);
  } }, cancel() { cancellations++; } };
  const voice = voiceSession({
    on(name, handler) { handlers.set(name, handler); return () => handlers.delete(name); },
    sessionController: { commands: {
      resolveAgent: async () => agent,
      prompt: async (_request, source) => {
        admitted.resolve(); await admission.promise;
        agent.inbox.nextTurn.push({ id: 'voice', source });
      },
    } },
  });
  try {
    const call = voice.call('prompt', { session: 'session', interaction: 'cancel-own', text: '你好' });
    call.catch(() => {});
    await admitted.promise;
    agent.status = 'running'; agent.inbox.nextTurn = pending.slice();
    await voice.call('cancel', { interaction: 'old-round' });
    assert.equal(cancellations, 0);
    const cancelled = voice.call('cancel', { interaction: 'cancel-own' });
    admission.resolve();
    await cancelled;
    await assert.rejects(call, /取消/);
    assert.equal(cancellations, 0, 'the admitted typed task belongs to someone else');
    assert.deepEqual(agent.inbox.nextTurn.map(message => message.id), ['typed']);
  } finally { admission.resolve(); voice.close(); }
});

for (const target of [{ provider: 'dshx-local', model: 'qwen3-0.6b' }, { provider: 'dshx-local', model: 'custom' }, { provider: 'fixture', model: 'small-online' }]) {
test(`voice temporary model selection ${target.model} respects user switches and rejects unsuccessful turns`, async () => {
  const { voiceSession } = await import(pathToFileURL(join(core, 'voice.mjs')));
  const handlers = new Map();
  const selection = { current: { provider: 'original', model: 'one' } };
  const agent = { status: 'idle', inbox: { nextTurn: [], nextStep: [], hasPending: false }, cancel() {} };
  const chosen = { provider: 'user-picked', model: 'two' };
  let failure = { kind: 'error' };
  let cancel = false;
  const session = { id: 'switch-session' };
  const voice = voiceSession({
    on(name, handler) { handlers.set(name, handler); return () => handlers.delete(name); },
    sessionController: {
      agents: {
        selectionFor: () => selection,
        selectForNextRequest(_agent, selected) {
          selection.current = selected;
          handlers.get('session/event')(session, { type: 'model/selection', data: selected });
        },
      },
      commands: {
        resolveAgent: async () => agent,
        prompt: async (request, source) => {
          handlers.get('agent/inbox/claimed')({ agent, message: { role: 'user', content: request.content, source }, turn: 1 });
          assert.deepEqual(selection.current, target);
          const fallback = signal => handlers.get('agent/request-error')({ agent, provider: 'dshx-local',
            failure: { code: 'LOCAL_CONTEXT' }, signal }, () => 'unhandled');
          if (cancel) {
            void voice.call('cancel', { interaction: 'cancel-context' });
            assert.equal(fallback(new AbortController().signal), 'unhandled', 'cancelled turns cannot retry');
            return;
          }
          const aborted = new AbortController(); aborted.abort();
          assert.equal(fallback(aborted.signal), 'unhandled', 'aborted requests cannot retry');
          selection.current = chosen;
          handlers.get('session/event')(session, { type: 'model/selection', data: chosen });
          assert.equal(fallback(new AbortController().signal), 'unhandled', 'a user model switch prevents fallback');
          handlers.get('session/event')(session, { type: 'assistant/message', data: { message: { content: [{ type: 'text', text: 'partial' }] } } });
          handlers.get('session/event')(session, { type: 'turn/end', data: { turn: 1, reason: failure } });
        },
      },
    },
  });
  try {
    await assert.rejects(voice.call('prompt', { session: session.id, interaction: 'switch', text: '你好', local: true, smallModel: target }), /失败/);
    assert.equal(selection.current, chosen, 'an explicit user change wins over restoring the previous choice');
    failure = { kind: 'error', error: { message: '当前可用内存不足，本地模型暂不加载。请关闭其他占用内存的应用后重试。' } };
    await assert.rejects(voice.call('prompt', { session: session.id, interaction: 'memory', text: '你好', local: true, smallModel: target }), { code: 'VOICE_MEMORY' });
    cancel = true;
    await assert.rejects(voice.call('prompt', { session: session.id, interaction: 'cancel-context', text: '你好', local: true, smallModel: target }), /取消/);
    assert.equal(selection.current, chosen);
  } finally { voice.close(); }
});
}

test('voice local adapter bounds Unicode history and records cancellation without prompt content', async () => {
  const { LlmAdapter } = await load('@deepseek-ai/dsh-llm');
  const { localAdapter } = await import(pathToFileURL(join(core, 'voice.mjs')));
  const dir = await mkdtemp(join(directory, 'voice-projection-'));
  const previous = process.env.DSHX_ASSISTANT_DIR;
  process.env.DSHX_ASSISTANT_DIR = dir;
  const abort = new AbortController();
  const messages = Array.from({ length: 10 }, (_, index) => [
    { role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: `问题${index}` }] },
    { role: 'assistant', source: { kind: 'model' }, content: [{ type: 'text', text: '🙂'.repeat(600) }] },
  ]).flat();
  messages.push({ role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: '你好' }] });
  messages.unshift({ role: 'system', content: [{ type: 'text', text: 'private system' }] });
  const adapter = localAdapter(LlmAdapter, { query: async (_method, data) => {
    assert.equal(data.messages.length, 5);
    assert.deepEqual(data.messages.map(message => message.role), ['user', 'assistant', 'user', 'assistant', 'user']);
    assert.ok(data.messages.filter(message => message.role === 'assistant').every(message => message.text === '🙂'.repeat(600)));
    assert.ok(data.messages.reduce((sum, message) => sum + [...message.text].length, 0) <= 1800);
    assert.ok(Buffer.byteLength(JSON.stringify(data)) < 32768, 'the projection must fit the authenticated native line limit');
    abort.abort(); throw new Error('private generation detail');
  } });
  try {
    await assert.rejects(async () => { for await (const _ of adapter.stream({ sessionId: 'bounded', messages, signal: abort.signal })) {} }, /private generation detail/);
    const text = await readFile(join(dir, 'local-usage.jsonl'), 'utf8');
    assert.doesNotMatch(text, /private|🙂/);
    const rows = text.trim().split('\n').map(JSON.parse);
    assert.equal(rows.at(-1).outcome, 'cancelled');
    assert.equal(rows.at(-1).usage, null);
    await assert.rejects(stat(join(dir, 'usage.jsonl')), { code: 'ENOENT' });
  } finally {
    if (previous === undefined) delete process.env.DSHX_ASSISTANT_DIR; else process.env.DSHX_ASSISTANT_DIR = previous;
  }
});

// Local memory tests use real DSH JSON storage in one private directory per fixture.
async function localMemoryFixture(t, { open = true } = {}) {
  const { Context } = await load('@deepseek-ai/cordis');
  const storage = await import(pathToFileURL(join(core, 'memory-store.mjs')));
  const memory = await import(pathToFileURL(join(core, 'memory.mjs')));
  const ctx = new Context();
  const dir = await mkdtemp(join(directory, 'local-memory-'));
  const f = { ctx, dir, ...storage, ...memory, store: undefined, memory: undefined, extracts: 0, installs: 0 };
  t.after(async () => {
    try { if (f.memory) await f.memory.close(); else await f.store?.close(); }
    finally { await ctx.fiber.dispose(); }
  });
  await ctx.plugin((await load('@deepseek-ai/dsh-storage')).default);
  await ctx.plugin(await load('@deepseek-ai/dsh-storage-json'), { root: dir });
  await ctx.plugin(await load('@deepseek-ai/dsh-storage-domain'), { backend: 'json' });
  await ctx.plugin((await load('@deepseek-ai/dsh-session')).default);
  await ctx.plugin((await load('@deepseek-ai/dsh-system-prompt')).default);
  await ctx.plugin((await load('@deepseek-ai/dsh-session-projection')).default);
  await ctx.plugin((await load('@deepseek-ai/dsh-fs')).default);
  await ctx.plugin((await load('@deepseek-ai/dsh-working-directory')).default, { defaultDirectory: workspace });
  ctx.llm = { listProviders: () => [{ id: 'fixture', name: 'fixture' }] };
  ctx.pluginManager = {
    async installBundle() { f.installs++; throw new Error('local memory must never install a package'); },
  };
  f.start = async (options = {}) => {
    f.memory = memory.memorySession(ctx, { store: f.store, extract: async () => { f.extracts++; return []; }, ...options });
    await f.memory.ready;
    return f.memory;
  };
  f.data = (key, extra = {}) => ({ type: 'fact', scope: { kind: 'user' }, key, summary: key, content: key, ...extra });
  f.settings = async (enabled, autoSave = false) => f.memory.call('settings', { enabled, autoSave, revision: f.store.read().revision });
  f.session = (id, provider = 'fixture') => ({ session: ctx.sessions.create(id, { meta: { cwd: workspace } }), options: { provider, model: 'test' } });
  f.before = async (agent, text = '咖啡') => {
    const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
    const message = createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text }] });
    const decision = { kind: 'enter', messages: [message] };
    const result = await f.memory.before({ agent, messages: agent.session.deriveMessages(), signal: new AbortController().signal }, async () => decision);
    return { result, decision, message };
  };
  f.commit = (agent, result) => {
    for (const message of result.messages) agent.session.append('user/message', message, { surfaceOp: 'append' });
  };
  if (open) f.store = await storage.openMemory(ctx);
  return f;
}

test('Android memory local storage layout uses the actual profile paths for sessions attachments and both domains', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { entryListSchema, applyEntryPatches } = await load('@deepseek-ai/cordis-plugin-include');
  const { interpolate } = await load('@deepseek-ai/cordis-plugin-loader');
  const { dshHomePath } = await load('@deepseek-ai/dsh-home-paths');
  const yaml = require('js-yaml');
  const home = await mkdtemp(join(directory, 'storage-layout-'));
  const previous = process.env.DSH_HOME;
  process.env.DSH_HOME = home;
  const ctx = new Context();
  let memory, knowledge;
  try {
    const base = yaml.load(await readFile(require.resolve('@deepseek-ai/dsh-base/cordis.patch.yml'), 'utf8'), { schema: entryListSchema });
    const patches = yaml.load(await readFile(new URL('./android.patch.yml', import.meta.url), 'utf8'), { schema: entryListSchema });
    const ids = ['session-persistence-jsonl', 'attachment-local', 'storage-json'];
    const rows = applyEntryPatches(base.flatMap(row => row.insert ?? []), patches.filter(row => ids.includes(row.id)), assert.fail);
    const config = id => interpolate({ dshHomePath }, rows.find(row => row.id === id).config ?? {});
    await ctx.plugin((await load('@deepseek-ai/dsh-storage')).default);
    await ctx.plugin(await load('@deepseek-ai/dsh-storage-json'), config('storage-json'));
    await ctx.plugin(await load('@deepseek-ai/dsh-storage-domain'), { backend: 'json' });
    await ctx.plugin((await load('@deepseek-ai/dsh-session')).default);
    await ctx.plugin(await load('@deepseek-ai/dsh-session-persistence-jsonl'), config('session-persistence-jsonl'));
    await ctx.plugin((await load('@deepseek-ai/dsh-attachment-local')).default, config('attachment-local'));
    const log = ctx.sessionPersistence.locate({ id: 'layout-session' }).path;
    assert.ok(log.startsWith(join(home, 'storages', 'sessions') + '/'), log);
    const attachment = await ctx.attachments.saveFile({ data: Buffer.from('附件保持可读'), name: '资料.txt' });
    const file = ctx.attachments.fileHostPath(attachment);
    assert.ok(file.startsWith(join(home, 'storages', 'attachments', 'v1') + '/'), file);
    assert.equal(await readFile(file, 'utf8'), '附件保持可读');
    memory = await (await import(pathToFileURL(join(core, 'memory-store.mjs')))).openMemory(ctx);
    await memory.save({ type: 'fact', scope: { kind: 'user' }, key: 'language', summary: '中文', content: '使用中文' });
    knowledge = await (await import(pathToFileURL(join(core, 'knowledge-store.mjs')))).openKnowledge(ctx);
    await knowledge.domain.global.set(knowledge.read());
    assert.equal(knowledge.root, join(home, 'storages', 'knowledge'));
    for (const name of ['memory', 'knowledge']) {
      assert.equal(JSON.parse(await readFile(join(home, 'storages', name, 'state.json'), 'utf8')).unit.name, name);
      await assert.rejects(stat(join(home, 'storages', name + '.json')), { code: 'ENOENT' });
    }
  } finally {
    await knowledge?.close(); await memory?.close(); await ctx.fiber.dispose();
    if (previous === undefined) delete process.env.DSH_HOME; else process.env.DSH_HOME = previous;
  }
});

test('Android memory local JSON commits concurrent changes with revisions and survives reopen', async t => {
  const f = await localMemoryFixture(t);
  assert.deepEqual(f.store.read(), f.initialMemory());
  const results = await Promise.allSettled([
    f.store.save(f.data('first', { revision: 0 })),
    f.store.save(f.data('stale', { revision: 0 })),
  ]);
  assert.deepEqual(results.map(row => row.status), ['fulfilled', 'rejected']);
  assert.equal(results[1].reason.code, 'MEMORY_CONFLICT');
  await Promise.all(Array.from({ length: 5 }, (_, index) => f.store.save(f.data(`queued-${index}`))));
  const snapshot = f.store.read();
  assert.equal(snapshot.revision, 6);
  assert.deepEqual(snapshot.entries.map(row => row.key).sort(), ['first', ...Array.from({ length: 5 }, (_, i) => `queued-${i}`)].sort());
  snapshot.entries[0].content = 'borrowed mutation';
  assert.equal(f.store.read().entries[0].content, 'first');
  const saved = f.store.read();
  const disk = JSON.parse(await readFile(join(f.dir, 'memory', 'state.json'), 'utf8'));
  assert.deepEqual(disk.unit, { name: 'memory', version: 1 });
  assert.deepEqual(disk.global, saved);
  await f.store.close();
  await assert.rejects(f.store.save(f.data('closed')), { code: 'MEMORY_STOPPED' });
  f.store = await f.openMemory(f.ctx);
  assert.deepEqual(f.store.read(), saved);
  await f.store.save(f.data(' ＦＩＲＳＴ ', { summary: '更正后的事实', content: '新内容', revision: saved.revision }));
  const updated = f.store.read();
  assert.equal(updated.entries.length, saved.entries.length);
  assert.equal(updated.entries.find(row => row.key === 'first').id, saved.entries[0].id);
  assert.equal(updated.entries.find(row => row.key === 'first').locked, true);
});

test('Android memory local rejects malformed and unsupported JSON without replacing the file', async t => {
  for (const corrupt of ['{broken', JSON.stringify({ unit: { name: 'memory', version: 1 }, global: { version: 99 }, tables: {} })]) {
    const f = await localMemoryFixture(t, { open: false });
    const path = join(f.dir, 'memory', 'state.json');
    await mkdir(join(f.dir, 'memory'), { recursive: true });
    await writeFile(path, corrupt);
    await assert.rejects(f.openMemory(f.ctx));
    assert.equal(await readFile(path, 'utf8'), corrupt);
    await f.start();
    const status = await f.memory.call('status');
    assert.equal(status.ready, false);
    assert.equal(status.settings, null);
    await assert.rejects(f.memory.call('save', f.data('must-not-replace', { revision: 0 })), { code: 'MEMORY_READ' });
    assert.equal(await readFile(path, 'utf8'), corrupt);
  }
});

test('Android memory local failed durable publication leaves the last snapshot and queue usable', async () => {
  const { MemoryStore, initialMemory } = await import(pathToFileURL(join(core, 'memory-store.mjs')));
  let current = initialMemory(), fail = true, writes = 0;
  const store = new MemoryStore({ global: {
    get: () => current,
    async set(next) { writes++; if (fail) throw new Error('private disk detail'); current = structuredClone(next); },
  }, async close() {} });
  try {
    await assert.rejects(store.change(next => { next.settings.enabled = true; }), { code: 'MEMORY_WRITE' });
    assert.deepEqual(store.read(), initialMemory());
    assert.equal(current.revision, 0);
    await assert.rejects(store.change(next => { next.settings.enabled = 'invalid'; }), { code: 'MEMORY_READ' });
    assert.equal(writes, 1, 'invalid snapshots must not reach the backend');
    fail = false;
    await store.change(next => { next.settings.enabled = true; }, 0);
    assert.equal(store.read().revision, 1);
    assert.equal(store.read().settings.enabled, true);
  } finally { await store.close(); }
});

test('Android memory local Chinese search isolates workspace state expiry and unrelated text', async t => {
  const f = await localMemoryFixture(t);
  const rows = [
    f.data('drink', { summary: '用户偏好无糖咖啡', content: '饮品选择无糖咖啡', type: 'preference' }),
    f.data('workspace-a', { summary: '甲项目提供咖啡', scope: { kind: 'workspace', workspace } }),
    f.data('workspace-b', { summary: '乙项目提供咖啡', scope: { kind: 'workspace', workspace: join(workspace, 'other') } }),
    f.data('pending', { summary: '未确认咖啡偏好' }),
    f.data('expired', { summary: '过期咖啡偏好', expiresAt: Date.now() - 1 }),
    f.data('unrelated', { summary: '乘坐地铁通勤', content: '每天步行到地铁站' }),
    f.data('english', { summary: 'Use Go for backend', content: 'Go is preferred' }),
  ];
  for (const data of rows) await f.store.save(data);
  await f.store.change(next => { next.entries.find(row => row.key === 'pending').state = 'pending'; });
  const snapshot = f.store.read();
  assert.deepEqual(f.searchMemory(snapshot, '咖啡', workspace).map(row => row.key).sort(), ['drink', 'workspace-a']);
  assert.deepEqual(f.searchMemory(snapshot, '咖啡', join(workspace, 'other')).map(row => row.key).sort(), ['drink', 'workspace-b']);
  assert.deepEqual(f.searchMemory(snapshot, '火星探测器', workspace), []);
  assert.deepEqual(f.searchMemory(snapshot, 'goal', workspace), [], 'English tokens must not match Go as a prefix');
  assert.deepEqual(f.searchMemory(snapshot, 'ＧＯ', workspace).map(row => row.key), ['english']);
  assert.equal(f.searchMemory(snapshot, '咖啡', workspace, 1).length, 1);
  assert.deepEqual(f.searchMemory(snapshot, '火星探测器', workspace, 10, true).map(row => row.key), ['drink']);
  await f.start(); await f.settings(true);
  for (const key of ['workspace-b', 'pending', 'expired']) {
    assert.throws(() => f.memory.read(snapshot.entries.find(row => row.key === key).id, workspace), { code: 'MEMORY_MISSING' });
  }
  const visible = f.memory.read(snapshot.entries.find(row => row.key === 'drink').id, workspace);
  visible.content = 'borrowed mutation';
  assert.notEqual(f.memory.read(visible.id, workspace).content, visible.content);
});

test('Android memory local defaults make no model or installation calls and disabled settings retain management', async t => {
  const f = await localMemoryFixture(t);
  f.ctx.llm = { listProviders: () => [{ id: 'fixture', name: 'fixture' }],
    async resolveModel() { assert.fail('default memory cannot resolve a model'); }, stream() { assert.fail('default memory cannot send model requests'); } };
  await f.start();
  let status = await f.memory.call('status');
  assert.deepEqual(status.settings, { enabled: false, autoSave: false });
  assert.equal(status.ready, true); assert.equal(status.active, false);
  assert.equal(status.revision, 0);
  assert.equal(status.autoReady, false);
  await assert.rejects(stat(join(f.dir, 'memory', 'state.json')), { code: 'ENOENT' });
  assert.equal(f.extracts, 0); assert.equal(f.installs, 0);
  assert.throws(() => f.memory.search('咖啡', workspace), { code: 'MEMORY_NOT_READY' });
  const saved = await f.memory.call('save', f.data('drink', { summary: '喜欢咖啡', revision: status.revision }));
  const listed = await f.memory.call('list');
  const id = listed.entries[0].id;
  assert.equal(saved.active, false); assert.equal(listed.entries[0].locked, true);
  assert.equal((await f.memory.call('read', { id })).entry.summary, '喜欢咖啡');
  await assert.rejects(f.memory.call('save', f.data('stale', { revision: status.revision })), { code: 'MEMORY_CONFLICT' });
  await assert.rejects(f.memory.call('settings', { enabled: true, autoSave: false }), { code: 'MEMORY_INPUT' });
  await f.settings(true);
  assert.equal(f.memory.search('咖啡', workspace)[0].id, id);
  assert.equal(f.memory.read(id, workspace).id, id);
  assert.throws(() => f.memory.search('', workspace), { code: 'MEMORY_INPUT' });
  const changed = await f.memory.call('save', f.data('drink', { id, content: '新正文', revision: f.store.read().revision }));
  assert.equal(changed.active, true);
  await f.settings(false);
  assert.throws(() => f.memory.read(id, workspace), { code: 'MEMORY_NOT_READY' });
  assert.equal((await f.memory.call('read', { id })).entry.content, '新正文');
  await f.memory.call('remove', { id, revision: f.store.read().revision });
  assert.equal((await f.memory.call('list')).entries.length, 0);
  assert.equal(f.store.read().blocked[0].key, 'drink');
  await f.memory.call('save', f.data('again', { revision: f.store.read().revision }));
  await f.memory.call('clear', { revision: f.store.read().revision });
  assert.deepEqual((await f.memory.call('list')).entries, []);
  assert.equal(f.extracts, 0); assert.equal(f.installs, 0);
});

test('Android memory local honors aborted management without changing the saved snapshot', async t => {
  const f = await localMemoryFixture(t);
  await f.start();
  const aborted = new AbortController(); aborted.abort();
  const snapshot = f.store.read();
  await assert.rejects(f.memory.call('save', f.data('aborted', { revision: snapshot.revision }), aborted.signal), { name: 'AbortError' });
  assert.deepEqual(f.store.read(), snapshot);
  const decision = { kind: 'stop' };
  assert.equal(await f.memory.before({ signal: new AbortController().signal }, async () => decision), decision);
});

test('Android memory local injection keeps source metadata whole summaries and local or cloud budgets', async t => {
  const f = await localMemoryFixture(t);
  await f.start(); await f.settings(true);
  for (let i = 0; i < 8; i++) await f.store.save(f.data(`coffee-${i}`, { summary: `咖啡偏好${i}🙂🙂🙂🙂🙂` }));
  const long = '咖啡' + '长'.repeat(158);
  await f.store.save(f.data('long', { summary: long }));
  for (const [provider, budget] of [['fixture', 256], ['dshx-local', 256]]) {
    const agent = f.session(`budget-${provider}`, provider);
    agent.session.append('request/header', { header: { config: { provider: 'previous-cloud', model: 'old-route' } }, reason: 'initial' });
    const { result, decision, message } = await f.before(agent);
    assert.equal(result.messages.length, 2);
    const injected = result.messages[0];
    assert.equal(result.messages[1], message);
    assert.equal(decision.messages.length, 1, 'the borrowed decision stays unchanged');
    assert.equal(injected.role, 'user');
    assert.equal(injected.source.kind, 'plugin:dshx-memory');
    assert.equal(injected.source.form, 'recall');
    assert.ok(injected.source.entries.length > 0 && injected.source.entries.length <= 6);
    assert.equal(injected.source.revision, f.store.read().revision);
    assert.ok(Object.isFrozen(injected));
    const text = injected.content[0].text;
    assert.ok(Buffer.byteLength(text) + 64 <= budget);
    for (const source of injected.source.entries) {
      const row = f.store.read().entries.find(row => row.id === source.id);
      assert.equal(source.updatedAt, row.updatedAt);
      assert.equal(source.revision, f.store.read().revision);
      assert.deepEqual(source.scope, row.scope);
      assert.ok(text.includes(row.summary), 'each selected summary must stay complete');
    }
    if (provider === 'dshx-local') assert.equal(text.includes(long), false, 'omit an oversized summary as a whole');
    const unaccepted = await f.before(agent);
    assert.equal(unaccepted.result.messages.length, 2, 'unaccepted recalls must not suppress the next admission');
    f.commit(agent, result);
    const accepted = new Set(injected.source.entries.map(row => row.id));
    let exhausted = false;
    for (let i = 0; i < 9; i++) {
      const duplicate = await f.before(agent);
      if (duplicate.result === duplicate.decision) { exhausted = true; break; }
      for (const entry of duplicate.result.messages[0].source.entries) {
        assert.equal(accepted.has(entry.id), false, 'an accepted version must not be injected twice');
        accepted.add(entry.id);
      }
      f.commit(agent, duplicate.result);
    }
    assert.equal(exhausted, true);
  }
  assert.equal(f.extracts, 0);
});

test('Android memory local injection restores logged recall identity and sends update deletion expiry and clear notices', async t => {
  const f = await localMemoryFixture(t);
  await f.start(); await f.settings(true);
  await f.store.save(f.data('drink', { summary: '喜欢咖啡' }));
  const agent = f.session('invalidation');
  const first = await f.before(agent);
  f.commit(agent, first.result);
  const original = first.result.messages[0];
  await f.memory.close();
  f.store = await f.openMemory(f.ctx);
  await f.start();
  const resumed = await f.before(agent);
  assert.equal(resumed.result, resumed.decision, 'logged recalls must not be injected again after reopening');
  const row = f.store.read().entries[0];
  await f.store.save(f.data('drink', { id: row.id, summary: '喜欢无糖咖啡', revision: f.store.read().revision }));
  const update = await f.before(agent);
  assert.equal(update.result.messages[0].source.reset, true);
  assert.match(update.result.messages[0].content[0].text, /背景已失效/);
  assert.match(update.result.messages[0].content[0].text, /喜欢无糖咖啡/);
  assert.equal(original.content[0].text.includes('喜欢无糖咖啡'), false);
  f.commit(agent, update.result);
  await f.memory.call('remove', { id: row.id, revision: f.store.read().revision });
  const removed = await f.before(agent);
  assert.equal(removed.result.messages[0].source.kind, 'plugin:dshx-memory');
  assert.deepEqual(removed.result.messages[0].source.entries, []);
  assert.equal(removed.result.messages[0].source.reset, true);
  assert.match(removed.result.messages[0].content[0].text, /背景已失效/);
  f.commit(agent, removed.result);
  assert.equal((await f.before(agent)).result.messages.length, 1);
  await f.store.save(f.data('expires', { summary: '咖啡即将过期' }));
  const expiring = await f.before(agent); f.commit(agent, expiring.result);
  await f.store.change(next => { next.entries[0].expiresAt = Date.now() - 1; });
  const expired = await f.before(agent);
  assert.equal(expired.result.messages[0].source.reset, true);
  assert.match(expired.result.messages[0].content[0].text, /背景已失效/);
  f.commit(agent, expired.result);
  await f.store.save(f.data('clear', { summary: '咖啡清空测试' }));
  const clearing = await f.before(agent); f.commit(agent, clearing.result);
  await f.memory.call('clear', { revision: f.store.read().revision });
  const cleared = await f.before(agent);
  assert.equal(cleared.result.messages[0].source.reset, true);
  assert.match(cleared.result.messages[0].content[0].text, /背景已失效/);
});

test('Android memory local model preserves recall notices and rejects incomplete input before native inference', async () => {
  const { localInput, localAdapter } = await import(pathToFileURL(join(core, 'voice.mjs')));
  const { createUserMessage, LlmAdapter } = await load('@deepseek-ai/dsh-llm');
  const make = (text, source) => createUserMessage({ content: [{ type: 'text', text }], source });
  const task = make('你好', { kind: 'user' });
  const recall = make('偏好简短中文', { kind: 'plugin:dshx-memory', form: 'recall', entries: [] });
  const notice = make('旧偏好已删除，停止使用。', { kind: 'plugin:dshx-memory', form: 'recall', entries: [], notice: true });
  const messages = [recall, task, notice], before = JSON.stringify(messages);
  assert.deepEqual(localInput(messages), [
    { role: 'context', kind: 'memory', text: '偏好简短中文' },
    { role: 'context', kind: 'memory', text: '旧偏好已删除，停止使用。' },
    { role: 'user', text: '你好' },
  ]);
  assert.equal(JSON.stringify(messages), before);
  assert.deepEqual(localInput([make('unrelated', { kind: 'plugin:other' }), task]), [{ role: 'user', text: '你好' }]);
  let nativeCalls = 0;
  const adapter = localAdapter(LlmAdapter, { async query(_method, data) {
    nativeCalls++; assert.deepEqual(data.messages, localInput(messages));
    return { text: '你好', inputTokens: 20, outputTokens: 2 };
  } });
  assert.equal((await Array.fromAsync(adapter.stream({ messages }))).at(-1).reason.kind, 'stop');
  for (const input of [
    [make('🙂'.repeat(1799), recall.source), task],
    [...Array.from({ length: 9 }, () => recall), task],
    [{ ...recall, content: [...recall.content, { type: 'image' }] }, task],
  ]) {
    assert.equal(localInput(input), null);
    const events = await Array.fromAsync(adapter.stream({ messages: input }));
    assert.equal(events.at(-1).reason.failure.code, 'LOCAL_MEMORY_CONTEXT');
    assert.match(events.at(-1).reason.failure.message, /无法完整.*不会自动转用云端/);
  }
  assert.equal(localInput([make('🙂'.repeat(1798), recall.source), task])[0].text, '🙂'.repeat(1798));
  assert.equal(nativeCalls, 1);
});

test('Android memory local real agent loop never retries oversized recall or notice on a cloud model', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools } = await load('@deepseek-ai/dsh-tools');
  const { default: LlmRuntime, LlmAdapter, createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const { default: Agents, installModelSelection } = await load('@deepseek-ai/dsh-agent');
  const { default: Sessions } = await load('@deepseek-ai/dsh-session');
  const { default: Projections } = await load('@deepseek-ai/dsh-session-projection');
  const { default: AgentLoop } = await load('@deepseek-ai/dsh-agent-loop');
  const { installModelSelectionProjection } = await import(new URL('./types/model-selection-projection.js', pathToFileURL(require.resolve('@deepseek-ai/dsh-api-session-controller'))));
  const { voiceSession, localAdapter } = await import(pathToFileURL(join(core, 'voice.mjs')));
  for (const mode of ['early-recall', 'late-recall', 'notice']) {
    const ctx = new Context();
    const original = { provider: 'fixture', model: 'remote' }, selection = { current: original };
    let remote = 0, local = 0, voice, disposeSelection;
    try {
      for (const plugin of [SystemPrompt, Tools, LlmRuntime, Agents, Sessions, Projections]) await ctx.plugin(plugin);
      installModelSelectionProjection(ctx);
      await ctx.plugin(AgentLoop, { agents: [] });
      class Remote extends LlmAdapter { async *stream() { remote++; yield { type: 'finish', reason: { kind: 'stop' } }; } }
      ctx.llm.registerAdapter(['fixture'], new Remote());
      ctx.llm.registerAdapter(['dshx-local'], localAdapter(LlmAdapter, { async query() { local++; assert.fail('overflow must not invoke native inference'); } }));
      const agent = await ctx.agentLoop.create(`memory-${mode}`, original);
      disposeSelection = installModelSelection(agent.ctx, selection);
      ctx.sessionController = { agents: {
        selectionFor: () => selection,
        selectForNextRequest(agent, selected) { agent.session.append('model/selection', selected); selection.current = selected; },
      }, commands: {
        resolveAgent: async () => agent,
        prompt: async (request, source) => { agent.followup(createUserMessage({ content: request.content, source })); return { accepted: true }; },
      } };
      const inject = () => agent.session.append('user/message', createUserMessage({ content: [{ type: 'text', text: 'x'.repeat(1800) }],
        source: { kind: 'plugin:dshx-memory', form: 'recall', entries: [], ...(mode === 'notice' ? { notice: true } : {}) } }), { surfaceOp: 'append' });
      if (mode !== 'late-recall') inject();
      else {
        let injected = false;
        ctx.on('agent/request', (_payload, next) => { if (!injected) { injected = true; inject(); } return next(); });
      }
      voice = voiceSession(ctx);
      await assert.rejects(voice.call('prompt', { session: agent.session.id, interaction: mode, text: '你好', local: true }), { code: 'LOCAL_MEMORY_CONTEXT' });
      await agent.whenIdle();
      assert.equal(remote, 0); assert.equal(local, 0);
      assert.equal(selection.current, original);
    } finally { voice?.close(); disposeSelection?.(); await ctx.fiber.dispose(); }
  }
});

test('Android memory local authenticated host validates actions and sanitizes management errors', async t => {
  const f = await localMemoryFixture(t); await f.start();
  const host = new PolicyHost(), frames = [];
  host.socket = { write: value => frames.push(JSON.parse(value)), destroy() {} };
  t.after(() => host.close());
  const request = { memory: 'memory-request', action: 'status' };
  const frame = async () => { for (let i = 0; i < 100 && !frames.length; i++) await delay(2); assert.ok(frames.length, 'missing host response'); return frames.shift(); };
  assert.throws(() => host.receive(request), /原生记忆请求无效/);
  host.connected = true;
  host.receive(request);
  assert.equal((await frame()).error, f.memoryError({ code: 'MEMORY_NOT_READY' }));
  host.memory = f.memory.call;
  host.receive(request);
  assert.deepEqual((await frame()).result.settings, { enabled: false, autoSave: false });
  for (const action of ['status', 'settings', 'list', 'read', 'save', 'remove', 'clear', 'retry', 'organize']) {
    host.memory = async (actual, data) => { assert.equal(actual, action); assert.deepEqual(data, { check: true }); return { action }; };
    host.receive({ ...request, action, data: { check: true } });
    assert.equal((await frame()).result.action, action);
  }
  for (const invalid of [{ ...request, action: 'install' }, { ...request, action: 'anything' }, { ...request, other: true }, { ...request, memory: '../bad' }]) {
    assert.throws(() => host.receive(invalid), /原生记忆请求无效/);
  }
  host.memory = async () => { throw new Error('private-test-key raw provider details'); };
  host.receive(request);
  const failure = await frame();
  assert.equal(failure.ok, false);
  assert.doesNotMatch(JSON.stringify(failure), /private-test-key|provider details/);
});

test('Android memory local real Cordis profile starts local storage without an external memory plugin', { timeout: 35000 }, async () => {
  const dir = await mkdtemp(join(directory, 'local-memory-profile-'));
  const yaml = require('js-yaml');
  const { entryListSchema } = await load('@deepseek-ai/cordis-plugin-include');
  const patches = yaml.load(await readFile(new URL('./android.patch.yml', import.meta.url), 'utf8'), { schema: entryListSchema });
  assert.doesNotMatch(JSON.stringify(patches), /openviking/iu, 'the Android profile must remove the old integration entirely');
  const local = patches.flatMap(row => row.insert ?? []).filter(row => row.id === 'dshx-android-memory');
  assert.equal(local.length, 1);
  assert.equal(local[0].name, 'file:@CORE@/memory.mjs');
  const run = async () => {
    const assert = (await import('node:assert/strict')).default;
    const { createRequire } = await import('node:module');
    const { pathToFileURL } = await import('node:url');
    const { join } = await import('node:path');
    const { mkdirSync, writeFileSync } = await import('node:fs');
    const core = process.env.DSHX_CORE, dir = process.env.DSHX_MEMORY_TEST_DIR;
    const require = createRequire(join(core, 'lib/node_modules/package.json'));
    const load = name => import(pathToFileURL(require.resolve(name)));
    const { packageManager } = await import(pathToFileURL(join(core, 'plugins.mjs')));
    const { runProfile } = await load('@deepseek-ai/dsh/profile-boot');
    const { loadProfileDirectory } = await load('@deepseek-ai/dsh-app-boot');
    const { createLaunchEnvironmentSnapshot } = await load('@deepseek-ai/dsh-launch-environment');
    const profile = join(dir, 'profile'); mkdirSync(profile);
    const names = [
      ['timer', '@deepseek-ai/cordis-plugin-timer'], ['hmr', '@deepseek-ai/dsh-hmr', { root: [] }],
      ['plugin-manager', '@deepseek-ai/dsh-plugin-manager'], ['storage', '@deepseek-ai/dsh-storage'],
      ['storage-json', '@deepseek-ai/dsh-storage-json', { root: join(dir, 'storages') }],
      ['storage-domain', '@deepseek-ai/dsh-storage-domain', { backend: 'json' }],
      ['system-prompt', '@deepseek-ai/dsh-system-prompt'], ['tools', '@deepseek-ai/dsh-tools'],
      ['llm', '@deepseek-ai/dsh-llm'], ['agents', '@deepseek-ai/dsh-agent'], ['sessions', '@deepseek-ai/dsh-session'],
      ['session-projection', '@deepseek-ai/dsh-session-projection'], ['fs', '@deepseek-ai/dsh-fs'],
      ['working-directory', '@deepseek-ai/dsh-working-directory', { defaultDirectory: dir }],
      ['session-persistence', '@deepseek-ai/dsh-session-persistence-jsonl', { root: join(dir, 'sessions'), compression: 'none' }],
    ];
    const entries = names.map(([id, name, config]) => ({ id, name: pathToFileURL(require.resolve(name)).href, ...(config ? { config } : {}) }));
    entries.push({ id: 'memory', name: pathToFileURL(join(core, 'memory.mjs')).href });
    writeFileSync(join(profile, 'package.json'), '{"private":true,"dsh":{"profile":{"bundles":[]}}}');
    writeFileSync(join(profile, 'cordis.patch.yml'), JSON.stringify([{ insert: entries }]));
    const anchor = require.resolve('@deepseek-ai/dsh/package.json'), invocation = packageManager(core);
    const app = await runProfile({ environment: createLaunchEnvironmentSnapshot([]), profile: 'memory-local-test',
      patchFiles: [], args: [],
      resolvedProfile: { profile: loadProfileDirectory('dsh', profile, anchor), installAnchor: anchor },
      packageManager: { ...invocation, env: { ...invocation.env, npm_config_offline: 'true' } },
    });
    try {
      const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
      assert.equal(typeof policyHost.memory, 'function');
      const status = await policyHost.memory('status');
      assert.equal(status.ready, true, JSON.stringify(status));
      assert.deepEqual(status.settings, { enabled: false, autoSave: false });
      assert.equal(status.active, false);
      assert.ok(app.ctx.tools.get('memory_search')); assert.ok(app.ctx.tools.get('memory_read'));
      console.log('Local memory profile passed');
    } finally { await app.shutdown.shutdown(0); }
  };
  const child = spawn(process.execPath, ['--expose-internals', '--preserve-symlinks', '--preserve-symlinks-main', '--import', join(core, 'compat.mjs'), '--input-type=module', '-e', '(' + run.toString() + ')().catch(error => { console.error(error); process.exitCode = 1; })'], {
    env: { ...process.env, DSHX_MEMORY_TEST_DIR: dir, DSH_HOME: join(dir, 'home') }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; }); child.stderr.on('data', chunk => { output += chunk; });
  const timeout = setTimeout(() => child.kill('SIGKILL'), 25000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    assert.equal(code, 0, output); assert.match(output, /Local memory profile passed/);
  } finally { clearTimeout(timeout); if (child.exitCode === null) child.kill('SIGKILL'); }
});

async function localMemoryWait(check, label) {
  for (let i = 0; i < 1000; i++) { if (check()) return; await delay(2); }
  assert.fail(label);
}

for (const action of ['remove', 'remove-renamed-source', 'clear', 'settings', 'llm/adapters-updated']) {
  test(`Android memory local P2 ${action} prevents a delayed extraction from restoring obsolete memory`, { timeout: 10000 }, async t => {
    const f = await localMemoryFixture(t);
    const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
    const sessionId = `race-${action.replaceAll('/', '-')}`;
    await f.store.save(f.data('coffee', { summary: '原咖啡偏好', scope: { kind: 'workspace', workspace } }));
    await f.store.change(next => {
      next.entries[0].source = action === 'remove-renamed-source'
        ? { kind: 'auto', session: sessionId, events: [0] } : { kind: 'auto', session: 'old-session', events: [99] };
      next.entries[0].locked = false;
    });
    const old = f.store.read().entries[0];
    const handlers = new Map(), events = [];
    const flush = Promise.withResolvers(), release = Promise.withResolvers(), returned = Promise.withResolvers();
    let flushes = 0, extraction;
    const session = { id: sessionId, seq: 0, header: { cwd: workspace },
      snapshotEvents: () => events, requestHeader: () => ({ config: { provider: 'fixture', model: 'source-model' } }) };
    const ctx = {
      on(name, handler) {
        if (!handlers.has(name)) handlers.set(name, new Set());
        handlers.get(name).add(handler);
        return () => handlers.get(name).delete(handler);
      },
      llm: { listProviders: () => [{ id: 'fixture', name: 'fixture' }] },
      workingDirectory: { get: () => workspace },
      pluginManager: f.ctx.pluginManager,
      sessions: { async flush(current) { assert.equal(current, session); flushes++; return flush.promise; } },
      sessionPersistence: { async open(id, mode, { signal } = {}) {
        assert.equal(id, session.id); assert.equal(mode, 'read'); signal?.throwIfAborted();
        return { async read(from = 0, count = events.length - from) { return { events: events.slice(from, from + count) }; }, async close() {} };
      } },
    };
    const emit = (name, ...args) => { for (const handler of handlers.get(name) ?? []) handler(...args); };
    f.memory = f.memorySession(ctx, { store: f.store, extract: async (_ctx, options) => {
      assert.equal(_ctx, ctx); extraction = options;
      try { return await release.promise; } finally { returned.resolve(); }
    } });
    try {
      await f.memory.ready;
      await f.memory.call('settings', { enabled: true, autoSave: true, revision: f.store.read().revision });
      const user = createUserMessage({ content: [{ type: 'text', text: '更正：新的咖啡偏好' }], source: { kind: 'user' } });
      const decision = { kind: 'enter', messages: [user] };
      await f.memory.before({ agent: { session }, messages: [], signal: new AbortController().signal }, async () => decision);
      const cursor = f.store.read().cursors[session.id];
      assert.equal(cursor.start, 0); assert.equal(cursor.next, 0);
      events.push({ seq: 0, type: 'user/message', data: user });
      const end = { seq: 1, type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } };
      events.push(end); session.seq = events.length;
      emit('session/event', session, end);
      await localMemoryWait(() => flushes === 1, 'turn/end did not request a durable checkpoint');
      assert.equal(f.store.read().jobs.length, 0);
      assert.equal(extraction, undefined, 'extraction must wait for flush acknowledgement');
      flush.resolve(true);
      await localMemoryWait(() => extraction !== undefined, 'registered task never reached the injected extractor');
      assert.equal(extraction.session, session.id);
      assert.equal(extraction.provider, 'fixture'); assert.equal(extraction.model, 'source-model');
      assert.deepEqual(extraction.events.map(row => row.seq), [0, 1]);
      assert.equal(f.store.read().jobs[0].attempts, 1);
      const epoch = f.store.read().epoch;
      if (action.startsWith('remove')) await f.memory.call('remove', { id: old.id, revision: f.store.read().revision });
      else if (action === 'clear') await f.memory.call('clear', { revision: f.store.read().revision });
      else if (action === 'settings') await f.memory.call('settings', { enabled: false, autoSave: false, revision: f.store.read().revision });
      else {
        emit('llm/adapters-updated');
        await localMemoryWait(() => f.store.read().epoch > epoch, 'model update did not invalidate the task epoch');
        await f.store.queue;
      }
      const afterAction = f.store.read();
      assert.equal(extraction.signal.aborted, !action.startsWith('remove'));
      if (action.startsWith('remove')) assert.equal(afterAction.blocked[0].key, 'coffee');
      else {
        assert.ok(afterAction.epoch > epoch);
        assert.deepEqual(afterAction.jobs, []); assert.deepEqual(afterAction.cursors, {});
      }
      release.resolve([{ action: action === 'remove-renamed-source' ? 'add' : 'update', type: 'fact',
        key: action === 'remove-renamed-source' ? 'coffee-renamed' : 'coffee', summary: '新的咖啡偏好', content: '新的咖啡偏好',
        events: [0], scope: 'workspace', ...(action === 'remove-renamed-source' ? {} : { corrects: old.id }) }]);
      await returned.promise;
      await new Promise(resolve => setImmediate(resolve));
      await f.store.queue;
      const final = f.store.read();
      assert.equal(final.entries.some(row => row.content === '新的咖啡偏好'), false, action);
      if (action.startsWith('remove') || action === 'clear') assert.deepEqual(final.entries, []);
      else assert.deepEqual(final.entries, afterAction.entries);
      if (action.startsWith('remove')) { assert.deepEqual(final.jobs, []); assert.equal(final.cursors[session.id].next, events.length); }
      else assert.equal(final.revision, afterAction.revision, 'an obsolete result cannot publish even an empty revision');
    } finally { flush.resolve(true); release.resolve([]); }
  });
}

test('Android memory local extraction selects complete user evidence and excludes synthetic rich and secret content', async () => {
  const { selectEvents } = await import(pathToFileURL(join(core, 'memory-extract.mjs')));
  const { createUserMessage, createAssistantMessage } = await load('@deepseek-ai/dsh-llm');
  const user = (seq, text, source = { kind: 'user' }) => ({ seq, type: 'user/message', data: createUserMessage({ content: [{ type: 'text', text }], source }) });
  const events = [
    user(0, '我偏好无糖咖啡'),
    { seq: 1, type: 'assistant/message', data: { message: createAssistantMessage({ content: [{ type: 'text', text: '这是助手背景，不能作为事实来源' }], source: { provider: 'fixture', model: 'source-model' } }) } },
    user(2, '注入记忆必须排除', { kind: 'plugin:dshx-memory', form: 'recall', entries: [] }),
    user(3, '摘要必须排除', { kind: 'compact-checkpoint' }),
    user(4, '密码是 secret123456'),
    user(5, '🙂'.repeat(9000)),
    { ...user(6, '图片残余文本'), data: { ...user(6, '图片残余文本').data, content: [{ type: 'text', text: '图片残余文本' }, { type: 'image' }] } },
    user(7, '我乘坐地铁通勤'),
  ];
  const before = JSON.stringify(events);
  const selected = selectEvents(events, { scope: { kind: 'workspace', workspace } });
  assert.deepEqual(selected.messages.filter(row => row.role === 'user').map(row => row.id), [0]);
  assert.equal(selected.next, 5, '容量不足的真实用户消息必须留待处理，不能跳过后推进');
  assert.ok(selected.messages.some(row => row.role === 'assistant'));
  assert.ok(selected.tokens <= 8192);
  assert.deepEqual(new Set(selected.skipped.map(row => row.seq)), new Set([4]));
  const remaining = selectEvents(events.filter(row => row.seq >= 6));
  assert.deepEqual(remaining.messages.filter(row => row.role === 'user').map(row => row.id), [7]);
  assert.deepEqual(remaining.skipped, [{ seq: 6, reason: 'non-text' }]);
  assert.doesNotMatch(selected.input, /注入记忆|摘要必须|secret123456|图片残余/);
  assert.equal(JSON.stringify(events), before);
  assert.equal(selected.messages.find(row => row.id === 0).text, '我偏好无糖咖啡');
});

test('Android memory local extraction validates whole batches and rejects invented sources scope and facts', async () => {
  const { selectEvents, validateCandidates } = await import(pathToFileURL(join(core, 'memory-extract.mjs')));
  const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const event = (seq, text) => ({ seq, type: 'user/message', data: createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } }) });
  const selection = selectEvents([event(10, '我偏好无糖咖啡'), event(12, '请记住：我乘坐地铁通勤')]);
  const candidate = { action: 'add', type: 'preference', key: 'coffee', summary: '无糖咖啡', content: '我偏好无糖咖啡', events: [10] };
  const valid = validateCandidates([candidate], selection)[0];
  assert.equal(valid.scope, 'user'); assert.deepEqual(valid.events, [10]);
  const explicit = validateCandidates([{ ...candidate, key: 'commute', summary: '地铁通勤', content: '我乘坐地铁通勤', events: [12] }], selection)[0];
  assert.equal(explicit.explicit, true); assert.equal(explicit.state, 'pending');
  for (const invalid of [
    { ...candidate, events: [999] }, { ...candidate, content: '用户住在月球' },
    { ...candidate, scope: 'workspace' }, { ...candidate, workspace: '/another-project' },
    { ...candidate, explicit: true }, { ...candidate, action: 'update', corrects: 'unknown' },
    { ...candidate, summary: '长'.repeat(161) },
  ]) assert.throws(() => validateCandidates([candidate, invalid], selection), error => ['MEMORY_SOURCE', 'MEMORY_EXTRACT'].includes(error.code));
  assert.throws(() => validateCandidates(Array(11).fill(candidate), selection), { code: 'MEMORY_EXTRACT' });
  assert.throws(() => validateCandidates([candidate, candidate], selection), { code: 'MEMORY_EXTRACT' });
});

test('Android memory local extraction uses the source route without tools and rejects truncated malformed or tool output', async () => {
  const { extract } = await import(pathToFileURL(join(core, 'memory-extract.mjs')));
  const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const events = [
    { seq: 0, type: 'request/header', data: { header: { config: { provider: 'fixture', model: 'source-model' } } } },
    { seq: 1, type: 'user/message', data: createUserMessage({ content: [{ type: 'text', text: '我偏好无糖咖啡' }], source: { kind: 'user' } }) },
  ];
  const candidate = { action: 'add', type: 'preference', key: 'coffee', summary: '无糖咖啡', content: '我偏好无糖咖啡', events: [1] };
  const cases = [
    { label: 'valid', text: JSON.stringify([candidate]), finish: 'stop' },
    { label: 'truncated', text: JSON.stringify([candidate]), finish: 'max-tokens' },
    { label: 'malformed', text: '[{broken', finish: 'stop' },
    { label: 'missing finish', text: JSON.stringify([candidate]) },
    { label: 'invented source', text: JSON.stringify([{ ...candidate, events: [999] }]), finish: 'stop' },
    { label: 'tool output', tool: true, finish: 'stop' },
  ];
  for (const scenario of cases) {
    let calls = 0;
    const ctx = { llm: { async prepareCall(config, signal, refine) {
      calls++; assert.equal(config.provider, 'fixture'); assert.equal(config.model, 'source-model');
      const controls = refine(config, { provider: 'fixture', id: 'source-model', context: { contextWindow: 16384 }, defaultMaxTokens: 2048 });
      return { config: controls, async *stream(options) {
        assert.equal(options.sessionId, 'extraction-session'); assert.equal(options.purpose, 'memory-extraction');
        assert.deepEqual(options.tools, []); assert.equal(options.messages[0].source.kind, 'plugin:dshx-memory');
        assert.equal(options.provider, 'fixture'); assert.equal(options.model, 'source-model');
        assert.ok(options.maxTokens <= 2048); assert.equal(options.signal.aborted, false);
        if (scenario.tool) yield { type: 'tool-call-delta', index: 0, id: 'forbidden', name: 'write', argumentsDelta: '{}' };
        else yield { type: 'text-delta', index: 0, text: scenario.text };
        if (scenario.finish) yield { type: 'finish', reason: { kind: scenario.finish } };
      } };
    } } };
    const request = { session: 'extraction-session', events, provider: 'fixture', model: 'source-model' };
    if (scenario.label === 'valid') assert.equal((await extract(ctx, request))[0].content, candidate.content);
    else await assert.rejects(extract(ctx, request), error => ['MEMORY_EXTRACT', 'MEMORY_SOURCE'].includes(error.code), scenario.label);
    assert.equal(calls, 1);
    await assert.rejects(extract(ctx, { ...request, provider: 'other-cloud' }), { code: 'MEMORY_MODEL' });
    assert.equal(calls, 1, 'a mismatched route cannot resolve or contact another model');
  }
  const aborted = new AbortController(); aborted.abort();
  await assert.rejects(extract({ llm: { prepareCall() { assert.fail('aborted extraction reached a model'); } } }, {
    session: 'extraction-session', events, provider: 'fixture', model: 'source-model', signal: aborted.signal,
  }), { code: 'MEMORY_EXTRACT' });
});

test('Android memory local concurrent settings keep the newest generation barrier until its own save succeeds', async t => {
  const f = await localMemoryFixture(t); await f.start(); await f.settings(true);
  const change = f.store.change.bind(f.store), gates = [Promise.withResolvers(), Promise.withResolvers()];
  let calls = 0;
  f.store.change = (...args) => { const gate = gates[calls++]; return gate.promise.then(() => change(...args)); };
  const revision = f.store.read().revision;
  const first = f.memory.call('settings', { enabled: true, autoSave: false, revision });
  const second = f.memory.call('settings', { enabled: false, autoSave: false, revision });
  second.catch(() => {});
  try {
    await localMemoryWait(() => calls === 2, 'concurrent settings did not raise both barriers');
    assert.equal(f.memory.active(), false);
    gates[0].resolve(); await first;
    assert.equal(f.store.read().settings.enabled, true);
    assert.equal(f.memory.active(), false, 'the older successful write cannot lift the newer pending barrier');
    assert.throws(() => f.memory.search('咖啡', workspace), { code: 'MEMORY_NOT_READY' });
    gates[1].resolve();
    await assert.rejects(second, { code: 'MEMORY_CONFLICT' });
    assert.equal(f.memory.active(), false, 'failed newest settings stay paused instead of reviving old intent');
    assert.match((await f.memory.call('status')).message, /暂停/);
    f.store.change = change;
    await f.settings(true);
    assert.equal(f.memory.active(), true);
  } finally { for (const gate of gates) gate.resolve(); f.store.change = change; await Promise.allSettled([first, second]); }
});

test('Android memory local topic edits reject collisions and preserve deletion sources after editing or confirmation', async t => {
  const f = await localMemoryFixture(t);
  await f.store.save(f.data('coffee', { summary: '咖啡偏好' }));
  await f.store.save(f.data('commute', { summary: '地铁通勤' }));
  const snapshot = f.store.read(), first = snapshot.entries.find(row => row.key === 'coffee');
  const disk = await readFile(join(f.dir, 'memory', 'state.json'), 'utf8');
  await assert.rejects(f.store.save(f.data('commute', { id: first.id, summary: '覆盖另一个主题', revision: snapshot.revision })), { code: 'MEMORY_CONFLICT' });
  assert.deepEqual(f.store.read(), snapshot);
  assert.equal(await readFile(join(f.dir, 'memory', 'state.json'), 'utf8'), disk);
  const invalid = structuredClone(snapshot);
  invalid.entries[1].key = first.key;
  assert.throws(() => f.checkMemory(invalid), { code: 'MEMORY_READ' });
  const ref = { session: 'original-source', events: [1, 2] };
  for (const mode of ['manual-id', 'manual-upsert', 'auto-upsert']) {
    const key = `sources-${mode}`;
    await f.store.save(f.data(key));
    await f.store.change(next => {
      next.entries.find(row => row.key === key).source = mode === 'auto-upsert'
        ? { kind: 'auto', session: 'primary-source', events: [3], refs: [ref] }
        : { kind: 'manual', refs: [ref] };
    });
    const before = f.store.read().entries.find(row => row.key === key);
    await f.store.save(f.data(key, { ...(mode === 'manual-id' ? { id: before.id } : {}), content: '用户修改后的正文' }));
    const saved = f.store.read().entries.find(row => row.key === key);
    assert.deepEqual(saved.source.refs, [ref], `${mode} keeps merged history`);
    if (mode === 'auto-upsert') assert.equal(saved.source.confirmed, true);
    await f.store.remove({ id: saved.id });
    assert.ok(f.store.read().blocked.at(-1).sources.some(row => row.session === ref.session));
  }
  await f.store.save(f.data('confirm-sources'));
  await f.store.change(next => {
    const active = next.entries.find(row => row.key === 'confirm-sources');
    active.source = { kind: 'auto', session: 'active-source', events: [7], refs: [ref] };
    next.entries.push({ ...active, id: 'pending-source', state: 'pending', locked: false,
      source: { kind: 'auto', session: 'candidate-source', events: [8] }, content: '候选正文' });
  });
  await f.store.save(f.data('confirm-sources', { id: 'pending-source', content: '用户确认后的正文' }));
  assert.equal(f.store.read().entries.filter(row => row.key === 'confirm-sources').length, 1);
  await f.store.remove({ id: 'pending-source' });
  assert.deepEqual(f.store.read().blocked.at(-1).sources.map(row => row.session).sort(),
    ['active-source', 'candidate-source', 'original-source']);
});

test('Android memory local effective working directory controls injection and resets automatic collection after a change', async t => {
  const f = await localMemoryFixture(t); await f.start(); await f.settings(true, true);
  const other = join(workspace, 'new-workspace');
  await f.store.save(f.data('original', { summary: '咖啡原工作区', scope: { kind: 'workspace', workspace } }));
  await f.store.save(f.data('current', { summary: '咖啡当前工作区', scope: { kind: 'workspace', workspace: other } }));
  const agent = f.session('working-directory');
  const first = await f.before(agent); f.commit(agent, first.result);
  assert.equal(first.result.messages[0].source.entries[0].id, f.store.read().entries.find(row => row.key === 'original').id);
  const event = agent.session.append('working-directory/change', { cwd: other });
  await f.store.queue;
  const cursor = f.store.read().cursors[agent.session.id];
  assert.equal(cursor.workspace, other);
  assert.equal(cursor.start, event.seq + 1); assert.equal(cursor.next, event.seq + 1);
  assert.equal(agent.session.header.cwd, workspace, 'the immutable original header must remain different');
  assert.equal(f.ctx.workingDirectory.get(agent.session), other);
  const current = await f.before(agent);
  const recall = current.result.messages[0];
  assert.equal(recall.source.reset, true);
  assert.ok(recall.source.entries.every(row => row.scope.workspace === other));
  assert.match(recall.content[0].text, /咖啡当前工作区/);
  assert.doesNotMatch(recall.content[0].text, /咖啡原工作区/);
});

test('Android memory local recovery creates per-turn jobs from completed source routes without sending a model request', async t => {
  const f = await localMemoryFixture(t);
  const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const user = text => createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } });
  const id = 'recover-root';
  const events = [
    { seq: 0, type: 'request/header', data: { header: { config: { provider: 'route-a', model: 'a' } } } },
    { seq: 1, type: 'user/message', data: user('咖啡偏好') },
    { seq: 2, type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } },
    { seq: 3, type: 'request/header', data: { header: { config: { provider: 'route-b', model: 'b' } } } },
    { seq: 4, type: 'user/message', data: user('地铁通勤') },
    { seq: 5, type: 'turn/end', data: { turn: 2, reason: { kind: 'completed' } } },
    { seq: 6, type: 'request/header', data: { header: { config: { provider: 'incomplete-cloud', model: 'never-use' } } } },
    { seq: 7, type: 'user/message', data: user('尚未完成的轮次') },
  ];
  await f.store.change(next => {
    next.settings = { enabled: true, autoSave: true }; next.epoch = 3;
    next.cursors[id] = { epoch: 3, start: 0, next: 0, workspace };
  });
  let modelCalls = 0;
  let providers = [{ id: 'route-a', name: 'route-a' }];
  const ctx = {
    on() { return () => {}; }, workingDirectory: { get: session => session.header.cwd },
    llm: { listProviders: () => providers },
    sessions: { flush() { assert.fail('recovery reads existing checkpoints instead of flushing a detached session'); } },
    sessionPersistence: { async open(actual, mode) {
      assert.equal(actual, id); assert.equal(mode, 'read');
      return { async read(from = 0, count = events.length - from) { return { events: events.slice(from, from + count) }; }, async close() {} };
    } },
  };
  f.memory = f.memorySession(ctx, { store: f.store, extract: async () => { modelCalls++; return []; } });
  await f.memory.ready;
  const jobs = f.store.read().jobs;
  assert.deepEqual(jobs.map(row => [row.from, row.to, row.route.provider, row.route.model]), [[0, 3, 'route-a', 'a'], [3, 6, 'route-b', 'b']]);
  assert.ok(jobs.every(row => row.attempts === 0 && row.state === 'waiting'));
  assert.equal(f.store.read().cursors[id].next, 0, 'registering a job is not successful extraction');
  assert.equal(modelCalls, 0);
  const available = await f.memory.call('status');
  assert.equal(available.autoReady, true);
  assert.deepEqual(available.jobs.map(row => row.modelReady), [true, false], 'availability follows each job source provider id');
  const waiting = f.store.read();
  await assert.rejects(f.memory.call('retry', { id: jobs[1].id }), { code: 'MEMORY_MODEL' });
  assert.deepEqual(f.store.read(), waiting, 'missing source provider must not consume a retry or revision');
  providers = [];
  const unavailable = await f.memory.call('status');
  assert.equal(unavailable.autoReady, false);
  assert.ok(unavailable.jobs.every(row => row.modelReady === false));
  await assert.rejects(f.memory.call('retry', { id: jobs[0].id }), { code: 'MEMORY_MODEL' });
  const task = user('继续整理');
  const session = { id, seq: events.length, header: { cwd: workspace }, snapshotEvents: () => events,
    requestHeader: () => ({ config: { provider: 'route-a', model: 'a' } }) };
  await f.memory.before({ agent: { session }, messages: [], signal: new AbortController().signal }, async () => ({ kind: 'enter', messages: [task] }));
  await new Promise(resolve => setImmediate(resolve));
  await f.store.queue;
  assert.deepEqual(f.store.read().jobs, waiting.jobs, 'automatic draining pauses unavailable source jobs without consuming attempts');
  assert.equal(modelCalls, 0);
});

test('Android memory local host cancels one pending management request and preserves the other reply', async () => {
  const host = new PolicyHost(), frames = [], releases = new Map(), signals = new Map();
  host.socket = { write: value => frames.push(JSON.parse(value)), destroy() {} };
  host.connected = true;
  host.memory = async (_action, data, signal) => {
    const release = Promise.withResolvers(); releases.set(data.id, release); signals.set(data.id, signal);
    return release.promise;
  };
  try {
    host.receive({ memory: 'first', action: 'save', data: { id: 'first' } });
    host.receive({ memory: 'second', action: 'save', data: { id: 'second' } });
    await localMemoryWait(() => releases.size === 2, 'management requests did not start');
    assert.throws(() => host.receive({ memory: 'first', action: 'status' }), /重复/);
    assert.throws(() => host.receive({ memory: 'cancel-invalid', action: 'cancel', data: { id: '../bad' } }), /取消请求无效/);
    host.receive({ memory: 'cancel-first', action: 'cancel', data: { id: 'first' } });
    assert.equal(signals.get('first').aborted, true); assert.equal(signals.get('second').aborted, false);
    releases.get('first').resolve({ late: true }); releases.get('second').resolve({ saved: true });
    await localMemoryWait(() => frames.length === 1 && host.memoryCalls.size === 0, 'uncancelled reply or request cleanup missing');
    assert.deepEqual(frames, [{ memory: 'second', ok: true, result: { saved: true } }]);
  } finally { for (const release of releases.values()) release.resolve({}); host.close(); }
});

// 知识库测试只追加在本文件末尾；运行时使用摘要核对后的独立运行包。
async function localKnowledgeModules() {
  return {
    ...await import(pathToFileURL(join(core, 'knowledge-store.mjs'))),
    ...await import(pathToFileURL(join(core, 'knowledge-search.mjs'))),
  };
}

function localKnowledgeCheckChunks(text, chunks, sourceId, version) {
  const chars = [...text];
  assert.ok(chunks.length > 0, '非空资料必须产生分块');
  const covered = new Set();
  chunks.forEach((chunk, index) => {
    assert.equal(chunk.sourceId, sourceId);
    assert.equal(chunk.sourceVersion, version);
    assert.equal(chunk.text, chars.slice(chunk.startOffset, chunk.endOffset).join(''), '分块偏移必须按完整 Unicode 字符定位真实正文');
    assert.ok([...chunk.text].length <= 800, '分块不能突破约定的 800 码点上限');
    assert.ok(chunk.startLine >= 1 && chunk.endLine >= chunk.startLine);
    assert.ok(!chunk.text.includes('\ufffd'), '不得破坏中文或 emoji');
    if (index) assert.equal(chunk.previous, chunks[index - 1].id);
    if (index + 1 < chunks.length) assert.equal(chunk.next, chunks[index + 1].id);
    for (let offset = chunk.startOffset; offset < chunk.endOffset; offset++) covered.add(offset);
  });
  for (let index = 0; index < chars.length; index++) {
    if (chars[index].trim()) assert.ok(covered.has(index), `原文第 ${index} 个非空字符未进入任何分块`);
  }
}

test('Android knowledge local chunks preserve Chinese emoji offsets and adjacent references', async () => {
  const { chunkText } = await localKnowledgeModules();
  const text = '# 恢复说明\n\n' + '中文🙂恢复步骤🚀必须保留数据库。'.repeat(110);
  const chunks = chunkText(text, { sourceId: 'unicode-source', version: 3, format: 'md' });
  localKnowledgeCheckChunks(text, chunks, 'unicode-source', 3);
  assert.ok(chunks.length >= 3);
});

test('Android knowledge local chunks retain Markdown tables fenced code and long lines without omissions', async () => {
  const { chunkText } = await localKnowledgeModules();
  const text = '# 部署\n\n## 数据库\n\n| 文件 | 保留 |\n| --- | --- |\n' + '| 数据🙂 | 必须 |\n'.repeat(90) +
    '\n```sh\n' + 'printf 数据恢复🙂\n'.repeat(90) + '```\n\n' + '连续长行🚀'.repeat(300);
  const chunks = chunkText(text, { sourceId: 'markdown-source', version: 1, format: 'md' });
  localKnowledgeCheckChunks(text, chunks, 'markdown-source', 1);
  assert.ok(chunks.some(chunk => chunk.heading.includes('数据库')));
});

test('Android knowledge local chunks use repeatable IDs and distinguish source versions', async () => {
  const { chunkText } = await localKnowledgeModules();
  const text = '保持资料稳定。'.repeat(250);
  const options = { sourceId: 'stable-source', version: 1, format: 'txt' };
  const before = chunkText(text, options);
  assert.deepEqual(chunkText(text, options), before);
  const after = chunkText(text, { ...options, version: 2 });
  assert.ok(after.every(chunk => chunk.sourceVersion === 2));
  assert.equal(new Set(before.map(chunk => chunk.id)).size, before.length);
});

function localKnowledgeRows(texts) {
  return texts.map((text, index) => ({ id: `chunk-${index}`, sourceId: `source-${index}`, sourceVersion: 1,
    kind: 'document', title: `资料 ${index}`, heading: [], text, startOffset: 0, endOffset: [...text].length, startLine: 1, endLine: 1 }));
}

test('Android knowledge local keyword search finds Chinese phrases and rejects unrelated queries', async () => {
  const { keywordSearch } = await localKnowledgeModules();
  const rows = localKnowledgeRows(['恢复数据库时必须保留数据目录和备份文件。', '每天骑车通勤。', '升级失败先停止服务，再恢复数据库。']);
  const found = keywordSearch(rows, '恢复数据库', { limit: 10 });
  assert.deepEqual(new Set(found.map(item => item.row.sourceId)), new Set(['source-0', 'source-2']));
  assert.deepEqual(keywordSearch(rows, '火星探测器', { limit: 10 }), []);
});

test('Android knowledge local keyword search preserves exact error codes model names and version tokens', async () => {
  const { keywordSearch } = await localKnowledgeModules();
  const rows = localKnowledgeRows(['Intel BE213 遇到 E_CONN_RESET，版本 v0.2.1-rc.2。', 'Intel BE200 网络正常。', '使用 Go 编写后端。']);
  for (const query of ['BE213', 'E_CONN_RESET', 'v0.2.1-rc.2']) {
    assert.equal(keywordSearch(rows, query, { limit: 10 })[0].row.sourceId, 'source-0');
  }
  assert.equal(keywordSearch(rows, 'ＧＯ', { limit: 10 })[0].row.sourceId, 'source-2');
  assert.deepEqual(keywordSearch(rows, 'goal', { limit: 10 }), []);
});

test('Android knowledge local keyword search respects limits without mutating source rows', async () => {
  const { keywordSearch } = await localKnowledgeModules();
  const rows = localKnowledgeRows(Array.from({ length: 30 }, (_, i) => `数据库恢复资料 ${i}`));
  const before = structuredClone(rows);
  assert.equal(keywordSearch(rows, '数据库恢复', { limit: 4 }).length, 4);
  assert.deepEqual(rows, before);
});

async function localKnowledgeFixture(t, options = {}) {
  const f = await localMemoryFixture(t, { open: false });
  Object.assign(f, await localKnowledgeModules());
  f.root = join(f.dir, 'knowledge');
  f.instance = 'knowledge-test-instance';
  f.workspaces = [workspace, join(workspace, 'other')];
  f.operation = (data = {}) => ({ operationId: globalThis.crypto.randomUUID(), createdAt: Date.now(), ...data });
  f.manage = (action, data = {}, extra = {}) => f.store.manage(action, data, { instance: f.instance, workspaces: f.workspaces, ...extra });
  f.start = async (sessionOptions = {}) => {
    const { knowledgeSession } = await import(pathToFileURL(join(core, 'knowledge.mjs')));
    f.knowledge = knowledgeSession(f.ctx, { store: f.store, capacity: 100000, memory: { view: () => ({ enabled: false, revision: 0, entries: [] }) }, ...sessionOptions });
    await f.knowledge.ready;
    return f.knowledge;
  };
  f.agent = async (cwd = workspace) => {
    const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
    const session = f.ctx.sessions.create(globalThis.crypto.randomUUID(), { meta: { cwd } });
    const agent = { id: globalThis.crypto.randomUUID(), session };
    f.agents.push(agent);
    session.append('user/message', createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: '查阅本机知识资料' }] }), { surfaceOp: 'append' });
    return agent;
  };
  f.agents = [];
  f.ctx.agents = { list: () => f.agents };
  f.execute = (agent, name, data = {}, signal = new AbortController().signal) => f.knowledge.execute(name, data, { agent, signal });
  t.after(async () => { await f.knowledge?.close(); });
  f.store = await f.openKnowledge(f.ctx, { root: f.root, ...options });
  await f.store.ready;
  f.collection = async (title = '测试集合', extra = {}) => {
    const result = await f.manage('collection', f.operation({ type: 'create', title, scope: { kind: 'user' }, ...extra }));
    await f.store.idle();
    return result.collection ?? f.store.read().collections.find(row => row.title === title);
  };
  f.import = async (text = '数据库恢复必须保留数据目录。', extra = {}) => {
    const collectionId = extra.collectionId ?? (await f.collection()).id;
    const result = await f.manage('import', f.operation({ text, title: '恢复手册', format: 'txt', ...extra, collectionId }));
    await f.store.idle();
    const status = result.job ? await f.manage('status', { operationId: result.operation.operationId }) : result;
    if (status.job) assert.equal(status.job.state, 'succeeded', JSON.stringify(status.job));
    const source = f.store.read().sources.find(row => row.id === (status.source?.id ?? result.source?.id));
    assert.ok(source, '导入成功必须有正式来源记录');
    return source;
  };
  f.enable = () => f.manage('settings', f.operation({ expectedRevision: f.store.read().settingsRevision, settings: { enabled: true } }));
  return f;
}

test('Android knowledge local imports BOM UTF8 preserves original bytes and normalizes reference text', async t => {
  const f = await localKnowledgeFixture(t);
  const original = '\ufeff# 恢复🙂\r\n\r\n保留数据库🚀\r\n下一行';
  const source = await f.import(original, { format: 'md' });
  assert.equal(source.bytes, Buffer.byteLength(original));
  assert.equal(source.hash, createHash('sha256').update(original).digest('hex'));
  assert.equal(await f.store.sourceText(source), '# 恢复🙂\n\n保留数据库🚀\n下一行');
  assert.equal((await f.store.sourceOriginal(source)).toString('utf8'), original);
  localKnowledgeCheckChunks(await f.store.sourceText(source), await f.store.sourceChunks(source), source.id, source.version);
});

test('Android knowledge local rejects empty unsupported and oversized input without publishing sources', async t => {
  const f = await localKnowledgeFixture(t);
  const collection = await f.collection();
  for (const payload of [{ text: '' }, { text: ' \n\t' }, { text: '正文', format: 'pdf' }, { text: '中'.repeat(700000) }]) {
    await assert.rejects(f.manage('import', f.operation({ title: '无效资料', format: 'txt', collectionId: collection.id, ...payload })),
      error => ['KNOWLEDGE_INPUT', 'KNOWLEDGE_LIMIT'].includes(error.code));
    await f.store.idle();
    assert.equal(f.store.read().sources.filter(row => row.state !== 'deleted').length, 0);
  }
});

test('Android knowledge local concurrent duplicate imports publish exactly one source', async t => {
  const f = await localKnowledgeFixture(t);
  const collection = await f.collection();
  const data = { text: '相同资料🙂保留数据目录', title: '并发资料', format: 'txt', collectionId: collection.id };
  await Promise.all(Array.from({ length: 6 }, () => f.manage('import', f.operation(data))));
  await f.store.idle();
  const sources = f.store.read().sources.filter(row => row.state !== 'deleted');
  assert.equal(sources.length, 1);
  assert.equal(await f.store.sourceText(sources[0]), data.text);
});

test('Android knowledge local repeated operation returns one durable effect and rejects changed parameters', async t => {
  const f = await localKnowledgeFixture(t);
  const collection = await f.collection();
  const data = f.operation({ text: '操作重试不重复发布', title: '重试资料', format: 'txt', collectionId: collection.id });
  const [first, second] = await Promise.all([f.manage('import', data), f.manage('import', data)]);
  await f.store.idle();
  assert.equal(first.job?.id ?? first.source?.id, second.job?.id ?? second.source?.id);
  assert.equal(f.store.read().sources.filter(row => row.state !== 'deleted').length, 1);
  await assert.rejects(f.manage('import', { ...data, text: '同编号不同资料' }), { code: 'KNOWLEDGE_CONFLICT' });
  assert.equal(f.store.read().sources.filter(row => row.state !== 'deleted').length, 1);
});

test('Android knowledge local rejects expired operation beyond 24 hours and preserves existing documents', async t => {
  const f = await localKnowledgeFixture(t);
  const source = await f.import();
  const before = f.store.read();
  await assert.rejects(f.manage('remove', f.operation({ sourceId: source.id, expectedRevision: source.revision,
    createdAt: Date.now() - 24 * 60 * 60 * 1000 - 1000 })), { code: 'KNOWLEDGE_OPERATION_EXPIRED' });
  assert.deepEqual(f.store.read(), before);
  assert.equal(await f.store.sourceText(source), '数据库恢复必须保留数据目录。');
});

test('Android knowledge local edits metadata once and rejects stale source revisions', async t => {
  const f = await localKnowledgeFixture(t);
  const source = await f.import();
  await f.manage('update', f.operation({ sourceId: source.id, expectedRevision: source.revision, patch: { title: '新标题', note: '重要资料' } }));
  await f.store.idle();
  const updated = f.store.read().sources.find(row => row.id === source.id);
  assert.equal(updated.title, '新标题'); assert.equal(updated.note, '重要资料');
  assert.equal(updated.version, source.version); assert.ok(updated.revision > source.revision);
  await assert.rejects(f.manage('update', f.operation({ sourceId: source.id, expectedRevision: source.revision, patch: { title: '过期覆盖' } })), { code: 'KNOWLEDGE_CONFLICT' });
  assert.equal(f.store.read().sources.find(row => row.id === source.id).title, '新标题');
});

test('Android knowledge local body replacement publishes a new version while invalid replacement retains old text', async t => {
  const f = await localKnowledgeFixture(t);
  const source = await f.import('旧版必须保留文件');
  await assert.rejects(f.manage('update', f.operation({ sourceId: source.id, expectedRevision: source.revision, patch: { text: '' } })), { code: 'KNOWLEDGE_INPUT' });
  assert.equal(await f.store.sourceText(f.store.read().sources.find(row => row.id === source.id)), '旧版必须保留文件');
  await f.manage('update', f.operation({ sourceId: source.id, expectedRevision: source.revision, patch: { text: '新版应保留数据库🙂' } }));
  await f.store.idle();
  const updated = f.store.read().sources.find(row => row.id === source.id);
  assert.ok(updated.version > source.version); assert.ok(updated.revision > source.revision);
  assert.equal(await f.store.sourceText(updated), '新版应保留数据库🙂');
});

test('Android knowledge local collection tree rejects cycles excessive depth and nonempty removal', async t => {
  const f = await localKnowledgeFixture(t);
  const root = await f.collection('根集合');
  const child = await f.collection('子集合', { parentId: root.id });
  const leaf = await f.collection('末级集合', { parentId: child.id });
  await assert.rejects(f.collection('第四层', { parentId: leaf.id }), error => ['KNOWLEDGE_INPUT', 'KNOWLEDGE_LIMIT'].includes(error.code));
  await assert.rejects(f.manage('collection', f.operation({ type: 'update', id: root.id, expectedRevision: root.revision, parentId: child.id })), error => ['KNOWLEDGE_INPUT', 'KNOWLEDGE_CONFLICT'].includes(error.code));
  await assert.rejects(f.manage('collection', f.operation({ type: 'remove', id: root.id, expectedRevision: f.store.read().collections.find(row => row.id === root.id).revision })), error => ['KNOWLEDGE_INPUT', 'KNOWLEDGE_CONFLICT', 'KNOWLEDGE_BUSY'].includes(error.code));
  assert.equal(f.store.read().collections.length, 3);
});

test('Android knowledge local disabled knowledge still permits management reading and exporting', async t => {
  const f = await localKnowledgeFixture(t);
  const source = await f.import('关闭工具仍保留用户资料');
  assert.equal(f.store.read().enabled, false);
  assert.equal((await f.manage('read', { sourceId: source.id })).text, '关闭工具仍保留用户资料');
  assert.ok((await f.manage('list')).sources.some(row => row.id === source.id));
  assert.ok((await localKnowledgeExport(f)).bytes.length > 0);
  await f.start();
  const agent = await f.agent();
  await assert.rejects(f.execute(agent, 'knowledge_search', { query: '资料' }), { code: 'KNOWLEDGE_NOT_READY' });
});

test('Android knowledge local cancelled request cannot publish or delete source data', async t => {
  const f = await localKnowledgeFixture(t);
  const source = await f.import();
  const controller = new AbortController(); controller.abort();
  const before = f.store.read();
  await assert.rejects(f.manage('remove', f.operation({ sourceId: source.id, expectedRevision: source.revision }), { signal: controller.signal }),
    error => error.name === 'AbortError' || error.code === 'KNOWLEDGE_CANCELLED');
  await f.store.idle();
  assert.deepEqual(f.store.read(), before);
  assert.equal(await f.store.sourceText(source), '数据库恢复必须保留数据目录。');
});

for (const corrupt of ['{broken', JSON.stringify({ unit: { name: 'knowledge', version: 1 }, global: { schemaVersion: 99 }, tables: {} })]) {
  test(`Android knowledge local preserves corrupt catalog ${corrupt.startsWith('{broken') ? 'JSON' : 'schema'} on failed reopen`, async t => {
    const f = await localMemoryFixture(t, { open: false });
    const { openKnowledge } = await localKnowledgeModules();
    const target = join(f.dir, 'knowledge', 'state.json');
    await mkdir(join(f.dir, 'knowledge'), { recursive: true });
    await writeFile(target, corrupt);
    await assert.rejects(openKnowledge(f.ctx, { root: join(f.dir, 'knowledge') }));
    assert.equal(await readFile(target, 'utf8'), corrupt);
  });
}

test('Android knowledge local tools require a registered real agent and a user request', async t => {
  const f = await localKnowledgeFixture(t); await f.enable(); await f.start();
  const agent = await f.agent();
  await assert.rejects(f.execute({ ...agent, id: 'unregistered' }, 'knowledge_search', { query: '资料' }), { code: 'KNOWLEDGE_NOT_READY' });
  const session = f.ctx.sessions.create(globalThis.crypto.randomUUID(), { meta: { cwd: workspace } });
  const empty = { id: 'empty', session }; f.agents.push(empty);
  await assert.rejects(f.execute(empty, 'knowledge_search', { query: '资料' }), { code: 'KNOWLEDGE_NOT_READY' });
  assert.deepEqual((await f.execute(agent, 'knowledge_search', { query: '不存在的资料' })).results, []);
});

test('Android knowledge local filters workspace sources before limiting search candidates', async t => {
  const f = await localKnowledgeFixture(t);
  const elsewhere = await f.collection('其他工作区', { scope: { kind: 'workspace', workspace: f.workspaces[1] } });
  for (let i = 0; i < 24; i++) await f.import(`恢复数据库：另一个项目的重要资料 ${i}`, { title: `恢复数据库 ${i}`, collectionId: elsewhere.id });
  const here = await f.collection('当前工作区', { scope: { kind: 'workspace', workspace } });
  const source = await f.import('当前项目恢复数据库需要保留目录', { collectionId: here.id });
  await f.enable(); await f.start();
  const result = await f.execute(await f.agent(), 'knowledge_search', { query: '恢复数据库', limit: 1 });
  assert.equal(result.results.length, 1); assert.equal(result.results[0].sourceId, source.id);
  assert.equal(result.coverage.eligible, source.chunkCount);
});

test('Android knowledge local old refs reject body changes while new refs read the new text', async t => {
  const f = await localKnowledgeFixture(t);
  const source = await f.import('旧词条数据库保留'); await f.enable(); await f.start();
  const agent = await f.agent();
  const old = (await f.execute(agent, 'knowledge_search', { query: '旧词条' })).results[0];
  assert.ok(old.ref);
  await f.manage('update', f.operation({ sourceId: source.id, expectedRevision: source.revision, patch: { text: '新词条数据库备份🙂' } }));
  await f.store.idle();
  await assert.rejects(f.execute(agent, 'knowledge_read', { ref: old.ref }), { code: 'KNOWLEDGE_VERSION_CHANGED' });
  const result = (await f.execute(agent, 'knowledge_search', { query: '新词条' })).results[0];
  assert.notEqual(result.ref, old.ref);
  assert.equal((await f.execute(agent, 'knowledge_read', { ref: result.ref })).text, '新词条数据库备份🙂');
});

test('Android knowledge local deleted refs reject reads and removed text does not return to search', async t => {
  const f = await localKnowledgeFixture(t);
  const source = await f.import('待删除的独特知识'); await f.enable(); await f.start();
  const agent = await f.agent();
  const old = (await f.execute(agent, 'knowledge_search', { query: '独特知识' })).results[0];
  await f.manage('remove', f.operation({ sourceId: source.id, expectedRevision: source.revision }));
  await f.store.idle();
  await assert.rejects(f.execute(agent, 'knowledge_read', { ref: old.ref }), error => ['KNOWLEDGE_MISSING', 'KNOWLEDGE_VERSION_CHANGED'].includes(error.code));
  assert.deepEqual((await f.execute(agent, 'knowledge_search', { query: '独特知识' })).results, []);
  await assert.rejects(f.manage('read', { sourceId: source.id }), { code: 'KNOWLEDGE_MISSING' });
});

test('Android knowledge local tools reject caller supplied paths scopes and oversized queries', async t => {
  const f = await localKnowledgeFixture(t); await f.enable(); await f.start();
  const agent = await f.agent();
  for (const args of [{ query: '资料', workspace: f.workspaces[1] }, { query: '资料', path: '/etc/passwd' }, { query: '中'.repeat(501) }]) {
    await assert.rejects(f.execute(agent, 'knowledge_search', args), { code: 'KNOWLEDGE_INPUT' });
  }
  const other = await f.agent();
  await assert.rejects(f.execute(other, 'knowledge_read', { ref: '../../etc/passwd' }), error => ['KNOWLEDGE_INPUT', 'KNOWLEDGE_VERSION_CHANGED', 'KNOWLEDGE_MISSING'].includes(error.code));
});

test('Android knowledge local reading offsets count whole emoji characters and retain pagination boundaries', async t => {
  const f = await localKnowledgeFixture(t);
  const source = await f.import('甲🙂乙🚀丙\n第二行'); await f.enable(); await f.start();
  const agent = await f.agent();
  const ref = `kb:document:${source.id}:v${source.version}:text`;
  const first = await f.execute(agent, 'knowledge_read', { ref, maxChars: 2 });
  assert.equal(first.text, '甲🙂'); assert.equal(first.nextOffset, 2); assert.equal(first.truncated, true);
  const second = await f.execute(agent, 'knowledge_read', { ref, offset: first.nextOffset, maxChars: 3 });
  assert.equal(second.text, '乙🚀丙'); assert.equal(second.nextOffset, 5);
  const last = await f.execute(agent, 'knowledge_read', { ref, offset: second.nextOffset, maxChars: 20 });
  assert.equal(last.text, '\n第二行'); assert.equal(last.nextOffset, null); assert.equal(last.truncated, false);
  assert.equal(first.text + second.text + last.text, '甲🙂乙🚀丙\n第二行');
});

test('Android knowledge local search cursors bind query session and catalog revision', async t => {
  const f = await localKnowledgeFixture(t); const collection = await f.collection();
  for (let i = 0; i < 5; i++) await f.import(`恢复说明 ${i}`, { collectionId: collection.id, title: `恢复说明 ${i}` });
  await f.enable(); await f.start();
  const agent = await f.agent(), other = await f.agent();
  const first = await f.execute(agent, 'knowledge_search', { query: '恢复', limit: 1 });
  assert.equal(first.hasMore, true); assert.ok(first.cursor);
  await assert.rejects(f.execute(other, 'knowledge_search', { query: '恢复', limit: 1, cursor: first.cursor }), { code: 'KNOWLEDGE_VERSION_CHANGED' });
  await assert.rejects(f.execute(agent, 'knowledge_search', { query: '说明', limit: 1, cursor: first.cursor }), { code: 'KNOWLEDGE_VERSION_CHANGED' });
  await f.import('新增恢复说明', { collectionId: collection.id });
  await assert.rejects(f.execute(agent, 'knowledge_search', { query: '恢复', limit: 1, cursor: first.cursor }), { code: 'KNOWLEDGE_VERSION_CHANGED' });
});

test('Android knowledge local per user request search budget survives repeated calls and resets on new user input', async t => {
  const f = await localKnowledgeFixture(t); await f.enable(); await f.start();
  const agent = await f.agent();
  for (let i = 0; i < 3; i++) await f.execute(agent, 'knowledge_search', { query: '资料' });
  await assert.rejects(f.execute(agent, 'knowledge_search', { query: '资料' }), { code: 'KNOWLEDGE_LIMIT' });
  const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
  agent.session.append('user/message', createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: '新的知识问题' }] }), { surfaceOp: 'append' });
  assert.deepEqual((await f.execute(agent, 'knowledge_search', { query: '资料' })).results, []);
});

test('Android knowledge local concurrent calls reserve shared request capacity before reading', async t => {
  const f = await localKnowledgeFixture(t); await f.import('知识库并发预算正文'.repeat(100)); await f.enable();
  const gate = Promise.withResolvers(); let started = 0;
  await f.start({ capacity: 900, search: async rows => { started++; await gate.promise; return { mode: 'keyword', results: rows.map(row => ({ row, score: 1 })) }; } });
  const agent = await f.agent();
  const first = f.execute(agent, 'knowledge_search', { query: '正文' });
  await localMemoryWait(() => started === 1, '第一个请求未进入检索');
  try { await assert.rejects(f.execute(agent, 'knowledge_search', { query: '正文' }), { code: 'KNOWLEDGE_LIMIT' }); }
  finally { gate.resolve(); }
  const result = await first;
  assert.ok(Buffer.byteLength(JSON.stringify(result)) <= 900);
});

test('Android knowledge local cancels delayed search before returning prepared evidence', async t => {
  const f = await localKnowledgeFixture(t); await f.import('取消后的证据不返回'); await f.enable();
  const gate = Promise.withResolvers(); let started = false;
  await f.start({ search: async rows => { started = true; await gate.promise; return { mode: 'keyword', results: rows.map(row => ({ row, score: 1 })) }; } });
  const agent = await f.agent(), controller = new AbortController();
  const result = f.execute(agent, 'knowledge_search', { query: '证据' }, controller.signal);
  await localMemoryWait(() => started, '延迟检索未开始');
  controller.abort(); gate.resolve();
  await assert.rejects(result, error => error.name === 'AbortError' || error.code === 'KNOWLEDGE_CANCELLED');
});

test('Android knowledge local effective memory filters pending expired and foreign facts without enabling documents', async t => {
  const f = await localKnowledgeFixture(t);
  const now = Date.now();
  const entry = { id: 'valid-fact', summary: '咖啡偏好', content: '用户喜欢无糖咖啡🙂', state: 'active', scope: { kind: 'user' }, updatedAt: now, expiresAt: null, source: { kind: 'manual' } };
  const entries = [entry, { ...entry, id: 'pending', state: 'pending' }, { ...entry, id: 'expired', expiresAt: now - 1 },
    { ...entry, id: 'foreign', scope: { kind: 'workspace', workspace: f.workspaces[1] } }];
  let revision = 1, enabled = true;
  await f.start({ memory: { view: () => ({ enabled, revision, entries }) } });
  const agent = await f.agent();
  const result = await f.execute(agent, 'knowledge_search', { query: '咖啡' });
  assert.deepEqual(result.results.map(row => row.sourceId), ['valid-fact']);
  assert.deepEqual(result.disabledKinds, ['document']);
  const read = await f.execute(agent, 'knowledge_read', { ref: result.results[0].ref });
  assert.equal(read.text, entry.content); assert.deepEqual(read.source, entry.source);
  entries[0] = { ...entry, content: '改为饮茶', updatedAt: now + 1 }; revision++;
  await assert.rejects(f.execute(agent, 'knowledge_read', { ref: result.results[0].ref }), { code: 'KNOWLEDGE_VERSION_CHANGED' });
  enabled = false; revision++;
  await assert.rejects(f.execute(agent, 'knowledge_search', { query: '咖啡' }), { code: 'KNOWLEDGE_NOT_READY' });
});

async function localKnowledgeVectors(rows, changes = {}) {
  const { vectorKey } = await import(pathToFileURL(join(core, 'knowledge-search.mjs')));
  const { normalizeEmbedding, embeddingIdentity, embeddingInput } = await import(pathToFileURL(join(core, 'knowledge-model.mjs')));
  const config = normalizeEmbedding({ enabled: true, configId: 'fixture-vector-config', provider: 'fixture', model: 'embedding-fixture', dimensions: 2, kinds: ['document', 'memory'], ...changes });
  const vectors = new Map(rows.map((row, index) => [vectorKey(row, config), {
    modelConfigId: embeddingIdentity(config), inputTemplateVersion: config.inputTemplateVersion,
    inputTextHash: createHash('sha256').update(embeddingInput(row, config)).digest('hex'),
    sourceId: row.sourceId, sourceVersion: row.sourceVersion, vector: index ? [0, 1] : [1, 0],
  }]));
  return { ...config, vectors, embedQuery: async () => [1, 0] };
}

test('Android knowledge local hybrid combines complete compatible vectors with keyword candidates', async () => {
  const { hybridSearch } = await localKnowledgeModules();
  const rows = localKnowledgeRows(['数据库故障恢复方案', '骑车通勤计划']);
  const embedding = await localKnowledgeVectors(rows);
  const result = await hybridSearch(rows, '数据库恢复', { embedding });
  assert.equal(result.mode, 'hybrid'); assert.deepEqual(result.coverage, { eligible: 2, indexed: 2 });
  assert.equal(result.results[0].row.id, rows[0].id);
  assert.deepEqual(new Set(result.results[0].matchedBy), new Set(['keyword', 'semantic']));
  assert.equal(new Set(result.results.map(hit => hit.row.id)).size, result.results.length);
});

test('Android knowledge local partial semantic coverage falls back completely without requesting a query vector', async () => {
  const { hybridSearch } = await localKnowledgeModules();
  const rows = localKnowledgeRows(['数据库恢复方案', '数据库备份资料']);
  const embedding = await localKnowledgeVectors(rows); embedding.vectors.delete(embedding.vectors.keys().next().value);
  embedding.embedQuery = async () => assert.fail('覆盖不完整时不能调用查询嵌入');
  const result = await hybridSearch(rows, '数据库', { embedding });
  assert.equal(result.mode, 'keyword'); assert.deepEqual(result.coverage, { eligible: 2, indexed: 1 });
  assert.ok(result.degradedReason); assert.ok(result.results.every(row => row.matchedBy.join() === 'keyword'));
  assert.equal(result.results.length, 2);
});

for (const [name, change] of [
  ['wrong model', record => { record.modelConfigId = 'another-model'; }],
  ['stale version', record => { record.sourceVersion++; }],
  ['wrong text hash', record => { record.inputTextHash = '0'.repeat(64); }],
  ['nonfinite vector', record => { record.vector = [NaN, Infinity]; }],
  ['wrong dimensions', record => { record.vector = [1, 0, 0]; }],
  ['zero vector', record => { record.vector = [0, 0]; }],
]) {
  test(`Android knowledge local hybrid rejects ${name} cache and keeps keyword evidence`, async () => {
    const { hybridSearch } = await localKnowledgeModules();
    const rows = localKnowledgeRows(['数据库恢复的真实正文']);
    const embedding = await localKnowledgeVectors(rows); change(embedding.vectors.values().next().value);
    embedding.embedQuery = async () => assert.fail('不兼容缓存不得触发查询向量');
    const result = await hybridSearch(rows, '数据库', { embedding });
    assert.equal(result.mode, 'keyword'); assert.deepEqual(result.coverage, { eligible: 1, indexed: 0 });
    assert.equal(result.results[0].row.text, rows[0].text);
  });
}

test('Android knowledge local query vector failure and invalid results degrade to keyword evidence', async () => {
  const { hybridSearch } = await localKnowledgeModules();
  const rows = localKnowledgeRows(['数据库恢复方案']);
  const embedding = await localKnowledgeVectors(rows);
  for (const embedQuery of [async () => { throw new Error('服务端敏感响应'); }, async () => [NaN, 1], async () => [1], async () => [0, 0]]) {
    const result = await hybridSearch(rows, '数据库', { embedding: { ...embedding, embedQuery } });
    assert.equal(result.mode, 'keyword'); assert.equal(result.coverage.indexed, 1);
    assert.equal(result.results[0].row.id, rows[0].id);
    assert.ok(result.degradedReason); assert.doesNotMatch(JSON.stringify(result), /敏感响应/);
  }
});

test('Android knowledge local semantic kinds must cover both document and memory before hybrid ranking', async () => {
  const { hybridSearch } = await localKnowledgeModules();
  const rows = localKnowledgeRows(['数据库恢复手册', '用户数据库保留偏好']); rows[1].kind = 'memory';
  const embedding = await localKnowledgeVectors(rows, { kinds: ['document'] });
  const result = await hybridSearch(rows, '数据库', { embedding });
  assert.equal(result.mode, 'keyword'); assert.deepEqual(result.coverage, { eligible: 2, indexed: 1 });
  assert.equal(result.results.length, 2);
  assert.equal((await hybridSearch([rows[0]], '数据库', { embedding })).mode, 'hybrid');
});

test('Android knowledge local hybrid honors cancellation before exposing semantic candidates', async () => {
  const { hybridSearch } = await localKnowledgeModules();
  const rows = localKnowledgeRows(['数据库恢复']); const controller = new AbortController();
  const embedding = await localKnowledgeVectors(rows);
  embedding.embedQuery = async () => { controller.abort(); return [1, 0]; };
  await assert.rejects(hybridSearch(rows, '数据库', { embedding, signal: controller.signal }), { code: 'KNOWLEDGE_CANCELLED' });
});

async function localKnowledgeModel(t, fetch, overrides = {}) {
  const { createModelClient } = await import(pathToFileURL(join(core, 'knowledge-model.mjs')));
  const events = [], ctx = {
    llm: { listConfigurableProviders: () => [{ provider: 'fixture', settingsNs: 'fixture', defaultBaseURL: 'https://fixture.invalid/v1' }] },
    settings: { describe: () => [{ ns: 'fixture', revision: 1, value: { baseURL: 'https://fixture.invalid/v1', apiKeyEnv: 'KNOWLEDGE_TEST_KEY' } }] },
    credentials: { resolve: async () => ({ value: 'private-knowledge-key' }) },
  };
  const client = createModelClient(ctx, { fetch, reportUsage: event => events.push(event), ...overrides });
  t.after(() => client.close());
  return { client, events, ctx, config: { enabled: true, configId: 'fixture-protocol-config', provider: 'fixture', model: 'fixture-embedding', dimensions: 2 } };
}

test('Android knowledge local embedding protocol orders indexed responses and records only actual known usage', async t => {
  let attempts = 0;
  const f = await localKnowledgeModel(t, async (url, request) => {
    attempts++; assert.equal(url, 'https://fixture.invalid/v1/embeddings'); assert.equal(request.redirect, 'error');
    assert.deepEqual(JSON.parse(request.body).input, ['正文甲', '正文乙']);
    return Response.json({ model: 'fixture-embedding', data: [{ index: 1, embedding: [0, 2] }, { index: 0, embedding: [3, 0] }], usage: { prompt_tokens: 8 } });
  });
  const result = await f.client.embed(['正文甲', '正文乙'], f.config);
  assert.deepEqual(result.map(row => Array.from(row)), [[1, 0], [0, 1]]); assert.equal(attempts, 1);
  const finish = f.events.find(row => row.phase === 'finish');
  assert.equal(finish.outcome, 'success'); assert.equal(finish.usageStatus, 'partial');
  assert.deepEqual(finish.requestUsage, { inputTokens: 8 });
  assert.doesNotMatch(JSON.stringify(f.events), /正文甲|正文乙|private-knowledge-key/);
});

for (const [name, data] of [
  ['duplicate indices', [{ index: 0, embedding: [1, 0] }, { index: 0, embedding: [0, 1] }]],
  ['missing response', [{ index: 0, embedding: [1, 0] }]],
  ['wrong vector dimensions', [{ index: 0, embedding: [1] }, { index: 1, embedding: [0, 1] }]],
  ['invalid vector values', [{ index: 0, embedding: [null, 0] }, { index: 1, embedding: [0, 1] }]],
]) {
  test(`Android knowledge local embedding protocol rejects ${name} without retrying invalid data`, async t => {
    let attempts = 0;
    const f = await localKnowledgeModel(t, async () => { attempts++; return Response.json({ data }); });
    await assert.rejects(f.client.embed(['正文甲', '正文乙'], f.config), { code: 'KNOWLEDGE_MODEL' });
    assert.equal(attempts, 1);
    assert.equal(f.events.filter(row => row.phase === 'start').length, 1);
    assert.equal(f.events.filter(row => row.phase === 'finish' && row.outcome === 'failure').length, 1);
  });
}

test('Android knowledge local embedding retries transient failure once and accounts both attempts', async t => {
  let attempts = 0;
  const f = await localKnowledgeModel(t, async () => ++attempts === 1 ? new Response('private service detail', { status: 503 })
    : Response.json({ data: [{ index: 0, embedding: [1, 0] }] }));
  assert.deepEqual(Array.from((await f.client.embed(['正文'], f.config))[0]), [1, 0]);
  assert.equal(attempts, 2); assert.equal(f.events.filter(row => row.phase === 'start').length, 2);
  assert.deepEqual(f.events.filter(row => row.phase === 'finish').map(row => row.outcome), ['failure', 'success']);
  assert.ok(f.events.every(row => row.usageStatus === 'unknown'));
  assert.doesNotMatch(JSON.stringify(f.events), /private service detail/);
});

test('Android knowledge local embedding rejects oversized batches and input before network calls', async t => {
  const f = await localKnowledgeModel(t, async () => assert.fail('超限请求不能发出网络调用'));
  await assert.rejects(f.client.embed(Array(17).fill('正文'), f.config), { code: 'KNOWLEDGE_INPUT' });
  await assert.rejects(f.client.embed(['中'.repeat(3000)], f.config), { code: 'KNOWLEDGE_LIMIT' });
  assert.equal(f.events.length, 0);
});

async function localKnowledgeTicket(f, bytes, { format = 'txt', title = '票据资料', operation = f.operation() } = {}) {
  const ticket = globalThis.crypto.randomUUID();
  await mkdir(join(f.root, 'inbox', ticket), { recursive: true });
  await writeFile(join(f.root, 'inbox', ticket, 'input'), bytes);
  await f.manage('ticket', { ...operation, ticket, bytes: bytes.length, hash: createHash('sha256').update(bytes).digest('hex'), format, title });
  return { ...operation, ticket };
}

async function localKnowledgeExport(f, data = {}) {
  const operation = f.operation(data), result = await f.manage('export', operation);
  const job = await f.store.wait(result.job.id);
  assert.equal(job.state, 'succeeded', JSON.stringify(job));
  const status = await f.manage('status', { operationId: operation.operationId });
  assert.ok(status.archive?.path);
  return { ...status, bytes: await readFile(join(f.root, status.archive.path)) };
}

test('Android knowledge local file ticket imports once and binds instance operation and integrity', async t => {
  const f = await localKnowledgeFixture(t), collection = await f.collection();
  const payload = await localKnowledgeTicket(f, Buffer.from('\ufeff中文文件🙂\r\n恢复说明'));
  await assert.rejects(f.manage('import', { ...payload, collectionId: collection.id }, { instance: 'another-app-instance' }), { code: 'KNOWLEDGE_INPUT' });
  await assert.rejects(f.manage('import', { ...payload, operationId: globalThis.crypto.randomUUID(), collectionId: collection.id }), { code: 'KNOWLEDGE_INPUT' });
  const result = await f.manage('import', { ...payload, collectionId: collection.id });
  assert.equal((await f.store.wait(result.job.id)).state, 'succeeded');
  const source = f.store.read().sources[0];
  assert.equal(await f.store.sourceText(source), '中文文件🙂\n恢复说明');
  const retry = await f.manage('import', { ...payload, collectionId: collection.id });
  assert.equal(retry.job.id, result.job.id);
  assert.equal(f.store.read().sources.length, 1);
});

test('Android knowledge local ticket rejects invalid UTF8 incorrect hash and tampering after registration', async t => {
  const f = await localKnowledgeFixture(t), collection = await f.collection();
  await assert.rejects(localKnowledgeTicket(f, Buffer.from([0xc3, 0x28])), { code: 'KNOWLEDGE_INPUT' });
  const ticket = globalThis.crypto.randomUUID();
  await mkdir(join(f.root, 'inbox', ticket), { recursive: true });
  await writeFile(join(f.root, 'inbox', ticket, 'input'), '真实文件');
  await assert.rejects(f.manage('ticket', f.operation({ ticket, bytes: Buffer.byteLength('真实文件'), hash: '0'.repeat(64), format: 'txt' })), { code: 'KNOWLEDGE_INPUT' });
  const good = await localKnowledgeTicket(f, Buffer.from('登记时的正文'));
  await writeFile(join(f.root, 'inbox', good.ticket, 'input'), '被篡改的正文');
  await assert.rejects(f.manage('import', { ...good, collectionId: collection.id }), { code: 'KNOWLEDGE_INPUT' });
  assert.equal(f.store.read().sources.length, 0);
});

test('Android knowledge local ticket rejects path traversal symbolic links and ambiguous text plus file input', async t => {
  const f = await localKnowledgeFixture(t), collection = await f.collection();
  await assert.rejects(f.manage('ticket', f.operation({ ticket: '../escape', bytes: 1, hash: '0'.repeat(64), format: 'txt' })), { code: 'KNOWLEDGE_INPUT' });
  const ticket = globalThis.crypto.randomUUID(), target = join(f.dir, 'outside.txt');
  await writeFile(target, '外部文件'); await mkdir(join(f.root, 'inbox', ticket));
  await symlink(target, join(f.root, 'inbox', ticket, 'input'));
  await assert.rejects(f.manage('ticket', f.operation({ ticket, bytes: Buffer.byteLength('外部文件'), hash: createHash('sha256').update('外部文件').digest('hex'), format: 'txt' })),
    error => ['KNOWLEDGE_INPUT', 'ELOOP'].includes(error.code));
  const good = await localKnowledgeTicket(f, Buffer.from('正常资料'));
  await assert.rejects(f.manage('import', { ...good, text: '有歧义的正文', collectionId: collection.id }), { code: 'KNOWLEDGE_INPUT' });
  assert.equal(f.store.read().sources.length, 0); assert.equal(await readFile(target, 'utf8'), '外部文件');
});

test('Android knowledge local durable catalog failure preserves snapshot and leaves write queue usable', async t => {
  const f = await localKnowledgeFixture(t), before = f.store.read();
  const save = f.store.domain.global.set.bind(f.store.domain.global);
  let fail = true;
  f.store.domain.global.set = async next => { if (fail) throw new Error('private disk failure'); return save(next); };
  await assert.rejects(f.enable(), { code: 'KNOWLEDGE_WRITE' });
  assert.deepEqual(f.store.read(), before);
  fail = false;
  await f.enable(); assert.equal(f.store.read().enabled, true);
  assert.equal(JSON.parse(await readFile(join(f.dir, 'knowledge', 'state.json'), 'utf8')).global.enabled, true);
});

test('Android knowledge local failed replacement publication retains old version and cleans prepared files', async t => {
  const f = await localKnowledgeFixture(t), source = await f.import('正式旧版正文');
  const save = f.store.domain.global.set.bind(f.store.domain.global);
  let rejected = false;
  f.store.domain.global.set = async next => {
    if (next.sources.some(row => row.id === source.id && row.version > source.version)) { rejected = true; throw new Error('private publication failure'); }
    return save(next);
  };
  const result = await f.manage('update', f.operation({ sourceId: source.id, expectedRevision: source.revision, patch: { text: '不可发布的新版正文' } }));
  const job = await f.store.wait(result.job.id);
  assert.equal(rejected, true); assert.equal(job.state, 'failed'); assert.equal(job.errorCode, 'KNOWLEDGE_WRITE');
  const current = f.store.read().sources.find(row => row.id === source.id);
  assert.equal(current.version, source.version); assert.equal(current.revision, source.revision);
  assert.equal(await f.store.sourceText(current), '正式旧版正文');
  assert.deepEqual(await readdir(join(f.root, 'sources', source.id)), [String(source.version)]);
});

test('Android knowledge local successful operation and source remain consistent after reopening durable storage', async t => {
  const f = await localKnowledgeFixture(t), collection = await f.collection();
  const operation = f.operation({ text: '跨重启仍可核对的正文🙂', title: '重启资料', format: 'txt', collectionId: collection.id });
  const accepted = await f.manage('import', operation); await f.store.idle();
  const before = f.store.read(); await f.store.close();
  f.store = await f.openKnowledge(f.ctx, { root: f.root }); await f.store.ready;
  assert.deepEqual(f.store.read(), before);
  const retry = await f.manage('import', operation);
  assert.equal(retry.job.id, accepted.job.id); assert.equal(f.store.read().sources.length, 1);
  assert.equal(await f.store.sourceText(f.store.read().sources[0]), operation.text);
});

test('Android knowledge local keyword rebuild preserves refs while chunks rebuild invalidates their version', async t => {
  const f = await localKnowledgeFixture(t), source = await f.import('关键词重建保留原文引用🙂');
  await f.enable(); await f.start(); const agent = await f.agent();
  const hit = (await f.execute(agent, 'knowledge_search', { query: '重建' })).results[0];
  const keyword = await f.manage('rebuild', f.operation({ kind: 'keyword', sourceId: source.id, expectedRevision: source.revision }));
  assert.equal((await f.store.wait(keyword.job.id)).state, 'succeeded');
  assert.equal((await f.execute(agent, 'knowledge_read', { ref: hit.ref })).text, '关键词重建保留原文引用🙂');
  const current = f.store.read().sources.find(row => row.id === source.id);
  const chunks = await f.manage('rebuild', f.operation({ kind: 'chunks', sourceId: source.id, expectedRevision: current.revision }));
  assert.equal((await f.store.wait(chunks.job.id)).state, 'succeeded');
  await assert.rejects(f.execute(agent, 'knowledge_read', { ref: hit.ref }), { code: 'KNOWLEDGE_VERSION_CHANGED' });
  const newest = f.store.read().sources.find(row => row.id === source.id);
  assert.ok(newest.version > current.version); assert.equal(await f.store.sourceText(newest), '关键词重建保留原文引用🙂');
});

test('Android knowledge local export includes verified document files and omits memory secrets and derived indexes', async t => {
  const f = await localKnowledgeFixture(t); await f.import('导出的资料原文🙂');
  const exported = await localKnowledgeExport(f);
  assert.equal(exported.archive.bytes, exported.bytes.length);
  assert.equal(exported.archive.hash, createHash('sha256').update(exported.bytes).digest('hex'));
  const { gunzipSync } = await import('node:zlib');
  const archive = JSON.parse(gunzipSync(exported.bytes));
  assert.equal(archive.format, 'dshx-knowledge'); assert.equal(archive.version, 1);
  assert.equal(archive.sources.length, 1); assert.equal(archive.entries.length, 3);
  assert.doesNotMatch(JSON.stringify(archive), /memory\.json|apiKey|credentials|indexes\//);
  for (const entry of archive.entries) {
    const bytes = Buffer.from(entry.data, 'base64');
    assert.equal(entry.bytes, bytes.length); assert.equal(entry.hash, createHash('sha256').update(bytes).digest('hex'));
  }
});

for (const [name, corrupt] of [
  ['path traversal', archive => { archive.entries[0].path = '../outside'; }],
  ['absolute path', archive => { archive.entries[0].path = '/tmp/outside'; }],
  ['wrong hash', archive => { archive.entries[0].hash = '0'.repeat(64); }],
  ['wrong bytes', archive => { archive.entries[0].bytes++; }],
  ['duplicate entry', archive => { archive.entries.push(structuredClone(archive.entries[0])); }],
  ['missing entry', archive => { archive.entries.pop(); }],
  ['unknown format version', archive => { archive.version = 99; }],
]) {
  test(`Android knowledge local restore rejects archive ${name} before changing live sources`, async t => {
    const f = await localKnowledgeFixture(t); await f.import('当前库必须保留的资料');
    const exported = await localKnowledgeExport(f), { gunzipSync } = await import('node:zlib');
    const archive = JSON.parse(gunzipSync(exported.bytes)); corrupt(archive);
    const before = f.store.read().sources;
    const payload = await localKnowledgeTicket(f, gzipSync(Buffer.from(JSON.stringify(archive))), { format: 'archive' });
    const accepted = await f.manage('restore', payload), job = await f.store.wait(accepted.job.id);
    assert.equal(job.state, 'failed'); assert.equal(job.effect, 'none');
    assert.ok(['KNOWLEDGE_INPUT', 'KNOWLEDGE_READ'].includes(job.errorCode));
    assert.deepEqual(f.store.read().sources, before);
    assert.equal(await f.store.sourceText(before[0]), '当前库必须保留的资料');
  });
}

test('Android knowledge local merge restore skips equal content adds same title conflicts and survives retry', async t => {
  const f = await localKnowledgeFixture(t), first = await f.import('归档中的旧版正文', { title: '同名资料' });
  const exported = await localKnowledgeExport(f);
  await f.manage('update', f.operation({ sourceId: first.id, expectedRevision: first.revision, patch: { text: '现有库的新正文' } }));
  await f.store.idle();
  const payload = await localKnowledgeTicket(f, exported.bytes, { format: 'archive' });
  const accepted = await f.manage('restore', payload), job = await f.store.wait(accepted.job.id);
  assert.equal(job.state, 'succeeded', JSON.stringify(job)); assert.equal(job.effect, 'restored');
  const status = await f.manage('status', { operationId: payload.operationId });
  assert.equal(status.restored, 1); assert.equal(status.skipped, 0); assert.deepEqual(status.conflicts, ['同名资料']);
  const sources = f.store.read().sources.filter(row => row.state === 'active');
  assert.equal(sources.length, 2); assert.equal(new Set(sources.map(row => row.id)).size, 2);
  assert.deepEqual(new Set(await Promise.all(sources.map(row => f.store.sourceText(row)))), new Set(['归档中的旧版正文', '现有库的新正文']));
  assert.equal((await f.manage('restore', payload)).job.id, accepted.job.id);
  const again = await localKnowledgeTicket(f, exported.bytes, { format: 'archive' });
  const repeated = await f.manage('restore', again); assert.equal((await f.store.wait(repeated.job.id)).state, 'succeeded');
  const repeatedStatus = await f.manage('status', { operationId: again.operationId });
  assert.equal(repeatedStatus.restored, 0); assert.equal(repeatedStatus.skipped, 1);
  assert.equal(f.store.read().sources.filter(row => row.state === 'active').length, 2);
  for (const row of f.store.read().sources.filter(row => row.state === 'active')) localKnowledgeCheckChunks(await f.store.sourceText(row), await f.store.sourceChunks(row), row.id, row.version);
});

test('Android knowledge local real Cordis profile registers three tools with valid schemas and execution', { timeout: 35000 }, async () => {
  const dir = await mkdtemp(join(directory, 'local-knowledge-profile-'));
  const yaml = require('js-yaml'), { entryListSchema } = await load('@deepseek-ai/cordis-plugin-include');
  const patches = yaml.load(await readFile(new URL('./android.patch.yml', import.meta.url), 'utf8'), { schema: entryListSchema });
  const entries = patches.flatMap(row => row.insert ?? []).filter(row => row.id === 'dshx-android-knowledge');
  assert.equal(entries.length, 1); assert.equal(entries[0].name, 'file:@CORE@/knowledge.mjs');
  const run = async () => {
    const assert = (await import('node:assert/strict')).default;
    const { createRequire } = await import('node:module'), { pathToFileURL } = await import('node:url');
    const { join } = await import('node:path'), { mkdirSync, writeFileSync } = await import('node:fs');
    const core = process.env.DSHX_CORE, dir = process.env.DSHX_KNOWLEDGE_TEST_DIR;
    const require = createRequire(join(core, 'lib/node_modules/package.json'));
    const load = name => import(pathToFileURL(require.resolve(name)));
    const { packageManager } = await import(pathToFileURL(join(core, 'plugins.mjs')));
    const { runProfile } = await load('@deepseek-ai/dsh/profile-boot');
    const { loadProfileDirectory } = await load('@deepseek-ai/dsh-app-boot');
    const { createLaunchEnvironmentSnapshot } = await load('@deepseek-ai/dsh-launch-environment');
    const profile = join(dir, 'profile'); mkdirSync(profile);
    const names = [
      ['timer', '@deepseek-ai/cordis-plugin-timer'], ['hmr', '@deepseek-ai/dsh-hmr', { root: [] }],
      ['plugin-manager', '@deepseek-ai/dsh-plugin-manager'], ['storage', '@deepseek-ai/dsh-storage'],
      ['storage-json', '@deepseek-ai/dsh-storage-json', { root: join(dir, 'storages') }],
      ['storage-domain', '@deepseek-ai/dsh-storage-domain', { backend: 'json' }],
      ['system-prompt', '@deepseek-ai/dsh-system-prompt'], ['tools', '@deepseek-ai/dsh-tools'],
      ['llm', '@deepseek-ai/dsh-llm'], ['agents', '@deepseek-ai/dsh-agent'], ['sessions', '@deepseek-ai/dsh-session'],
      ['session-projection', '@deepseek-ai/dsh-session-projection'], ['fs', '@deepseek-ai/dsh-fs'],
      ['working-directory', '@deepseek-ai/dsh-working-directory', { defaultDirectory: dir }],
      ['session-persistence', '@deepseek-ai/dsh-session-persistence-jsonl', { root: join(dir, 'sessions'), compression: 'none' }],
    ];
    const entries = names.map(([id, name, config]) => ({ id, name: pathToFileURL(require.resolve(name)).href, ...(config ? { config } : {}) }));
    entries.push({ id: 'knowledge', name: pathToFileURL(join(core, 'knowledge.mjs')).href });
    writeFileSync(join(profile, 'package.json'), '{"private":true,"dsh":{"profile":{"bundles":[]}}}');
    writeFileSync(join(profile, 'cordis.patch.yml'), JSON.stringify([{ insert: entries }]));
    const anchor = require.resolve('@deepseek-ai/dsh/package.json'), invocation = packageManager(core);
    const app = await runProfile({ environment: createLaunchEnvironmentSnapshot([]), profile: 'knowledge-local-test', patchFiles: [], args: [],
      resolvedProfile: { profile: loadProfileDirectory('dsh', profile, anchor), installAnchor: anchor },
      packageManager: { ...invocation, env: { ...invocation.env, npm_config_offline: 'true' } },
    });
    try {
      const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
      assert.equal(typeof policyHost.knowledge, 'function');
      const status = await policyHost.knowledge('status');
      console.log('KNOWLEDGE_PROFILE_STATUS', JSON.stringify({ ready: status.ready, enabled: status.enabled, message: status.message ?? null }));
      const knowledgeModule = await import(pathToFileURL(join(core, 'knowledge.mjs')));
      console.log('KNOWLEDGE_PROFILE_INJECT', JSON.stringify(knowledgeModule.inject));
      assert.equal(status.ready, true, JSON.stringify(status)); assert.equal(status.enabled, false);
      const list = app.ctx.tools.get('knowledge_list'), search = app.ctx.tools.get('knowledge_search'), read = app.ctx.tools.get('knowledge_read');
      console.log('KNOWLEDGE_PROFILE_SCHEMAS', JSON.stringify([list, search, read].map(tool => ({ name: tool?.name, parameters: tool?.parameters, output: tool?.output?.schema }))));
      for (const tool of [list, search, read]) {
        assert.ok(tool); assert.equal(tool.parameters.type, 'object'); assert.equal(tool.output.schema.type, 'object');
        assert.equal(typeof tool.execute, 'function');
        const validArgs = tool.name === 'knowledge_search' ? { query: '资料' } : tool.name === 'knowledge_read' ? { ref: 'kb:document:sample:v1:text' } : {};
        console.log('KNOWLEDGE_PROFILE_CONCURRENCY', JSON.stringify({ name: tool.name, valid: tool.isConcurrencySafe(validArgs), empty: tool.isConcurrencySafe({}) }));
        assert.equal(tool.isConcurrencySafe(validArgs), true);
        assert.doesNotMatch(JSON.stringify(tool.parameters), /workspace|operationId|sourceId/);
      }
      assert.ok(search.parameters.required.includes('query')); assert.ok(read.parameters.required.includes('ref'));
      await assert.rejects(search.execute({}, {}), error => {
        console.log('KNOWLEDGE_PROFILE_VIOLATIONS', JSON.stringify({ name: search.name, code: error.code, violations: error.violations }));
        return error.code === 'INVALID_ARGS';
      });
      await assert.rejects(search.execute({ query: '资料', kind: 'unknown' }, {}), { code: 'INVALID_ARGS' });
      await assert.rejects(read.execute({}, {}), error => {
        console.log('KNOWLEDGE_PROFILE_VIOLATIONS', JSON.stringify({ name: read.name, code: error.code, violations: error.violations }));
        return error.code === 'INVALID_ARGS';
      });
      await policyHost.knowledge('settings', { operationId: crypto.randomUUID(), createdAt: Date.now(), expectedRevision: status.settingsRevision, settings: { enabled: true } });
      const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
      const session = app.ctx.sessions.create(crypto.randomUUID(), { meta: { cwd: dir } });
      session.append('user/message', createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: '查阅知识库' }] }), { surfaceOp: 'append' });
      session.requestHeader = () => ({ config: { provider: 'fixture', model: 'fixture', maxTokens: 1024 } });
      app.ctx.llm.resolveModelInfo = async () => ({ context: { contextWindow: 32000 } });
      const agent = { id: session.id, session, ctx: app.ctx };
      const detach = await app.ctx.agents.register(agent);
      try {
        const exec = { agent, signal: new AbortController().signal };
        const listed = await list.execute({}, exec); assert.deepEqual(listed.entries, []);
        const found = await search.execute({ query: '不存在的知识' }, exec); assert.equal(found.mode, 'keyword'); assert.deepEqual(found.results, []);
        const rendered = search.output.render({ query: '不存在的知识' }, found);
        assert.deepEqual(JSON.parse(rendered[0].text), found);
      } finally { await detach(); }
      console.log('Local knowledge Cordis profile passed');
    } finally { await app.shutdown.shutdown(0); }
  };
  const child = spawn(process.execPath, ['--expose-internals', '--preserve-symlinks', '--preserve-symlinks-main', '--import', join(core, 'compat.mjs'), '--input-type=module', '-e', '(' + run.toString() + ')().catch(error => { console.error(error); process.exitCode = 1; })'], {
    env: { ...process.env, DSHX_KNOWLEDGE_TEST_DIR: dir, DSH_HOME: join(dir, 'home') }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; }); child.stderr.on('data', chunk => { output += chunk; });
  const timeout = setTimeout(() => child.kill('SIGKILL'), 25000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    for (const line of output.split('\n')) if (line.startsWith('KNOWLEDGE_PROFILE_')) console.log(line);
    assert.equal(code, 0, output); assert.match(output, /Local knowledge Cordis profile passed/);
  } finally { clearTimeout(timeout); if (child.exitCode === null) child.kill('SIGKILL'); }
});

test('Android knowledge local explicit job cancellation before publication retains the previous version', async t => {
  const f = await localKnowledgeFixture(t), source = await f.import('取消前的正式正文');
  const prepare = f.store.prepare.bind(f.store), gate = Promise.withResolvers(); let started = false;
  f.store.prepare = async (...args) => { started = true; await gate.promise; return prepare(...args); };
  const accepted = await f.manage('update', f.operation({ sourceId: source.id, expectedRevision: source.revision, patch: { text: '不得发布的取消正文' } }));
  await localMemoryWait(() => started, '正文准备任务未开始');
  try { await f.manage('cancel', f.operation({ jobId: accepted.job.id })); }
  finally { gate.resolve(); }
  const job = await f.store.wait(accepted.job.id);
  assert.equal(job.state, 'cancelled'); assert.equal(job.effect, 'none');
  const current = f.store.read().sources.find(row => row.id === source.id);
  assert.equal(current.version, source.version); assert.equal(current.revision, source.revision);
  assert.equal(await f.store.sourceText(current), '取消前的正式正文');
});

test('Android knowledge local cancellation after source publication stops processing and preserves the published effect', async t => {
  const gate = Promise.withResolvers(); let started = false;
  const f = await localKnowledgeFixture(t, { process: async () => { started = true; await gate.promise; return { complete: true }; } });
  const collection = await f.collection();
  await f.manage('settings', f.operation({ expectedRevision: f.store.read().settingsRevision, settings: { enabled: true,
    embedding: { enabled: true, configId: 'fixture-embedding', provider: 'fixture', model: 'fixture', dimensions: 2 } } }));
  const accepted = await f.manage('import', f.operation({ text: '已经发布的正式资料', format: 'txt', title: '发布后取消', collectionId: collection.id }));
  await localMemoryWait(() => started, '发布后的模型加工未开始');
  const source = f.store.read().sources.find(row => row.title === '发布后取消');
  assert.ok(source); assert.equal(await f.store.sourceText(source), '已经发布的正式资料');
  try { await f.manage('cancel', f.operation({ jobId: accepted.job.id })); }
  finally { gate.resolve(); }
  const job = await f.store.wait(accepted.job.id);
  assert.equal(job.state, 'cancelled'); assert.equal(job.effect, 'published');
  assert.equal(await f.store.sourceText(f.store.read().sources.find(row => row.id === source.id)), '已经发布的正式资料');
  assert.equal((await f.manage('status', { operationId: accepted.operation.operationId })).operation.effect, 'published');
});

test('Android knowledge local retries accepted operations within 24 hours and refuses the same expired request', async t => {
  const f = await localKnowledgeFixture(t), collection = await f.collection();
  const data = f.operation({ text: '完整窗口内的幂等资料', title: '有效窗口', format: 'txt', collectionId: collection.id });
  const accepted = await f.manage('import', data); await f.store.idle();
  const before = f.store.read(), now = Date.now();
  const clock = t.mock.method(Date, 'now', () => now + 23 * 60 * 60 * 1000);
  try {
    assert.equal((await f.manage('import', data)).job.id, accepted.job.id);
    clock.mock.mockImplementation(() => now + 24 * 60 * 60 * 1000 + 1000);
    await assert.rejects(f.manage('import', data), { code: 'KNOWLEDGE_OPERATION_EXPIRED' });
    assert.deepEqual(f.store.read(), before);
  } finally { clock.mock.restore(); }
});

test('Android knowledge local restore refuses corrupt gzip and requires an explicit allowed workspace mapping', async t => {
  const f = await localKnowledgeFixture(t), collection = await f.collection('旧工作区集合', { scope: { kind: 'workspace', workspace: f.workspaces[1] } });
  await f.import('映射后才可恢复的工作区资料', { collectionId: collection.id });
  const exported = await localKnowledgeExport(f), sources = f.store.read().sources;
  const broken = await localKnowledgeTicket(f, Buffer.from('not a gzip archive'), { format: 'archive' });
  const bad = await f.manage('restore', broken); assert.equal((await f.store.wait(bad.job.id)).state, 'failed');
  assert.deepEqual(f.store.read().sources, sources);
  const unmapped = await localKnowledgeTicket(f, exported.bytes, { format: 'archive' });
  const rejected = await f.manage('restore', unmapped);
  const failed = await f.store.wait(rejected.job.id); assert.equal(failed.state, 'failed'); assert.equal(failed.errorCode, 'KNOWLEDGE_INPUT');
  assert.deepEqual(f.store.read().sources, sources);
  const mapped = await localKnowledgeTicket(f, exported.bytes, { format: 'archive' });
  const accepted = await f.manage('restore', { ...mapped, workspaceMap: { [f.workspaces[1]]: 'user' } });
  assert.equal((await f.store.wait(accepted.job.id)).state, 'succeeded');
  const restored = f.store.read().sources.find(row => row.scope.kind === 'user');
  assert.ok(restored); assert.notEqual(restored.id, sources[0].id);
  assert.equal(await f.store.sourceText(restored), '映射后才可恢复的工作区资料');
});

// 仅替换模型服务；存储、任务、批次文件、切换和运行时检索均使用实际实现。
async function localKnowledgeRuntime(t) {
  const f = await localKnowledgeFixture(t);
  const { createKnowledgeSearch, loadVectorGeneration, processKnowledgeJob } = await import(pathToFileURL(join(core, 'knowledge-search.mjs')));
  f.loadVectorGeneration = loadVectorGeneration; f.processKnowledgeJob = processKnowledgeJob;
  f.model = { calls: [], summaries: [], fail: false, summaryText: '恢复前先停止服务，必须保留数据库和备份。' };
  f.ctx.llm = {
    listConfigurableProviders: () => [{ provider: 'fixture', settingsNs: 'knowledge-runtime', defaultBaseURL: 'https://fixture.invalid/v1' }],
    resolveModelInfo: async () => ({ provider: 'fixture', id: 'fixture-summary', context: { contextWindow: 32000 } }),
    prepareCall: async (config, signal, adjust) => {
      adjust?.({}, { provider: config.provider, id: config.model, context: { contextWindow: 32000 } });
      return { config, stream: async function* (request) {
        f.model.summaries.push(request);
        await f.model.onSummary?.(request);
        request.signal.throwIfAborted();
        yield { type: 'text-delta', text: f.model.summaryText };
        yield { type: 'finish', reason: { kind: 'stop' } };
      } };
    },
  };
  f.ctx.settings = { describe: () => [{ ns: 'knowledge-runtime', revision: 1,
    value: { baseURL: 'https://fixture.invalid/v1', apiKeyEnv: 'KNOWLEDGE_RUNTIME_TEST_KEY' } }] };
  f.ctx.credentials = { resolve: async () => ({ value: 'private-runtime-fixture-key' }) };
  for (const name of ['llm', 'settings', 'credentials']) f.ctx.provide(name, f.ctx[name]);
  f.runtimeOptions = { usageDirectory: f.root, fetch: async (url, request) => {
    const body = JSON.parse(request.body); f.model.calls.push({ url, inputs: body.input });
    if (f.model.onEmbed) return f.model.onEmbed(body, request);
    if (f.model.fail) return new Response('private model failure', { status: 400 });
    return Response.json({ model: body.model, data: body.input.map((text, index) => ({ index, embedding: text.includes('通勤') ? [0, 1] : [1, 0] })) });
  } };
  f.newRuntime = () => createKnowledgeSearch(f.ctx, f.store, f.runtimeOptions);
  f.runtime = f.newRuntime();
  f.store.options.process = (job, store) => f.runtime.process(job, store);
  t.after(async () => { await f.runtime.close(); });
  f.rows = async () => {
    const rows = [];
    for (const source of f.store.read().sources.filter(row => row.state === 'active')) {
      for (const chunk of await f.store.sourceChunks(source)) rows.push({ ...chunk, id: `${source.id}:${chunk.id}`, chunkId: chunk.id,
        kind: 'document', title: source.title, ref: `kb:document:${source.id}:v${source.version}:${chunk.id}` });
    }
    return rows;
  };
  f.configure = (settings = {}) => f.manage('settings', f.operation({ expectedRevision: f.store.read().settingsRevision,
    settings: { enabled: true, ...settings } }));
  f.embedding = (extra = {}) => ({ enabled: true, configId: 'runtime-embedding-v1', provider: 'fixture', model: 'fixture-embedding',
    dimensions: 2, normalized: true, kinds: ['document'], batchSize: 1, ...extra });
  f.summary = (extra = {}) => ({ enabled: true, configId: 'runtime-summary-v1', provider: 'fixture', model: 'fixture-summary', promptVersion: 1, ...extra });
  f.rebuild = async (kind, extra = {}) => {
    const accepted = await f.manage('rebuild', f.operation({ kind, ...extra }));
    const job = await f.store.wait(accepted.job.id);
    return { accepted, job, status: await f.manage('status', { operationId: accepted.operation.operationId }) };
  };
  f.reopen = async () => {
    await f.runtime.close(); await f.store.close();
    f.store = await f.openKnowledge(f.ctx, { root: f.root, process: (job, store) => f.runtime.process(job, store) });
    f.runtime = f.newRuntime();
  };
  return f;
}

function localKnowledgeReload(f) {
  const run = async () => {
    const { createRequire } = await import('node:module'), { pathToFileURL } = await import('node:url'), { join } = await import('node:path');
    const core = process.env.DSHX_CORE, require = createRequire(join(core, 'lib/node_modules/package.json'));
    const load = name => import(pathToFileURL(require.resolve(name)));
    const { Context } = await load('@deepseek-ai/cordis'), ctx = new Context(); let store;
    try {
      await ctx.plugin((await load('@deepseek-ai/dsh-storage')).default);
      await ctx.plugin(await load('@deepseek-ai/dsh-storage-json'), { root: process.env.DSHX_KNOWLEDGE_CATALOG_DIR });
      await ctx.plugin(await load('@deepseek-ai/dsh-storage-domain'), { backend: 'json' });
      const { openKnowledge } = await import(pathToFileURL(join(core, 'knowledge-store.mjs')));
      const { loadVectorGeneration } = await import(pathToFileURL(join(core, 'knowledge-search.mjs')));
      store = await openKnowledge(ctx, { root: process.env.DSHX_KNOWLEDGE_ROOT });
      const loaded = await loadVectorGeneration(store, store.read().embedding);
      console.log(JSON.stringify({ generation: loaded?.generation, count: loaded?.vectors.size,
        finite: [...loaded?.vectors.values() ?? []].every(row => Array.from(row.vector).every(Number.isFinite)) }));
    } catch (error) { console.error(JSON.stringify({ code: error.code, frames: error.stack?.split('\n').slice(1, 4) })); process.exitCode = 1; }
    finally { await store?.close(); await ctx.fiber.dispose(); }
  };
  return spawnSync(process.execPath, ['--input-type=module', '-e', '(' + run.toString() + ')()'], {
    env: { ...process.env, DSHX_KNOWLEDGE_CATALOG_DIR: f.dir, DSHX_KNOWLEDGE_ROOT: f.root }, encoding: 'utf8', timeout: 15000,
  });
}

test('Android knowledge local runtime rebuild writes a complete generation readable by fresh process and hybrid search', async t => {
  const f = await localKnowledgeRuntime(t);
  await f.import('恢复数据库必须保留备份。', { title: '恢复手册' });
  await f.import('每天骑车通勤。', { title: '通勤说明' });
  await f.configure({ embedding: f.embedding() });
  const built = await f.rebuild('semantic');
  assert.equal(built.job.state, 'succeeded', JSON.stringify(built.job)); assert.equal(built.job.effect, 'switched');
  const snapshot = f.store.read(), generation = snapshot.semanticIndex.generation;
  assert.equal(snapshot.semanticGeneration, generation);
  const manifest = JSON.parse(await readFile(join(f.root, 'indexes', generation, 'manifest.json'), 'utf8'));
  assert.equal(manifest.complete, true); assert.equal(manifest.count, (await f.rows()).length);
  for (const [name, info] of Object.entries(manifest.files)) {
    const bytes = await readFile(join(f.root, 'indexes', generation, name));
    assert.equal(bytes.length, info.bytes); assert.equal(createHash('sha256').update(bytes).digest('hex'), info.hash);
  }
  const found = await f.runtime.search(await f.rows(), '数据库恢复');
  assert.equal(found.mode, 'hybrid'); assert.deepEqual(found.coverage, { eligible: 2, indexed: 2 });
  assert.ok(found.results[0].matchedBy.includes('semantic'));
  const restarted = localKnowledgeReload(f);
  assert.equal(restarted.status, 0, restarted.stderr);
  assert.deepEqual(JSON.parse(restarted.stdout.trim()), { generation, count: 2, finite: true });
  await f.reopen();
  assert.equal((await f.runtime.search(await f.rows(), '数据库恢复')).mode, 'hybrid');
});

test('Android knowledge local runtime updated document falls back to new keyword text without old vectors', async t => {
  const f = await localKnowledgeRuntime(t), source = await f.import('旧版恢复要求保留数据库');
  await f.configure({ embedding: f.embedding() });
  assert.equal((await f.rebuild('semantic')).job.state, 'succeeded');
  const generation = f.store.read().semanticGeneration;
  f.model.fail = true;
  const accepted = await f.manage('update', f.operation({ sourceId: source.id, expectedRevision: source.revision, patch: { text: '新版恢复要求保留快照🙂' } }));
  const job = await f.store.wait(accepted.job.id);
  assert.equal(job.effect, 'published'); assert.equal(job.state, 'waitingRetry');
  assert.equal(f.store.read().semanticGeneration, generation);
  const rows = await f.rows(), found = await f.runtime.search(rows, '新版恢复');
  assert.equal(found.mode, 'keyword'); assert.deepEqual(found.coverage, { eligible: 1, indexed: 0 });
  assert.equal(found.results[0].row.text, '新版恢复要求保留快照🙂');
  assert.ok(found.results.every(hit => hit.row.sourceVersion > source.version));
  assert.ok(found.results.every(hit => !hit.matchedBy.includes('semantic')));
});

test('Android knowledge local runtime deleted document never returns from a previously complete generation', async t => {
  const f = await localKnowledgeRuntime(t), removed = await f.import('独特秘密资料只能属于已删文档', { title: '独特秘密' });
  await f.import('保留的恢复手册', { title: '恢复手册' });
  await f.configure({ embedding: f.embedding() });
  assert.equal((await f.rebuild('semantic')).job.state, 'succeeded');
  const accepted = await f.manage('remove', f.operation({ sourceId: removed.id, expectedRevision: removed.revision }));
  assert.equal((await f.store.wait(accepted.job.id)).state, 'succeeded');
  const result = await f.runtime.search(await f.rows(), '独特秘密');
  assert.equal(result.coverage.eligible, 1);
  assert.ok(result.results.every(hit => hit.row.sourceId !== removed.id && !hit.row.text.includes('独特秘密')));
});

test('Android knowledge local runtime incomplete semantic rebuild keeps the prior generation and does not report full coverage', async t => {
  const f = await localKnowledgeRuntime(t);
  await f.import('第一份恢复资料'); await f.import('第二份恢复资料');
  await f.configure({ embedding: f.embedding() });
  assert.equal((await f.rebuild('semantic')).job.state, 'succeeded');
  const previous = f.store.read().semanticGeneration;
  await f.configure({ embedding: f.embedding({ configId: 'runtime-embedding-v2' }) });
  let calls = 0;
  f.model.onEmbed = async body => ++calls === 1 ? Response.json({ model: body.model, data: body.input.map((_, index) => ({ index, embedding: [1, 0] })) })
    : new Response('interrupted second batch', { status: 400 });
  const rebuilt = await f.rebuild('semantic');
  assert.equal(rebuilt.job.state, 'waitingRetry'); assert.equal(rebuilt.job.effect, 'none');
  assert.equal(f.store.read().semanticGeneration, previous); assert.equal(calls, 2);
  const result = await f.runtime.search(await f.rows(), '恢复资料');
  assert.equal(result.mode, 'keyword'); assert.equal(result.coverage.indexed, 0);
  assert.equal(result.results.length, 2);
  const unfinished = (await readdir(join(f.root, 'indexes'))).filter(name => name.startsWith('g-') && name !== previous);
  assert.equal(unfinished.length, 1);
  await assert.rejects(readFile(join(f.root, 'indexes', unfinished[0], 'manifest.json')), { code: 'ENOENT' });
  await f.reopen();
  assert.equal(f.store.read().semanticGeneration, previous);
  assert.equal((await f.runtime.search(await f.rows(), '恢复资料')).mode, 'keyword');
});

for (const filename of ['records.json', 'vectors.f32']) {
  test(`Android knowledge local runtime fresh process rejects corrupted generation ${filename} and preserves source text`, async t => {
    const f = await localKnowledgeRuntime(t), source = await f.import('索引损坏不能损坏正式原文');
    await f.configure({ embedding: f.embedding() });
    assert.equal((await f.rebuild('semantic')).job.state, 'succeeded');
    const generation = f.store.read().semanticGeneration, path = join(f.root, 'indexes', generation, filename);
    const bytes = await readFile(path); bytes[0] ^= 1; await writeFile(path, bytes);
    const restarted = localKnowledgeReload(f);
    assert.equal(restarted.status, 1); assert.equal(JSON.parse(restarted.stderr.trim()).code, 'KNOWLEDGE_INDEX');
    assert.equal(await f.store.sourceText(f.store.read().sources.find(row => row.id === source.id)), '索引损坏不能损坏正式原文');
    assert.equal(f.store.read().semanticGeneration, generation);
  });
}

test('Android knowledge local runtime failed generation switch preserves the previous durable index pointer', async t => {
  const f = await localKnowledgeRuntime(t); await f.import('切换失败后仍保留原文和旧批次');
  await f.configure({ embedding: f.embedding() }); assert.equal((await f.rebuild('semantic')).job.state, 'succeeded');
  const previous = f.store.read().semanticGeneration;
  await f.configure({ embedding: f.embedding({ configId: 'runtime-switch-v2' }) });
  const save = f.store.domain.global.set.bind(f.store.domain.global); let refused = false;
  f.store.domain.global.set = async next => {
    if (next.semanticGeneration && next.semanticGeneration !== previous) { refused = true; throw new Error('fixture switch failure'); }
    return save(next);
  };
  const result = await f.rebuild('semantic');
  assert.equal(refused, true); assert.equal(result.job.state, 'waitingRetry'); assert.equal(result.job.effect, 'none');
  assert.equal(result.job.errorCode, 'KNOWLEDGE_WRITE'); assert.equal(f.store.read().semanticGeneration, previous);
  const disk = JSON.parse(await readFile(join(f.dir, 'knowledge', 'state.json'), 'utf8'));
  assert.equal(disk.global.semanticGeneration, previous);
  const other = (await readdir(join(f.root, 'indexes'))).find(name => name.startsWith('g-') && name !== previous);
  assert.ok(other);
  assert.equal(JSON.parse(await readFile(join(f.root, 'indexes', other, 'manifest.json'), 'utf8')).complete, true);
  assert.equal((await f.runtime.search(await f.rows(), '保留原文')).mode, 'keyword');
});

test('Android knowledge local runtime cancellation during semantic batching cannot switch a partial generation', async t => {
  const f = await localKnowledgeRuntime(t); await f.import('取消批次仍保留正式资料');
  await f.configure({ embedding: f.embedding() });
  const gate = Promise.withResolvers(); let started = false;
  f.model.onEmbed = async body => { started = true; await gate.promise; return Response.json({ model: body.model, data: [{ index: 0, embedding: [1, 0] }] }); };
  const accepted = await f.manage('rebuild', f.operation({ kind: 'semantic' }));
  await localMemoryWait(() => started, '语义批次没有开始');
  try { await f.manage('cancel', f.operation({ jobId: accepted.job.id })); }
  finally { gate.resolve(); }
  const job = await f.store.wait(accepted.job.id);
  assert.equal(job.state, 'cancelled'); assert.equal(job.effect, 'none');
  assert.equal(f.store.read().semanticGeneration, undefined);
  assert.equal((await f.runtime.search(await f.rows(), '保留正式资料')).mode, 'keyword');
  assert.equal(await f.store.sourceText(f.store.read().sources[0]), '取消批次仍保留正式资料');
});

test('Android knowledge local runtime generated overview is persisted readable and exposed through actual knowledge tool refs', async t => {
  const f = await localKnowledgeRuntime(t), collection = await f.collection('恢复资料集合');
  await f.import('恢复前停止服务，保留数据库。', { collectionId: collection.id, title: '恢复步骤' });
  await f.import('备份文件必须妥善保留。', { collectionId: collection.id, title: '备份要求' });
  await f.configure({ summary: f.summary() });
  const built = await f.rebuild('summary', { collectionId: collection.id, expectedRevision: f.store.read().collections.find(row => row.id === collection.id).revision });
  assert.equal(built.job.state, 'succeeded', JSON.stringify(built.job)); assert.equal(built.job.effect, 'switched');
  const current = f.store.read().collections.find(row => row.id === collection.id), overview = await f.runtime.overview(current);
  assert.ok(overview); assert.equal(overview.generated, true); assert.equal(overview.text, f.model.summaryText);
  assert.equal(overview.coveredCount, 2); assert.equal(overview.totalCount, 2);
  assert.deepEqual(new Set(overview.sources.map(row => row.sourceId)), new Set(f.store.read().sources.map(row => row.id)));
  const saved = await readFile(join(f.root, current.summary.path));
  assert.equal(createHash('sha256').update(saved).digest('hex'), current.summary.fileHash);
  assert.equal(f.model.summaries.length, 1);
  assert.deepEqual(f.model.summaries[0].tools, []);
  await f.start(); const agent = await f.agent();
  const listed = await f.execute(agent, 'knowledge_list', { kind: 'document' });
  const item = listed.entries.find(row => row.id === collection.id);
  assert.match(item.overviewRef, /^kb:overview:/); assert.equal(item.summaryState, 'ready');
  const read = await f.execute(agent, 'knowledge_read', { ref: item.overviewRef });
  assert.equal(read.kind, 'overview'); assert.equal(read.generated, true); assert.equal(read.text, f.model.summaryText);
  assert.deepEqual(read.coverage, { coveredCount: 2, totalCount: 2 });
  assert.equal(read.sources.length, 2); assert.ok(read.sources.every(row => row.ref.startsWith('kb:document:')));
  await f.reopen();
  assert.equal((await f.runtime.overview(f.store.read().collections.find(row => row.id === collection.id))).text, f.model.summaryText);
});

test('Android knowledge local runtime corrupt overview falls back to verified navigation and rejects its old generated ref', async t => {
  const f = await localKnowledgeRuntime(t), collection = await f.collection('概览损坏测试');
  await f.import('保留数据库和备份。', { collectionId: collection.id, title: '正式恢复原文' });
  await f.configure({ summary: f.summary() });
  assert.equal((await f.rebuild('summary', { collectionId: collection.id, expectedRevision: collection.revision })).job.state, 'succeeded');
  await f.start(); const firstAgent = await f.agent();
  const generated = (await f.execute(firstAgent, 'knowledge_list', { kind: 'document' })).entries.find(row => row.id === collection.id);
  assert.match(generated.overviewRef, /^kb:overview:/);
  const current = f.store.read().collections.find(row => row.id === collection.id);
  await writeFile(join(f.root, current.summary.path), '{broken overview');
  assert.equal(await f.runtime.overview(current), null);
  const agent = await f.agent(), fallback = (await f.execute(agent, 'knowledge_list', { kind: 'document' })).entries.find(row => row.id === collection.id);
  assert.equal(fallback.summaryState, 'stale'); assert.notEqual(fallback.overviewRef, generated.overviewRef);
  await assert.rejects(f.execute(agent, 'knowledge_read', { ref: generated.overviewRef }), { code: 'KNOWLEDGE_VERSION_CHANGED' });
  const read = await f.execute(agent, 'knowledge_read', { ref: fallback.overviewRef });
  assert.equal(read.generated, false); assert.equal(read.navigation, true); assert.match(read.text, /正式恢复原文/);
  assert.doesNotMatch(read.text, /broken overview/);
  assert.equal(await f.store.sourceText(f.store.read().sources[0]), '保留数据库和备份。');
});

test('Android knowledge local runtime member update invalidates both generated overview and earlier navigation fingerprints', async t => {
  const f = await localKnowledgeRuntime(t), collection = await f.collection('成员变更测试');
  const source = await f.import('旧版保留数据库', { collectionId: collection.id });
  await f.enable(); await f.start();
  const initialAgent = await f.agent();
  const navigation = (await f.execute(initialAgent, 'knowledge_list', { kind: 'document' })).entries.find(row => row.id === collection.id);
  assert.match(navigation.overviewRef, /:overview:n/);
  await f.configure({ summary: f.summary() });
  assert.equal((await f.rebuild('summary', { collectionId: collection.id, expectedRevision: collection.revision })).job.state, 'succeeded');
  const agent = await f.agent(), generated = (await f.execute(agent, 'knowledge_list', { kind: 'document' })).entries.find(row => row.id === collection.id);
  assert.match(generated.overviewRef, /^kb:overview:/);
  f.model.onSummary = async () => { throw new Error('fixture summary unavailable'); };
  const accepted = await f.manage('update', f.operation({ sourceId: source.id, expectedRevision: source.revision, patch: { text: '新版改为保留快照' } }));
  const job = await f.store.wait(accepted.job.id); assert.equal(job.effect, 'published'); assert.equal(job.state, 'waitingRetry');
  const current = f.store.read().collections.find(row => row.id === collection.id);
  assert.equal(await f.runtime.overview(current), null);
  await assert.rejects(f.execute(agent, 'knowledge_read', { ref: generated.overviewRef }), { code: 'KNOWLEDGE_VERSION_CHANGED' });
  await assert.rejects(f.execute(agent, 'knowledge_read', { ref: navigation.overviewRef }), { code: 'KNOWLEDGE_VERSION_CHANGED' });
  const after = (await f.execute(await f.agent(), 'knowledge_list', { kind: 'document' })).entries.find(row => row.id === collection.id);
  assert.notEqual(after.overviewRef, navigation.overviewRef);
  assert.equal((await f.execute(agent, 'knowledge_read', { ref: after.overviewRef })).generated, false);
});

test('Android knowledge local runtime incomplete summary input cannot publish a generated overview', async t => {
  const f = await localKnowledgeRuntime(t), collection = await f.collection('完整覆盖测试');
  await f.import('第一份恢复步骤', { collectionId: collection.id });
  await f.import('第二份必要条件', { collectionId: collection.id });
  await f.configure({ summary: f.summary() });
  f.runtimeOptions.getRows = async () => (await f.rows()).slice(0, 1);
  const built = await f.rebuild('summary', { collectionId: collection.id, expectedRevision: collection.revision });
  assert.equal(built.job.state, 'waitingRetry'); assert.equal(built.job.effect, 'none');
  assert.equal(built.job.errorCode, 'KNOWLEDGE_VERSION_CHANGED');
  assert.equal(f.model.summaries.length, 0);
  const current = f.store.read().collections.find(row => row.id === collection.id);
  assert.notEqual(current.summaryState, 'ready'); assert.equal(await f.runtime.overview(current), null);
});

// Emoji 的回归在子进程运行，旧版同步截断死循环也不会卡住整组定向测试。
test('Android knowledge local P1 emoji snippet fitting terminates when metadata cannot fit the budget', async () => {
  const run = async () => {
    const assert = (await import('node:assert/strict')).default;
    const { createRequire } = await import('node:module'), { pathToFileURL } = await import('node:url'), { join } = await import('node:path');
    const { rm } = await import('node:fs/promises');
    const core = process.env.DSHX_CORE, require = createRequire(join(core, 'lib/node_modules/package.json'));
    const load = name => import(pathToFileURL(require.resolve(name)));
    const { Context } = await load('@deepseek-ai/cordis'), ctx = new Context();
    const dir = process.env.DSHX_KNOWLEDGE_EMOJI_DIR; let knowledge, store;
    try {
      await ctx.plugin((await load('@deepseek-ai/dsh-storage')).default);
      await ctx.plugin(await load('@deepseek-ai/dsh-storage-json'), { root: dir });
      await ctx.plugin(await load('@deepseek-ai/dsh-storage-domain'), { backend: 'json' });
      await ctx.plugin((await load('@deepseek-ai/dsh-session')).default);
      await ctx.plugin((await load('@deepseek-ai/dsh-system-prompt')).default);
      await ctx.plugin((await load('@deepseek-ai/dsh-session-projection')).default);
      await ctx.plugin((await load('@deepseek-ai/dsh-fs')).default);
      await ctx.plugin((await load('@deepseek-ai/dsh-working-directory')).default, { defaultDirectory: dir });
      const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
      const { openKnowledge } = await import(pathToFileURL(join(core, 'knowledge-store.mjs')));
      const { knowledgeSession } = await import(pathToFileURL(join(core, 'knowledge.mjs')));
      store = await openKnowledge(ctx, { root: join(dir, 'knowledge') });
      const operation = data => ({ operationId: crypto.randomUUID(), createdAt: Date.now(), ...data });
      await store.manage('settings', operation({ expectedRevision: store.read().settingsRevision, settings: { enabled: true } }));
      const session = ctx.sessions.create('emoji-fit', { meta: { cwd: dir } }), agent = { id: 'emoji-agent', session };
      session.append('user/message', createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: '查阅🙂' }] }), { surfaceOp: 'append' });
      ctx.agents = { list: () => [agent] };
      const memory = { view: () => ({ enabled: true, revision: 1, entries: [{ id: 'emoji-source', updatedAt: 1, expiresAt: null,
        state: 'active', summary: '🙂资料', content: '🙂'.repeat(100), scope: { kind: 'user' }, source: { kind: 'manual' } }] }) };
      knowledge = knowledgeSession(ctx, { store, memory, capacity: 256,
        search: async rows => ({ mode: 'keyword', results: rows.map(row => ({ row, matchedBy: ['keyword'] })) }) });
      await knowledge.ready;
      await assert.rejects(knowledge.execute('knowledge_search', { query: '🙂' }, { agent, signal: new AbortController().signal }), { code: 'KNOWLEDGE_LIMIT' });
      console.log('emoji fit rejected bounded output');
    } finally { await knowledge?.close(); await store?.close(); await ctx.fiber.dispose(); await rm(dir, { recursive: true, force: true }); }
  };
  const dir = await mkdtemp(join(directory, 'knowledge-emoji-fit-'));
  const child = spawnSync(process.execPath, ['--input-type=module', '-e', '(' + run.toString() + ')().catch(error => { console.error(error); process.exitCode = 1; })'],
    { env: { ...process.env, DSHX_KNOWLEDGE_EMOJI_DIR: dir }, encoding: 'utf8', timeout: 4000 });
  assert.equal(child.error, undefined, `emoji 截断没有在时限内终止：${child.error?.code}`);
  assert.equal(child.status, 0, child.stderr); assert.match(child.stdout, /emoji fit rejected bounded output/);
});

for (const tool of ['knowledge_search', 'knowledge_read']) {
  test(`Android knowledge local P1 ${tool} revalidates sources after the outer await`, async t => {
    const f = await localKnowledgeFixture(t);
    const entry = { id: 'outer-await-fact', summary: '旧来源', content: '旧来源不能越过外层等待返回', state: 'active',
      updatedAt: 10, expiresAt: null, scope: { kind: 'user' }, source: { kind: 'auto', session: 'source-session', events: [0] } };
    let revision = 1, entries = [entry], armed = false, invalidated = false;
    f.ctx.provide('sessionPersistence', { open: async () => ({
      read: async () => ({ events: [{ seq: 0, type: 'user/message' }] }), close: async () => { armed = true; },
    }) });
    await f.start({ memory: { view: () => {
      // 内层 ensure 读取旧快照后安排一次已提交的来源变化；变化先于外层 await 恢复。
      if (armed) { armed = false; queueMicrotask(() => { entries = []; revision++; invalidated = true; }); }
      return { enabled: true, revision, entries };
    } }, search: async rows => { armed = true;
      return { mode: 'keyword', results: rows.map(row => ({ row, matchedBy: ['keyword'] })) };
    } });
    const agent = await f.agent();
    const args = tool === 'knowledge_search' ? { query: '来源' } : { ref: `kb:memory:${entry.id}:u${entry.updatedAt}` };
    await assert.rejects(f.execute(agent, tool, args), { code: 'KNOWLEDGE_VERSION_CHANGED' });
    assert.equal(invalidated, true, '必须在内层读取完成、外层等待恢复之间使来源失效');
  });
}

test('Android knowledge local pagination keeps the original ranked candidate snapshot across model ordering changes', async t => {
  const f = await localKnowledgeFixture(t), collection = await f.collection();
  for (let i = 0; i < 4; i++) await f.import(`分页资料正文${i}`, { collectionId: collection.id, title: `分页资料${i}` });
  await f.enable(); let searches = 0, original;
  await f.start({ search: async rows => {
    original ??= rows.map(row => row.ref);
    const ranked = ++searches === 1 ? rows : rows.toReversed();
    return { mode: 'keyword', results: ranked.map(row => ({ row, matchedBy: ['keyword'] })) };
  } });
  const refs = []; let cursor;
  do {
    const page = await f.knowledge.call('search', { query: '分页', limit: 1, ...cursor ? { cursor } : {} });
    refs.push(...page.results.map(row => row.ref)); cursor = page.cursor;
    assert.ok(refs.length <= 4, '分页不能无限返回重复候选');
  } while (cursor);
  assert.deepEqual(refs, original); assert.equal(new Set(refs).size, 4);
  assert.equal(searches, 1, '后续页应复用首轮候选，避免额外模型请求和排序漂移');
});

test('Android knowledge local final output remeasures a model context window changed during search', async t => {
  const f = await localKnowledgeFixture(t); await f.import('正文🙂'.repeat(100)); await f.enable();
  let window = 16000, measurements = 0;
  f.ctx.llm.resolveModelInfo = async () => { measurements++; return { context: { contextWindow: window } }; };
  await f.start({ capacity: undefined, search: async rows => {
    window = 2400;
    return { mode: 'keyword', results: rows.map(row => ({ row, matchedBy: ['keyword'] })) };
  } });
  const agent = await f.agent();
  agent.session.requestHeader = () => ({ config: { provider: 'fixture', model: 'fixture', maxTokens: 100 } });
  const result = await f.execute(agent, 'knowledge_search', { query: '正文' });
  assert.ok(measurements >= 2, '返回前必须重新读取当前模型容量');
  assert.ok(Buffer.byteLength(JSON.stringify(result)) <= 600, '最终 JSON 必须服从更新后窗口四分之一的容量上限');
  assert.ok(!result.results[0].snippet.includes('\ufffd'));
});

test('Android knowledge local final output includes context tokens added while evidence was prepared', async t => {
  const f = await localKnowledgeFixture(t); await f.import('不能挤出最终上下文的正文'.repeat(30)); await f.enable();
  let measured = 0;
  f.ctx.provide('tokenMeter', { measure: () => ({ totalTokens: measured }) });
  f.ctx.llm.resolveModelInfo = async () => ({ context: { contextWindow: 8000 } });
  await f.start({ capacity: undefined, search: async rows => {
    measured = 7500;
    return { mode: 'keyword', results: rows.map(row => ({ row, matchedBy: ['keyword'] })) };
  } });
  const agent = await f.agent(); agent.session.requestHeader = () => ({ config: { provider: 'fixture', model: 'fixture', maxTokens: 100 } });
  await assert.rejects(f.execute(agent, 'knowledge_search', { query: '正文' }), { code: 'KNOWLEDGE_LIMIT' });
});

test('Android knowledge local disposed agent cannot replenish the same root user request budget', async t => {
  const f = await localKnowledgeFixture(t); await f.enable(); await f.start();
  const agent = await f.agent();
  for (let i = 0; i < 3; i++) await f.execute(agent, 'knowledge_search', { query: '资料' });
  f.ctx.emit('agent/disposed', { agent });
  const replacement = { id: crypto.randomUUID(), session: agent.session };
  f.agents.splice(f.agents.indexOf(agent), 1, replacement);
  await assert.rejects(f.execute(replacement, 'knowledge_search', { query: '资料' }), { code: 'KNOWLEDGE_LIMIT' });
  const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
  replacement.session.append('user/message', createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: '新的根用户问题' }] }), { surfaceOp: 'append' });
  assert.deepEqual((await f.execute(replacement, 'knowledge_search', { query: '资料' })).results, []);
});

for (const route of ['execute', 'call']) {
  test(`Android knowledge local close waits for pending ${route} and rejects delayed evidence`, async t => {
    const f = await localKnowledgeFixture(t); await f.import('关闭后不得返回的证据'); await f.enable();
    const gate = Promise.withResolvers(); let started = false, finished = false;
    await f.start({ search: async rows => { started = true; await gate.promise; finished = true;
      return { mode: 'keyword', results: rows.map(row => ({ row, matchedBy: ['keyword'] })) }; } });
    const agent = await f.agent();
    const request = route === 'execute' ? f.execute(agent, 'knowledge_search', { query: '证据' }) : f.knowledge.call('search', { query: '证据' });
    const rejected = assert.rejects(request, { code: 'KNOWLEDGE_CANCELLED' });
    await localMemoryWait(() => started, '待关闭检索没有进入等待');
    let closed = false; const closing = f.knowledge.close().then(() => { closed = true; });
    try {
      await delay(20);
      assert.equal(closed, false, 'close 必须等待已开始的工具或管理请求离开');
    } finally { gate.resolve(); await Promise.all([rejected, closing]); }
    assert.equal(finished, true); assert.equal(closed, true);
    await assert.rejects(f.execute(agent, 'knowledge_search', { query: '证据' }), { code: 'KNOWLEDGE_NOT_READY' });
    await assert.rejects(f.knowledge.call('search', { query: '证据' }), { code: 'KNOWLEDGE_NOT_READY' });
  });
}

for (const [tool, change] of [['knowledge_search', 'update'], ['knowledge_read', 'remove']]) {
  test(`Android knowledge local P1 ${tool} refuses a document ${change} committed during final model resolution`, async t => {
    const f = await localKnowledgeFixture(t), source = await f.import('旧版正式原文不得在最终等待后返回');
    await f.enable();
    const gate = Promise.withResolvers(); let resolutions = 0, entered = false, settled = false;
    f.ctx.llm.resolveModelInfo = async () => {
      if (++resolutions === 2) { entered = true; await gate.promise; }
      return { context: { contextWindow: 16000 } };
    };
    await f.start({ capacity: undefined });
    const agent = await f.agent(); agent.session.requestHeader = () => ({ config: { provider: 'fixture', model: 'fixture', maxTokens: 100 } });
    const args = tool === 'knowledge_search' ? { query: '正式原文' } : { ref: `kb:document:${source.id}:v${source.version}:text` };
    const request = f.execute(agent, tool, args);
    request.then(() => { settled = true; }, () => { settled = true; });
    const rejected = assert.rejects(request, { code: 'KNOWLEDGE_VERSION_CHANGED' });
    try {
      await localMemoryWait(() => entered || settled, '最终模型容量查询没有完成也没有进入等待');
      assert.equal(entered, true, '必须在返回证据前再次异步解析当前模型');
      const result = await f.manage(change, f.operation({ sourceId: source.id, expectedRevision: source.revision,
        ...change === 'update' ? { patch: { text: '已发布新版正式原文' } } : {} }));
      assert.equal((await f.store.wait(result.job.id)).state, 'succeeded');
    } finally { gate.resolve(); await rejected; }
    if (change === 'update') assert.equal(await f.store.sourceText(f.store.read().sources.find(row => row.id === source.id)), '已发布新版正式原文');
    else assert.ok(f.store.read().sources.every(row => row.id !== source.id || row.state === 'deleted'));
  });
}

// 调度回归使用真实 MemoryStore、extract 和输入构造，仅模型传输返回固定空数组。
async function localMemoryFlow(t, options = {}) {
  const f = await localMemoryFixture(t);
  const { createUserMessage, createAssistantMessage } = await load('@deepseek-ai/dsh-llm');
  const { extract } = await import(pathToFileURL(join(core, 'memory-extract.mjs')));
  const handlers = new Map(), events = [], requests = [];
  let time = 1000000, window = options.window ?? 32768;
  const session = { id: crypto.randomUUID(), seq: 0, header: { cwd: workspace }, snapshotEvents: () => events,
    requestHeader: () => ({ config: { provider: 'fixture', model: 'source-model' } }) };
  const ctx = {
    on(name, handler) { if (!handlers.has(name)) handlers.set(name, new Set()); handlers.get(name).add(handler); return () => handlers.get(name).delete(handler); },
    workingDirectory: { get: () => workspace }, sessions: { flush: async () => true },
    sessionPersistence: { open: async () => ({ read: async (from = 0, count = events.length - from) => ({ events: events.slice(from, from + count) }), close: async () => {} }) },
    llm: { listProviders: () => [{ id: 'fixture' }], async prepareCall(config, signal, refine) {
      const controls = refine(config, { provider: 'fixture', id: 'source-model', context: { contextWindow: window }, defaultMaxTokens: 256 });
      return { config: controls, async *stream(request) {
        requests.push({ system: request.system, input: JSON.parse(request.messages[0].content[0].text) });
        yield { type: 'text-delta', index: 0, text: '[]' }; yield { type: 'finish', reason: { kind: 'stop' } };
      } };
    } },
  };
  const emit = (name, ...args) => { for (const handler of handlers.get(name) ?? []) handler(...args); };
  const start = async () => {
    f.memory = f.memorySession(ctx, { store: f.store, extract, now: () => time, ...options });
    await f.memory.ready;
  };
  await start(); await f.settings(true, true);
  const settle = async () => {
    for (let i = 0; i < 8; i++) { await new Promise(resolve => setImmediate(resolve)); await f.store.queue; }
  };
  return Object.assign(f, { events, requests, session, flow: ctx, emit, start, settle,
    advance(ms) { time += ms; }, window(value) { window = value; },
    async turn(text = '请说明当前代码如何工作', { source = { kind: 'user' }, answer = '这是助手背景', reason = 'completed', during } = {}) {
      const message = createUserMessage({ source, content: [{ type: 'text', text }] });
      const decision = await f.memory.before({ agent: { session }, messages: [], signal: new AbortController().signal }, async () => ({ kind: 'enter', messages: [message] }));
      const append = (type, data) => { const event = { seq: events.length, type, data }; events.push(event); session.seq = events.length; return event; };
      append('request/header', { header: session.requestHeader() });
      append('user/message', message);
      await during?.();
      append('assistant/message', { message: createAssistantMessage({ source: session.requestHeader().config, content: [{ type: 'text', text: answer }] }) });
      const end = append('turn/end', { turn: events.length, reason: { kind: reason } });
      emit('session/event', session, end);
      await localMemoryWait(() => f.store.read().jobs.some(job => job.to === session.seq) || f.store.read().cursors[session.id]?.next === session.seq, '回合范围未持久登记');
      await settle();
      return decision;
    },
  });
}

test('Android memory local specimen auto save works independently of conversation recall and survives reopen', async t => {
  const f = await localMemoryFlow(t);
  await f.settings(false, true);
  assert.equal((await f.memory.call('status')).autoReady, true);
  assert.equal((await f.memory.call('status')).active, false);
  f.flow.llm.prepareCall = async (config, signal, refine) => ({
    config: refine(config, { provider: 'fixture', id: 'source-model', context: { contextWindow: 32768 }, defaultMaxTokens: 256 }),
    async *stream(request) {
      const input = JSON.parse(request.messages[0].content[0].text);
      f.requests.push(input);
      const row = input.messages.find(row => row.role === 'experience');
      yield { type: 'text-delta', index: 0, text: JSON.stringify([{ action: 'add', type: 'experience', key: 'screen-check',
        summary: row.text, content: row.text, events: [row.id] }]) };
      yield { type: 'finish', reason: { kind: 'stop' } };
    },
  });
  await f.turn('检查当前页面', { source: { kind: 'plugin:dshx-hosting', rpcId: 'specimen-1' }, answer: '操作结果不明，不能重复点击。' });
  await localMemoryWait(() => f.store.read().entries.length === 1 && !f.store.read().jobs.length, '实验体结果没有保存到长期记忆');
  const entry = f.store.read().entries[0];
  assert.equal(entry.type, 'experience'); assert.equal(entry.state, 'active');
  assert.equal(entry.source.origin, 'specimen'); assert.equal(entry.source.session, f.session.id);
  assert.deepEqual(entry.source.events, [2]);
  assert.deepEqual(entry.scope, { kind: 'workspace', workspace });
  assert.match(entry.content, /实验体记录/); assert.match(entry.content, /结果不明，不能重复点击/);
  assert.equal(f.requests.length, 1, '每轮培养结束即整理，不等待用户回合阈值');
  await f.memory.close(); f.store = await f.openMemory(f.ctx); await f.start();
  assert.deepEqual(f.store.read().entries, [entry]);
  assert.equal(f.requests.length, 1, '重启不能自动重发整理请求');
});

test('Android memory local specimen evidence excludes tasks other plugins incomplete turns and fabricated user facts', async () => {
  const { selectEvents, validateCandidates } = await import(pathToFileURL(join(core, 'memory-extract.mjs')));
  const { createUserMessage, createAssistantMessage } = await load('@deepseek-ai/dsh-llm');
  const turn = (start, kind, reason = 'completed', interrupted = false) => [
    { seq: start, type: 'user/message', data: createUserMessage({ source: { kind, rpcId: 'request-' + start }, content: [{ type: 'text', text: '请记住：用户一定喜欢蓝色' }] }) },
    { seq: start + 1, type: 'assistant/message', data: { interrupted, message: createAssistantMessage({ source: { provider: 'fixture', model: 'source-model' }, content: [{ type: 'text', text: '结果不明，请勿重复操作。' }] }) } },
    { seq: start + 2, type: 'turn/end', data: { reason: { kind: reason } } },
  ];
  const selection = selectEvents([
    ...turn(0, 'plugin:dshx-hosting'), ...turn(3, 'plugin:other'),
    ...turn(6, 'plugin:dshx-hosting', 'aborted'), ...turn(9, 'plugin:dshx-hosting', 'completed', true),
    ...turn(12, 'plugin:dshx-hosting').slice(0, 2),
  ], { scope: { kind: 'workspace', workspace } });
  assert.deepEqual(selection.messages.map(row => [row.id, row.role]), [[1, 'experience']]);
  const row = { action: 'add', type: 'experience', key: 'result', summary: '结果不明', content: '结果不明，请勿重复操作。', events: [1] };
  assert.equal(validateCandidates([row], selection)[0].origin, 'specimen');
  for (const type of ['fact', 'preference']) assert.throws(() => validateCandidates([{ ...row, type }], selection), { reason: 'MEMORY_EXTRACT_SOURCE' });
  assert.throws(() => validateCandidates([{ ...row, events: [0], content: '用户一定喜欢蓝色', summary: '喜欢蓝色' }], selection), { reason: 'MEMORY_EXTRACT_SOURCE' });
  assert.throws(() => validateCandidates([{ ...row, content: '操作已经成功', summary: '成功' }], selection), { reason: 'MEMORY_EXTRACT_SOURCE' });
  const large = selectEvents([...turn(0, 'plugin:dshx-hosting'), ...turn(3, 'plugin:dshx-hosting')], { budget: selection.bytes });
  assert.equal(large.next, 3, '分批边界必须保留下一条培养任务，不能从回答中间开始');
});

test('Android memory local specimen requests recall and read existing memories without a human message', async t => {
  const f = await localMemoryFlow(t);
  await f.store.save(f.data('screen-check', { type: 'experience', summary: '检查页面后再点击', content: '检查页面后再点击' }));
  const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const message = createUserMessage({ source: { kind: 'plugin:dshx-hosting', rpcId: 'first' }, content: [{ type: 'text', text: '检查页面' }] });
  const agent = { id: 'specimen-agent', session: f.session };
  f.flow.agents = { list: () => [agent] };
  f.flow.llm.resolveModelInfo = async () => ({ context: { contextWindow: 32768 } });
  const decision = await f.memory.before({ agent, messages: [] }, async () => ({ kind: 'enter', messages: [message] }));
  assert.match(decision.messages[0].content[0].text, /检查页面后再点击/);
  const append = data => { f.events.push({ seq: f.events.length, time: Date.now(), type: 'user/message', data }); f.session.seq = f.events.length; };
  append(message);
  for (let i = 0; i < 3; i++) assert.equal((await f.memory.execute('memory_search', { query: '检查页面' }, { agent })).entries.length, 1);
  await assert.rejects(f.memory.execute('memory_search', { query: '检查页面' }, { agent }), { code: 'MEMORY_TOOL_LIMIT' });
  append(createUserMessage({ source: { kind: 'plugin:dshx-memory' }, content: [{ type: 'text', text: '注入内容不能重置额度' }] }));
  await assert.rejects(f.memory.execute('memory_search', { query: '检查页面' }, { agent }), { code: 'MEMORY_TOOL_LIMIT' });
  append(createUserMessage({ source: { kind: 'plugin:dshx-hosting', rpcId: 'second' }, content: [{ type: 'text', text: '检查页面' }] }));
  assert.equal((await f.memory.execute('memory_search', { query: '检查页面' }, { agent })).entries.length, 1);
});

test('Android memory local specimen auto save off makes no extraction requests', async t => {
  const f = await localMemoryFlow(t);
  await f.settings(true, false);
  const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const message = createUserMessage({ source: { kind: 'plugin:dshx-hosting', rpcId: 'disabled' }, content: [{ type: 'text', text: '检查页面' }] });
  await f.memory.before({ agent: { session: f.session }, messages: [] }, async () => ({ kind: 'enter', messages: [message] }));
  const end = { seq: 0, type: 'turn/end', data: { reason: { kind: 'completed' } } };
  f.events.push(end); f.session.seq = 1; f.emit('session/event', f.session, end); await f.settle();
  assert.deepEqual(f.store.read().cursors, {}); assert.deepEqual(f.store.read().jobs, []);
  assert.deepEqual(f.store.read().entries, []); assert.equal(f.requests.length, 0);
});

test('Android memory local specimen reading switch preserves accumulating and in-flight automatic work', async t => {
  const f = await localMemoryFlow(t);
  await f.turn('普通聊天也要在关闭读取时继续整理');
  const pending = f.store.read().jobs;
  await f.settings(false, true);
  assert.deepEqual(f.store.read().jobs, pending, '关闭读取不能丢掉已登记的自动整理范围');
  for (let i = 0; i < 3; i++) await f.turn('继续积累可整理的聊天');
  await localMemoryWait(() => f.requests.length === 1 && !f.store.read().jobs.length, '只开启自动整理时普通聊天没有达到整理入口');
  assert.equal(f.requests[0].input.messages.filter(row => row.role === 'user').length, 4);
});

test('Android memory local specimen namespaced keys remain stable and locked memories cannot be overwritten', async t => {
  const f = await localMemoryFixture(t);
  const { selectEvents, validateCandidates } = await import(pathToFileURL(join(core, 'memory-extract.mjs')));
  const { createUserMessage, createAssistantMessage } = await load('@deepseek-ai/dsh-llm');
  await f.store.save(f.data('specimen.result', { type: 'experience', scope: { kind: 'workspace', workspace },
    summary: '实验体记录：结果不明', content: '实验体记录：结果不明' }));
  const events = [
    { seq: 0, type: 'user/message', data: createUserMessage({ source: { kind: 'plugin:dshx-hosting', rpcId: 'namespaced' }, content: [{ type: 'text', text: '核对结果' }] }) },
    { seq: 1, type: 'assistant/message', data: { message: createAssistantMessage({ source: { provider: 'fixture', model: 'source-model' }, content: [
      { type: 'reasoning', text: '这些推理不能保存' }, { type: 'text', text: '结果不明' },
    ] }) } },
    { seq: 2, type: 'turn/end', data: { reason: { kind: 'completed' } } },
  ];
  const selection = selectEvents(events, { entries: f.store.read().entries, scope: { kind: 'workspace', workspace } });
  assert.deepEqual(selection.messages.map(row => row.text), ['结果不明']);
  const row = { action: 'add', type: 'experience', key: 'specimen.result', summary: '结果不明', content: '结果不明', events: [1] };
  const candidate = validateCandidates([row], selection)[0];
  assert.equal(candidate.key, 'specimen.result', '不能每次整理给主题键重复加前缀');
  assert.equal(candidate.action, 'ignore', '相同经验不应反复生成待确认条目');
  events[1].data.message = createAssistantMessage({ source: { provider: 'fixture', model: 'source-model' }, content: [{ type: 'text', text: '结果不明，暂时停止' }] });
  const changed = selectEvents(events, { entries: f.store.read().entries, scope: { kind: 'workspace', workspace } });
  assert.equal(validateCandidates([{ ...row, content: '结果不明，暂时停止' }], changed)[0].state, 'pending', '手动锁定的经验不能被自动覆盖');
});

for (const wait of [true, false]) {
  test(`Android memory local specimen compaction preserves unfinished evidence with waiting ${wait}`, async t => {
    const f = await localMemoryFlow(t);
    await f.turn('核对培养结果', { source: { kind: 'plugin:dshx-hosting', rpcId: 'compacting' }, during: async () => {
      const background = { seq: f.events.length, type: 'user/message', data: {
        role: 'user', source: { kind: 'runtime-context' }, content: [{ type: 'text', text: '当前工作区背景' }],
      } };
      f.events.push(background);
      const event = { seq: f.events.length, type: 'compaction/start', data: {} };
      f.events.push(event); f.session.seq = f.events.length;
      f.emit('session/event', f.session, event);
      if (wait) {
        await f.settle();
        assert.ok(f.store.read().cursors[f.session.id].next <= 1, '未结束的培养输入不能被压缩提前消费');
      }
    } });
    await localMemoryWait(() => f.requests.length === 1 && !f.store.read().jobs.length, '压缩后完整培养回答未参与整理');
    assert.equal(f.requests[0].input.messages.find(row => row.role === 'experience').text, '这是助手背景');
    assert.equal(f.store.read().cursors[f.session.id].next, f.events.length);
  });
}

test('Android memory local specimen repeated experience cannot exhaust references and block later memories', async t => {
  const f = await localMemoryFlow(t);
  let removeDuringExtraction = false;
  f.flow.llm.prepareCall = async (config, signal, refine) => ({
    config: refine(config, { provider: 'fixture', id: 'source-model', context: { contextWindow: 32768 }, defaultMaxTokens: 256 }),
    async *stream(request) {
      const input = JSON.parse(request.messages[0].content[0].text);
      const row = input.messages.find(row => row.role === 'experience');
      if (removeDuringExtraction) {
        const entry = f.store.read().entries.find(row => row.key === 'specimen.observe');
        await f.memory.call('remove', { id: entry.id, revision: f.store.read().revision });
      }
      yield { type: 'text-delta', index: 0, text: JSON.stringify([{ action: 'add', type: 'experience',
        key: removeDuringExtraction ? 'renamed' : row.text.includes('先观察再点击') ? 'observe' : 'new-result',
        summary: row.text, content: row.text, events: [row.id] }]) };
      yield { type: 'finish', reason: { kind: 'stop' } };
    },
  });
  for (let i = 0; i < 37; i++) {
    await f.turn('核对页面', { source: { kind: 'plugin:dshx-hosting', rpcId: `repeat-${i}` },
      answer: i < 35 ? '先观察再点击' : i === 35 ? '更正：先观察再点击，之后核实结果。' : '未知结果不重放' });
    assert.equal(f.store.read().jobs.length, 0, `第${i + 1}轮重复结果阻塞了整理`);
  }
  assert.equal(f.store.read().entries.length, 2);
  assert.match(f.store.read().entries.find(row => row.key === 'specimen.observe').content, /之后核实结果/);
  assert.ok(f.store.read().entries.every(row => (row.source.refs?.length ?? 0) <= 32));
  assert.equal(f.store.read().cursors[f.session.id].next, f.events.length);
  removeDuringExtraction = true;
  await f.turn('核对页面', { source: { kind: 'plugin:dshx-hosting', rpcId: 'deleted-repeat' }, answer: '更正：先观察再点击，之后核实结果。' });
  assert.equal(f.store.read().entries.length, 1);
  assert.equal(f.store.read().entries[0].key, 'specimen.new-result', '省略冗余出处后，删除中的重复内容不能换主题复活');
  assert.equal(f.store.read().jobs.length, 0);
});

test('Android memory local schedule batches four user turns and preserves pending progress before the threshold', async t => {
  const f = await localMemoryFlow(t);
  for (let i = 0; i < 3; i++) await f.turn(`第${i}轮，请分析项目结构`);
  assert.equal(f.requests.length, 0);
  assert.equal(f.store.read().jobs.length, 1);
  assert.equal(f.store.read().jobs[0].ready, false);
  assert.equal(f.store.read().cursors[f.session.id].next, 0);
  await f.turn('第四轮，继续分析模块');
  await localMemoryWait(() => !f.store.read().jobs.length, '四轮批次没有完成');
  assert.equal(f.requests.length, 1);
  assert.equal(f.requests[0].input.messages.filter(row => row.role === 'user').length, 4);
  assert.equal(f.store.read().cursors[f.session.id].next, f.events.length);
});

test('Android memory local schedule cools ordinary batches while explicit memory requests bypass the delay', async t => {
  const f = await localMemoryFlow(t);
  for (let i = 0; i < 8; i++) await f.turn();
  assert.equal(f.requests.length, 1);
  assert.equal(f.store.read().jobs.length, 1);
  assert.equal(f.store.read().jobs[0].ready, true);
  assert.ok(f.store.read().jobs[0].notBefore > f.store.read().cursors[f.session.id].lastRun);
  await f.turn('请记住：我习惯先检查受影响的模块');
  await localMemoryWait(() => !f.store.read().jobs.length, '明确记住没有绕过冷却');
  assert.equal(f.requests.length, 2);
  assert.equal(f.requests[1].input.messages.filter(row => row.role === 'user').length, 5);
});

test('Android memory local schedule drains a cooled batch when the interval has elapsed', async t => {
  const f = await localMemoryFlow(t, { schedule: { cooldown: 50 } });
  for (let i = 0; i < 8; i++) await f.turn();
  assert.equal(f.requests.length, 1);
  f.advance(51);
  await localMemoryWait(() => !f.store.read().jobs.length, '到期的批次没有继续');
  assert.equal(f.requests.length, 2);
});

for (const trigger of ['retry', 'organize', 'compaction/start']) {
  test(`Android memory local schedule ${trigger} flushes accumulated evidence before the ordinary threshold`, async t => {
    const f = await localMemoryFlow(t);
    await f.turn('这是一条尚未达到整理阈值的普通消息');
    assert.equal(f.requests.length, 0);
    if (trigger === 'compaction/start') {
      const event = { seq: f.events.length, type: trigger, data: {} }; f.events.push(event); f.session.seq++;
      f.emit('session/event', f.session, event);
    } else await f.memory.call(trigger, trigger === 'retry' ? { id: f.store.read().jobs[0].id } : {});
    await localMemoryWait(() => f.requests.length === 1 && !f.store.read().jobs.length, `${trigger} 未整理当前积累`);
    assert.equal(f.requests[0].input.messages.filter(row => row.role === 'user').length, 1);
  });
}

test('Android memory local schedule restarts without sending and combines the saved pending range', async t => {
  const f = await localMemoryFlow(t);
  for (let i = 0; i < 3; i++) await f.turn();
  const pending = f.store.read().jobs[0];
  await f.memory.close(); f.store = await f.openMemory(f.ctx); await f.start();
  assert.equal(f.requests.length, 0);
  assert.equal(f.store.read().jobs.length, 1);
  assert.equal(f.store.read().jobs[0].from, pending.from);
  assert.equal(f.store.read().jobs[0].to, pending.to);
  await f.turn('重启后的第四个回合');
  await localMemoryWait(() => !f.store.read().jobs.length, '恢复范围没有和新增内容一起处理');
  assert.equal(f.requests.length, 1);
  assert.equal(f.requests[0].input.messages.filter(row => row.role === 'user').length, 4);
});

test('Android memory local schedule byte trigger processes complete evidence across a smaller actual model window', async t => {
  const f = await localMemoryFlow(t, { window: 6000 });
  for (let i = 0; i < 2; i++) await f.turn(`第${i}条` + '中文'.repeat(350));
  await localMemoryWait(() => !f.store.read().jobs.length, '实际窗口收紧后未继续分批');
  assert.equal(f.requests.length, 2);
  const ids = f.requests.flatMap(row => row.input.messages.filter(message => message.role === 'user').map(message => message.id));
  assert.deepEqual(ids, f.events.filter(row => row.type === 'user/message').map(row => row.seq));
  assert.equal(f.store.read().cursors[f.session.id].next, f.events.length);
});

test('Android memory local schedule oversized single message stays failed without advancing or automatic retry', async t => {
  const f = await localMemoryFlow(t);
  await f.turn('超大原文'.repeat(3000));
  await localMemoryWait(() => f.store.read().jobs[0]?.state === 'failed', '超大单条没有留下失败任务');
  assert.equal(f.requests.length, 0);
  assert.equal(f.store.read().cursors[f.session.id].next, 0);
  assert.equal(f.store.read().jobs[0].attempts, 1);
  assert.match(f.store.read().jobs[0].message, /单条用户消息.*保留/);
  await f.turn('稍后的普通内容');
  await f.settle();
  assert.equal(f.requests.length, 0);
  assert.equal(f.store.read().jobs[0].attempts, 1);
  assert.equal(f.store.read().cursors[f.session.id].next, 0);
});

test('Android memory local extraction backgrounds rank related topics and keep locked correction context within its own budget', async t => {
  const f = await localMemoryFixture(t);
  const { selectEvents } = await import(pathToFileURL(join(core, 'memory-extract.mjs')));
  const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const scope = { kind: 'workspace', workspace };
  await f.store.save(f.data('coffee', { scope, summary: '无糖咖啡偏好', content: '我通常喜欢无糖咖啡' }));
  for (let i = 0; i < 20; i++) await f.store.save(f.data(`unrelated-${i}`, { scope, summary: `门禁编号${i}`, content: `门禁编号${i}由管理员维护` }));
  const event = text => [{ seq: 1, type: 'user/message', data: createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text }] }) }];
  const regular = selectEvents(event('我通常喜欢无糖咖啡'), { entries: f.store.read().entries, scope });
  assert.equal(regular.entries.length, 1);
  assert.equal(regular.entries[0].key, 'coffee');
  assert.equal(regular.entries[0].locked, true);
  assert.equal(regular.entries[0].content, undefined, '普通批次不重复传旧正文');
  const correction = selectEvents(event('更正咖啡偏好：我现在喜欢加奶咖啡'), { entries: f.store.read().entries, scope });
  assert.equal(correction.entries[0].content, '我通常喜欢无糖咖啡');
  assert.ok(Buffer.byteLength(JSON.stringify(correction.entries)) <= 1536);
  assert.deepEqual(selectEvents(event('请分析星系的形成'), { entries: f.store.read().entries, scope }).entries, []);
});

async function localSharedMemory(f, options = {}) {
  const memory = await import(pathToFileURL(join(core, 'memory.mjs')));
  const storage = await import(pathToFileURL(join(core, 'memory-store.mjs')));
  const store = await storage.openMemory(f.ctx);
  f.memory = memory.memorySession(f.ctx, { store, capacity: 100000, ...options });
  await f.memory.ready;
  await f.memory.call('settings', { enabled: true, autoSave: false, revision: store.read().revision });
  await store.save(f.data('shared', { content: '共享读取预算的事实正文' }));
  return { store, memory: f.memory, execute: (agent, tool, args = {}, signal) => f.memory.execute(tool, args, { agent, signal }) };
}

for (const first of ['memory_search', 'knowledge_search']) {
  test(`Android knowledge local shared ${first} cannot bypass the search count through the other tool family`, async t => {
    const f = await localKnowledgeFixture(t); await f.enable(); await f.start();
    const memory = await localSharedMemory(f), agent = await f.agent();
    const old = (tool) => tool.startsWith('memory') ? memory.execute(agent, tool, { query: '共享' }) : f.execute(agent, tool, { query: '共享' });
    for (let i = 0; i < 3; i++) await old(first);
    const other = first === 'memory_search' ? 'knowledge_search' : 'memory_search';
    await assert.rejects(old(other), { code: other.startsWith('memory') ? 'MEMORY_TOOL_LIMIT' : 'KNOWLEDGE_LIMIT' });
  });
}

test('Android knowledge local shared read counts combine old memory reads with knowledge reads', async t => {
  const f = await localKnowledgeFixture(t), source = await f.import('共享读取的知识正文'); await f.enable(); await f.start();
  const memory = await localSharedMemory(f), agent = await f.agent(), row = memory.store.read().entries[0];
  for (let i = 0; i < 3; i++) {
    assert.equal((await memory.execute(agent, 'memory_read', { id: row.id })).content, row.content);
    await f.execute(agent, 'knowledge_read', { ref: `kb:document:${source.id}:v${source.version}:text` });
  }
  await assert.rejects(memory.execute(agent, 'memory_read', { id: row.id }), { code: 'MEMORY_TOOL_LIMIT' });
  await assert.rejects(f.execute(agent, 'knowledge_read', { ref: `kb:document:${source.id}:v${source.version}:text` }), { code: 'KNOWLEDGE_LIMIT' });
  assert.equal((await memory.memory.call('read', { id: row.id })).entry.content, row.content, '原生管理读取不消耗模型工具额度');
});

test('Android knowledge local shared specimen source evidence requires a complete cultivation turn', async t => {
  const f = await localKnowledgeFixture(t); await f.enable();
  const { createUserMessage, createAssistantMessage } = await load('@deepseek-ai/dsh-llm');
  const events = [
    { seq: 0, type: 'user/message', data: createUserMessage({ source: { kind: 'plugin:dshx-hosting', rpcId: 'evidence' }, content: [{ type: 'text', text: '核对结果' }] }) },
    { seq: 1, type: 'assistant/message', data: { message: createAssistantMessage({ source: { provider: 'fixture', model: 'source-model' }, content: [{ type: 'text', text: '结果不明' }] }) } },
    { seq: 2, type: 'turn/end', data: { reason: { kind: 'completed' } } },
  ];
  f.ctx.provide('sessionPersistence', { async open() { return { async read(from, count) { return { events: from === undefined ? events : events.slice(from, from + count) }; }, async close() {} }; } });
  const source = { kind: 'auto', origin: 'specimen', session: 'specimen-origin', events: [1] };
  const entry = { id: 'specimen-entry', type: 'experience', scope: { kind: 'user' }, key: 'specimen.result',
    summary: '实验体记录：结果不明', content: '实验体记录：结果不明', state: 'active', source, updatedAt: 1, expiresAt: null };
  await f.start({ memory: { view: () => ({ enabled: true, revision: 1, entries: [entry] }) } });
  const agent = await f.agent();
  const read = () => f.execute(agent, 'knowledge_read', { ref: 'kb:memory:specimen-entry:u1' });
  assert.equal((await read()).sourceEvidenceAvailable, true);
  events.pop();
  assert.equal((await read()).sourceEvidenceAvailable, false, '没有轮次结束证据不能称来源可核验');
});

test('Android knowledge local shared bytes from memory reads reduce the remaining knowledge capacity', async t => {
  const f = await localKnowledgeFixture(t), source = await f.import('长知识正文'.repeat(160)); await f.enable(); await f.start({ capacity: 900 });
  const memory = await localSharedMemory(f, { capacity: 900 }), agent = await f.agent(), row = memory.store.read().entries[0];
  let used = Buffer.byteLength(JSON.stringify(await memory.execute(agent, 'memory_read', { id: row.id })));
  try {
    const result = await f.execute(agent, 'knowledge_read', { ref: `kb:document:${source.id}:v${source.version}:text` });
    used += Buffer.byteLength(JSON.stringify(result));
  } catch (error) { assert.equal(error.code, 'KNOWLEDGE_LIMIT'); }
  assert.ok(used <= 900);
  await assert.rejects(memory.execute(agent, 'memory_read', { id: row.id }), { code: 'MEMORY_TOOL_LIMIT' });
});

test('Android knowledge local shared concurrency reserves knowledge capacity before an old memory read', async t => {
  const f = await localKnowledgeFixture(t); await f.import('并发读取正文'); await f.enable();
  const gate = Promise.withResolvers(); let started = false;
  await f.start({ capacity: 900, search: async rows => { started = true; await gate.promise; return { mode: 'keyword', results: rows.map(row => ({ row, score: 1 })) }; } });
  const memory = await localSharedMemory(f, { capacity: 900 }), agent = await f.agent(), row = memory.store.read().entries[0];
  const request = f.execute(agent, 'knowledge_search', { query: '正文' });
  try {
    await localMemoryWait(() => started, '知识读取没有进入并发准备');
    await assert.rejects(memory.execute(agent, 'memory_read', { id: row.id }), { code: 'MEMORY_TOOL_LIMIT' });
  } finally { gate.resolve(); await request; }
  assert.equal((await memory.execute(agent, 'memory_read', { id: row.id })).content, row.content, '并发预留必须在完成后释放');
});

test('Android memory local tool preserves full legacy entries and rejects oversized complete results', async t => {
  const f = await localKnowledgeFixture(t);
  const memory = await localSharedMemory(f, { capacity: 700 }), agent = await f.agent(), row = memory.store.read().entries[0];
  await memory.store.save(f.data('shared', { id: row.id, content: '中文长正文'.repeat(300) }));
  await assert.rejects(memory.execute(agent, 'memory_read', { id: row.id }), { code: 'MEMORY_TOOL_LIMIT' });
  assert.equal((await memory.memory.call('read', { id: row.id })).entry.content, '中文长正文'.repeat(300));
  await assert.rejects(memory.execute({ session: agent.session }, 'memory_read', { id: row.id }), { code: 'MEMORY_NOT_READY' });
});

for (const change of ['remove', 'expiry', 'workspace', 'cancel', 'close']) {
  test(`Android memory local tool ${change} during final model resolution cannot expose prepared evidence`, async t => {
    const f = await localKnowledgeFixture(t);
    const memory = await localSharedMemory(f, { capacity: undefined }), agent = await f.agent(), row = memory.store.read().entries[0];
    const gate = Promise.withResolvers(); let calls = 0, entered = false;
    f.ctx.llm.resolveModelInfo = async () => { if (++calls === 2) { entered = true; await gate.promise; } return { context: { contextWindow: 32768 } }; };
    agent.session.requestHeader = () => ({ config: { provider: 'fixture', model: 'fixture', maxTokens: 100 } });
    const controller = new AbortController();
    const request = memory.execute(agent, 'memory_read', { id: row.id }, controller.signal);
    const rejected = assert.rejects(request, error => error.name === 'AbortError' || ['MEMORY_VERSION_CHANGED', 'MEMORY_STOPPED', 'MEMORY_MISSING'].includes(error.code));
    let closing;
    try {
      await localMemoryWait(() => entered, '记忆返回前未复核模型容量');
      if (change === 'remove') await memory.store.remove({ id: row.id });
      if (change === 'expiry') await memory.store.change(next => { next.entries[0].expiresAt = Date.now() - 1; });
      if (change === 'workspace') f.ctx.workingDirectory.get = () => join(workspace, 'changed');
      if (change === 'cancel') controller.abort();
      if (change === 'close') closing = memory.memory.close();
    } finally { gate.resolve(); await rejected; await closing; }
  });
}

test('Android knowledge local shared budget survives a knowledge module close and reopen', async t => {
  const f = await localKnowledgeFixture(t); await f.enable(); await f.start();
  const agent = await f.agent();
  for (let i = 0; i < 3; i++) await f.execute(agent, 'knowledge_search', { query: '资料' });
  await f.knowledge.close();
  const { openKnowledge } = await localKnowledgeModules();
  f.store = await openKnowledge(f.ctx, { root: f.root }); await f.store.ready;
  await f.start();
  await assert.rejects(f.execute(agent, 'knowledge_search', { query: '资料' }), { code: 'KNOWLEDGE_LIMIT' });
});

test('Android knowledge local shared budget follows real plugin child contexts under one engine', async t => {
  const f = await localKnowledgeFixture(t); await f.enable();
  const { knowledgeSession } = await import(pathToFileURL(join(core, 'knowledge.mjs')));
  const child = f.ctx.extend();
  assert.notEqual(child, f.ctx); assert.equal(child.root, f.ctx.root);
  f.knowledge = knowledgeSession(child, { store: f.store, capacity: 100000, memory: { view: () => ({ enabled: false, revision: 0, entries: [] }) } });
  await f.knowledge.ready;
  const memory = await localSharedMemory(f), agent = await f.agent();
  for (let i = 0; i < 3; i++) await f.execute(agent, 'knowledge_search', { query: '共享' });
  await assert.rejects(memory.execute(agent, 'memory_search', { query: '共享' }), { code: 'MEMORY_TOOL_LIMIT' });
});

test('Android memory local extraction keeps the message limit as a continuation boundary', async () => {
  const { selectEvents } = await import(pathToFileURL(join(core, 'memory-extract.mjs')));
  const { createUserMessage } = await load('@deepseek-ai/dsh-llm');
  const events = Array.from({ length: 40 }, (_, seq) => ({ seq, type: 'user/message', data: createUserMessage({
    source: { kind: 'user' }, content: [{ type: 'text', text: `第${seq}条` }],
  }) }));
  const first = selectEvents(events);
  assert.equal(first.messages.length, 32); assert.equal(first.next, 32);
  assert.deepEqual(first.skipped, []);
  const last = selectEvents(events.filter(row => row.seq >= first.next));
  assert.deepEqual([...first.messages, ...last.messages].map(row => row.id), events.map(row => row.seq));
});

for (const event of ['llm/adapters-updated', 'loader/volatile-update', 'credentials/reference-updated', 'credentials/record-updated']) {
  test(`Android memory local tool source change ${event} rejects delayed reads while automatic extraction is disabled`, async t => {
    const f = await localKnowledgeFixture(t);
    const memory = await localSharedMemory(f, { capacity: undefined }), agent = await f.agent(), row = memory.store.read().entries[0];
    const gate = Promise.withResolvers(); let calls = 0, entered = false;
    f.ctx.llm.resolveModelInfo = async () => { if (++calls === 2) { entered = true; await gate.promise; } return { context: { contextWindow: 32768 } }; };
    agent.session.requestHeader = () => ({ config: { provider: 'fixture', model: 'fixture', maxTokens: 100 } });
    assert.equal(memory.store.read().settings.autoSave, false);
    const request = memory.execute(agent, 'memory_read', { id: row.id });
    const rejected = assert.rejects(request, { code: 'MEMORY_VERSION_CHANGED' });
    try { await localMemoryWait(() => entered, '读取未进入最终容量等待'); f.ctx.emit(event); }
    finally { gate.resolve(); await rejected; }
  });
}

test('Android memory local schedule route boundary releases an earlier accumulating range before an urgent later range', async t => {
  const f = await localMemoryFlow(t);
  await f.turn('路由A下的普通内容');
  assert.equal(f.requests.length, 0);
  f.session.requestHeader = () => ({ config: { provider: 'fixture', model: 'source-model-b' } });
  f.flow.llm.prepareCall = async (config, signal, refine) => {
    const controls = refine(config, { provider: config.provider, id: config.model, context: { contextWindow: 32768 }, defaultMaxTokens: 256 });
    return { config: controls, async *stream(request) {
      f.requests.push({ route: config.model, input: JSON.parse(request.messages[0].content[0].text) });
      yield { type: 'text-delta', index: 0, text: '[]' }; yield { type: 'finish', reason: { kind: 'stop' } };
    } };
  };
  await f.turn('请记住：路由B下我习惯保留数据');
  await localMemoryWait(() => !f.store.read().jobs.length, '来源边界的积累任务挡住后续明确请求');
  assert.deepEqual(f.requests.map(row => row.route), ['source-model', 'source-model-b']);
  assert.deepEqual(f.requests.flatMap(row => row.input.messages.filter(m => m.role === 'user').map(m => m.id)), [1, 5]);
  assert.equal(f.store.read().cursors[f.session.id].next, f.events.length);
});

test('Android memory local schedule recalculates ordinary cooldown after a previous in-flight batch commits', async t => {
  const f = await localMemoryFlow(t);
  const prepare = f.flow.llm.prepareCall, gate = Promise.withResolvers(); let started = false;
  f.flow.llm.prepareCall = async (...args) => {
    const call = await prepare(...args);
    return { ...call, async *stream(request) { started = true; await gate.promise; yield* call.stream(request); } };
  };
  for (let i = 0; i < 4; i++) await f.turn();
  assert.equal(started, true);
  for (let i = 0; i < 4; i++) await f.turn();
  assert.equal(f.store.read().jobs.length, 2);
  assert.equal(f.store.read().jobs[1].ready, true);
  gate.resolve();
  await localMemoryWait(() => f.requests.length >= 1 && !f.store.read().jobs.some(row => row.message === '整理中'), '首批没有离开运行状态');
  await f.settle();
  assert.equal(f.requests.length, 1, '新普通任务必须以首批刚写入的成功时间计算冷却');
  assert.equal(f.store.read().jobs.length, 1);
  await f.memory.call('organize');
  await localMemoryWait(() => f.requests.length === 2 && !f.store.read().jobs.length, '手动整理仍应绕过冷却');
});

test('Android knowledge local shared budget rejects a request header changed while evidence was prepared', async t => {
  const f = await localKnowledgeFixture(t); await f.import('请求路由变化前的证据'); await f.enable();
  f.ctx.llm.resolveModelInfo = async () => ({ context: { contextWindow: 32768 } });
  const agent = await f.agent(), header = { config: { provider: 'fixture', model: 'original', maxTokens: 100 } };
  agent.session.requestHeader = () => header;
  await f.start({ capacity: undefined, search: async rows => {
    header.config.model = 'changed';
    return { mode: 'keyword', results: rows.map(row => ({ row, matchedBy: ['keyword'] })) };
  } });
  await assert.rejects(f.execute(agent, 'knowledge_search', { query: '证据' }), { code: 'KNOWLEDGE_VERSION_CHANGED' });
});

test('Android hosting learning distinguishes execution from business results and rejects missing evidence', async () => {
  const { specimenEvidence } = await import(pathToFileURL(join(core, 'specimen.mjs')));
  const source = { kind: 'plugin:dshx-hosting', rpcId: 'learn-1' };
  const event = (seq, type, data) => ({ seq, type, data });
  const rows = [event(0, 'user/message', { role: 'user', source, content: [{ type: 'text', text: '打开设置' }] }),
    event(1, 'tool/call', { turn: 1, step: 0, callId: 'click-1', name: 'phone_click', arguments: '{}' }),
    event(2, 'tool/result', { message: { toolCallId: 'click-1', content: [{ type: 'text', text: JSON.stringify({ ok: true, execution_state: 'completed' }) }] } }),
    event(3, 'assistant/message', { message: { role: 'assistant', content: [{ type: 'text', text: '已成功打开设置' }] } }),
    event(4, 'turn/end', { turn: 1, reason: { kind: 'completed' } })];
  const run = { id: 'learn-1', from: 0, to: 5, session: 's', workspace, execution: 'completed' };
  assert.equal(specimenEvidence(rows, run).result, 'not_checked', '点击成功与模型总结不能证明目标完成');
  rows[2].data.message.content[0].text = JSON.stringify({ ok: false, execution_state: 'unknown' });
  assert.equal(specimenEvidence(rows, run).result, 'unknown');
  rows[2].data.message.content[0].text = JSON.stringify({ ok: false, execution_state: 'not_started', code: 'locked' });
  assert.equal(specimenEvidence(rows, run).result, 'failed');
  assert.throws(() => specimenEvidence(rows.filter(row => row.seq !== 2), run), /来源/);
  assert.throws(() => specimenEvidence(rows, { ...run, id: 'another-opportunity' }), /来源/);
  const longRows = [rows[0], ...Array.from({ length: 800 }, (_, index) => event(index + 1, 'assistant/message', {
    message: { content: [{ type: 'text', text: 'x'.repeat(5999) }] } })), event(801, 'turn/end', { reason: { kind: 'completed' } })];
  const limited = specimenEvidence(longRows, { ...run, to: 802 });
  assert.equal(limited.coverage.complete, false); assert.ok(limited.coverage.omitted.length > 0);
  assert.ok(Buffer.byteLength(JSON.stringify(limited)) < 30000);
  const observations = [rows[0], ...['系统设置', '其他页面'].flatMap((text, index) => [
    event(index * 2 + 1, 'tool/call', { callId: `observation-${index}`, name: 'phone_observe' }),
    event(index * 2 + 2, 'tool/result', { message: { toolCallId: `observation-${index}`, content: [{ type: 'text', text: JSON.stringify({ ok: true, nodes: [{ text }] }) }] } }),
  ]), event(5, 'turn/end', { reason: { kind: 'completed' } })];
  assert.equal(specimenEvidence(observations, { ...run, to: 6, checkText: '系统设置' }).result, 'not_checked', '以最后一次观察为准，离开目标页后不沿用旧检查');
});

async function specimenFixture(t, options = {}) {
  const f = await localMemoryFixture(t);
  const { specimenSession } = await import(pathToFileURL(join(core, 'specimen.mjs')));
  const events = [], listeners = new Map(), session = { id: 'specimen-fixture', seq: 0, header: { cwd: workspace } };
  const flow = { storageDomain: f.ctx.storageDomain, workingDirectory: { get: () => workspace },
    sessions: { flush: options.flush ?? (async () => true) },
    sessionPersistence: { open: async () => ({ read: async (from, count) => {
      const rows = events.slice(from, from + count); await options.read?.(); return { events: rows };
    }, close: async () => {} }) },
    on(name, fn) { listeners.set(name, fn); return () => listeners.delete(name); } };
  let learning = await specimenSession(flow, () => ({ enabled: true }));
  t.after(() => learning.close());
  return { events, listeners, session, get learning() { return learning; },
    add(type, data) { const event = { seq: session.seq++, type, data }; events.push(event); return event; },
    async reopen() { await learning.close(); learning = await specimenSession(flow, () => ({ enabled: true })); },
    async goal(mode = 'save') { return learning.call('goal', { id: 'current', mode, revision: learning.status().revision,
      text: '找到设置页面', checkText: '系统设置', maxRuns: 10 }); },
  };
}

test('Android hosting learning blocks uncertain actions after recovery and source flush failure', async t => {
  for (const mode of ['restart', 'flush-failed']) {
    const f = await specimenFixture(t, { flush: async () => { throw new Error('fixture disk failure'); } });
    await f.goal();
    const active = { id: mode, session: f.session.id, agent: { session: f.session }, startedAt: 1, turn: 1, entered: true, toolCalls: 1 };
    await f.learning.start(active);
    if (mode === 'restart') await f.reopen();
    else await f.learning.finish(active, 'completed', 'completed');
    assert.equal(f.learning.blocked(), 'unknown_result');
    assert.equal(f.learning.status().goal.state, 'paused');
    await assert.rejects(f.goal(), /未知/);
    const revision = f.learning.status().revision;
    await f.learning.call('resolve', { id: mode, revision });
    await f.goal(); assert.equal(f.learning.blocked(), undefined);
  }
});

test('Android hosting learning catches the end event while reading an earlier source range', async t => {
  let release, entered; const reading = new Promise(resolve => { entered = resolve; });
  let once = true;
  const f = await specimenFixture(t, { read: async () => { if (once) { once = false; entered(); await new Promise(resolve => { release = resolve; }); } } });
  const active = { id: 'racing-end', session: f.session.id, agent: { session: f.session }, startedAt: 1, turn: 1, entered: true };
  await f.learning.start(active);
  f.add('user/message', { source: { kind: 'plugin:dshx-hosting', rpcId: active.id }, content: [{ type: 'text', text: '观察' }] });
  const finishing = f.learning.finish(active, 'cancelled', 'user_input');
  await reading;
  const end = f.add('turn/end', { turn: 1, reason: { kind: 'cancelled' } });
  f.listeners.get('session/event')(f.session, end); release();
  await finishing; await f.learning.idle();
  assert.equal(f.learning.status().runs[0].to, 2, '迟到事件应触发追读，不能永久保留空结束位置');
});

test('Android hosting learning finishes a cancelled source after its delayed end and marks source failure incomplete', async t => {
  const f = await localMemoryFixture(t);
  const { specimenSession } = await import(pathToFileURL(join(core, 'specimen.mjs')));
  for (const mode of ['cancelled', 'flush-failed']) {
    const events = [], listeners = new Map(), session = { id: mode, seq: 0, header: { cwd: workspace } }; let flush = mode !== 'flush-failed';
    const ctx = { storageDomain: f.ctx.storageDomain, workingDirectory: { get: () => workspace }, sessions: { flush: async () => flush },
      sessionPersistence: { open: async () => ({ read: async (from = 0, count = events.length) => ({ events: events.slice(from, from + count) }), close: async () => {} }) },
      on(name, fn) { listeners.set(name, fn); return () => listeners.delete(name); } };
    const learning = await specimenSession(ctx, () => ({ enabled: true }));
    try {
      const active = { id: mode, session: session.id, agent: { session }, turn: 1, startedAt: 1 };
      await learning.start(active);
      events.push({ seq: session.seq++, type: 'user/message', data: { role: 'user', source: { kind: 'plugin:dshx-hosting', rpcId: mode }, content: [{ type: 'text', text: '查看当前状态' }] } });
      await learning.finish(active, mode === 'cancelled' ? 'cancelled' : 'completed', mode); await learning.idle();
      assert.notEqual(learning.status().runs.find(row => row.id === mode).execution, 'running');
      assert.equal(learning.status().runs.find(row => row.id === mode).result, 'unknown');
      flush = true;
      const end = { seq: session.seq++, type: 'turn/end', data: { turn: 1, reason: { kind: 'cancelled' } } }; events.push(end);
      listeners.get('session/event')(session, end); await learning.idle();
      assert.equal(learning.status().runs.find(row => row.id === mode).to, 2);
      assert.equal((await learning.evidence(mode)).rows[0].seq, 0);
    } finally { await learning.close(); }
  }
});

test('Android hosting learning background requests serialize and foreground cancellation releases the queue', async () => {
  const { runBackground, interruptBackground } = await import(pathToFileURL(join(core, 'background.mjs')));
  const ctx = {}, gate = Promise.withResolvers(), events = [];
  const first = runBackground(ctx, undefined, async signal => {
    events.push('first'); await gate.promise; signal.throwIfAborted();
  });
  const cancelled = assert.rejects(first, /前台/);
  await delay(0);
  const second = runBackground(ctx, undefined, async () => events.push('second'));
  assert.deepEqual(events, ['first']);
  interruptBackground(ctx); gate.resolve(); await cancelled; await second;
  assert.deepEqual(events, ['first', 'second']);
});

test('Android hosting learning candidate steps require actual paired calls and exact source quotations', async () => {
  const { specimenCandidate } = await import(pathToFileURL(join(core, 'specimen.mjs')));
  const run = { id: 'candidate-run', result: 'not_checked' };
  const evidence = { result: 'not_checked', calls: [{ seq: 1, name: 'phone_open', result: 2 }],
    rows: [{ seq: 2, kind: 'tool/result', text: '{"ok":true}' }] };
  const value = { name: 'open-settings', title: '打开设置', description: '使用真实包名打开设置', conditions: ['系统设置可启动'],
    steps: [{ text: '按真实包名打开设置，然后重新观察。', call: 1, evidence: [{ seq: 2, text: '"ok":true' }] }],
    pitfalls: ['页面不匹配时停止'], verification: '重新观察实际页面，核对设置标题。' };
  const candidate = specimenCandidate(value, evidence, run);
  assert.equal(candidate.state, 'candidate'); assert.match(candidate.body, /适用条件/); assert.equal(candidate.uses.length, 0);
  assert.throws(() => specimenCandidate({ ...value, steps: [{ ...value.steps[0], call: 99 }] }, evidence, run), /依据/);
  assert.throws(() => specimenCandidate({ ...value, steps: [{ ...value.steps[0], evidence: [{ seq: 2, text: 'invented' }] }] }, evidence, run), /依据/);
  assert.throws(() => specimenCandidate(value, { ...evidence, result: 'unknown' }, run), /未知/);
});

test('Android hosting learning completes one bounded review and stages an idempotent native adoption', async t => {
  const f = await localMemoryFixture(t);
  const { specimenSession } = await import(pathToFileURL(join(core, 'specimen.mjs')));
  const events = [], session = { id: 'learning-review', seq: 0, header: { cwd: workspace } };
  const settings = { enabled: true, learningEnabled: true }, listeners = new Map(); let requests = 0;
  const flow = { storageDomain: f.ctx.storageDomain, workingDirectory: { get: () => workspace }, sessions: { flush: async () => true },
    sessionPersistence: { open: async () => ({ read: async (from = 0, count = events.length) => ({ events: events.slice(from, from + count) }), close: async () => {} }) },
    on(name, fn) { listeners.set(name, fn); return () => listeners.delete(name); },
    llm: { async prepareCall(config, signal, refine) {
      return { config: refine(config, { provider: 'fixture', id: 'source-model', context: { contextWindow: 32768 } }), async *stream(options) {
        requests++; assert.deepEqual(options.tools, []); assert.equal(options.purpose, 'specimen-extraction');
        yield { type: 'text-delta', index: 0, text: JSON.stringify({ name: 'observe-page', title: '读取页面', description: '读取当前页面的可见文字',
          conditions: ['设备解锁且无障碍可用'], steps: [{ text: '读取当前页面控件。', call: 2, evidence: [{ seq: 3, text: '"ok":true' }] }],
          pitfalls: ['界面变化后重新观察'], verification: '只报告实际读到的文字。' }) };
        yield { type: 'finish', reason: { kind: 'stop' } };
      } };
    } } };
  const learning = await specimenSession(flow, () => settings); t.after(() => learning.close());
  const active = { id: 'learn-review', session: session.id, agent: { session }, startedAt: 1, turn: 1 };
  await learning.start(active);
  const add = (type, data) => events.push({ seq: session.seq++, type, data });
  add('user/message', { role: 'user', source: { kind: 'plugin:dshx-hosting', rpcId: active.id }, content: [{ type: 'text', text: '读取当前页面' }] });
  add('request/header', { header: { config: { provider: 'fixture', model: 'source-model' } } });
  add('tool/call', { turn: 1, callId: 'observe-1', name: 'phone_observe', arguments: '{}' });
  add('tool/result', { message: { toolCallId: 'observe-1', content: [{ type: 'text', text: '{"ok":true,"nodes":[{"text":"设置"}]}' }] } });
  add('assistant/message', { message: { role: 'assistant', content: [{ type: 'text', text: '已读取设置页面' }] } });
  add('turn/end', { turn: 1, reason: { kind: 'completed' } });
  await learning.finish(active, 'completed', 'completed'); await learning.idle();
  assert.equal(requests, 1); assert.equal(learning.status().skills.length, 1); assert.equal(learning.status().jobs.length, 0);
  const candidate = learning.status().skills[0]; assert.equal(candidate.state, 'candidate'); assert.equal(candidate.body, undefined);
  const prepared = await learning.call('prepare', { id: candidate.id, revision: candidate.revision });
  const repeated = await learning.call('prepare', { id: candidate.id, revision: candidate.revision });
  assert.equal(repeated.skill.operation, prepared.skill.operation);
  await assert.rejects(learning.call('discard', { id: candidate.id, revision: candidate.revision }), /采用/);
  await assert.rejects(learning.call('rejected', { id: candidate.id, operation: 'wrong', digest: prepared.skill.digest }), /不匹配/);
  await learning.call('rejected', { id: candidate.id, operation: prepared.skill.operation, digest: prepared.skill.digest });
  const unlocked = learning.status().skills[0]; assert.equal(unlocked.operation, undefined);
  const retry = await learning.call('prepare', { id: candidate.id, revision: unlocked.revision });
  assert.notEqual(retry.skill.operation, prepared.skill.operation);
  const receipt = { id: candidate.id, operation: retry.skill.operation, digest: retry.skill.digest, path: `project:${retry.skill.name}/SKILL.md` };
  await learning.call('applied', receipt); await learning.call('applied', receipt);
  assert.equal(learning.status().skills[0].state, 'applied');
  await learning.finish(active, 'completed', 'completed'); await learning.idle();
  assert.equal(requests, 1, '重复结束通知不应再次整理');
  settings.learningEnabled = false; settings.prompt = '读取当前页面的可见文字';
  const skillPath = join(workspace, '.dsh/skills', retry.skill.name, 'SKILL.md');
  await mkdir(join(workspace, '.dsh/skills', retry.skill.name), { recursive: true }); await writeFile(skillPath, retry.skill.body);
  const reuse = { id: 'reuse-review', session: session.id, agent: { session }, startedAt: 2, turn: 2 };
  await learning.start(reuse);
  assert.equal(reuse.skill, candidate.id); assert.match(reuse.context, /相关已采用方法/);
  add('user/message', { source: { kind: 'plugin:dshx-hosting', rpcId: reuse.id }, content: [{ type: 'text', text: settings.prompt }] });
  add('tool/call', { callId: 'observe-2', name: 'phone_observe', arguments: '{}' });
  add('tool/result', { message: { toolCallId: 'observe-2', content: [{ type: 'text', text: '{"ok":true,"nodes":[{"text":"设置"}]}' }] } });
  add('turn/end', { turn: 2, reason: { kind: 'completed' } });
  await learning.finish(reuse, 'completed', 'completed'); await learning.idle();
  assert.equal(learning.status().skills[0].useCount, 1); assert.equal(learning.status().skills[0].supportedCount, 0, '实际调用但未核验不计成功');
  await writeFile(skillPath, retry.skill.body + '\n用户编辑');
  const edited = { id: 'edited-review', session: session.id, agent: { session }, startedAt: 3 };
  await learning.start(edited); assert.equal(edited.skill, undefined); assert.doesNotMatch(edited.context, /相关已采用方法/);
  await learning.finish(edited, 'cancelled', 'fixture'); await learning.idle();
  await rm(join(workspace, '.dsh/skills', retry.skill.name), { recursive: true });
});

test('Android hosting learning durable state rejects stale updates and preserves completed runs across reopen', async t => {
  const f = await localMemoryFixture(t);
  const { openSpecimen } = await import(pathToFileURL(join(core, 'specimen-store.mjs')));
  let store = await openSpecimen(f.ctx); t.after(() => store.close());
  await store.change(next => next.runs.push({ id: 'learn-store', session: 's', workspace, from: 0, to: 5,
    execution: 'completed', result: 'not_checked', startedAt: 1, endedAt: 2, revision: 0, requests: [] }));
  const saved = store.read();
  await assert.rejects(store.change(next => { next.runs = []; }, 0), /变化/);
  await store.close(); store = await openSpecimen(f.ctx);
  assert.deepEqual(store.read(), saved);
  await assert.rejects(store.change(next => { next.runs[0].to = -1; }), /无效/);
  assert.equal(store.read().runs.length, 1);
});

async function hostingFixture(t, options = {}) {
  const { hostingSession } = await import(pathToFileURL(join(core, 'hosting.mjs')));
  const listeners = new Map(), timers = new Map(), records = [], prompts = [], removed = [];
  let clock = 0, nextTimer = 0, queries = 0, resolutions = 0, rolls = 0, cancellations = 0;
  const config = { enabled: true, probability: 100, session: 'hosting-session', prompt: 'private hosting task' };
  const status = { ok: true, enabled: true, locked: false, interactive: true, accessibility: true, ready: true };
  const agent = { id: config.session, status: 'idle', session: { id: config.session, header: {}, deriveMessages: () => [] },
    inbox: { nextTurn: [], nextStep: [], get hasPending() { return Boolean(this.nextTurn.length || this.nextStep.length); },
      remove(id) { removed.push(id); this.nextTurn = this.nextTurn.filter(message => message.id !== id); } },
    cancel() { cancellations++; agent.status = 'idle'; } };
  const agents = [agent];
  const emit = (name, ...args) => { for (const listener of [...listeners.get(name) ?? []]) listener(...args); };
  const ctx = { agents: { list: () => agents },
    on(name, listener) { const entries = listeners.get(name) ?? new Set(); listeners.set(name, entries); entries.add(listener); return () => entries.delete(listener); },
    sessionController: { async create() { return options.create ? options.create() : { sessionId: config.session }; }, commands: {
      async resolveAgent() { resolutions++; await options.resolve?.(); return agent; },
      async prompt(request, source) {
        prompts.push({ request, source });
        if (options.prompt) return options.prompt(request, source);
        emit('agent/inbox/claimed', { agent, message: { source }, turn: prompts.length });
        agent.status = 'running';
      },
    } },
  };
  const hosting = hostingSession(ctx, {
    store: options.store,
    learning: options.learning,
    settings: () => ({ ...config }), now: () => clock,
    record: row => { if (options.record) options.record(row); records.push(structuredClone(row)); },
    host: { async query(method, data) { assert.equal(method, 'phone-status'); assert.deepEqual(data, {}); queries++; return options.status ? options.status() : { ...status }; } },
    dice: () => { rolls++; return options.roll ?? 0; },
    setTimeout(fn, ms) { const id = ++nextTimer; timers.set(id, { fn, at: clock + ms }); return id; },
    clearTimeout(id) { timers.delete(id); },
  });
  t.after(() => hosting.close());
  return { hosting, config, status, agent, agents, records, prompts, removed, emit, timers,
    get queries() { return queries; }, get resolutions() { return resolutions; }, get rolls() { return rolls; }, get cancellations() { return cancellations; },
    async advance(ms) { clock += ms; const due = [...timers].filter(([, timer]) => timer.at <= clock); for (const [id, timer] of due) { if (timers.delete(id)) timer.fn(); } await delay(0); },
    async preStep(messages) { const handlers = [...listeners.get('agent/pre-step') ?? []]; assert.equal(handlers.length, 1);
      return handlers[0]({ agent }, async () => ({ kind: 'enter', messages })); },
    end(kind = 'completed') { agent.status = 'idle'; emit('session/event', agent.session, { type: 'turn/end', data: { turn: prompts.length, reason: { kind } } }); },
  };
}

test('Android hosting learning rechecks other foreground work after durable start admission', async t => {
  const gate = Promise.withResolvers();
  const f = await hostingFixture(t, { learning: { start: () => gate.promise, finish() {} } });
  const checking = f.hosting.check(); await delay(0);
  f.agents.push({ status: 'running', inbox: { hasPending: false } });
  gate.resolve(); await checking;
  assert.equal(f.prompts.length, 0); assert.equal(f.records.at(-1).reason, 'busy');
});

test('Android hosting learning commits goal management before cancellation and rejects stale input without stopping', async t => {
  const source = await specimenFixture(t);
  const learning = source.learning;
  await source.goal();
  const f = await hostingFixture(t, { learning });
  f.agent.session = source.session;
  await f.hosting.check();
  const id = f.prompts[0].source.rpcId;
  source.add('user/message', { source: { kind: 'plugin:dshx-hosting', rpcId: id }, content: [{ type: 'text', text: '观察' }] });
  source.add('turn/end', { turn: 1, reason: { kind: 'cancelled' } });
  const revision = learning.status().revision;
  await assert.rejects(f.hosting.manage('goal', { id: 'current', mode: 'pause', revision: revision - 1 }), /变化/);
  assert.equal(f.cancellations, 0); assert.equal(f.hosting.status().running, true);
  await f.hosting.manage('goal', { id: 'current', mode: 'pause', revision });
  assert.equal(f.cancellations, 1); assert.equal(learning.status().goal.state, 'paused');
  assert.equal(learning.status().goal.reason, 'user_paused');
  const interrupted = await specimenFixture(t); await interrupted.goal();
  const update = interrupted.learning.store.change, gate = Promise.withResolvers(), admitted = Promise.withResolvers(); let hold = true;
  interrupted.learning.store.change = async (...args) => {
    if (hold) { hold = false; admitted.resolve(); await gate.promise; }
    return update(...args);
  };
  const second = await hostingFixture(t, { learning: interrupted.learning }); second.agent.session = interrupted.session;
  const checking = second.hosting.check(); await admitted.promise;
  await second.hosting.manage('goal', { id: 'current', mode: 'save', revision: interrupted.learning.status().revision,
    text: '新的目标', checkText: '', maxRuns: 2 });
  gate.resolve(); await checking;
  assert.equal(second.prompts.length, 0); assert.equal(interrupted.learning.status().runs.length, 0, '已取消的启动不能迟到发布 running 记录');
  await interrupted.reopen(); assert.equal(interrupted.learning.blocked(), undefined);
});

test('Android hosting learning enforces the request budget before model dispatch and includes retries', async t => {
  const { usageStream } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const f = await hostingFixture(t); await f.hosting.check(); let dispatched = 0;
  const request = async () => {
    for await (const _ of usageStream({ purpose: 'conversation', provider: 'fixture', model: 'one', sessionId: f.agent.session.id }, async function* () {
      dispatched++; yield { type: 'finish', reason: { kind: 'stop' } };
    })) { /* drain */ }
  };
  for (let index = 0; index < 6; index++) await request();
  await assert.rejects(request(), /上限/);
  assert.equal(dispatched, 6); assert.equal(f.records.at(-1).reason, 'request_limit');
  const tools = await hostingFixture(t); await tools.hosting.check();
  for (let index = 0; index < 20; index++) tools.emit('tools/pre-execute', { agent: tools.agent, name: 'read_file' }, () => {});
  assert.throws(() => tools.emit('tools/pre-execute', { agent: tools.agent, name: 'read_file' }, () => {}), /上限/);
  assert.equal(tools.records.at(-1).reason, 'tool_limit', '非手机工具同样计数');
});

test('Android hosting learning pauses repeated observations and preserves its budget after restart', async t => {
  const f = await specimenFixture(t); await f.goal();
  for (let turn = 1; turn <= 4; turn++) {
    const active = { id: `no-progress-${turn}`, session: f.session.id, agent: { session: f.session }, startedAt: turn, turn };
    await f.learning.start(active);
    f.add('user/message', { source: { kind: 'plugin:dshx-hosting', rpcId: active.id }, content: [{ type: 'text', text: '查找设置' }] });
    f.add('tool/call', { callId: `observe-${turn}`, name: 'phone_observe', arguments: '{}' });
    f.add('tool/result', { message: { toolCallId: `observe-${turn}`, content: [{ type: 'text', text: '{"ok":true,"nodes":[{"text":"同一页面"}]}' }] } });
    f.add('assistant/message', { message: { content: [{ type: 'text', text: `第 ${turn} 次改写总结` }] } });
    f.add('turn/end', { turn, reason: { kind: 'completed' } });
    await f.learning.finish(active, 'completed', 'completed');
  }
  assert.equal(f.learning.status().goal.noProgress, 3); assert.equal(f.learning.status().goal.state, 'paused');
  assert.equal(f.learning.status().goal.attempts, 4); await f.reopen();
  assert.equal(f.learning.blocked(), 'goal_paused'); assert.equal(f.learning.status().goal.attempts, 4);
  await assert.rejects(f.goal('resume'), /预算|进展/);
});

test('Android hosting learning goal budgets survive restart and completion requires a real final observation', async t => {
  const f = await localMemoryFixture(t);
  const { specimenSession } = await import(pathToFileURL(join(core, 'specimen.mjs')));
  const events = [], session = { id: 'goal-session', seq: 0, header: { cwd: workspace } };
  const flow = { storageDomain: f.ctx.storageDomain, workingDirectory: { get: () => workspace }, sessions: { flush: async () => true },
    sessionPersistence: { open: async () => ({ read: async (from, count) => ({ events: events.slice(from, from + count) }), close: async () => {} }) },
    on() { return () => {}; } };
  let learning = await specimenSession(flow, () => ({ enabled: true, prompt: '打开设置' }));
  try {
    await learning.call('goal', { id: 'current', mode: 'save', revision: learning.status().revision, text: '找到设置页面', checkText: '系统设置', maxRuns: 1 });
    const active = { id: 'goal-run', session: session.id, agent: { session }, startedAt: 1, turn: 1 };
    await learning.start(active);
    const add = (type, data) => events.push({ seq: session.seq++, type, data });
    add('user/message', { role: 'user', source: { kind: 'plugin:dshx-hosting', rpcId: active.id }, content: [{ type: 'text', text: '设置' }] });
    add('tool/call', { callId: 'observe-goal', name: 'phone_observe' });
    add('tool/result', { message: { toolCallId: 'observe-goal', content: [{ type: 'text', text: '{"ok":true,"nodes":[{"text":"系统设置"}]}' }] } });
    add('turn/end', { turn: 1, reason: { kind: 'completed' } });
    await learning.finish(active, 'completed', 'completed'); await learning.idle();
    assert.equal(learning.status().goal.state, 'completed'); assert.equal(learning.status().runs[0].result, 'supported');
    await learning.finish(active, 'completed', 'completed'); await learning.idle();
    assert.equal(learning.status().goal.attempts, 1); assert.equal(learning.status().goal.findings.length, 1);
    await learning.close(); learning = await specimenSession(flow, () => ({ enabled: true }));
    assert.equal(learning.blocked(), 'goal_completed'); assert.equal(learning.status().goal.attempts, 1);
  } finally { await learning.close(); }
});

test('Android hosting uses local probability and never resolves a model session on a miss or while disabled', async t => {
  const f = await hostingFixture(t, { roll: 20 });
  f.config.enabled = false;
  await f.hosting.check();
  assert.equal(f.queries, 0); assert.equal(f.records.length, 0);
  f.config.enabled = true; f.config.probability = 20;
  await f.hosting.check();
  assert.equal(f.resolutions, 0); assert.equal(f.prompts.length, 0);
  assert.equal(f.records.at(-1).reason, 'probability');
  f.config.probability = 21;
  await f.hosting.check();
  assert.equal(f.prompts.length, 1);
  assert.equal(f.prompts[0].source.kind, 'plugin:dshx-hosting');
  assert.equal(f.prompts[0].request.sessionId, 'hosting-session');
  assert.match(f.prompts[0].request.content[0].text, /private hosting task/);
  assert.doesNotMatch(JSON.stringify(f.records), /private hosting task/);
});

test('Android hosting skips locked screen-off disabled offline and unknown devices before rolling or sending', async t => {
  for (const [patch, reason] of [
    [{ locked: true }, 'locked'], [{ interactive: false }, 'screen_off'], [{ enabled: false }, 'phone_disabled'],
    [{ accessibility: false }, 'accessibility_offline'], [{ ready: false }, 'device_unavailable'], [{ ok: false }, 'device_unavailable'],
  ]) {
    const f = await hostingFixture(t); Object.assign(f.status, patch);
    await f.hosting.check();
    assert.equal(f.records.at(-1).reason, reason);
    assert.equal(f.rolls, 0); assert.equal(f.resolutions, 0); assert.equal(f.prompts.length, 0);
  }
});

test('Android hosting waits five minutes and coalesces an hour asleep into only the current opportunity', async t => {
  const f = await hostingFixture(t, { roll: 99 }); f.config.probability = 1;
  await f.advance(299999); assert.equal(f.records.length, 0);
  await f.advance(1); assert.equal(f.records.length, 1);
  await f.advance(3600000); assert.equal(f.records.length, 2);
  await f.advance(299999); assert.equal(f.records.length, 2);
  await f.advance(1); assert.equal(f.records.length, 3);
  f.hosting.close(); await f.advance(3600000); assert.equal(f.records.length, 3);
});

test('Android hosting skips busy sessions and rechecks a lock or user work after asynchronous session activation', async t => {
  const f = await hostingFixture(t);
  f.agent.status = 'running'; await f.hosting.check();
  assert.equal(f.records.at(-1).reason, 'busy'); assert.equal(f.rolls, 0);
  f.agent.status = 'idle'; f.agent.inbox.nextTurn.push({ id: 'human' }); await f.hosting.check();
  assert.equal(f.prompts.length, 0); f.agent.inbox.nextTurn = [];
  const lock = await hostingFixture(t, { resolve: () => { lock.status.locked = true; } });
  await lock.hosting.check(); assert.equal(lock.records.at(-1).reason, 'locked'); assert.equal(lock.prompts.length, 0);
  const work = await hostingFixture(t, { resolve: () => { work.agent.status = 'running'; } });
  await work.hosting.check(); assert.equal(work.records.at(-1).reason, 'busy'); assert.equal(work.prompts.length, 0);
});

test('Android hosting applies configuration changes during checks and cancels only its own active turn', async t => {
  let release;
  const f = await hostingFixture(t, { status: () => new Promise(resolve => { release = resolve; }) });
  const pending = f.hosting.check();
  f.config.enabled = false; f.hosting.refresh();
  release({ ...f.status }); await pending;
  assert.equal(f.prompts.length, 0); assert.equal(f.records.at(-1).reason, 'settings_changed');
  const active = await hostingFixture(t); await active.hosting.check();
  active.config.enabled = false; active.hosting.refresh();
  assert.equal(active.cancellations, 1); assert.equal(active.records.at(-1).reason, 'settings_changed');
  await active.advance(600000); assert.equal(active.prompts.length, 1);
});

test('Android hosting rejects stale recovered inputs and preserves an ordinary human input in the same batch', async t => {
  const f = await hostingFixture(t);
  const stale = { id: 'stale', role: 'user', source: { kind: 'plugin:dshx-hosting', rpcId: 'old-process' } };
  const human = { id: 'human', role: 'user', source: { kind: 'user' } };
  assert.deepEqual(await f.preStep([stale]), { kind: 'reject' });
  assert.deepEqual((await f.preStep([stale, human])).messages, [human]);
  f.agent.inbox.nextTurn = [stale, human]; f.emit('agent/created', { agent: f.agent });
  assert.deepEqual(f.agent.inbox.nextTurn, [human]);
  f.agent.session.deriveMessages = () => [stale];
  assert.deepEqual(await f.preStep([]), { kind: 'reject' });
});

test('Android hosting user input and timeouts cancel the current turn without queuing missed opportunities', async t => {
  const f = await hostingFixture(t); await f.hosting.check();
  f.emit('agent/inbox/inserted', { agent: f.agent, message: { source: { kind: 'user' } } });
  assert.equal(f.cancellations, 1); assert.equal(f.records.at(-1).reason, 'user_input');
  await f.hosting.check(); assert.equal(f.prompts.length, 2);
  await f.advance(170000);
  assert.equal(f.cancellations, 2); assert.equal(f.records.at(-1).reason, 'timeout');
  await f.advance(130000); assert.equal(f.prompts.length, 3);
});

test('Android hosting checks current device readiness again before the model step and stops on lock', async t => {
  const f = await hostingFixture(t); await f.hosting.check();
  const message = { source: f.prompts[0].source };
  assert.equal((await f.preStep([message])).kind, 'enter');
  f.status.locked = true;
  assert.deepEqual(await f.preStep([]), { kind: 'reject' });
  assert.equal(f.cancellations, 1); assert.equal(f.records.at(-1).reason, 'locked');
  f.status.locked = false; await f.hosting.check(); assert.equal(f.prompts.length, 2);
});

test('Android hosting records final turn and native failure or unknown results without private content or replay', async t => {
  for (const [result, outcome, key] of [
    [{ value: { ok: true, execution_state: 'completed' } }, 'completed', 'completed'],
    [{ value: { ok: false, execution_state: 'not_started' } }, 'failed', 'failed'],
    [{ value: { ok: false, execution_state: 'unknown' } }, 'unknown', 'unknown'],
  ]) {
    const f = await hostingFixture(t); await f.hosting.check();
    f.emit('tools/result', { agent: f.agent, name: 'phone_home' }, result);
    f.end();
    assert.equal(f.records.at(-1).event, 'finish'); assert.equal(f.records.at(-1).outcome, outcome);
    assert.equal(f.records.at(-1).tools[key], 1); assert.equal(f.prompts.length, 1);
    await f.hosting.check(); assert.equal(f.prompts.length, 2);
  }
});

test('Android hosting requires a valid task and refuses a model submission if durable start recording fails', async t => {
  const f = await hostingFixture(t); f.config.prompt = ' '; await f.hosting.check();
  assert.equal(f.records.at(-1).reason, 'not_configured'); assert.equal(f.queries, 0);
  f.config.prompt = 'task'; f.config.session = '../session'; await f.hosting.check();
  assert.equal(f.records.at(-1).reason, 'not_configured'); assert.equal(f.prompts.length, 0);
  const broken = await hostingFixture(t, { record: row => { if (row.event === 'start') throw new Error('disk full'); } });
  await broken.hosting.check(); assert.equal(broken.prompts.length, 0);
  assert.equal(broken.records.at(-1).reason, 'unavailable');
  const { hostingEvent } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const dir = await mkdtemp(join(directory, 'hosting-log-'));
  hostingEvent({ event: 'check', outcome: 'skipped', reason: 'locked' }, dir);
  const file = join(dir, 'hosting.jsonl');
  assert.equal((await stat(file)).mode & 0o777, 0o600);
  assert.equal(JSON.parse((await readFile(file, 'utf8')).trim()).reason, 'locked');
});

test('Android hosting creates one independent session after a hit and reuses its saved identity after restart', async t => {
  let value = { session: '' }, created = 0, saves = 0;
  const store = { get: () => ({ ...value }), async set(next) { saves++; value = { ...next }; } };
  const options = { store, create: async () => { created++; return { sessionId: 'hosting-session' }; } };
  const f = await hostingFixture(t, options); f.config.session = ''; f.config.probability = 0;
  await f.hosting.check(); assert.equal(created, 0); assert.equal(saves, 0);
  f.config.probability = 100; await f.hosting.check();
  assert.equal(created, 1); assert.equal(saves, 1); assert.equal(value.session, 'hosting-session');
  f.end(); f.hosting.close();
  const restarted = await hostingFixture(t, options); restarted.config.session = '';
  await restarted.hosting.check();
  assert.equal(restarted.prompts[0].request.sessionId, 'hosting-session');
  assert.equal(created, 1); assert.equal(saves, 1);
});

test('Android hosting actual command admission model loop and phone results settle one recorded opportunity', async t => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools, defineTool } = await load('@deepseek-ai/dsh-tools');
  const { default: LlmRuntime, LlmAdapter } = await load('@deepseek-ai/dsh-llm');
  const { default: Agents } = await load('@deepseek-ai/dsh-agent');
  const { default: Sessions } = await load('@deepseek-ai/dsh-session');
  const { default: Projections } = await load('@deepseek-ai/dsh-session-projection');
  const { default: AgentLoop } = await load('@deepseek-ai/dsh-agent-loop');
  const { SessionCommandController } = await import(pathToFileURL(join(require.resolve('@deepseek-ai/dsh-api-session-controller'), '..', 'types/commands.js')));
  const { hostingSession } = await import(pathToFileURL(join(core, 'hosting.mjs')));
  const { memorySession } = await import(pathToFileURL(join(core, 'memory.mjs')));
  const { openMemory } = await import(pathToFileURL(join(core, 'memory-store.mjs')));
  const { trackPhone } = await import(pathToFileURL(join(core, 'phone.mjs')));
  const { trackActions } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
  const oldMode = policyHost.cachedMode;
  policyHost.cachedMode = () => 'full';
  const dir = await mkdtemp(join(directory, 'hosting-loop-'));
  const ctx = new Context(), records = [];
  let requests = 0, sends = 0, hosting, memory, memoryStore, learning, extractions = 0;
  class Adapter extends LlmAdapter {
    async *stream(options) {
      if (options.purpose === 'memory-extraction') {
        extractions++;
        const input = JSON.parse(options.messages[0].content[0].text);
        const result = input.messages.find(row => row.role === 'experience');
        assert.ok(result, '真实后台会话必须进入实验体经验提取');
        yield { type: 'text-delta', index: 0, text: JSON.stringify([{ action: 'add', type: 'experience', key: 'observe',
          summary: result.text, content: result.text, events: [result.id] }]) };
        yield { type: 'finish', reason: { kind: 'stop' } };
        return;
      }
      requests++;
      assert.match(JSON.stringify(options.messages), /托管验证/);
      if (requests === 1) {
        yield { type: 'tool-call-delta', index: 0, id: 'hosting-observe', name: 'phone_observe', argumentsDelta: '{}' };
        yield { type: 'finish', reason: { kind: 'tool-calls' } };
      } else {
        assert.equal(requests, 2);
        assert.match(JSON.stringify(options.messages), /fixture-visible/);
        yield { type: 'text-delta', index: 0, text: '本次已观察界面，无需继续操作。' };
        yield { type: 'finish', reason: { kind: 'stop' } };
      }
    }
  }
  try {
    for (const plugin of [SystemPrompt, Tools, LlmRuntime, Agents, Sessions, Projections]) await ctx.plugin(plugin);
    await ctx.plugin((await load('@deepseek-ai/dsh-storage')).default);
    await ctx.plugin(await load('@deepseek-ai/dsh-storage-json'), { root: join(dir, 'storages') });
    await ctx.plugin(await load('@deepseek-ai/dsh-storage-domain'), { backend: 'json' });
    await ctx.plugin(await load('@deepseek-ai/dsh-session-persistence-jsonl'), { root: join(dir, 'sessions'), compression: 'none' });
    await ctx.plugin((await load('@deepseek-ai/dsh-fs-local')).default);
    await ctx.plugin((await load('@deepseek-ai/dsh-working-directory')).default, { defaultDirectory: workspace });
    await ctx.plugin(AgentLoop, { agents: [], maxParallelToolCalls: 1 });
    ctx.llm.registerAdapter(['fixture'], new Adapter());
    ctx.llm.listProviders = () => [{ id: 'fixture' }];
    trackPhone(ctx); trackActions(ctx, dir);
    ctx.provide('attachments', { async admitPromptContent(content) { return content; } });
    ctx.provide('fileUploads', { bindPrompt() { return { commit() {}, [Symbol.dispose]() {} }; } });
    const agent = await ctx.agentLoop.create('hosting-real', { provider: 'fixture', model: 'single' });
    memoryStore = await openMemory(ctx);
    memory = memorySession(ctx, { store: memoryStore }); await memory.ready;
    await memory.call('settings', { enabled: false, autoSave: true, revision: memoryStore.read().revision });
    const controller = new SessionCommandController(ctx, { async resolveAgent(id) { assert.equal(id, agent.session.id); return { agent }; } }, workspace);
    ctx.provide('sessionController', { commands: controller });
    ctx.tools.register(defineTool({ name: 'phone_observe', description: 'fixture', parameters: {},
      output: { schema: { type: 'object', additionalProperties: true }, render: (_args, value) => phoneContent(value) },
      execute: (args, exec) => callPhone(ctx, { async phone() { sends++; return { ok: true, nodes: [{ text: 'fixture-visible' }] }; } }, 'observe', args, exec) }));
    learning = await (await import(pathToFileURL(join(core, 'specimen.mjs')))).specimenSession(ctx, () => ({ enabled: true }));
    hosting = hostingSession(ctx, { settings: () => ({ enabled: true, probability: 100, session: agent.session.id, prompt: '托管验证' }), learning,
      host: { async query() { return { ok: true, enabled: true, locked: false, interactive: true, accessibility: true, ready: true }; } },
      record: row => records.push(structuredClone(row)) });
    await hosting.check(); await agent.whenIdle();
    assert.equal(requests, 2, JSON.stringify({ records, status: await memory.call('status'), events: agent.session.snapshotEvents() })); assert.equal(sends, 1);
    assert.deepEqual(records.map(row => row.event), ['start', 'finish']);
    assert.equal(records.at(-1).outcome, 'completed');
    assert.equal(records.at(-1).tools.completed, 1);
    const events = agent.session.snapshotEvents();
    assert.ok(events.some(event => event.type === 'user/message' && event.data.source.kind === 'plugin:dshx-hosting'));
    assert.ok(events.some(event => event.type === 'tool/result'));
    assert.ok(JSON.stringify(events).includes('本次已观察界面'));
    await learning.idle();
    assert.equal(learning.status().runs.length, 1, learning.status().message);
    const run = learning.status().runs[0];
    assert.equal(run.execution, 'completed'); assert.equal(run.result, 'not_checked');
    assert.equal(run.requests.length, 2, '必须关联实际模型请求，而不是只保存最后一个模型');
    assert.equal(events[run.from].data.source.rpcId, run.id);
    assert.equal(events[run.to - 1].type, 'turn/end');
    const evidence = await learning.evidence(run.id);
    assert.ok(evidence.rows.some(row => row.kind === 'tool/result' && row.text.includes('fixture-visible')));
    await localMemoryWait(() => memoryStore.read().entries.length === 1, '真实培养轮次结束后没有保存经验：' + JSON.stringify(await memory.call('status')));
    assert.equal(extractions, 1);
    assert.equal(memoryStore.read().entries[0].source.origin, 'specimen');
    assert.match(memoryStore.read().entries[0].content, /本次已观察界面/);
    const actions = (await readFile(join(dir, 'actions.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
    assert.equal(actions.filter(row => row.event === 'action-finish').length, 1);
    assert.equal(actions.find(row => row.event === 'action-finish').outcome, 'success');
  } finally { hosting?.close(); await learning?.close(); await memory?.close(); policyHost.cachedMode = oldMode; await ctx.fiber.dispose(); }
});

test('Android hosting actual profile saves native settings through the Android overlay and restores them after restart', { timeout: 35000 }, async () => {
  const dir = await mkdtemp(join(directory, 'hosting-profile-'));
  const run = async () => {
    const assert = (await import('node:assert/strict')).default;
    const { createRequire } = await import('node:module'), { pathToFileURL } = await import('node:url');
    const { join } = await import('node:path'), { mkdirSync, writeFileSync } = await import('node:fs');
    const core = process.env.DSHX_CORE, dir = process.env.DSHX_HOSTING_TEST_DIR;
    const require = createRequire(join(core, 'lib/node_modules/package.json'));
    const load = name => import(pathToFileURL(require.resolve(name)));
    const { packageManager } = await import(pathToFileURL(join(core, 'plugins.mjs')));
    const { runProfile } = await load('@deepseek-ai/dsh/profile-boot');
    const { loadProfileDirectory } = await load('@deepseek-ai/dsh-app-boot');
    const { createLaunchEnvironmentSnapshot } = await load('@deepseek-ai/dsh-launch-environment');
    const profile = join(dir, 'profile'); mkdirSync(profile);
    const fixture = join(dir, 'services.mjs');
    writeFileSync(fixture, `export function apply(ctx) { ctx.provide('agents', { list: () => [] }); ctx.provide('sessionController', {});
      ctx.provide('sessions', {}); ctx.provide('sessionPersistence', {}); ctx.provide('workingDirectory', {}); ctx.provide('llm', {}); }`);
    const names = [
      ['timer', '@deepseek-ai/cordis-plugin-timer'], ['hmr', '@deepseek-ai/dsh-hmr', { root: [] }],
      ['plugin-manager', '@deepseek-ai/dsh-plugin-manager'], ['config-editor', '@deepseek-ai/dsh-config-editor'], ['settings', '@deepseek-ai/dsh-settings'],
      ['storage', '@deepseek-ai/dsh-storage'], ['storage-json', '@deepseek-ai/dsh-storage-json', { root: join(dir, 'storages') }],
      ['storage-domain', '@deepseek-ai/dsh-storage-domain', { backend: 'json' }],
    ];
    const entries = names.map(([id, name, config]) => ({ id, name: pathToFileURL(require.resolve(name)).href, ...(config ? { config } : {}) }));
    entries.push({ id: 'services', name: pathToFileURL(fixture).href });
    const overlay = join(dir, 'android.patch.yml');
    writeFileSync(overlay, JSON.stringify([{ insert: [{ id: 'dshx-android-hosting', name: pathToFileURL(join(core, 'hosting.mjs')).href }] }]));
    writeFileSync(join(profile, 'package.json'), '{"private":true,"dsh":{"profile":{"bundles":[]}}}');
    writeFileSync(join(profile, 'cordis.patch.yml'), JSON.stringify([{ insert: entries }]));
    const anchor = require.resolve('@deepseek-ai/dsh/package.json'), invocation = packageManager(core);
    const start = () => runProfile({ environment: createLaunchEnvironmentSnapshot([]), profile: 'hosting-test', patchFiles: [overlay], args: [],
      resolvedProfile: { profile: loadProfileDirectory('dsh', profile, anchor), installAnchor: anchor }, packageManager: invocation });
    mkdirSync(join(dir, 'storages'), { recursive: true });
    writeFileSync(join(dir, 'storages/hosting.json'), JSON.stringify({ unit: { name: 'hosting', version: 1 },
      global: { session: 'legacy-hosting' }, tables: {} }));
    let app = await start();
    try {
      const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
      const status = await policyHost.hosting('status', {});
      assert.equal(status.session, 'legacy-hosting');
      assert.equal(status.revision, 0);
      assert.deepEqual(status.recent, []);
      assert.deepEqual(status.settings, { enabled: false, probability: 20, session: '', prompt: '', learningEnabled: false });
      await assert.rejects(policyHost.hosting('settings', { expectedRevision: status.revision, settings: { probability: 101 } }));
      await assert.rejects(policyHost.hosting('settings', { expectedRevision: status.revision, settings: { enabled: true } }));
      const saved = await policyHost.hosting('settings', { expectedRevision: status.revision, settings: { probability: 42, prompt: '保存托管任务' } });
      assert.equal(saved.settings.probability, 42);
      assert.equal(saved.revision, 1);
      await assert.rejects(policyHost.hosting('settings', { expectedRevision: status.revision, settings: { probability: 99 } }));
      const concurrent = await Promise.allSettled([1, 2].map(() => policyHost.hosting('settings', {
        expectedRevision: saved.revision, settings: { probability: 42 } })));
      assert.equal(concurrent.filter(row => row.status === 'fulfilled').length, 1);
      assert.equal(concurrent.filter(row => row.status === 'rejected').length, 1);
    } finally { await app.shutdown.shutdown(0); }
    app = await start();
    try {
      const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
      const status = await policyHost.hosting('status', {});
      assert.equal(status.settings.probability, 42);
      assert.equal(status.settings.prompt, '保存托管任务');
      assert.equal(status.settings.enabled, false);
      assert.equal(status.revision, 2);
      assert.equal(status.session, 'legacy-hosting');
      console.log('Hosting profile settings and restart passed');
    } finally { await app.shutdown.shutdown(0); }
  };
  const child = spawn(process.execPath, ['--expose-internals', '--preserve-symlinks', '--preserve-symlinks-main', '--import', join(core, 'compat.mjs'), '--input-type=module', '-e', '(' + run.toString() + ')().catch(error => { console.error(error); process.exitCode = 1; })'], {
    env: { ...process.env, DSHX_HOSTING_TEST_DIR: dir, DSH_HOME: join(dir, 'home') }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; }); child.stderr.on('data', chunk => { output += chunk; });
  const timeout = setTimeout(() => child.kill('SIGKILL'), 30000);
  try {
    const code = await new Promise(resolve => child.once('exit', resolve));
    assert.equal(code, 0, output);
    assert.match(output, /Hosting profile settings and restart passed/);
  } finally { clearTimeout(timeout); if (child.exitCode === null) child.kill('SIGKILL'); }
});

test('Android hosting authenticated native channel rejects extra commands and sanitizes configuration errors', async () => {
  const host = new PolicyHost(); host.connected = true;
  const frames = [], calls = [];
  host.send = value => { frames.push(value); return true; };
  host.hosting = async (action, data) => { calls.push({ action, data }); return { revision: 3, settings: { enabled: false } }; };
  const request = { hosting: 'hosting-call', action: 'status', data: {} };
  host.receive(request); await delay(0);
  assert.equal(calls.length, 1); assert.equal(frames.at(-1).hosting, 'hosting-call');
  assert.equal(frames.at(-1).result.revision, 3);
  for (const invalid of [{ ...request, action: 'prompt' }, { ...request, session: 'other' }, { ...request, hosting: '../bad' }]) {
    assert.throws(() => host.receive(invalid), /原生托管请求无效/);
  }
  host.hosting = async () => { throw new Error('private provider credential'); };
  host.receive({ ...request, hosting: 'hosting-failure', action: 'settings' }); await delay(0);
  assert.equal(frames.at(-1).ok, false);
  assert.doesNotMatch(JSON.stringify(frames), /private provider credential/);
  assert.equal(host.hostingCalls.size, 0);
});

test('Android hosting recent history survives restart and ignores a torn final record', async () => {
  const { hostingEvent, hostingHistory } = await import(pathToFileURL(join(core, 'assistant.mjs')));
  const dir = await mkdtemp(join(directory, 'hosting-history-'));
  for (let index = 0; index < 30; index++) hostingEvent({ event: 'check', at: index, outcome: 'skipped', reason: 'probability' }, dir);
  let recent = hostingHistory(dir);
  assert.equal(recent.length, 20); assert.equal(recent[0].at, 10); assert.equal(recent.at(-1).at, 29);
  const file = await open(join(dir, 'hosting.jsonl'), 'a');
  try { await file.write('{"partial":'); } finally { await file.close(); }
  assert.equal(hostingHistory(dir).length, 20);
  hostingEvent({ event: 'finish', at: 30, outcome: 'unknown' }, dir);
  recent = hostingHistory(dir); assert.equal(recent.length, 20); assert.equal(recent.at(-1).outcome, 'unknown');
});

test('Android micro apps register all tools through the official schema and preserve the native boundary', async () => {
  const { apply, appToolNames } = await import(pathToFileURL(join(core, 'apps.mjs')));
  const registered = [], cleanups = [], sections = [];
  await apply({ tools: { register: tool => registered.push(tool) }, systemPrompt: { section: value => sections.push(value) },
    effect: effect => cleanups.push(effect()), sessionController: {}, agents: {},
    storageDomain: { open: async () => ({ global: { get: () => ({ requests: [] }) }, close: async () => {} }) } });
  try {
    assert.equal(registered.length, 8);
    assert.equal(appToolNames.has('app_install'), true);
    assert.equal(appToolNames.has('app_shell'), false);
    assert.equal(sections.length, 1);
  } finally { cleanups.forEach(cleanup => cleanup?.()); }
});

test('Android micro apps recover the original creation session without resubmitting after a restart', async () => {
  const { appCreator } = await import(pathToFileURL(join(core, 'apps.mjs')));
  const saved = new Map(); let created = 0, prompted = 0;
  const ctx = { sessionController: { create: async () => { created++; return { sessionId: 'persistent-create' }; },
    commands: { resolveAgent: async () => ({ status: 'idle', inbox: { hasPending: false }, session: { header: {} } }),
      prompt: async () => { prompted++; } } } };
  const history = { get: id => saved.get(id), set: async (id, value) => saved.set(id, value) };
  const request = { operationId: 'persistent-request', prompt: '创建功能' };
  await appCreator(ctx, '/home/.dsh', history)(request);
  const restored = await appCreator(ctx, '/home/.dsh', history)(request);
  assert.equal(restored.session, 'persistent-create');
  assert.equal(restored.delivery, 'accepted');
  assert.equal(created, 1); assert.equal(prompted, 1);
  saved.set('unconfirmed', { signature: JSON.stringify({ operationId: 'unconfirmed', prompt: '创建功能' }), stage: 'starting' });
  await assert.rejects(appCreator(ctx, '/home/.dsh', history)({ operationId: 'unconfirmed', prompt: '创建功能' }));
  assert.equal(created, 1);
});

test('Android micro apps forward management with a live session and refuse unknown or cancelled calls', async () => {
  const { callApp } = await import(pathToFileURL(join(core, 'apps.mjs')));
  const calls = [];
  const host = { query: async (...args) => { calls.push(args); return { revision: 2 }; } };
  const exec = { agent: { session: { id: 'micro-session' } }, signal: new AbortController().signal };
  assert.deepEqual(await callApp(host, 'status', {}, exec), { revision: 2 });
  assert.equal(calls[0][0], 'apps');
  assert.deepEqual(calls[0][1], { action: 'status', args: {}, session: 'micro-session' });
  await assert.rejects(callApp(host, 'shell', {}, exec));
  await assert.rejects(callApp(host, 'status', {}, {}));
  const abort = new AbortController(); abort.abort();
  await assert.rejects(callApp(host, 'install', {}, { ...exec, signal: abort.signal }));
  assert.equal(calls.length, 1);
});

test('Android micro apps create one ordinary session per stable request and pass user text without shell execution', async () => {
  const { appCreator } = await import(pathToFileURL(join(core, 'apps.mjs')));
  let created = 0; const prompts = [];
  const agent = { status: 'idle', inbox: { hasPending: false }, session: { header: {} } };
  const create = appCreator({ sessionController: {
    create: async () => { created++; return { sessionId: 'created-micro-session' }; },
    commands: { resolveAgent: async () => agent, prompt: async (...args) => { prompts.push(args); } },
  } }, '/test/home/.dsh');
  const request = { operationId: 'create-once', prompt: '跳过广告；保留 $(不执行) 与 `字面文字`。' };
  const result = await Promise.all([create(request), create({ ...request })]);
  assert.deepEqual(result, [{ session: 'created-micro-session' }, { session: 'created-micro-session' }]);
  assert.equal(created, 1); assert.equal(prompts.length, 1);
  assert.match(prompts[0][0].content[0].text, /skills\/create-dshx-app\/SKILL.md/);
  assert.equal(prompts[0][0].content[0].text.endsWith(request.prompt), true);
  assert.equal(prompts[0][1].kind, 'user');
  await assert.rejects(create({ ...request, prompt: '不同需求' }));
  agent.session.header.parentSession = 'parent';
  await assert.rejects(create({ operationId: 'child-request', prompt: '创建', session: 'child' }));
  assert.equal(prompts.length, 1);
});

test('Android micro apps SDK connects once and performs real asynchronous storage calls', async () => {
  const { runInNewContext } = await import('node:vm');
  const sdk = await readFile(join(process.env.DSHX_APP_ASSETS, 'micro-app/sdk.js'), 'utf8');
  let connect; const data = new Map(); let calls = 0;
  const port = { start() {}, postMessage(text) {
    calls++; const row = JSON.parse(text);
    queueMicrotask(() => {
      let result;
      if (row.method === 'storage.set') { data.set(row.args.key, row.args.value); result = { ok: true }; }
      else if (row.method === 'storage.get') result = { value: data.get(row.args.key) ?? null };
      else if (row.method === 'info') result = { id: 'test-app' };
      port.onmessage({ data: JSON.stringify({ id: row.id, ok: !!result, result, error: 'not declared' }) });
    });
  } };
  const window = { addEventListener: (name, callback) => { connect = callback; } };
  runInNewContext(sdk, { window, setTimeout, clearTimeout, console });
  const pending = window.dshx.info();
  assert.equal(calls, 0);
  connect({ data: 'dshx-connect', ports: [port] });
  assert.equal((await pending).id, 'test-app');
  connect({ data: 'dshx-connect', ports: [{ postMessage: () => assert.fail('must not replace the connected port') }] });
  await window.dshx.storage.set('note', 'saved text');
  assert.equal(await window.dshx.storage.get('note'), 'saved text');
  assert.equal(await window.dshx.storage.get('missing'), null);
  await assert.rejects(window.dshx.screen.observe(), /not declared/);
  let events = 0; const unsubscribe = window.dshx.screen.onChange(() => { events++; });
  port.onmessage({ data: JSON.stringify({ event: 'screen', data: { package: 'test.app' } }) });
  await Promise.resolve(); await Promise.resolve();
  assert.equal(events, 1);
  unsubscribe();
  port.onmessage({ data: JSON.stringify({ event: 'screen', data: {} }) });
  await Promise.resolve(); assert.equal(events, 1);
});

test('Android micro apps SDK never replays an action after a timeout or a late result', async () => {
  const { runInNewContext } = await import('node:vm');
  const sdk = await readFile(join(process.env.DSHX_APP_ASSETS, 'micro-app/sdk.js'), 'utf8');
  const timers = new Map(); let next = 0, connect, sent = 0, request;
  const port = { postMessage: text => { sent++; request = JSON.parse(text); } };
  const window = { addEventListener: (_name, callback) => { connect = callback; } };
  runInNewContext(sdk, { window, console, setTimeout: callback => { timers.set(++next, callback); return next; }, clearTimeout: id => timers.delete(id) });
  connect({ data: 'dshx-connect', ports: [port] });
  const action = window.dshx.screen.click('snapshot', 'actual-node');
  await Promise.resolve();
  const rejection = assert.rejects(action, /超时/);
  for (const callback of timers.values()) callback();
  await rejection;
  port.onmessage({ data: JSON.stringify({ id: request.id, ok: true, result: { ok: true } }) });
  assert.equal(sent, 1);
});

test('Android micro apps notes example saves user input and restores it in a new page', async () => {
  const { runInNewContext } = await import('node:vm');
  const app = JSON.parse(await readFile(join(process.env.DSHX_APP_ASSETS, 'skills/create-dshx-app/assets/quick-notes.json'), 'utf8'));
  const data = new Map();
  const dshx = { ready: Promise.resolve(), storage: { get: async key => data.get(key), set: async (key, value) => data.set(key, value) } };
  const open = async () => {
    const nodes = { '#note': { value: '' }, '#status': {}, '#save': { disabled: true } };
    await runInNewContext(app.files['main.js'], { dshx, document: { querySelector: name => nodes[name] } });
    return nodes;
  };
  const page = await open();
  assert.equal(page['#save'].disabled, false);
  page['#note'].value = '新的内容';
  await page['#save'].onclick();
  assert.equal(page['#status'].textContent, '已保存');
  const restored = await open();
  assert.equal(restored['#note'].value, '新的内容');
});

test('Android micro apps ad example uses ordinary JavaScript and rejects ambiguous or incomplete pages', async () => {
  const { runInNewContext } = await import('node:vm');
  const app = JSON.parse(await readFile(join(process.env.DSHX_APP_ASSETS, 'skills/create-dshx-app/assets/skip-startup-ads.json'), 'utf8'));
  const find = runInNewContext(app.files['match.js'] + '\nfindAdTargets');
  const observation = (...texts) => ({ truncated: false, nodes: texts.map((text, index) => ({ id: `n${index}`, text, enabled: true,
    clickTarget: text.includes('跳过') ? `n${index}` : '', visible: true })) });
  const targets = input => Array.from(find(input));
  assert.deepEqual(targets(observation('广告', '跳过 3秒')), ['n1']);
  assert.deepEqual(targets(observation('欢迎使用', '跳过')), []);
  assert.equal(targets(observation('广告', '跳过', '跳过广告')).length, 2);
  assert.deepEqual(targets({ ...observation('广告', '跳过'), truncated: true }), []);
  const clipped = observation('广告', '跳过'); clipped.nodes[1].textTruncated = true;
  assert.deepEqual(targets(clipped), []);
  const shared = observation('广告', '跳过'); shared.nodes.forEach(node => { node.clickTarget = 'container'; });
  assert.deepEqual(targets(shared), []);
});


test('Android micro apps SDK recovers a late connection without sending expired requests', async () => {
  const { runInNewContext } = await import('node:vm');
  const sdk = await readFile(join(process.env.DSHX_APP_ASSETS, 'micro-app/sdk.js'), 'utf8');
  const timers = new Map(); let next = 0, connect, sent = 0;
  const window = { addEventListener: (_name, callback) => { connect = callback; } };
  runInNewContext(sdk, { window, console, setTimeout: callback => { timers.set(++next, callback); return next; }, clearTimeout: id => timers.delete(id) });
  const expired = window.dshx.screen.click('old-snapshot', 'old-node');
  const rejection = assert.rejects(expired, /超时/);
  for (const callback of timers.values()) callback();
  await rejection;
  const port = { postMessage: text => {
    sent++; const request = JSON.parse(text);
    port.onmessage({ data: JSON.stringify({ id: request.id, ok: true, result: { id: 'late-app' } }) });
  } };
  connect({ data: 'dshx-connect', ports: [port] });
  assert.equal((await window.dshx.info()).id, 'late-app');
  assert.equal(sent, 1, 'expired click must not be sent after connecting');
});
