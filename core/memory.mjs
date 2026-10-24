import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { policyHost } from './host.mjs';
import { openMemory, memoryFailure, searchMemory, accessible, normalize, sameScope } from './memory-store.mjs';
import { extract, selectEvents, memoryRequest } from './memory-extract.mjs';
import { readBudget } from './read-budget.mjs';

export const name = 'dshx-android-memory';
export const inject = ['storageDomain', 'sessions', 'sessionPersistence', 'workingDirectory', 'tools', 'agents', 'llm'];
const require = createRequire(new URL('./lib/node_modules/package.json', import.meta.url));
let owner;
export function memoryError(error) {
  const messages = {
    MEMORY_INPUT: '记忆内容或请求格式无效，请检查后重试。', MEMORY_READ: '本地记忆无法读取，原文件已保留，暂时不能保存。',
    MEMORY_WRITE: '记忆保存失败，请检查可用空间后重试。', MEMORY_CONFLICT: '记忆已发生变化，请刷新后重新编辑。',
    MEMORY_NOT_READY: '记忆管理尚未就绪，请先启动引擎。', MEMORY_STOPPED: '记忆连接已停止，请重新启动引擎。',
    MEMORY_MISSING: '这条记忆已删除或当前无法访问，请刷新列表。', MEMORY_LIMIT: '本地记忆达到容量限制，请先删除不再需要的内容。',
    MEMORY_MODEL: '来源模型当前不可用，整理任务已保留，可稍后继续。', MEMORY_SOURCE: '来源会话无法完整读取，整理任务未完成。',
    MEMORY_EXTRACT: '整理失败，可重试。', MEMORY_RETRY: '本次任务已重试，请检查模型配置后重新开启自动整理。',
    MEMORY_CAPACITY: '单条用户消息超过整理输入容量，任务已保留；请缩短来源内容或调整来源模型后重试。',
    MEMORY_TOOL_LIMIT: '已达到本次问题的记忆与知识共享读取额度，请缩小查询或提出新的问题。',
    MEMORY_VERSION_CHANGED: '记忆、范围或模型已变化，请重新查询。',
  };
  return messages[error?.code] ?? '记忆请求失败，请稍后重试。';
}
export const isMemoryTool = tool => tool === 'memory_search' || tool === 'memory_read';
export const memoryToolAvailable = tool => isMemoryTool(tool) && Boolean(owner?.active());
export const memoryView = workspace => owner?.view(workspace) ?? { enabled: false, revision: 0, entries: [] };
const userMessage = message => message?.role === 'user' && message.source?.kind === 'user';
const userEvent = event => event.type === 'user/message' && event.data.source?.kind === 'user';
const messageText = message => message.content.filter(part => part.type === 'text').map(part => part.text).join('\n');
const rootSession = session => !session.header.parentSession && session.header.origin !== 'subagent' && !(session.header.delegationDepth > 0);
const routeFor = session => session.requestHeader?.()?.config;
const scopeFor = (ctx, session) => ctx.workingDirectory?.get(session) ?? session.header.cwd;
const sourceRows = source => [...(source.kind === 'auto' ? [{ session: source.session, events: source.events }] : []), ...(source.refs ?? [])];
const revision = data => { if (!Number.isSafeInteger(data?.revision) || data.revision < 0) throw memoryFailure('MEMORY_INPUT'); };

export function memorySession(ctx, options = {}) {
  let store, error, message, timer, closed = false, barrier = false, generation = 0, serviceRevision = 0, run, wake = false, background = Promise.resolve();
  const now = options.now ?? Date.now;
  const schedule = { turns: 4, bytes: 4096, cooldown: 120000, ...options.schedule };
  const budget = readBudget(ctx, { capacity: options.capacity, failure: code => memoryFailure(code === 'LIMIT' ? 'MEMORY_TOOL_LIMIT' : `MEMORY_${code}`) });
  const pending = new Set();
  const reads = new AbortController();
  const off = [];
  const ready = (async () => {
    try {
      store = options.store ?? await openMemory(ctx);
      await recover();
    } catch (cause) { error = memoryError({ code: cause.code === 'MEMORY_LIMIT' ? cause.code : 'MEMORY_READ' }); }
  })();
  const active = () => !closed && !barrier && !error && Boolean(store?.read().settings.enabled);
  const automatic = () => active() && store.read().settings.autoSave;
  const dueAt = (job, snapshot) => job.urgent === false
    ? Math.max(job.notBefore ?? 0, (snapshot.cursors[job.session]?.lastRun ?? 0) + schedule.cooldown) : job.notBefore ?? 0;
  const providers = () => new Set((ctx.llm.listProviders?.() ?? []).map(provider => provider.id));
  const cancel = () => { barrier = true; wake = false; clearTimeout(timer); run?.controller.abort(); return ++generation; };
  const status = () => {
    if (!store || error) return { settings: null, ready: false, active: false, autoReady: false, revision: null, count: 0, pending: 0, jobs: [], message: error ?? memoryError({ code: 'MEMORY_NOT_READY' }) };
    const snapshot = store.read();
    const available = providers();
    return { settings: snapshot.settings, ready: !closed, active: active(), autoReady: automatic() && available.size > 0, revision: snapshot.revision,
      count: snapshot.entries.filter(row => row.state === 'active').length, pending: snapshot.entries.filter(row => row.state === 'pending').length,
      jobs: snapshot.jobs.map(job => ({ id: job.id, state: run?.job.id === job.id ? 'running' : job.state,
        attempts: job.attempts, modelReady: available.has(job.route.provider),
        message: available.has(job.route.provider) ? job.message ?? (job.ready === false ? '等待新增内容；可继续立即整理' : dueAt(job, snapshot) > now() ? '等待整理间隔；可继续立即整理' : '待继续') : memoryError({ code: 'MEMORY_MODEL' }) })),
      ...barrier ? { message: '当前记忆操作已暂停，请重新保存设置或重启引擎。' } : message ? { message } : {} };
  };
  function watch(promise) {
    pending.add(promise);
    promise.catch(cause => { if (!closed) message = memoryError(cause); }).finally(() => pending.delete(promise));
  }
  async function readEvents(id, from, to, signal) {
    const persistence = ctx.sessionPersistence ?? ctx.get?.('sessionPersistence');
    if (!persistence) throw memoryFailure('MEMORY_SOURCE');
    const handle = await persistence.open(id, 'read', { signal });
    try {
      const result = await handle.read(from, to - from, { signal });
      const events = result.events;
      if (!Array.isArray(events) || events.length !== to - from || events.some((event, index) => event.seq !== from + index)) throw memoryFailure('MEMORY_SOURCE');
      return events;
    } finally { await handle.close(); }
  }
  async function register(session) {
    if (!automatic() || !rootSession(session) || !scopeFor(ctx, session)) return;
    await store.change(next => {
      if (!next.settings.enabled || !next.settings.autoSave || barrier || closed) return false;
      const cursor = next.cursors[session.id];
      if (cursor?.epoch === next.epoch && cursor.workspace === scopeFor(ctx, session)) return false;
      next.cursors[session.id] = { epoch: next.epoch, start: session.seq, next: session.seq, workspace: scopeFor(ctx, session) };
    });
  }
  async function enqueue(session, end, resume = false, force = false) {
    if (!automatic() || !rootSession(session)) return;
    const snapshot = store.read(), cursor = snapshot.cursors[session.id];
    if (!cursor || cursor.epoch !== snapshot.epoch || end <= cursor.next) return;
    // A notification alone does not prove disk durability, and false means no writer participated.
    if (!resume && !await ctx.sessions.flush(session)) throw memoryFailure('MEMORY_SOURCE');
    const events = await readEvents(session.id, cursor.next, end);
    const route = session.snapshotEvents?.().slice(0, end).findLast(event => event.type === 'request/header')?.data.header.config ?? routeFor(session);
    if (!events.some(userEvent) || !route?.provider || !route?.model) return;
    await store.change(next => {
      const current = next.cursors[session.id];
      if (!automatic() || next.epoch !== snapshot.epoch || current?.epoch !== next.epoch) return false;
      const jobs = next.jobs.filter(job => job.session === session.id && job.epoch === next.epoch);
      const last = jobs.at(-1);
      // 来源边界封口：旧范围不会再累积同路由内容，必须先处理才能连续推进。
      if (last?.state === 'waiting' && (last.route.provider !== route.provider || last.route.model !== route.model || last.workspace !== current.workspace)) {
        last.ready = true; last.urgent = true; last.notBefore = 0;
      }
      const merge = last && last.id !== run?.job.id && last.state === 'waiting' && last.to <= end &&
        last.workspace === current.workspace && last.route.provider === route.provider && last.route.model === route.model;
      const from = merge ? last.from : Math.max(current.next, ...jobs.map(job => job.to));
      if (from >= end || !events.some(event => event.seq >= from && userEvent(event))) return false;
      const id = `${next.epoch}:${session.id}:${from}:${end}`;
      const duplicate = next.jobs.find(job => job.id === id);
      if (duplicate) {
        if (!force || duplicate.state !== 'waiting') return false;
        duplicate.ready = true; duplicate.urgent = true; duplicate.notBefore = 0;
        return;
      }
      const own = events.filter(event => event.seq >= from);
      const selected = selectEvents(own, { scope: { kind: 'workspace', workspace: current.workspace } });
      const users = selected.messages.filter(row => row.role === 'user');
      const urgent = force || selected.next !== undefined || users.some(row => memoryRequest(row.text));
      const userIds = new Set(users.map(row => row.id));
      let turns = 0, hasUser = false;
      for (const event of own) {
        if (userIds.has(event.seq)) hasUser = true;
        if (event.type === 'turn/end' && hasUser) { turns++; hasUser = false; }
      }
      const ready = urgent || turns >= schedule.turns || users.reduce((sum, row) => sum + Buffer.byteLength(row.text), 0) >= schedule.bytes;
      const notBefore = urgent ? 0 : (current.lastRun ?? 0) + schedule.cooldown;
      const job = { id, session: session.id, epoch: next.epoch, from, to: end, workspace: current.workspace,
        route: { provider: route.provider, model: route.model }, state: 'waiting', attempts: 0,
        ready: Boolean(ready || merge && last.ready !== false), urgent: Boolean(urgent || merge && last.urgent !== false && last.ready !== false),
        notBefore: merge ? Math.min(last.notBefore ?? notBefore, notBefore) : notBefore };
      if (merge) next.jobs[next.jobs.indexOf(last)] = job;
      else next.jobs.push(job);
    });
    if (wake && !resume) drain();
  }
  async function recover() {
    if (!store.read().settings.enabled || !store.read().settings.autoSave) return;
    // Only ids already registered in this epoch are read; no whole-library scan or model send.
    for (const [id, cursor] of Object.entries(store.read().cursors)) {
      if (cursor.epoch !== store.read().epoch) continue;
      try {
        const persistence = ctx.sessionPersistence ?? ctx.get?.('sessionPersistence');
        if (!persistence) continue;
        const handle = await persistence.open(id, 'read');
        let result;
        try { result = await handle.read(); } finally { await handle.close(); }
        const events = result.events;
        let route;
        for (const event of events) {
          if (event.type === 'request/header') route = event.data.header.config;
          if (event.seq < cursor.next) continue;
          if (event.type === 'working-directory/change') {
            await store.change(next => {
              const current = next.cursors[id];
              if (current?.epoch !== next.epoch) return false;
              next.cursors[id] = { epoch: next.epoch, start: event.seq + 1, next: event.seq + 1, workspace: event.data.cwd };
            });
          }
          if (event.type !== 'turn/end' || !route) continue;
          const current = store.read().cursors[id];
          const session = { id, seq: event.seq + 1, header: { cwd: current.workspace }, requestHeader: () => ({ config: route }) };
          await enqueue(session, event.seq + 1, true);
        }
      } catch {
        await store.change(next => {
          for (const job of next.jobs) if (job.session === id) { job.state = 'failed'; job.message = memoryError({ code: 'MEMORY_SOURCE' }); }
        });
      }
    }
  }
  function drain(id) {
    if (run || !automatic() || !wake) return background;
    clearTimeout(timer);
    const available = providers();
    const snapshot = store.read();
    const waiting = snapshot.jobs.filter(job => job.epoch === snapshot.epoch && job.attempts < 2 && available.has(job.route.provider) &&
      !snapshot.jobs.some(previous => previous.session === job.session && previous.epoch === job.epoch && previous.from < job.from));
    const job = waiting.find(job => id ? job.id === id : job.state === 'waiting' && job.ready !== false && dueAt(job, snapshot) <= now());
    if (!job) {
      const future = waiting.filter(job => job.state === 'waiting' && job.ready !== false && dueAt(job, snapshot) > now());
      if (future.length) { timer = setTimeout(() => drain(), Math.min(2147483647, Math.max(1, Math.min(...future.map(job => dueAt(job, snapshot))) - now()))); timer.unref?.(); }
      return background;
    }
    const controller = new AbortController();
    run = { job, controller };
    let skipped = [], selection;
    background = (async () => {
      try {
        await store.change(next => {
          const current = next.jobs.find(row => row.id === job.id);
          if (!current || !automatic() || next.epoch !== job.epoch) throw memoryFailure('MEMORY_STOPPED');
          current.attempts++; current.message = '整理中';
        });
        const events = await readEvents(job.session, job.from, job.to, controller.signal);
        const snapshot = store.read();
        const candidates = await (options.extract ?? extract)(ctx, { session: job.session, events, ...job.route,
          signal: controller.signal, entries: snapshot.entries.filter(row => accessible(row, job.workspace)), scope: { kind: 'workspace', workspace: job.workspace },
          onSelected: value => { selection = value; }, onSkipped: rows => { skipped = rows.slice(0, 32); } });
        controller.signal.throwIfAborted();
        const end = selection?.next ?? job.to;
        if (!Number.isSafeInteger(end) || end <= job.from || end > job.to) throw memoryFailure('MEMORY_CAPACITY');
        await store.change(next => {
          if (closed || barrier || controller.signal.aborted || !next.settings.enabled || !next.settings.autoSave || next.epoch !== job.epoch) return false;
          const current = next.jobs.find(row => row.id === job.id);
          if (!current) return false;
          for (const candidate of candidates) merge(next, candidate, job, events, snapshot);
          if (end < job.to) {
            current.from = end; current.attempts = 0; current.state = 'waiting'; current.ready = true; current.urgent = true; current.notBefore = 0; delete current.message;
          } else next.jobs = next.jobs.filter(row => row.id !== job.id);
          const cursor = next.cursors[job.session];
          if (cursor?.epoch === job.epoch && cursor.workspace === job.workspace) {
            // 前面仍有失败任务时不能越过它；后来完成的批次会保持独立登记。
            if (cursor.next >= job.from) cursor.next = Math.max(cursor.next, end);
            cursor.lastRun = now();
            cursor.skipped = skipped;
          }
          if (skipped.length) message = `有 ${skipped.length} 条消息因内容类型、凭据或容量限制未参与整理。`;
        });
      } catch (cause) {
        await store.change(next => {
          if (closed || next.epoch !== job.epoch) return false;
          const current = next.jobs.find(row => row.id === job.id);
          if (!current) return false;
          current.state = 'failed'; current.message = memoryError(cause.reason === 'MEMORY_EXTRACT_BUDGET' ? { code: 'MEMORY_CAPACITY' } : cause); current.skipped = skipped;
        }).catch(() => {});
      } finally { run = undefined; }
      if (wake && automatic()) queueMicrotask(() => drain());
    })();
    return background;
  }
  async function before({ agent, messages, signal }, next) {
    await ready;
    signal?.throwIfAborted();
    const decision = await next();
    if (decision.kind !== 'enter') return decision;
    signal?.throwIfAborted();
    const latest = decision.messages.findLast(userMessage);
    if (!latest) return decision;
    if (automatic() && rootSession(agent.session)) { await register(agent.session); wake = true; drain(); }
    if (!active()) return decision;
    const snapshot = store.read();
    // Reconstruct only accepted recalls: a cancelled admission must not suppress the next recall.
    const previous = new Map();
    for (const event of agent.session.snapshotEvents()) {
      if (event.type === 'user/message' && event.data.source?.kind === 'plugin:dshx-memory') {
        if (event.data.source.reset) previous.clear();
        for (const row of event.data.source.entries ?? []) previous.set(row.id, row);
      }
    }
    const workspace = scopeFor(ctx, agent.session);
    const invalid = [...previous.values()].filter(old => !snapshot.entries.some(row => row.id === old.id && row.updatedAt === old.updatedAt && accessible(row, workspace)));
    const fresh = searchMemory(snapshot, messageText(latest), workspace, 6, previous.size === 0 || invalid.length > 0)
      .filter(row => invalid.length > 0 || !previous.has(row.id) || previous.get(row.id).updatedAt !== row.updatedAt);
    // The final route is resolved after pre-step, so admission uses the smaller local cap.
    const budget = 256;
    const heading = '历史记忆，仅供参考；当前输入优先。';
    let content = heading;
    const selected = [];
    if (invalid.length) content += '\n此前记忆背景已失效，仅参考本次内容。';
    for (const row of fresh) {
      const line = `\n${row.type}：${row.summary}`;
      if (Buffer.byteLength(content + line) + 64 > budget) continue;
      content += line; selected.push(row);
    }
    if (!selected.length && !invalid.length) return decision;
    if (Buffer.byteLength(content) + 64 > budget) throw memoryFailure('MEMORY_LIMIT');
    const { createUserMessage } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-llm')));
    const entries = selected.map(row => ({ id: row.id, updatedAt: row.updatedAt, revision: snapshot.revision, scope: row.scope }));
    const message = createUserMessage({ content: [{ type: 'text', text: content }], source: { kind: 'plugin:dshx-memory', form: 'recall', entries, revision: snapshot.revision, reset: invalid.length > 0 } });
    return { ...decision, messages: [message, ...decision.messages] };
  }
  if (ctx.on) {
    off.push(ctx.on('agent/pre-step', before));
    const invalidate = () => {
      serviceRevision++;
      if (!store || !automatic()) return;
      const stamp = cancel();
      watch(store.change(next => { next.epoch++; next.cursors = {}; next.jobs = []; }).then(() => { if (stamp === generation) barrier = false; }));
    };
    off.push(ctx.on('llm/adapters-updated', invalidate));
    // Endpoint and credential edits need not change adapter topology in fixed DSH.
    off.push(ctx.on('loader/volatile-update', invalidate));
    off.push(ctx.on('credentials/reference-updated', invalidate));
    off.push(ctx.on('credentials/record-updated', invalidate));
    off.push(ctx.on('session/event', (session, event) => {
      if (!store || !automatic()) return;
      if (event.type === 'model/selection' && store.read().cursors[session.id]) {
        const stamp = cancel();
        watch(store.change(next => { next.epoch++; next.cursors = {}; next.jobs = []; }).then(() => { if (stamp === generation) barrier = false; }));
      }
      if (event.type === 'working-directory/change' && rootSession(session)) {
        watch(store.change(next => {
          if (!automatic() || !next.cursors[session.id]) return false;
          next.cursors[session.id] = { epoch: next.epoch, start: event.seq + 1, next: event.seq + 1, workspace: event.data.cwd };
        }));
      }
      if (event.type === 'turn/end' && rootSession(session)) watch(enqueue(session, event.seq + 1));
      // DSH 的持久化日志保留被压缩原文；开标记时固定范围，不阻塞主会话压缩。
      if (event.type === 'compaction/start' && rootSession(session)) watch(enqueue(session, event.seq, false, true));
    }));
  }
  return {
    ready, active, before,
    execute(tool, args, exec) {
      const signal = exec.signal ? AbortSignal.any([exec.signal, reads.signal]) : reads.signal;
      const work = (async () => {
        await ready;
        signal.throwIfAborted();
        if (!active() || !isMemoryTool(tool)) throw memoryFailure('MEMORY_NOT_READY');
        const sourceRevision = serviceRevision;
        let revision, returned;
        return budget.execute(tool, { ...exec, signal }, workspace => {
          revision = store.read().revision;
          returned = tool === 'memory_search' ? { entries: searchMemoryResult(args.query, workspace) } : readMemory(args.id, workspace);
          return returned;
        }, (result, maximum) => {
          const value = structuredClone(result);
          while (Buffer.byteLength(JSON.stringify(value)) > maximum) {
            if (tool !== 'memory_search' || value.entries.length < 2) throw memoryFailure('MEMORY_TOOL_LIMIT');
            value.entries.pop();
          }
          return value;
        }, workspace => {
          signal.throwIfAborted();
          if (!active()) throw memoryFailure('MEMORY_NOT_READY');
          if (serviceRevision !== sourceRevision) throw memoryFailure('MEMORY_VERSION_CHANGED');
          if (store.read().revision !== revision) throw memoryFailure('MEMORY_VERSION_CHANGED');
          const ids = tool === 'memory_search' ? returned.entries.map(row => row.id) : [returned.id];
          if (ids.some(id => !store.read().entries.some(row => row.id === id && accessible(row, workspace)))) throw memoryFailure('MEMORY_VERSION_CHANGED');
        });
      })();
      pending.add(work);
      work.then(() => pending.delete(work), () => pending.delete(work));
      return work;
    },
    async call(action, data = {}, signal) {
      await ready; signal?.throwIfAborted();
      if (closed) throw memoryFailure('MEMORY_STOPPED');
      if (action === 'status') return status();
      if (error || !store) throw memoryFailure('MEMORY_READ');
      if (action === 'list') return { entries: store.read().entries, revision: store.read().revision };
      if (action === 'read') {
        const snapshot = store.read(), entry = snapshot.entries.find(row => row.id === data.id);
        if (!entry) throw memoryFailure('MEMORY_MISSING');
        return { entry, revision: snapshot.revision };
      }
      if (['save', 'remove', 'clear', 'settings'].includes(action)) revision(data);
      if (action === 'settings') {
        if (typeof data.enabled !== 'boolean' || typeof data.autoSave !== 'boolean') throw memoryFailure('MEMORY_INPUT');
        // Raise the runtime barrier synchronously, before waiting for any write queue slot.
        const stamp = cancel();
        try {
          await store.change(next => { next.settings = { enabled: data.enabled, autoSave: data.autoSave }; next.epoch++; next.cursors = {}; next.jobs = []; }, data.revision, signal);
          if (stamp === generation) barrier = false;
        } catch (cause) { throw cause; }
      } else if (action === 'save') {
        const snapshot = await store.save(data, signal);
        const entry = snapshot.entries.find(row => row.state === 'active' && row.key === normalize(data.key) && sameScope(row.scope, data.scope));
        return { ...status(), entry };
      }
      else if (action === 'remove') await store.remove(data, signal);
      else if (action === 'clear') {
        const stamp = cancel();
        await store.change(next => { next.entries = []; next.jobs = []; next.cursors = {}; next.blocked = []; next.epoch++; }, data.revision, signal);
        if (stamp === generation) barrier = false;
      } else if (action === 'organize') {
        if (!automatic()) throw memoryFailure('MEMORY_NOT_READY');
        if (data.session !== undefined && (typeof data.session !== 'string' || !store.read().cursors[data.session])) throw memoryFailure('MEMORY_INPUT');
        await store.change(next => {
          for (const job of next.jobs) if (job.state === 'waiting' && (data.session === undefined || job.session === data.session)) {
            job.ready = true; job.urgent = true; job.notBefore = 0;
          }
        }, undefined, signal);
        wake = true; drain();
      } else if (action === 'retry') {
        if (!automatic()) throw memoryFailure('MEMORY_NOT_READY');
        const job = store.read().jobs.find(row => row.id === data.id);
        if (!job) throw memoryFailure('MEMORY_MISSING');
        if (!providers().has(job.route.provider)) throw memoryFailure('MEMORY_MODEL');
        if (job.attempts >= 2) throw memoryFailure('MEMORY_RETRY');
        await store.change(next => {
          const current = next.jobs.find(row => row.id === job.id);
          if (!automatic() || !current || current.attempts >= 2) throw memoryFailure('MEMORY_RETRY');
          current.ready = true; current.urgent = true; current.notBefore = 0;
        }, undefined, signal);
        wake = true; drain(job.id);
      } else if (action !== 'settings') throw memoryFailure('MEMORY_INPUT');
      return status();
    },
    search: searchMemoryResult,
    read: readMemory,
    view(workspace) {
      if (!active()) return { enabled: false, revision: store?.read().revision ?? 0, entries: [] };
      const snapshot = store.read();
      return { enabled: true, revision: snapshot.revision,
        entries: snapshot.entries.filter(row => accessible(row, workspace)).map(row => structuredClone(row)) };
    },
    async close() {
      closed = true; cancel(); reads.abort(memoryFailure('MEMORY_STOPPED'));
      for (const dispose of off) dispose?.();
      await ready; await Promise.allSettled([...pending, background]);
      await store?.close();
    },
  };
  function searchMemoryResult(query, workspace) {
    if (!active()) throw memoryFailure('MEMORY_NOT_READY');
    if (typeof query !== 'string' || !query.trim() || [...query].length > 2000) throw memoryFailure('MEMORY_INPUT');
    return searchMemory(store.read(), query, workspace).map(row => ({ id: row.id, summary: row.summary, scope: row.scope }));
  }
  function readMemory(id, workspace) {
    if (!active()) throw memoryFailure('MEMORY_NOT_READY');
    const row = store.read().entries.find(row => row.id === id && accessible(row, workspace));
    if (!row) throw memoryFailure('MEMORY_MISSING');
    return structuredClone(row);
  }
}
function merge(next, candidate, job, events, base) {
  const scope = candidate.scope === 'user' ? { kind: 'user' } : { kind: 'workspace', workspace: job.workspace };
  const key = normalize(candidate.key);
  const sources = events.filter(userEvent).map(row => row.seq);
  if (!candidate.events?.length || candidate.events.some(seq => !sources.includes(seq))) throw memoryFailure('MEMORY_EXTRACT');
  if (next.blocked.some(row => sameScope(row.scope, scope) && (
    row.sources.some(source => source.session === job.session && source.events.some(seq => candidate.events.includes(seq))) ||
    row.key === key && !candidate.explicit && row.epoch === job.epoch))) return;
  const old = next.entries.find(row => row.state === 'active' && sameScope(row.scope, scope) && row.key === key) ??
    next.entries.find(row => row.id === candidate.corrects && row.state === 'active' && sameScope(row.scope, scope));
  const now = Math.max(Date.now(), (old?.updatedAt ?? 0) + 1);
  const duplicate = next.entries.find(row => sameScope(row.scope, scope) && normalize(row.content) === normalize(candidate.content));
  if (duplicate) {
    const refs = sourceRows(duplicate.source);
    if (!refs.some(ref => ref.session === job.session && candidate.events.every(seq => ref.events.includes(seq)))) {
      if (refs.length >= 32) throw memoryFailure('MEMORY_LIMIT');
      duplicate.source = { ...duplicate.source, refs: [...(duplicate.source.refs ?? []), { session: job.session, events: candidate.events }] };
    }
    return;
  }
  if (candidate.action === 'ignore') return;
  const previous = old && base.entries.find(row => row.id === old.id);
  const changed = old && (!previous || previous.updatedAt !== old.updatedAt || previous.content !== old.content);
  const state = candidate.state === 'pending' || candidate.explicit || old && (old.locked || changed || candidate.corrects !== old.id) ? 'pending' : 'active';
  if (next.entries.some(row => sameScope(row.scope, scope) && row.key === key && normalize(row.content) === normalize(candidate.content))) return;
  if (old && state === 'active' && sourceRows(old.source).length >= 32) throw memoryFailure('MEMORY_LIMIT');
  const entry = { id: old && state === 'active' ? old.id : randomUUID(), type: candidate.type, scope, key,
    summary: candidate.summary, content: candidate.content, source: { kind: 'auto', session: job.session, events: candidate.events, ...(old && state === 'active' ? { refs: sourceRows(old.source) } : {}) }, state, locked: false,
    createdAt: old && state === 'active' ? old.createdAt : now, updatedAt: now, expiresAt: null };
  next.entries = next.entries.filter(row => row.id !== entry.id); next.entries.push(entry);
}
export async function apply(ctx) {
  const memory = memorySession(ctx);
  owner = memory;
  policyHost.memory = memory.call;
  policyHost.stopMemory = () => { void memory.close(); };
  ctx.effect(() => async () => {
    await memory.close();
    if (owner === memory) owner = undefined;
    if (policyHost.memory === memory.call) policyHost.memory = undefined;
  });
  const { defineTool } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-tools')));
  for (const tool of [
    { name: 'memory_search', description: '查找当前用户及当前工作区的有效历史记忆，只返回摘要。不会保存记忆。', parameters: { query: { type: 'string', required: true, description: '要查找的内容。' } },
      execute: (args, exec) => memory.execute('memory_search', args, exec) },
    { name: 'memory_read', description: '读取一条当前仍可访问的历史记忆，不会修改记忆。', parameters: { id: { type: 'string', required: true, description: 'memory_search返回的记忆id。' } },
      execute: (args, exec) => memory.execute('memory_read', args, exec) },
  ]) ctx.tools.register(defineTool({ ...tool, isConcurrencySafe: () => true,
    output: { schema: { type: 'object', additionalProperties: true }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] } }));
  await memory.ready;
}
