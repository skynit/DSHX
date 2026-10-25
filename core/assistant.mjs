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

function append(directory, event, filename = 'usage.jsonl') {
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const file = openSync(join(directory, filename), 'a+', 0o600);
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

export function localUsage(event, directory = process.env.DSHX_ASSISTANT_DIR) {
  if (directory) append(directory, event, 'local-usage.jsonl');
}

// Built-in adapters opt in only when their model send path is instrumented.
export const modelTransport = Symbol('android-model-transport');
const calls = new AsyncLocalStorage();
// Only the current conversation step is retained; accounting on disk is durable.
const actionRequests = new Map();
const actionKey = (directory, sessionId) => JSON.stringify([directory, sessionId]);

export function forgetActions(sessionId, directory = process.env.DSHX_ASSISTANT_DIR) {
  actionRequests.delete(actionKey(directory, sessionId));
}

function actionSource(tool) {
  if (tool === 'phone_observe') return 'component-tree';
  if (tool === 'phone_screenshot') return 'screenshot';
  if (['phone_click', 'phone_input', 'phone_scroll'].includes(tool)) return 'node';
  if (['phone_tap', 'phone_swipe'].includes(tool)) return 'coordinate';
  return 'other';
}

function plannedTools(request) {
  return [...(request?.tools.values() ?? [])].filter(tool => tool.id && tool.name);
}

function requestUsage(request) {
  const usage = request?.reported;
  return {
    requestId: request?.id ?? null,
    provider: request?.provider ?? null,
    model: request?.model ?? null,
    requestUsage: usage ?? null,
    usageStatus: !usage ? 'unknown' : usage.inputTokens !== undefined && usage.outputTokens !== undefined ? 'reported' : 'partial',
    usageSource: request?.usageSource ?? null,
    usageFinal: request?.usageFinal === true,
    tokenScope: 'model-request',
    requestActionCount: request ? plannedTools(request).length : null,
  };
}

// Registry events cover denied/cancelled calls too, and the final observer sees
// the accepted outcome after output validation and post-execution policies.
export function trackActions(ctx, directory = process.env.DSHX_ASSISTANT_DIR) {
  if (!directory) return;
  const runs = new Map();
  const begin = exec => {
    const sessionId = exec.agent?.session?.id;
    const rootCallId = exec.rootCallId ?? exec.callId;
    const plan = actionRequests.get(actionKey(directory, sessionId))?.get(rootCallId);
    const run = {
      id: randomUUID(), sessionId: sessionId ?? null, callId: exec.callId, rootCallId,
      tool: exec.name, source: actionSource(exec.name), startedAt: Date.now(),
      ...(plan ? { planId: plan.id } : {}), ...requestUsage(plan?.request),
      dispatchReached: false,
    };
    append(directory, { event: 'action-start', ...run }, 'actions.jsonl');
    if (plan) plan.started = true;
    runs.set(exec.token, { row: run, clock: performance.now() });
    return runs.get(exec.token);
  };
  ctx.on('tools/pre-execute', (exec, next) => { begin(exec); return next(); });
  ctx.on('tools/execute', (exec, next) => {
    const run = runs.get(exec.token) ?? begin(exec);
    run.row.dispatchReached = true;
    return next();
  });
  const complete = (exec, result) => {
    const run = runs.get(exec.token) ?? begin(exec);
    const value = result.value;
    const nativeState = value?.execution_state;
    const code = value?.code ?? result.error?.info?.code;
    const cancelled = exec.signal?.aborted || ['cancelled', 'ABORTED', 'ABORTED_BEFORE_DISPATCH'].includes(code);
    const outcome = nativeState === 'unknown' ? 'unknown' : cancelled ? 'cancelled'
      : result.isError || value?.ok === false ? 'failed' : 'success';
    const metrics = {};
    if (exec.name === 'phone_observe' && Array.isArray(value?.nodes)) {
      metrics.nodeCount = value.nodes.length;
      metrics.treeTruncated = value.truncated === true;
    }
    if (exec.name === 'phone_screenshot' && value?.image) {
      for (const key of ['bytes', 'width', 'height']) {
        if (Number.isSafeInteger(value.image[key]) && value.image[key] >= 0) metrics[key] = value.image[key];
      }
    }
    append(directory, {
      event: 'action-finish', ...run.row, outcome, endedAt: Date.now(),
      durationMs: Math.max(0, Math.round(performance.now() - run.clock)),
      ...(['not_started', 'completed', 'unknown'].includes(nativeState) ? { executionState: nativeState } : {}),
      ...(typeof code === 'string' && /^[a-zA-Z0-9_.:-]{1,80}$/.test(code) ? { code } : {}),
      ...metrics,
    }, 'actions.jsonl');
    runs.delete(exec.token);
  };
  ctx.on('tools/result', complete);
  // The agent scheduler records queued cancellations straight to the session,
  // bypassing registry hooks. Only supplement plans never seen by those hooks.
  ctx.on('session/event', (session, event) => {
    if (event.type !== 'tool/result' || event.data.error?.code !== 'ABORTED_BEFORE_DISPATCH') return;
    const callId = event.data.message?.toolCallId;
    const plan = actionRequests.get(actionKey(directory, session.id))?.get(callId);
    if (!plan || plan.started) return;
    complete({ token: Symbol(), callId, name: plan.tool, agent: { session } }, {
      isError: true, value: { execution_state: 'not_started' }, error: { info: event.data.error },
    });
  });
  ctx.effect(() => () => {
    try {
      for (const { row, clock } of runs.values()) append(directory, {
        event: 'action-finish', ...row, outcome: 'incomplete', endedAt: Date.now(),
        durationMs: Math.max(0, Math.round(performance.now() - clock)),
      }, 'actions.jsonl');
    } finally { runs.clear(); }
  });
}

function write(state, event) {
  if (state.directory) append(state.directory, event);
}

function finish(state, outcome) {
  const request = state.request;
  if (!request || request.finished) return;
  // Opaque adapters confirm their own cumulative usage only on a successful
  // terminal chunk. Instrumented providers require a protocol-owned signal.
  if (!state.transport && outcome === 'success' && request.reported) request.usageFinal = true;
  write(state, { event: 'finish', id: request.id, outcome, usageFinal: request.usageFinal === true, endedAt: Date.now() });
  request.finished = true;
  if (state.directory && state.options.purpose === 'conversation' && state.options.sessionId) {
    const plans = new Map();
    for (const { id: callId, name: tool } of plannedTools(request)) {
      const id = randomUUID();
      append(state.directory, {
        event: 'action-plan', id, sessionId: state.options.sessionId, callId, tool,
        source: actionSource(tool), requestOutcome: outcome, plannedAt: Date.now(),
        ...requestUsage(request),
      }, 'actions.jsonl');
      plans.set(callId, { id, tool, request, started: false });
    }
    // Failed attempts can contain partial calls; they never own later actions.
    if (outcome === 'success') actionRequests.set(actionKey(state.directory, state.options.sessionId), plans);
  }
}

function start(state, dispatch) {
  if (!state || state.closed) return;
  // A provider retry replaces the previous transport, never its accounting.
  finish(state, 'failed');
  const { options } = state;
  if (options.purpose === 'conversation') forgetActions(options.sessionId, state.directory);
  const id = randomUUID();
  write(state, {
    event: 'start', id, provider: options.provider, model: options.model,
    ...(options.sessionId === undefined ? {} : { sessionId: options.sessionId }),
    purpose: ['conversation', 'compaction', 'session-title', 'memory-extraction', 'knowledge-summary', 'knowledge-embedding', 'screenshot-recognition'].includes(options.purpose) ? options.purpose : 'other',
    dispatch, startedAt: Date.now(),
    // Tool results (including images) are billed when a subsequent request reads
    // them. Keep all input references, rather than charging an image to its capture.
    ...(options.messages?.some(message => message.role === 'tool') ? {
      toolResultCallIds: [...new Set(options.messages.filter(message => message.role === 'tool')
        .map(message => message.toolCallId).filter(id => typeof id === 'string'))],
    } : {}),
  });
  state.request = { id, provider: options.provider, model: options.model, reported: undefined, finished: false, tools: new Map() };
}

function report(state, value, source, replace = false, anomaly, final = false) {
  const request = state?.request;
  if (!request || request.finished || state.closed) return;
  const usage = reportedUsage(value);
  if (!usage) return;
  const next = replace ? usage : { ...request.reported, ...usage };
  const usageFinal = request.usageFinal === true || final;
  if (JSON.stringify(next) === JSON.stringify(request.reported) && anomaly === request.anomaly && usageFinal === (request.usageFinal === true)) return;
  write(state, { event: 'usage', id: request.id, usage: next, usageSource: source,
    usageFinal,
    ...(anomaly ? { usageAnomaly: anomaly } : {}) });
  request.reported = next;
  request.usageSource = source;
  request.anomaly = anomaly;
  request.usageFinal = usageFinal;
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

export function reportProviderUsage(usage, final = false) {
  report(calls.getStore(), usage, 'provider', false, undefined, final);
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
  const final = api === 'anthropic-messages'
    ? event.type === 'message_delta' && event.delta?.stop_reason != null && reported.outputTokens !== undefined
    : api === 'openai-completions'
      ? Array.isArray(event.choices) && (event.choices.length === 0 || event.choices.some(choice => choice.finish_reason != null))
      : ['response.completed', 'response.incomplete', 'response.failed'].includes(event.type);
  return { usage, anomaly, final };
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
      if (snapshot) report(state, snapshot.usage, 'provider', true, snapshot.anomaly, snapshot.final);
    }
  };
}

// Selection/metadata/projection happen before this boundary. An opaque adapter
// gets an explicitly scoped execution attempt; built-ins start at model fetch.
// Each iterator operation runs in its own context, including return on consumer
// close. Detached pi-ai producers inherit the same context without sharing it
// with another call or writing after its terminal record.
export async function* usageStream(options, stream, directory = process.env.DSHX_ASSISTANT_DIR, transport = false) {
  // Native inference owns a separate measured local ledger; it is never a remote request attempt.
  if (options.provider === 'dshx-local') { yield* stream(); return; }
  const state = { options, directory, transport, request: undefined, closed: false };
  let iterator;
  let outcome = 'incomplete';
  try {
    if (!transport) start(state, 'adapter-attempt');
    iterator = calls.run(state, () => stream()[Symbol.asyncIterator]());
    while (true) {
      const item = await calls.run(state, () => iterator.next());
      if (item.done) return;
      const chunk = item.value;
      const block = chunk.type === 'block-end' && chunk.block?.type === 'tool-call' ? chunk.block
        : chunk.type === 'tool-call-delta' ? chunk : undefined;
      if (block && state.request && !state.request.finished) {
        const key = Number.isInteger(chunk.index) ? chunk.index : block.id;
        const previous = state.request.tools.get(key);
        const id = typeof block.id === 'string' && block.id ? block.id : previous?.id;
        const name = typeof block.name === 'string' && block.name ? block.name : previous?.name;
        // An adapter may learn the call id/name only at block-end. Correlate by
        // block index and preserve partial identity, counting only complete calls.
        state.request.tools.set(key, { id, name });
      }
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
