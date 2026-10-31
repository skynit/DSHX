import { policyHost } from './host.mjs';
import { localUsage } from './assistant.mjs';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { localRouter } from './local-route.mjs';

export const name = 'dshx-android-voice';
export const inject = ['sessionController', 'agents', 'llm'];
const route = { provider: 'dshx-local', model: 'qwen3-0.6b' };

function localTask(text) {
  // Capability exclusions are independent of similarity: an explicit prefix
  // does not give the local text generator live facts or tool access.
  if ([...text].length > 160 || /打开|关闭|点击|搜索|查询|今天|现在|天气|新闻|文件|屏幕|应用|定位|下载|安装|代码|执行|提醒|记住|上次|\b(open|close|click|search|weather|news|file|install|execute|remember)\b/i.test(text)) return null;
  return localRouter.match(text.replace(/^本地回答[：:，,\s]+/, '')).route;
}

export function localQuestion(text) { return localTask(text) !== null; }

function checkedRoute(selected) {
  if (!selected || typeof selected !== 'object' || Array.isArray(selected) ||
      Object.keys(selected).some(key => !['provider', 'model'].includes(key)) ||
      ['provider', 'model'].some(key => typeof selected[key] !== 'string' || !selected[key].trim() || selected[key].length > 256 || /[\u0000-\u001f\u007f]/.test(selected[key])) ||
      selected.provider === route.provider && ![route.model, 'custom'].includes(selected.model)) throw new Error('小模型配置无效。');
  return { provider: selected.provider.trim(), model: selected.model.trim() };
}

function needsHistory(text) {
  return localTask(text) === 'followup' || /这个|那个|它|上文|上面|前面|上一条|上一轮|继续|刚才|第[一二三四五六七八九十\d]+点|\b(it|that|previous|above)\b/i.test(text);
}

function isMemory(message) {
  return message.role === 'user' && message.source?.kind === 'plugin:dshx-memory';
}

// dsh owns the durable history. This is a detached, task-sized view, never a
// second conversation store. Keep complete turns rather than cutting text.
export function localInput(messages, needsHistory = false) {
  const turns = [];
  const memory = [];
  let summary;
  let instructions = [];
  for (const message of messages) {
    const text = message.content.filter(part => part.type === 'text').map(part => part.text).join('\n');
    const kind = message.source?.kind;
    if (message.role === 'user' && kind === 'compact-checkpoint') {
      summary = { role: 'context', kind: 'summary', text };
    } else if (message.role === 'user' && kind === 'agent-instructions') {
      if (message.source.baseline) instructions = [];
      instructions.push({ role: 'context', kind: 'instructions', text });
    } else if (isMemory(message)) {
      if (message.content.some(part => part.type !== 'text')) return null;
      // Updates and invalidation notices must follow older recalls in order.
      memory.push({ role: 'context', kind: 'memory', text });
    } else if (message.role === 'user' && (!kind || kind === 'user')) {
      turns.push([{ role: 'user', text, rich: message.content.some(part => part.type !== 'text') }]);
    } else if (message.role === 'assistant' && (!kind || kind === 'model') && text && turns.length) {
      turns.at(-1).push({ role: 'assistant', text, rich: message.content.some(part => part.type === 'tool-call') });
    } else if (message.role === 'assistant' && message.content.some(part => part.type === 'tool-call') && turns.length) {
      // A tool-dependent turn cannot become a complete text answer by dropping its calls/results.
      turns.at(-1)[0].rich = true;
    }
  }
  if (!turns.length || turns.at(-1).length !== 1 || !turns.at(-1)[0].text || turns.at(-1)[0].rich) return null;
  const context = [...summary ? [summary] : [], ...instructions, ...memory].filter(message => message.text);
  if (context.length > 8) return null;
  let remaining = 1800 - context.reduce((sum, message) => sum + [...message.text].length, 0);
  const selected = [];
  let count = 0;
  for (let index = turns.length - 1; index >= 0; index--) {
    const turn = turns[index];
    const size = turn.reduce((sum, message) => sum + [...message.text].length, 0);
    const required = index === turns.length - 1 || needsHistory && index === turns.length - 2;
    const incomplete = index < turns.length - 1 && !turn.some(message => message.role === 'assistant');
    if (incomplete || turn.some(message => message.rich) || count + turn.length > 8 || size > remaining) {
      if (required) return null;
      break;
    }
    selected.unshift(...turn.map(({ role, text }) => ({ role, text })));
    remaining -= size; count += turn.length;
  }
  if (needsHistory && (turns.length < 2 || !turns.at(-2).some(message => message.role === 'assistant'))) return null;
  return [...context, ...selected];
}

export function localAdapter(Base, host = policyHost) {
  return new class extends Base {
    providerInfo(provider) { return { id: provider, name: '本机小模型' }; }
    async listModels() { return [
      { provider: route.provider, id: route.model, name: 'Qwen3 0.6B · 本地短对话', inputModalities: ['text'] },
      { provider: route.provider, id: 'custom', name: '自定义本地模型', inputModalities: ['text'] },
    ]; }
    async resolveModel(provider, model) {
      const selected = (await this.listModels()).find(row => row.provider === provider && row.id === model);
      if (!selected) throw new Error('不支持的本地模型。');
      return { ...selected, defaultMaxTokens: 192 };
    }
    async *stream(options) {
      const id = randomUUID();
      const started = Date.now();
      let outcome = 'failed';
      let usage;
      const selected = { provider: route.provider, model: options.model ?? route.model };
      if (![route.model, 'custom'].includes(selected.model)) throw new Error('不支持的本地模型。');
      localUsage({ event: 'start', id, sessionId: options.sessionId ?? null, ...selected, at: started });
      try {
        const latest = options.messages.findLast(message => message.role === 'user' && (!message.source?.kind || message.source.kind === 'user'));
        const task = latest?.content.filter(part => part.type === 'text').map(part => part.text).join('\n') ?? '';
        const messages = localInput(options.messages, needsHistory(task));
        if (!messages) {
          const memory = options.messages.some(isMemory);
          yield { type: 'finish', reason: { kind: 'error', failure: {
            code: memory ? 'LOCAL_MEMORY_CONTEXT' : 'LOCAL_CONTEXT',
            message: memory ? '当前任务所需的记忆背景与上下文无法完整交给本地模型，不会自动转用云端模型。'
              : '当前任务所需的上下文无法完整交给本地模型。',
          } } };
          return;
        }
        const result = await host.query('local-model', { session: options.sessionId, messages,
          ...(selected.model === 'custom' ? { model: 'custom' } : {}) }, options.signal);
        options.signal?.throwIfAborted();
        if (typeof result.text !== 'string' || !result.text.trim() ||
          !Number.isSafeInteger(result.inputTokens) || result.inputTokens < 0 ||
          !Number.isSafeInteger(result.outputTokens) || result.outputTokens < 0) throw new Error('本地模型回执无效。');
        usage = { inputTokens: result.inputTokens, outputTokens: result.outputTokens };
        outcome = result.outputTokens >= 192 ? 'max-tokens' : 'success';
        yield { type: 'text-delta', index: 0, text: result.text };
        yield { type: 'usage', usage };
        yield { type: 'finish', reason: { kind: outcome === 'success' ? 'stop' : 'max-tokens' } };
      } finally {
        if (options.signal?.aborted) outcome = 'cancelled';
        localUsage({ event: 'finish', id, sessionId: options.sessionId ?? null, ...selected, outcome, usage: usage ?? null, durationMs: Date.now() - started });
      }
    }
  }();
}

/** 语音与文字共用正式会话，简单任务只临时选择模型。 */
export function voiceSession(ctx, host = policyHost) {
  let active;
  const tasks = new Map();
  const waiting = new Set();
  function restore(run) {
    if (!run.selection || run.restored) return;
    run.restored = true;
    // User model selections are durable events and always take precedence over our temporary route.
    if (!run.changed) run.selection.current = run.previous;
  }
  function select(run) {
    run.selection = ctx.sessionController.agents.selectionFor(run.agent);
    run.previous = run.selection.current;
    // 只把原模型作为后续选择写入历史，分工模型仅在当前轮次生效。
    if (run.previous && (run.previous.provider !== run.route.provider || run.previous.model !== run.route.model)) {
      run.recording = true;
      try { ctx.sessionController.agents.selectForNextRequest(run.agent, run.previous); }
      finally { run.recording = false; }
    }
    run.selection.current = { ...run.route };
  }
  function release(run) {
    restore(run);
    run.assembly?.(); run.retry?.();
    tasks.delete(run.agent);
  }
  function removePending(run) {
    if (!run.agent) return;
    for (const message of [...run.agent.inbox.nextTurn, ...run.agent.inbox.nextStep]) {
      if (message.source?.rpcId === run.request) run.agent.inbox.remove(message.id);
    }
  }
  function stop(run, error) {
    if (active !== run || run.cancelled) return;
    run.cancelled = true;
    removePending(run);
    restore(run);
    run.reject(error);
    if (run.entered && !run.finished) run.agent.cancel({ kind: 'user' }, { keepInbox: true });
  }
  function publish(run, text) {
    if (!text || !run.onEvent || run.cancelled || active !== run) return;
    text = [...text].slice(0, 16000 - run.streamedLength).join('');
    if (!text) return;
    run.streamedText += text;
    run.streamedLength += [...text].length;
    try {
      Promise.resolve(run.onEvent({ session: run.session, text })).catch(error => stop(run, error));
    } catch (error) { stop(run, error); }
  }
  const cancel = (interaction) => {
    for (const pending of waiting) {
      if (interaction && interaction !== pending.interaction) continue;
      pending.cancelled = true;
      pending.wake.resolve();
    }
    const run = active;
    if (!run || interaction && interaction !== run.interaction) return;
    stop(run, new Error('语音对话已取消。'));
    return run.done.promise;
  };
  const claimed = ctx.on('agent/inbox/claimed', ({ agent, message, turn }) => {
    const run = active;
    if (!run || run.agent !== agent || message.source?.kind !== 'user') return claimTask({ agent, message, turn });
    if (message.source.rpcId === run.request) {
      run.entered = true; run.turn = turn;
      if (run.cancelled) { agent.cancel({ kind: 'user' }, { keepInbox: true }); return; }
      // Claim happens before prompt assembly; user/message is persisted only after model preparation.
      if (run.local) {
        const task = localTask(run.input);
        const history = agent.session?.deriveMessages?.() ?? [];
        // Keep memory-bearing requests local so the adapter can report an
        // incomplete input instead of silently routing it to the original model.
        run.local = task !== null && (history.some(isMemory) || localInput([...history, message], needsHistory(run.input)) !== null);
      }
      if (run.local || run.online) {
        select(run);
      }
    } else {
      run.finished = true; run.cancelled = true;
      removePending(run); restore(run);
      run.reject(new Error('会话已收到新的输入，语音播报已停止。'));
      claimTask({ agent, message, turn });
    }
  });
  function claimTask({ agent, message, turn }) {
    if (active?.agent === agent && message.source?.rpcId === active.request) return;
    const previous = tasks.get(agent);
    if (previous) {
      release(previous);
      // 同一轮收到追加输入时不再将它当作独立简单任务。
      if (previous.turn === turn) {
        tasks.set(agent, { agent, session: agent.session.id, turn });
        return;
      }
    }
    if (!host.configured) return;
    const run = { agent, session: agent.session.id, turn };
    tasks.set(agent, run);
    if (message.source?.kind !== 'user' || message.content.some(part => part.type !== 'text')) return;
    const input = message.content.map(part => part.text).join('\n').trim();
    if (!localQuestion(input)) return;
    run.input = input;
    // 领取消息后、组装提示词前读取配置，让正式模型选择与界面入口无关。
    run.assembly = agent.ctx.on('system-prompt/assemble', async (_assembly, { signal }, next) => {
      if (!signal || run.prepared || tasks.get(agent) !== run) return next();
      run.prepared = true;
      const settings = await host.smallModel(run.session, signal);
      signal.throwIfAborted();
      if (tasks.get(agent) !== run || run.changed || settings?.enabled !== true) return next();
      run.route = checkedRoute(settings.textRoute ?? settings.route);
      run.local = run.route.provider === route.provider;
      const history = agent.session.deriveMessages();
      if (run.local && !history.some(isMemory) && localInput([...history, message], needsHistory(input)) === null) return next();
      select(run);
      return next();
    }, { prepend: true });
  }
  function taskEvent(session, entry) {
    for (const run of tasks.values()) {
      if (run.session !== session.id) continue;
      if (entry.type === 'model/selection' && !run.recording) run.changed = true;
      if (entry.type === 'turn/end' && entry.data.turn === run.turn) release(run);
    }
  }
  const request = ctx.on('agent/request', ({ agent, turn, signal }, next) => {
    const run = active;
    if (run?.agent === agent && run.entered && run.turn === turn) run.signal = signal;
    return next();
  });
  const stream = ctx.on('agent/assistant-stream', ({ agent, frame }) => {
    const run = active;
    if (!run || run.agent !== agent || !run.entered || run.cancelled || run.finished || run.signal?.aborted) return;
    if (frame.type === 'start') {
      run.attempt = frame.turn === run.turn ? { id: frame.attemptId, index: -1, text: '', length: 0 } : undefined;
      return;
    }
    if (!run.attempt || run.attempt.id !== frame.attemptId) return;
    if (frame.type === 'end') { run.attempt = undefined; return; }
    if (frame.type !== 'chunk' || frame.index <= run.attempt.index) return;
    run.attempt.index = frame.index;
    const chunk = frame.chunk;
    // 只转发正式文本通道；思考、工具参数及整块快照不能作为可播报增量。
    if (chunk.type !== 'text-delta' || typeof chunk.text !== 'string' || !chunk.text || !run.onEvent) return;
    const text = [...chunk.text].slice(0, Math.max(0, Math.min(4000 - run.attempt.length, 12000 - run.streamedLength))).join('');
    if (!text) return;
    // 实时增量最多累计 12000 码点，给结束时确认的最终回答预留 4000。
    run.attempt.text += text;
    run.attempt.length += [...text].length;
    publish(run, text);
  });
  const event = ctx.on('session/event', (session, entry) => {
    taskEvent(session, entry);
    const run = active;
    if (!run || run.session !== session.id) return;
    if (entry.type === 'model/selection' && run.selection && !run.recording) run.changed = true;
    if (entry.type === 'user/message' && entry.data.source?.kind === 'user') {
      if (entry.data.source?.rpcId === run.request) {
        if (run.cancelled) return;
      } else {
        // A typed turn takes over; never cancel or speak that later turn.
        run.finished = true;
        run.cancelled = true;
        removePending(run); restore(run);
        run.reject(new Error('会话已收到新的输入，语音播报已停止。'));
      }
    }
    if (run.cancelled) return;
    if (run.entered && entry.type === 'assistant/message' && !entry.data.interrupted &&
        (entry.data.turn === undefined || entry.data.turn === run.turn)) {
      const message = entry.data.message;
      if (!message.content.some(part => part.type === 'tool-call')) {
        run.text = message.content.filter(part => part.type === 'text').map(part => part.text).join('\n');
        run.answerStreamed = run.attempt?.text ?? '';
      }
    }
    if (run.entered && entry.type === 'turn/end' && entry.data.turn === run.turn) {
      run.finished = true; restore(run);
      if (entry.data.reason?.kind !== 'completed') {
        if (run.contextFailure) return run.reject(Object.assign(new Error(run.contextFailure.message), { code: run.contextFailure.code }));
        const memory = entry.data.reason?.error?.message === '当前可用内存不足，本地模型暂不加载。请关闭其他占用内存的应用后重试。';
        return run.reject(Object.assign(new Error('本轮已停止或失败，不再自动播报。'), memory ? { code: 'VOICE_MEMORY' } : {}));
      }
      if (!run.text) return run.reject(new Error('本轮没有可播报的回答。'));
      const text = [...run.text].slice(0, 4000).join('');
      // 快照或无 text-delta 的回答在这里补齐；只补已发前缀的后缀，不能整篇重读。
      if (text.startsWith(run.answerStreamed)) publish(run, text.slice(run.answerStreamed.length));
      if (run.cancelled) return;
      const local = typeof session.requestHeader === 'function' ? session.requestHeader()?.config.provider === route.provider : run.local;
      run.resolve({ session: session.id, text, local,
        ...(run.onEvent ? { streamedText: run.streamedText } : {}) });
    }
  });
  // Scoped instructions can enter after inbox claim. Recheck at the adapter
  // boundary, and retry that same logged user message once on its original model.
  const retry = ctx.on('agent/request-error', ({ agent, provider, failure, signal }, next) => {
    const run = active?.agent === agent ? active : tasks.get(agent);
    if (failure.code === 'LOCAL_MEMORY_CONTEXT' && provider === route.provider && run?.agent === agent) {
      run.contextFailure = failure;
      return next();
    }
    if (failure.code !== 'LOCAL_CONTEXT' || provider !== route.provider || signal.aborted || !run?.local ||
        run.cancelled || run.changed || run.agent !== agent || !run.previous || run.previous.provider === route.provider) return next();
    run.local = false;
    restore(run);
    run.recording = true;
    try { ctx.sessionController.agents.selectForNextRequest(agent, run.previous); }
    finally { run.recording = false; }
    // Model selection is snapshotted at prompt assembly. A same-step retry
    // needs a request override outside that snapshot, scoped to this run only.
    run.retry = agent.ctx.on('agent/request', async (_payload, next) => {
      const config = await next();
      if (run.cancelled || run.changed || (active !== run && tasks.get(agent) !== run)) return config;
      const { reasoningEffort: _effort, ...rest } = config;
      return { ...rest, ...run.previous };
    }, { prepend: true });
    return { kind: 'retry' };
  });
  return {
    async call(action, data, onEvent) {
      if (action === 'cancel') { await cancel(data.interaction); return { cancelled: true }; }
      if (action !== 'prompt') throw new Error('不支持的语音会话操作。');
      if (active?.cancelled) {
        const pending = { interaction: data.interaction, cancelled: false, wake: Promise.withResolvers() };
        waiting.add(pending);
        try {
          while (active?.cancelled && !pending.cancelled) await Promise.race([active.done.promise, pending.wake.promise]);
          if (pending.cancelled) throw new Error('语音对话已取消。');
        } finally { waiting.delete(pending); }
      }
      if (active) throw new Error('上一轮语音对话尚未结束。');
      if (onEvent !== undefined && typeof onEvent !== 'function') throw new Error('语音事件回调无效。');
      if (typeof data.interaction !== 'string' || !/^[a-zA-Z0-9-]{1,80}$/.test(data.interaction)) throw new Error('语音轮次无效。');
      if (typeof data.text !== 'string' || !data.text.trim() || Buffer.byteLength(data.text) > 16000) throw new Error('语音文字为空或过长。');
      const target = checkedRoute(data.smallModel ?? route);
      const split = data.local === true && localQuestion(data.text.trim());
      let resolve, reject;
      const reply = new Promise((yes, no) => { resolve = yes; reject = no; });
      reply.catch(() => {});
      const run = { request: randomUUID(), interaction: data.interaction, session: data.session, resolve, reject, input: data.text.trim(),
        route: target, local: split && target.provider === route.provider, online: split && target.provider !== route.provider,
        entered: false, cancelled: false, text: '', answerStreamed: '', onEvent, streamedText: '', streamedLength: 0,
        done: Promise.withResolvers() };
      active = run;
      const timer = setTimeout(() => cancel(run.interaction), 170000);
      try {
        if (!run.session) run.session = (await ctx.sessionController.create({})).sessionId;
        if (run.cancelled) throw new Error('语音对话已取消。');
        if (typeof run.session !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(run.session)) throw new Error('语音会话编号无效。');
        await run.onEvent?.({ session: run.session, text: '' });
        if (run.cancelled) throw new Error('语音对话已取消。');
        const agent = await ctx.sessionController.commands.resolveAgent(run.session);
        run.agent = agent;
        if (run.cancelled) throw new Error('语音对话已取消。');
        if (agent.status !== 'idle' || agent.inbox.hasPending) throw new Error('当前会话正在执行，请先停止或等待完成。');
        await ctx.sessionController.commands.prompt({
          sessionId: run.session, requestId: run.request, mode: 'queue', content: [{ type: 'text', text: data.text.trim() }],
        }, { kind: 'user', rpcId: run.request, clientTimeZone: 'Asia/Shanghai' });
        if (run.cancelled) { removePending(run); throw new Error('语音对话已取消。'); }
        return await reply;
      } catch (error) {
        stop(run, error);
        throw error;
      } finally {
        clearTimeout(timer); run.retry?.(); restore(run);
        try {
          // 取消回执等待本次清理和运行器停稳，避免下一轮抢入仍在收尾的 agent。
          if (run.cancelled && run.entered && !run.finished) await run.agent.whenIdle?.();
        } finally {
          if (active === run) active = undefined;
          run.done.resolve();
        }
      }
    },
    close() {
      cancel();
      for (const run of tasks.values()) release(run);
      retry(); event(); stream(); request(); claimed();
    },
  };
}

export async function apply(ctx) {
  const require = createRequire(new URL('./lib/node_modules/package.json', import.meta.url));
  const { LlmAdapter } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-llm')));
  const dispose = ctx.llm.registerAdapter([route.provider], localAdapter(LlmAdapter));
  const voice = voiceSession(ctx);
  policyHost.voice = voice.call;
  ctx.effect(() => () => { if (policyHost.voice === voice.call) policyHost.voice = undefined; voice.close(); dispose(); });
}
