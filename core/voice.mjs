import { policyHost } from './host.mjs';
import { localUsage } from './assistant.mjs';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

export const name = 'dshx-android-voice';
export const inject = ['sessionController', 'agents', 'llm'];
const route = { provider: 'dshx-local', model: 'qwen3-0.6b' };

// This is deliberately conservative: local text generation cannot execute tools or obtain live facts.
export function localQuestion(text) {
  if ([...text].length > 160 || /打开|关闭|点击|搜索|查询|今天|现在|天气|新闻|文件|屏幕|应用|定位|下载|安装|代码|执行|提醒|记住|上次|刚才/.test(text)) return false;
  return /^本地回答[：:，,\s]/.test(text) || /^(你好|您好|嗨|早上好|晚上好|谢谢|再见|你是谁|你叫什么|介绍一下自己)[。！!？?\s]*$/.test(text)
    || [...text].length <= 80 && /^(什么是|解释一下|简单解释)/.test(text);
}

export function localAdapter(Base, host = policyHost) {
  return new class extends Base {
    providerInfo(provider) { return { id: provider, name: '本机小模型' }; }
    async listModels() { return [{ provider: route.provider, id: route.model, name: 'Qwen3 0.6B · 本地短对话', inputModalities: ['text'] }]; }
    async resolveModel(provider, model) {
      if (provider !== route.provider || model !== route.model) throw new Error('不支持的本地模型。');
      return { ...(await this.listModels())[0], defaultMaxTokens: 192 };
    }
    async *stream(options) {
      const id = randomUUID();
      const started = Date.now();
      let outcome = 'failed';
      let usage;
      localUsage({ event: 'start', id, sessionId: options.sessionId ?? null, ...route, at: started });
      try {
        const messages = options.messages.filter(message => ['user', 'assistant'].includes(message.role))
          .filter(message => !message.source || message.source.kind === 'user')
          .map(message => ({ role: message.role, text: [...message.content.filter(part => part.type === 'text').map(part => part.text).join('\n')].slice(0, 600).join('') }))
          .filter(message => message.text).slice(-8);
        const result = await host.query('local-model', { session: options.sessionId, messages }, options.signal);
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
        localUsage({ event: 'finish', id, sessionId: options.sessionId ?? null, ...route, outcome, usage: usage ?? null, durationMs: Date.now() - started });
      }
    }
  }();
}

/** Native voice submits ordinary user messages; the official loop owns history and tools. */
export function voiceSession(ctx) {
  let active;
  function restore(run) {
    if (!run.selection || run.restored) return;
    run.restored = true;
    // User model selections are durable events and always take precedence over our temporary route.
    if (!run.changed) run.selection.current = run.previous;
  }
  function removePending(run) {
    if (!run.agent) return;
    for (const message of [...run.agent.inbox.nextTurn, ...run.agent.inbox.nextStep]) {
      if (message.source?.rpcId === run.request) run.agent.inbox.remove(message.id);
    }
  }
  const cancel = (interaction) => {
    const run = active;
    if (!run || interaction && interaction !== run.interaction) return;
    run.cancelled = true;
    removePending(run);
    restore(run);
    if (run.entered && !run.finished) run.agent.cancel({ kind: 'user' }, { keepInbox: true });
    run.reject(new Error('语音对话已取消。'));
  };
  const claimed = ctx.on('agent/inbox/claimed', ({ agent, message, turn }) => {
    const run = active;
    if (!run || run.agent !== agent || message.source?.kind !== 'user') return;
    if (message.source.rpcId === run.request) {
      run.entered = true; run.turn = turn;
      if (run.cancelled) { agent.cancel({ kind: 'user' }, { keepInbox: true }); return; }
      // Claim happens before prompt assembly; user/message is persisted only after model preparation.
      if (run.local) {
        run.selection = ctx.sessionController.agents.selectionFor(agent);
        run.previous = run.selection.current;
        // Keep the original route pending in durable history. A local request header
        // must not become the next model after the application recreates this Agent.
        if (run.previous && run.previous.provider !== route.provider) {
          run.recording = true;
          try { ctx.sessionController.agents.selectForNextRequest(agent, run.previous); }
          finally { run.recording = false; }
        }
        run.selection.current = { ...route };
      }
    } else {
      run.finished = true; run.cancelled = true;
      removePending(run); restore(run);
      run.reject(new Error('会话已收到新的输入，语音播报已停止。'));
    }
  });
  const event = ctx.on('session/event', (session, entry) => {
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
    if (run.entered && entry.type === 'assistant/message' && !entry.data.interrupted) {
      const message = entry.data.message;
      if (!message.content.some(part => part.type === 'tool-call')) {
        run.text = message.content.filter(part => part.type === 'text').map(part => part.text).join('\n');
      }
    }
    if (run.entered && entry.type === 'turn/end' && entry.data.turn === run.turn) {
      run.finished = true; restore(run);
      if (entry.data.reason?.kind !== 'completed') {
        const memory = entry.data.reason?.error?.message === '当前可用内存不足，本地模型暂不加载。请关闭其他占用内存的应用后重试。';
        return run.reject(Object.assign(new Error('本轮已停止或失败，不再自动播报。'), memory ? { code: 'VOICE_MEMORY' } : {}));
      }
      if (!run.text) return run.reject(new Error('本轮没有可播报的回答。'));
      const local = typeof session.requestHeader === 'function' ? session.requestHeader()?.config.provider === route.provider : run.local;
      run.resolve({ session: session.id, text: [...run.text].slice(0, 4000).join(''), local });
    }
  });
  return {
    async call(action, data) {
      if (action === 'cancel') { cancel(data.interaction); return { cancelled: true }; }
      if (action !== 'prompt') throw new Error('不支持的语音会话操作。');
      if (active) throw new Error('上一轮语音对话尚未结束。');
      if (typeof data.interaction !== 'string' || !/^[a-zA-Z0-9-]{1,80}$/.test(data.interaction)) throw new Error('语音轮次无效。');
      if (typeof data.text !== 'string' || !data.text.trim() || Buffer.byteLength(data.text) > 16000) throw new Error('语音文字为空或过长。');
      let resolve, reject;
      const reply = new Promise((yes, no) => { resolve = yes; reject = no; });
      reply.catch(() => {});
      const run = { request: randomUUID(), interaction: data.interaction, session: data.session, resolve, reject, local: data.local === true && localQuestion(data.text.trim()), entered: false, cancelled: false, text: '' };
      active = run;
      const timer = setTimeout(() => cancel(run.interaction), 170000);
      try {
        if (!run.session) run.session = (await ctx.sessionController.create({})).sessionId;
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
      } finally {
        clearTimeout(timer); restore(run);
        if (active === run) active = undefined;
      }
    },
    close() { cancel(); event(); claimed(); },
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
