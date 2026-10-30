import { randomUUID, randomInt } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { policyHost } from './host.mjs';
import { hostingEvent, hostingHistory, observeRequests } from './assistant.mjs';
import { specimenSession } from './specimen.mjs';

const require = createRequire(new URL('./lib/node_modules/package.json', import.meta.url));
export const name = 'dshx-android-hosting';
export const inject = ['sessionController', 'agents', 'storageDomain', 'sessions', 'sessionPersistence', 'workingDirectory', 'llm'];

const interval = 5 * 60_000;
const sourceKind = 'plugin:dshx-hosting';
const ownMessage = message => message?.source?.kind === sourceKind;
const readyReason = status => status?.ok !== true ? 'device_unavailable' : status.enabled !== true ? 'phone_disabled'
  : status.locked !== false ? 'locked' : status.interactive !== true ? 'screen_off'
  : status.accessibility !== true ? 'accessibility_offline' : status.ready !== true ? 'device_unavailable' : undefined;

/** 单个进程只保留当前机会；会话、模型循环和手机工具仍由 dsh 管理。 */
export function hostingSession(ctx, options = {}) {
  const host = options.host ?? policyHost;
  const settings = options.settings;
  const record = options.record ?? hostingEvent;
  const now = options.now ?? Date.now;
  const later = options.setTimeout ?? setTimeout;
  const clear = options.clearTimeout ?? clearTimeout;
  const dice = options.dice ?? (() => randomInt(100));
  let closed = false, checking = false, timer, run, nextCheckAt;
  const recent = [...options.recent ?? []];
  let previous = JSON.stringify(settings());
  const busy = () => ctx.agents.list().some(agent => agent.status !== 'idle' || agent.inbox.hasPending);
  const current = snapshot => !closed && JSON.stringify(settings()) === snapshot;
  const log = (event, row = {}) => {
    const entry = { event, at: now(), ...row };
    record(entry); recent.push(entry);
    if (recent.length > 20) recent.shift();
  };
  const removePending = active => {
    for (const message of [...active.agent.inbox.nextTurn, ...active.agent.inbox.nextStep]) {
      if (ownMessage(message) && message.source.rpcId === active.id) active.agent.inbox.remove(message.id);
    }
  };
  function finish(outcome, reason) {
    const active = run;
    if (!active) return;
    run = undefined;
    clear(active.timeout);
    active.unobserve?.();
    options.learning?.finish(active, outcome, reason);
    log('finish', { id: active.id, session: active.session, outcome, reason,
      durationMs: Math.max(0, now() - active.startedAt), tools: active.tools });
  }
  function cancel(reason) {
    const active = run;
    if (!active) return;
    removePending(active);
    try { finish('cancelled', reason); }
    finally {
      // 只取消自己的轮次；人类的新输入保留在原有收件队列中。
      if (active.entered && !active.ended) active.agent.cancel({ kind: 'user' }, { keepInbox: true });
    }
  }
  function arm() {
    clear(timer);
    nextCheckAt = now() + interval;
    if (!closed) timer = later(() => {
      // 从本次实际唤醒重新计时，休眠跨过多个周期也只检查一次。
      arm();
      void check().catch(() => console.warn('后台托管记录不可用，本次机会已停止。'));
    }, interval);
  }
  function refresh() {
    const next = JSON.stringify(settings());
    if (next === previous) return;
    previous = next;
    cancel('settings_changed');
    arm();
  }
  async function check() {
    if (closed || checking || !settings().enabled) return;
    checking = true;
    const config = settings(), snapshot = JSON.stringify(config), id = randomUUID();
    let session = config.session || options.store?.get().session;
    const skip = reason => log('check', { id, session: session || null, outcome: 'skipped', reason });
    try {
      if (!Number.isInteger(config.probability) || config.probability < 0 || config.probability > 100 ||
          typeof config.session !== 'string' || config.session !== '' && !/^[A-Za-z0-9._:-]{1,128}$/.test(config.session) ||
          !session && !options.store ||
          typeof config.prompt !== 'string' || !config.prompt.trim() || config.prompt.length > 4000) return skip('not_configured');
      if (run || busy()) return skip('busy');
      const blocked = options.learning?.blocked?.();
      if (blocked) return skip(blocked);
      let reason = readyReason(await host.query('phone-status', {}));
      if (!current(snapshot)) return skip('settings_changed');
      if (reason) return skip(reason);
      const roll = dice();
      if (roll >= config.probability) return log('check', { id, session: session || null, outcome: 'skipped', reason: 'probability', probability: config.probability, roll });
      if (busy()) return skip('busy');
      if (!session) {
        session = (await ctx.sessionController.create({})).sessionId;
        await options.store.set({ session });
      }
      const agent = await ctx.sessionController.commands.resolveAgent(session);
      if (!current(snapshot)) return skip('settings_changed');
      if (run || busy() || agent.status !== 'idle' || agent.inbox.hasPending) return skip('busy');
      if (agent.session.header.parentSession || agent.session.header.origin === 'subagent' || agent.session.header.delegationDepth > 0) return skip('child_session');
      reason = readyReason(await host.query('phone-status', {}));
      if (!current(snapshot)) return skip('settings_changed');
      if (reason) return skip(reason);
      if (busy()) return skip('busy');
      log('start', { id, session, probability: config.probability, roll });
      const active = { id, session, agent, snapshot, startedAt: now(), entered: false, ended: false,
        revision: options.revision?.() ?? 0, requests: new Map(), tools: { completed: 0, failed: 0, unknown: 0 } };
      run = active;
      active.timeout = later(() => { if (run === active) cancel('timeout'); }, 170_000);
      await options.learning?.start(active);
      if (run !== active || !current(snapshot)) return;
      if (busy()) { cancel('busy'); return; }
      active.unobserve = observeRequests(session, event => {
        if (event.event === 'start') {
          if (run !== active || !current(active.snapshot) || active.requests.size >= 6) {
            if (run === active) cancel('request_limit');
            throw new Error('培养请求次数已达上限或本轮已取消。');
          }
          active.requests.set(event.id, { id: event.id, provider: event.provider, model: event.model });
        }
        else if (active.requests.has(event.id)) Object.assign(active.requests.get(event.id), event.event === 'usage' ? { usage: event.usage } : { outcome: event.outcome });
        return active.id;
      });
      await ctx.sessionController.commands.prompt({ sessionId: session, requestId: id, mode: 'queue', content: [{ type: 'text',
        text: `这是用户启用的后台托管当前机会，不是补跑历史任务。先检查 phone_status，只执行下面任务说明中当前适合且已获授权的手机操作；锁屏、息屏、操作开关关闭或无障碍不可用时立即停止。先观察再操作，操作后核实结果，结果不明不能重放。没有合适操作时明确记录本次跳过。最后简要说明实际执行结果和失败原因。\n\n用户配置的任务：\n${config.prompt.trim()}${active.context ?? ''}`,
      }] }, { kind: sourceKind, rpcId: id });
      // 配置更改或停止可能发生在官方提示接收过程的等待中。
      if (run !== active) removePending(active);
    } catch {
      if (run?.id === id) cancel('request_failed');
      else skip('unavailable');
    } finally { checking = false; }
  }
  const off = [
    ctx.on('tools/pre-execute', (exec, next) => {
      if (exec.agent === run?.agent && run.entered) {
        run.toolCalls = (run.toolCalls ?? 0) + 1;
        if (run.toolCalls > 20) { cancel('tool_limit'); throw new Error('本轮培养工具次数已达上限。'); }
      }
      return next();
    }),
    ctx.on('agent/created', ({ agent }) => {
      for (const message of [...agent.inbox.nextTurn, ...agent.inbox.nextStep]) {
        if (ownMessage(message) && message.source.rpcId !== run?.id) agent.inbox.remove(message.id);
      }
    }),
    ctx.on('agent/inbox/inserted', ({ agent, message }) => {
      if (run?.agent === agent && !ownMessage(message) && message.source?.kind === 'user') cancel('user_input');
    }),
    ctx.on('agent/inbox/claimed', ({ agent, message, turn }) => {
      if (run?.agent === agent && ownMessage(message) && message.source.rpcId === run.id) {
        run.entered = true; run.turn = turn;
      }
    }),
    ctx.on('agent/pre-step', async ({ agent }, next) => {
      const decision = await next();
      if (decision.kind !== 'enter') return decision;
      // 进程恢复的旧托管输入没有当前运行凭证，不准重新进入模型。
      const messages = decision.messages.filter(message => !ownMessage(message) ||
        run?.agent === agent && message.source.rpcId === run.id && current(run.snapshot));
      if (decision.messages.length && !messages.length) return { kind: 'reject' };
      const active = run;
      if (active?.agent === agent && active.entered) {
        let reason;
        try { reason = readyReason(await host.query('phone-status', {})); }
        catch { reason = 'device_unavailable'; }
        if (run !== active) return { kind: 'reject' };
        if (!current(active.snapshot) || reason) { cancel(reason ?? 'settings_changed'); return { kind: 'reject' }; }
      } else if (!messages.some(message => message.source?.kind === 'user') && ownMessage(agent.session.deriveMessages?.()
        .findLast(message => message.role === 'user' && (ownMessage(message) || !message.source?.kind || message.source.kind === 'user')))) {
        return { kind: 'reject' };
      }
      return { ...decision, messages };
    }),
    ctx.on('tools/result', (exec, result) => {
      if (exec.agent !== run?.agent || !exec.name.startsWith('phone_')) return;
      const key = result.value?.execution_state === 'unknown' ? 'unknown' : result.isError || result.value?.ok === false ? 'failed' : 'completed';
      run.tools[key]++;
    }),
    ctx.on('session/event', (session, entry) => {
      if (!run || session.id !== run.session || !run.entered) return;
      if (entry.type === 'turn/end' && entry.data.turn === run.turn) {
        run.ended = true;
        const kind = entry.data.reason?.kind;
        const outcome = kind !== 'completed' ? kind === 'error' ? 'failed' : 'cancelled'
          : run.tools.unknown ? 'unknown' : run.tools.failed ? 'failed' : 'completed';
        finish(outcome, kind ?? 'unknown');
      }
    }),
    ctx.on('agent/disposed', ({ agent }) => { if (run?.agent === agent) cancel('session_disposed'); }),
  ];
  arm();
  return { check, refresh, stop: cancel, async manage(action, data) {
    // 先用调用者看到的版本提交管理变更，再停止旧轮次；失败请求不改变执行状态。
    const result = await options.learning.call(action, data);
    if (action === 'goal') { cancel('management_changed'); await options.learning.settled(); }
    return result;
  }, status: () => ({ running: Boolean(run), session: run?.session ?? (settings().session || options.store?.get().session || null),
    nextCheckAt: settings().enabled ? nextCheckAt : null, recent: [...recent].reverse() }), close() {
    closed = true; clear(timer);
    try { cancel('stopped'); } finally { for (const dispose of off) dispose(); }
  } };
}

export async function apply(ctx) {
  const { z: schema } = await import(pathToFileURL(require.resolve('zod')));
  const { defineDomain } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-storage-domain')));
  const settingsSchema = schema.object({ enabled: schema.boolean(), probability: schema.number().int().min(0).max(100),
    session: schema.string().regex(/^(?:[A-Za-z0-9._:-]{1,128})?$/), prompt: schema.string().max(4000),
    learningEnabled: schema.boolean().default(false),
  }).strict().refine(value => !value.enabled || value.prompt.trim().length > 0);
  const stateSchema = schema.object({ session: schema.string().regex(/^(?:[A-Za-z0-9._:-]{1,128})?$/),
    revision: schema.number().int().nonnegative().default(0),
    settings: settingsSchema.default({ enabled: false, probability: 20, session: '', prompt: '' }),
  }).strict();
  const domain = await ctx.storageDomain.open(defineDomain({ name: 'hosting', version: 1, layout: 'single', tables: {},
    global: { schema: stateSchema, initial: stateSchema.parse({ session: '' }) } }));
  const snapshot = () => stateSchema.parse(domain.global.get());
  let queue = Promise.resolve();
  const change = update => {
    // 配置保存和独立会话绑定共用写入队列，避免等待期间覆盖对方的新值。
    const result = queue.then(async () => { const next = update(snapshot()); await domain.global.set(next); });
    queue = result.catch(() => {});
    return result;
  };
  const learning = await specimenSession(ctx, () => snapshot().settings);
  const hosting = hostingSession(ctx, { store: { get: snapshot, set: binding => change(state => ({ ...state, ...binding })) },
    learning, revision: () => snapshot().revision, recent: hostingHistory(), settings: () => snapshot().settings });
  const call = async (action, data) => {
    if (action === 'evidence') {
      if (!data || Object.keys(data).some(key => key !== 'id') || typeof data.id !== 'string') throw new Error('培养来源请求无效。');
      return learning.evidence(data.id);
    }
    if (['skill', 'prepare', 'applied', 'rejected', 'discard', 'retry', 'goal', 'resolve'].includes(action)) {
      const fields = { skill: ['id'], prepare: ['id', 'revision'], applied: ['id', 'operation', 'digest', 'path'],
        rejected: ['id', 'operation', 'digest'], discard: ['id', 'revision'], retry: ['id', 'revision'], resolve: ['id', 'revision'],
        goal: ['id', 'revision', 'mode', 'text', 'checkText', 'maxRuns'] };
      if (!data || typeof data.id !== 'string' || data.id.length > 300 || Object.keys(data).some(key => !fields[action].includes(key)) ||
          ['prepare', 'discard', 'retry', 'goal', 'resolve'].includes(action) && (!Number.isSafeInteger(data.revision) || data.revision < 0) ||
          Buffer.byteLength(JSON.stringify(data)) > 8192) throw new Error('方法请求无效。');
      return hosting.manage(action, data);
    }
    if (action === 'settings') {
      if (!data || Object.keys(data).some(key => !['expectedRevision', 'settings'].includes(key)) ||
          !Number.isSafeInteger(data.expectedRevision) || data.expectedRevision < 0 || !data.settings ||
          Object.keys(data.settings).some(key => !['enabled', 'probability', 'session', 'prompt', 'learningEnabled'].includes(key))) throw new Error('托管配置无效。');
      await change(state => {
        if (state.revision !== data.expectedRevision) throw new Error('托管配置已经变化。');
        return { ...state, revision: state.revision + 1, settings: settingsSchema.parse({ ...state.settings, ...data.settings }) };
      });
      hosting.refresh();
      await learning.refresh();
    } else if (action !== 'status' || Object.keys(data ?? {}).length) throw new Error('不支持的托管操作。');
    const state = snapshot();
    return { settings: state.settings, revision: state.revision, ...hosting.status(), learning: learning.status() };
  };
  policyHost.hosting = call;
  ctx.effect(() => async () => {
    if (policyHost.hosting === call) policyHost.hosting = undefined;
    try { hosting.close(); } finally { await learning.close(); await queue; await domain.close(); }
  });
}
