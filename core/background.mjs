const queues = new WeakMap();
const busy = ctx => ctx.agents?.list().some(agent => agent.status !== 'idle' || agent.inbox?.hasPending);

function queueFor(ctx) {
  const root = ctx.root ?? ctx;
  if (queues.has(root)) return queues.get(root);
  const state = { waiting: [], active: null };
  queues.set(root, state);
  state.pump = () => {
    if (state.active || busy(ctx)) return;
    const task = state.waiting.shift();
    if (!task) return;
    if (task.signal.aborted) { task.reject(task.signal.reason); state.pump(); return; }
    state.active = task;
    const cancelled = new Promise((_, reject) => task.signal.addEventListener('abort', () => reject(task.signal.reason), { once: true }));
    Promise.race([Promise.resolve().then(() => task.work(task.signal)), cancelled]).then(task.resolve, task.reject).finally(() => {
      state.active = null; state.pump();
    });
  };
  if (ctx.on) {
    ctx.on('agent/inbox/inserted', ({ message }) => {
      if (message.source?.kind === 'user') interruptBackground(ctx);
    });
    ctx.on('session/event', (_session, event) => {
      if (event.type === 'turn/end') setTimeout(state.pump, 0).unref?.();
    });
  }
  return state;
}

export function interruptBackground(ctx) {
  const state = queues.get(ctx.root ?? ctx);
  state?.active?.controller.abort(Object.assign(new Error('后台整理已让位前台请求。'), { code: 'BACKGROUND_PREEMPTED' }));
}

/** 仅协调后台模型请求；业务队列及持久提交仍由调用方管理。 */
export function runBackground(ctx, signal, work) {
  const state = queueFor(ctx), controller = new AbortController();
  const combined = signal ? AbortSignal.any([signal, controller.signal]) : controller.signal;
  return new Promise((resolve, reject) => {
    const task = { controller, signal: combined, work, resolve, reject };
    if (combined.aborted) { reject(combined.reason); return; }
    combined.addEventListener('abort', () => {
      const index = state.waiting.indexOf(task);
      if (index >= 0) { state.waiting.splice(index, 1); reject(combined.reason); }
    }, { once: true });
    state.waiting.push(task); state.pump();
  });
}
