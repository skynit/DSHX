import { readFile } from 'node:fs/promises';
import { openSync, closeSync, mkdirSync, fstatSync, readSync, writeSync, fsyncSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { AsyncLocalStorage } from 'node:async_hooks';

// User configuration lives outside the replaceable, verified runtime tree.
export async function personaAssembly(assembly, directory = process.env.DSHX_ASSISTANT_DIR) {
  if (!directory) return assembly;
  let settings;
  try { settings = JSON.parse(await readFile(join(directory, 'settings.json'), 'utf8')); }
  catch (error) {
    if (error.code === 'ENOENT') return assembly;
    throw new Error('助手配置无法读取，请在设置中重新保存或恢复默认');
  }
  if (settings?.version !== 1) throw new Error('助手配置版本不支持，请在设置中重新保存');
  const fields = [['name', 80], ['identity', 2000], ['prompt', 16000]];
  for (const [key, limit] of fields) {
    if (typeof settings[key] !== 'string' || settings[key].length > limit) {
      throw new Error('助手配置格式或长度无效，请在设置中重新保存');
    }
  }
  const name = settings.name.trim();
  const identity = settings.identity.trim();
  const prompt = settings.prompt.trim();
  const text = [name && `你的名字是：${name}`, identity && `你的身份是：${identity}`, prompt].filter(Boolean).join('\n\n');
  if (!text) return assembly;
  // Runs after upstream's complete-prompt selection. Literal user text must
  // never become a {{variable}} reference or replace runtime tool facts.
  return { ...assembly, sections: [...assembly.sections, {
    name: 'android:assistant', text: `用户配置的助手人设与回答要求：\n${text}`, interpolate: false
  }] };
}

const usageFields = ['inputTokens', 'outputTokens', 'cacheReadTokens', 'cacheWriteTokens', 'reasoningTokens', 'totalTokens'];

function reportedUsage(value) {
  const usage = {};
  for (const key of usageFields) {
    if (Number.isSafeInteger(value?.[key]) && value[key] >= 0) usage[key] = value[key];
  }
  return Object.keys(usage).length ? usage : undefined;
}

function append(directory, event) {
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const file = openSync(join(directory, 'usage.jsonl'), 'a+', 0o600);
  try {
    const size = fstatSync(file).size;
    const last = Buffer.alloc(1);
    if (size > 0) {
      readSync(file, last, 0, 1, size - 1);
      // Preserve a torn record for diagnosis, and keep the next record readable.
      if (last[0] !== 10) writeSync(file, '\n');
    }
    const data = Buffer.from(JSON.stringify({ version: 1, ...event }) + '\n');
    let offset = 0;
    while (offset < data.length) offset += writeSync(file, data, offset, data.length - offset);
    fsyncSync(file);
  } finally { closeSync(file); }
}

// Built-in adapters opt in only when their model send path is instrumented.
export const modelTransport = Symbol('android-model-transport');
const calls = new AsyncLocalStorage();

function write(state, event) {
  if (state.directory) append(state.directory, event);
}

function finish(state, outcome) {
  const request = state.request;
  if (!request || request.finished) return;
  write(state, { event: 'finish', id: request.id, outcome, endedAt: Date.now() });
  request.finished = true;
}

function start(state, dispatch) {
  if (!state || state.closed) return;
  // A provider retry replaces the previous transport, never its accounting.
  finish(state, 'failed');
  const { options } = state;
  const id = randomUUID();
  write(state, {
    event: 'start', id, provider: options.provider, model: options.model,
    ...(options.sessionId === undefined ? {} : { sessionId: options.sessionId }),
    purpose: ['conversation', 'compaction', 'session-title'].includes(options.purpose) ? options.purpose : 'other',
    dispatch, startedAt: Date.now()
  });
  state.request = { id, reported: undefined, finished: false };
}

function report(state, value, source, replace = false, anomaly) {
  const request = state?.request;
  if (!request || request.finished || state.closed) return;
  const usage = reportedUsage(value);
  if (!usage) return;
  const next = replace ? usage : { ...request.reported, ...usage };
  if (JSON.stringify(next) === JSON.stringify(request.reported) && anomaly === request.anomaly) return;
  write(state, { event: 'usage', id: request.id, usage: next, usageSource: source,
    ...(anomaly ? { usageAnomaly: anomaly } : {}) });
  request.reported = next;
  request.anomaly = anomaly;
}

// Called only at the fixed built-in model fetch seams. No URL/body inspection,
// global fetch replacement, credential refresh or attachment upload interception.
export async function modelFetch(...args) {
  const state = calls.getStore();
  if (state?.closed) throw new Error('Model request accounting context is closed');
  start(state, 'http-request');
  try {
    const response = await globalThis.fetch(...args);
    if (!response.ok && state) finish(state, 'failed');
    return response;
  } catch (error) {
    if (state) finish(state, state.options.signal?.aborted ? 'cancelled' : 'failed');
    throw error;
  }
}

export function providerUsage() {
  const usage = calls.getStore()?.request?.reported;
  // Business consumers require both fields for arithmetic and serialization.
  if (usage?.inputTokens !== undefined && usage.outputTokens !== undefined) return usage;
}

export function reportProviderUsage(usage) {
  report(calls.getStore(), usage, 'provider');
}

// Decode only protocol-owned numeric usage fields, before pi-ai fills absent
// values with zero. A present field (including zero) is its provenance; totals
// not supplied by the provider stay unknown instead of being synthesized.
function piUsage(state, api, event) {
  let raw;
  let fields;
  let inclusive = false;
  switch (api) {
    case 'openai-completions':
      raw = event.usage || event.choices?.[0]?.usage;
      fields = {
        inputTokens: raw?.prompt_tokens, outputTokens: raw?.completion_tokens,
        cacheReadTokens: raw?.prompt_tokens_details?.cached_tokens ?? raw?.prompt_cache_hit_tokens ?? raw?.cached_tokens,
        cacheWriteTokens: raw?.prompt_tokens_details?.cache_write_tokens,
        reasoningTokens: raw?.completion_tokens_details?.reasoning_tokens, totalTokens: raw?.total_tokens
      };
      inclusive = true;
      break;
    case 'openai-responses':
    case 'azure-openai-responses':
    case 'openai-codex-responses':
      raw = event.response?.usage;
      fields = {
        inputTokens: raw?.input_tokens, outputTokens: raw?.output_tokens,
        cacheReadTokens: raw?.input_tokens_details?.cached_tokens,
        cacheWriteTokens: raw?.input_tokens_details?.cache_write_tokens,
        reasoningTokens: raw?.output_tokens_details?.reasoning_tokens, totalTokens: raw?.total_tokens
      };
      inclusive = true;
      break;
    case 'anthropic-messages':
      raw = event.type === 'message_start' ? event.message?.usage : event.usage;
      fields = {
        inputTokens: raw?.input_tokens, outputTokens: raw?.output_tokens,
        cacheReadTokens: raw?.cache_read_input_tokens, cacheWriteTokens: raw?.cache_creation_input_tokens,
        reasoningTokens: raw?.output_tokens_details?.thinking_tokens
      };
      break;
    // Catalog protocols without a verified field contract remain unknown.
    default: return;
  }
  const reported = reportedUsage(fields);
  if (!reported) return;
  const request = state?.request;
  const known = { ...request?.providerFields, ...reported };
  if (request) request.providerFields = known;
  const usage = { ...known };
  let anomaly;
  if (inclusive && usage.inputTokens !== undefined) {
    const cache = (usage.cacheReadTokens ?? 0) + (usage.cacheWriteTokens ?? 0);
    if (cache > usage.inputTokens) {
      delete usage.inputTokens;
      anomaly = 'cache-exceeds-input';
    } else usage.inputTokens -= cache;
  }
  return { usage, anomaly };
}

export function piOptions(model) {
  const state = calls.getStore();
  // These HTTP-only implementations forward options.fetch solely to the model
  // request SDK. Codex may use WebSocket, Google rejects custom fetch, and other
  // catalog transports have not established this guarantee: label their scope.
  const http = ['openai-completions', 'openai-responses', 'anthropic-messages', 'azure-openai-responses'].includes(model.api);
  if (!http) start(state, 'adapter-attempt');
  return {
    ...(http ? { fetch: modelFetch } : {}),
    onProviderStreamEvent: event => {
      const snapshot = piUsage(state, model.api, event);
      if (snapshot) report(state, snapshot.usage, 'provider', true, snapshot.anomaly);
    }
  };
}

// Selection/metadata/projection happen before this boundary. An opaque adapter
// gets an explicitly scoped execution attempt; built-ins start at model fetch.
// Each iterator operation runs in its own context, including return on consumer
// close. Detached pi-ai producers inherit the same context without sharing it
// with another call or writing after its terminal record.
export async function* usageStream(options, stream, directory = process.env.DSHX_ASSISTANT_DIR, transport = false) {
  const state = { options, directory, request: undefined, closed: false };
  let iterator;
  let outcome = 'incomplete';
  try {
    if (!transport) start(state, 'adapter-attempt');
    iterator = calls.run(state, () => stream()[Symbol.asyncIterator]());
    while (true) {
      const item = await calls.run(state, () => iterator.next());
      if (item.done) return;
      const chunk = item.value;
      if (chunk.type === 'usage') report(state, chunk.usage, transport ? 'provider' : 'adapter');
      if (chunk.type === 'finish') {
        const kind = chunk.reason?.kind;
        outcome = kind === 'aborted' ? 'cancelled' : kind === 'error' ? 'failed'
          : ['stop', 'tool-calls', 'max-tokens'].includes(kind) ? 'success' : 'incomplete';
        finish(state, outcome);
        yield chunk;
        return;
      }
      yield chunk;
    }
  } catch (error) {
    outcome = options.signal?.aborted ? 'cancelled' : 'failed';
    throw error;
  } finally {
    if (outcome === 'incomplete' && options.signal?.aborted) outcome = 'cancelled';
    state.closed = true;
    let storageError;
    try { finish(state, outcome); }
    catch (error) { storageError = error; }
    try {
      if (iterator?.return) await calls.run(state, () => iterator.return());
    } finally {
      if (storageError) throw storageError;
    }
  }
}
