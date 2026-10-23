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
      for (const name of ['read', 'write', 'edit', 'glob', 'grep', 'bash']) ctx.tools.register({
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
        assert.deepEqual(catalog(fullAgent).map(t => t.name).sort(), ['bash', 'edit', 'glob', 'grep', 'read', 'write', ...phoneNames].sort());
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
        name: 'bash', agent: { id: session, session: { id: session, header: { id: session } } },
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

test('voice local routing uses the real loop history restores selection and separates accounting', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools } = await load('@deepseek-ai/dsh-tools');
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
  const events = [];
  const original = { provider: 'fixture', model: 'remote' };
  const selection = { current: original };
  class Remote extends LlmAdapter {
    async *stream() { remote++; yield { type: 'text-delta', index: 0, text: '远程任务回答' }; yield { type: 'finish', reason: { kind: 'stop' } }; }
  }
  try {
    for (const plugin of [SystemPrompt, Tools, LlmRuntime, Agents, Sessions, Projections]) await ctx.plugin(plugin);
    installModelSelectionProjection(ctx);
    await ctx.plugin(AgentLoop, { agents: [] });
    ctx.llm.registerAdapter(['fixture'], new Remote());
    ctx.llm.registerAdapter(['dshx-local'], localAdapter(LlmAdapter, { query: async (method, data) => {
      local++; assert.equal(method, 'local-model');
      assert.equal(data.session, 'voice-session');
      assert.equal(data.messages.at(-1).text, '你好');
      assert.ok(data.messages.every(message => ['user', 'assistant'].includes(message.role)));
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
    const result = await voice.call('prompt', { interaction: 'first', text: '你好', local: true });
    await agent.whenIdle();
    assert.equal(result.text, '你好，我是小橙。');
    assert.equal(result.session, agent.session.id);
    assert.equal(result.local, true);
    assert.equal(local, 1); assert.equal(remote, 0);
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
    const followup = await voice.call('prompt', { interaction: 'second', session: agent.session.id, text: '搜索文件', local: true });
    await agent.whenIdle();
    assert.equal(followup.local, false); assert.equal(remote, 1);
    assert.equal(agent.session.requestHeader().config.provider, 'fixture');
    const ledger = (await readFile(join(dir, 'local-usage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
    assert.deepEqual(ledger.map(row => row.event), ['start', 'finish']);
    assert.deepEqual(ledger[1].usage, { inputTokens: 17, outputTokens: 8 });
    assert.equal(ledger[1].outcome, 'success');
    const cloudLedger = await readFile(join(dir, 'usage.jsonl'), 'utf8');
    assert.doesNotMatch(cloudLedger, /dshx-local/);
    assert.doesNotMatch(JSON.stringify(ledger), /你好|搜索文件|小橙/);
  } finally {
    voice?.close(); disposeSelection?.(); await ctx.fiber.dispose();
    if (previous === undefined) delete process.env.DSHX_ASSISTANT_DIR; else process.env.DSHX_ASSISTANT_DIR = previous;
  }
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
    await voice.call('cancel', { interaction: 'cancel-own' });
    admission.resolve();
    await assert.rejects(call, /取消/);
    assert.equal(cancellations, 0, 'the admitted typed task belongs to someone else');
    assert.deepEqual(agent.inbox.nextTurn.map(message => message.id), ['typed']);
  } finally { admission.resolve(); voice.close(); }
});

test('voice temporary model selection respects user switches and rejects unsuccessful turns', async () => {
  const { voiceSession } = await import(pathToFileURL(join(core, 'voice.mjs')));
  const handlers = new Map();
  const selection = { current: { provider: 'original', model: 'one' } };
  const agent = { status: 'idle', inbox: { nextTurn: [], nextStep: [], hasPending: false }, cancel() {} };
  const chosen = { provider: 'user-picked', model: 'two' };
  let failure = { kind: 'error' };
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
        prompt: async (_request, source) => {
          handlers.get('agent/inbox/claimed')({ agent, message: { source }, turn: 1 });
          assert.equal(selection.current.provider, 'dshx-local');
          selection.current = chosen;
          handlers.get('session/event')(session, { type: 'model/selection', data: chosen });
          handlers.get('session/event')(session, { type: 'assistant/message', data: { message: { content: [{ type: 'text', text: 'partial' }] } } });
          handlers.get('session/event')(session, { type: 'turn/end', data: { turn: 1, reason: failure } });
        },
      },
    },
  });
  try {
    await assert.rejects(voice.call('prompt', { session: session.id, interaction: 'switch', text: '你好', local: true }), /失败/);
    assert.equal(selection.current, chosen, 'an explicit user change wins over restoring the previous choice');
    failure = { kind: 'error', error: { message: '当前可用内存不足，本地模型暂不加载。请关闭其他占用内存的应用后重试。' } };
    await assert.rejects(voice.call('prompt', { session: session.id, interaction: 'memory', text: '你好', local: true }), { code: 'VOICE_MEMORY' });
  } finally { voice.close(); }
});

test('voice local adapter bounds Unicode history and records cancellation without prompt content', async () => {
  const { LlmAdapter } = await load('@deepseek-ai/dsh-llm');
  const { localAdapter } = await import(pathToFileURL(join(core, 'voice.mjs')));
  const dir = await mkdtemp(join(directory, 'voice-projection-'));
  const previous = process.env.DSHX_ASSISTANT_DIR;
  process.env.DSHX_ASSISTANT_DIR = dir;
  const abort = new AbortController();
  const messages = Array.from({ length: 20 }, () => ({ role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: '🙂'.repeat(1000) }] }));
  messages.unshift({ role: 'system', content: [{ type: 'text', text: 'private system' }] });
  const adapter = localAdapter(LlmAdapter, { query: async (_method, data) => {
    assert.equal(data.messages.length, 8);
    assert.ok(data.messages.every(message => [...message.text].length === 600 && message.text === '🙂'.repeat(600)));
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
