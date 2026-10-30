import { createHash } from 'node:crypto';
import { policyHost } from './host.mjs';
import { memoryInput } from './memory-extract.mjs';

const contexts = new WeakMap();
const bytes = value => Buffer.byteLength(JSON.stringify(value));
const digest = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const calls = { list: 1, search: 3, read: 6 };

// 同一个引擎的两套工具共用记录；模块关闭、代理重建不会给旧问题重新发额度。
export function readBudget(ctx, { capacity: override, failure } = {}) {
  const rootContext = ctx.root ?? ctx;
  if (!contexts.has(rootContext)) contexts.set(rootContext, { budgets: new Map(), floor: undefined });
  const state = contexts.get(rootContext);
  function sessionFor(agent) {
    if (!agent?.session?.id || !ctx.agents?.list?.().some(row => row === agent || row.id === agent.id && row.session === agent.session)) throw failure('NOT_READY');
    const session = agent.session;
    if (session.header?.parentSession || session.header?.origin === 'subagent' || session.header?.delegationDepth > 0 ||
        policyHost.cachedMode(session.id) === 'unavailable') throw failure('NOT_READY');
    const workspace = ctx.workingDirectory?.get(session) ?? session.header?.cwd;
    if (typeof workspace !== 'string' || !workspace.startsWith('/')) throw failure('NOT_READY');
    return { session, workspace };
  }
  function root(session) {
    const event = session.snapshotEvents?.().findLast(row => row.type === 'user/message' && memoryInput(row.data));
    if (!event || !Number.isSafeInteger(event.seq)) throw failure('NOT_READY');
    let budget = state.budgets.get(session.id);
    if (!budget || budget.request !== event.seq) {
      if (!budget && state.floor !== undefined && (!Number.isSafeInteger(event.time) || event.time <= state.floor)) throw failure('LIMIT');
      if (!budget && state.budgets.size >= 64) {
        const live = new Set(ctx.agents.list().map(row => row.session.id));
        const expired = [...state.budgets].find(([id, row]) => !live.has(id) && !row.reserved && Number.isSafeInteger(row.time));
        if (!expired || !Number.isSafeInteger(event.time) || event.time <= expired[1].time) throw failure('LIMIT');
        state.floor = Math.max(state.floor ?? 0, expired[1].time);
        state.budgets.delete(expired[0]);
      }
      budget = { request: event.seq, time: event.time, counts: {}, used: 0, reserved: 0 };
      state.budgets.set(session.id, budget);
    }
    return budget;
  }
  async function capacity(session, signal) {
    if (override !== undefined) return () => override;
    const header = session.requestHeader?.(), config = header?.config;
    if (!config) throw failure('LIMIT');
    const signature = digest(header);
    const info = await ctx.llm.resolveModelInfo(config.provider, config.model, signal);
    const window = info.context?.contextWindow;
    if (!Number.isSafeInteger(window) || window <= 0) throw failure('LIMIT');
    return () => {
      if (digest(session.requestHeader?.()) !== signature) throw failure('VERSION_CHANGED');
      const meter = typeof ctx.get === 'function' ? ctx.get('tokenMeter') : ctx.tokenMeter;
      const measured = meter?.measure(session, header)?.totalTokens;
      const input = Math.max(bytes(session.deriveMessages?.() ?? []) + bytes(header), Number.isFinite(measured) ? measured : 0);
      const output = config.maxTokens ?? info.defaultMaxTokens ?? Math.min(4096, Math.floor(window / 4));
      return Math.max(0, Math.min(4096, Math.floor(window / 4), window - input - output - 256));
    };
  }
  return {
    sessionFor,
    async execute(tool, exec, prepare, fit, ensure) {
      exec.signal?.throwIfAborted();
      const { session, workspace } = sessionFor(exec.agent);
      const budget = root(session), group = tool.slice(tool.lastIndexOf('_') + 1);
      if (!calls[group] || (budget.counts[group] ?? 0) >= calls[group]) throw failure('LIMIT');
      budget.counts[group] = (budget.counts[group] ?? 0) + 1;
      const measure = await capacity(session, exec.signal);
      exec.signal?.throwIfAborted();
      const maximum = Math.min(group === 'read' ? 12288 : 6144, measure() - budget.used - budget.reserved);
      if (maximum < 256) throw failure('LIMIT');
      budget.reserved += maximum;
      try {
        const result = await prepare(workspace, session);
        const remeasure = await capacity(session, exec.signal);
        exec.signal?.throwIfAborted();
        const remaining = Math.min(maximum, measure() - budget.used - (budget.reserved - maximum),
          remeasure() - budget.used - (budget.reserved - maximum));
        if (sessionFor(exec.agent).workspace !== workspace || root(session) !== budget) throw failure('VERSION_CHANGED');
        if (remaining < 256) throw failure('LIMIT');
        const fitted = fit(result, remaining);
        ensure(workspace);
        budget.used += bytes(fitted);
        return fitted;
      } finally { budget.reserved -= maximum; }
    },
  };
}
