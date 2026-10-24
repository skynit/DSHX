import { createConnection } from 'node:net';

// One authenticated connection to the native instance. Losing it
// clears every cached policy; reconnecting requires a new engine instance.
export class PolicyHost {
  constructor({ port, instance, token } = {}) {
    this.configured = [port, instance, token].some(value => value !== undefined);
    this.config = { port: Number(port), instance, token };
    this.sessions = new Map();
    this.pending = new Map();
    this.memoryCalls = new Map();
    this.knowledgeCalls = new Map();
    this.sequence = 0;
    this.revision = -1;
  }

  connect() {
    if (!this.configured) return Promise.resolve();
    if (this.ready) return this.ready;
    this.ready = new Promise((resolve, reject) => {
      this.resolveReady = resolve;
      this.rejectReady = reject;
    });
    const { port, instance, token } = this.config;
    if (!Number.isInteger(port) || port < 1 || port > 65535 || !instance || !token) {
      this.fail();
      return this.ready;
    }
    this.timer = setTimeout(() => this.fail(), 3000);
    this.socket = createConnection({ host: '127.0.0.1', port });
    this.socket.setNoDelay(true);
    this.socket.on('connect', () => this.send({ version: 1, instance, token }));
    let buffer = '';
    this.socket.setEncoding('utf8');
    this.socket.on('data', chunk => {
      buffer += chunk;
      if (Buffer.byteLength(buffer) > 4 * 1024 * 1024) return this.fail();
      let newline;
      while ((newline = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, newline);
        buffer = buffer.slice(newline + 1);
        try { this.receive(JSON.parse(line)); }
        catch { this.fail(); return; }
      }
    });
    this.socket.on('error', () => this.fail());
    this.socket.on('close', () => this.fail());
    return this.ready;
  }

  receive(message) {
    if (this.failure) return;
    if (typeof message.knowledge === 'string') {
      if (!this.connected || !/^[a-zA-Z0-9-]{1,80}$/.test(message.knowledge) ||
          !['status', 'settings', 'list', 'read', 'search', 'collection', 'ticket', 'import', 'update', 'remove', 'rebuild', 'cancel', 'retry', 'export', 'restore'].includes(message.action) ||
          !message.data || typeof message.data !== 'object' || Array.isArray(message.data) ||
          Object.keys(message).some(key => !['knowledge', 'action', 'data'].includes(key)) ||
          Buffer.byteLength(JSON.stringify(message)) > 256 * 1024) throw new Error('原生知识请求无效。');
      if (this.knowledgeCalls.has(message.knowledge) || this.knowledgeCalls.size >= 32) throw new Error('原生知识请求过多或重复。');
      const controller = new AbortController();
      this.knowledgeCalls.set(message.knowledge, controller);
      Promise.resolve().then(() => {
        controller.signal.throwIfAborted();
        if (!this.knowledge) throw Object.assign(new Error(), { code: 'KNOWLEDGE_NOT_READY' });
        return this.knowledge(message.action, message.data, controller.signal);
      }).then(result => {
        if (!result || typeof result !== 'object' || Array.isArray(result)) throw Object.assign(new Error(), { code: 'KNOWLEDGE_INPUT' });
        const frame = { knowledge: message.knowledge, ok: true, result };
        if (Buffer.byteLength(JSON.stringify(frame)) > 256 * 1024) throw Object.assign(new Error(), { code: 'KNOWLEDGE_LIMIT' });
        if (!controller.signal.aborted) this.send(frame);
      }).catch(async error => {
        const { knowledgeError } = await import('./knowledge.mjs');
        const code = /^KNOWLEDGE_[A-Z_]+$/.test(error?.code) ? error.code : 'KNOWLEDGE_NOT_READY';
        if (!controller.signal.aborted) this.send({ knowledge: message.knowledge, ok: false, error: knowledgeError({ code }), code });
      }).finally(() => this.knowledgeCalls.delete(message.knowledge));
      return;
    }
    if (typeof message.memory === 'string') {
      if (!this.connected || !/^[a-zA-Z0-9-]{1,80}$/.test(message.memory) ||
          !['status', 'settings', 'list', 'read', 'save', 'remove', 'clear', 'retry', 'organize', 'cancel'].includes(message.action) ||
          Object.keys(message).some(key => !['memory', 'action', 'data'].includes(key))) {
        throw new Error('原生记忆请求无效。');
      }
      if (message.action === 'cancel') {
        const id = message.data?.id;
        if (typeof id !== 'string' || !/^[a-zA-Z0-9-]{1,80}$/.test(id)) throw new Error('原生记忆取消请求无效。');
        this.memoryCalls.get(id)?.abort();
        return;
      }
      if (this.memoryCalls.has(message.memory) || this.memoryCalls.size >= 32) throw new Error('原生记忆请求过多或重复。');
      const controller = new AbortController();
      this.memoryCalls.set(message.memory, controller);
      Promise.resolve().then(async () => {
        controller.signal.throwIfAborted();
        if (!this.memory) throw Object.assign(new Error(), { code: 'MEMORY_NOT_READY' });
        return this.memory(message.action, message.data ?? {}, controller.signal);
      }).then(result => {
        if (!controller.signal.aborted) this.send({ memory: message.memory, ok: true, result });
      }, async error => {
        const { memoryError } = await import('./memory.mjs');
        if (!controller.signal.aborted) this.send({ memory: message.memory, ok: false, error: memoryError(error) });
      }).finally(() => this.memoryCalls.delete(message.memory));
      return;
    }
    if (typeof message.voice === 'string') {
      if (!this.connected || !/^[a-zA-Z0-9-]{1,80}$/.test(message.voice) || typeof message.action !== 'string') {
        throw new Error('原生语音请求无效。');
      }
      Promise.resolve().then(() => {
        if (!this.voice) throw Object.assign(new Error('语音会话尚未就绪。'), { code: 'VOICE_NOT_READY' });
        return this.voice(message.action, message.data ?? {});
      }).then(result => this.send({ voice: message.voice, ok: true, result }),
        error => {
          // Keep diagnostic frames, never provider error text or request/response bodies.
          console.warn('Android voice request failed:', error?.name ?? 'Error',
            typeof error?.code === 'string' && /^[A-Za-z0-9/_-]{1,80}$/.test(error.code) ? error.code : '',
            typeof error?.stack === 'string' ? error.stack.split('\n').slice(1, 5).join('\n') : '');
          const detail = error?.code === 'VOICE_NOT_READY' ? '语音会话尚未就绪，请稍后重试。'
            : error?.code === 'LOCAL_MEMORY_CONTEXT' ? '当前任务所需的记忆背景与上下文无法完整交给本地模型，不会自动转用云端模型。'
            : error?.code === 'VOICE_MEMORY' ? '当前可用内存不足，本地模型暂不加载。请关闭其他占用内存的应用后重试。'
            : '语音会话请求失败，请检查模型配置或重新开始。';
          this.send({ voice: message.voice, ok: false, error: detail });
        });
      return;
    }
    if (!Number.isSafeInteger(message.id) || message.id < 0 || typeof message.ok !== 'boolean') {
      throw new Error('原生查询响应无效。');
    }
    const pending = this.pending.get(message.id);
    if (message.id === 0 ? this.connected : !this.connected || !pending) {
      throw new Error('原生查询响应不匹配。');
    }
    if (!message.ok) {
      if (!pending) throw new Error('原生握手被拒绝。');
      clearTimeout(pending.timer);
      pending.cleanup?.();
      this.pending.delete(message.id);
      pending.reject(new Error(pending.phone || pending.local ? message.error || '本机处理失败，请重新开始。' : '原生会话查询被拒绝，会话可能已结束。'));
      return;
    }
    const state = message.state;
    if (state?.instance !== this.config.instance || state.connected !== true ||
        !Number.isSafeInteger(state.revision) || state.revision < this.revision || !Array.isArray(state.sessions) || state.sessions.length > 64 ||
        new Set(state.sessions.map(row => row?.id)).size !== state.sessions.length ||
        state.sessions.some(row => typeof row.id !== 'string' || !['read-only', 'workspace-write', 'full'].includes(row.mode))) {
      throw new Error('原生工具权限响应无效。');
    }
    if (state.revision >= this.revision) {
      this.revision = state.revision;
      this.sessions = new Map(state.sessions.map(row => [row.id, row.mode]));
    }
    if (message.id === 0) {
      this.connected = true;
      clearTimeout(this.timer);
      this.resolveReady();
    } else {
      clearTimeout(pending.timer);
      pending.cleanup?.();
      this.pending.delete(message.id);
      pending.resolve(message.result);
    }
  }

  async query(method, data, signal) {
    signal?.throwIfAborted();
    await this.connect();
    signal?.throwIfAborted();
    if (this.failure || !this.connected) throw this.failure ?? new Error('原生工具权限连接不可用。');
    const id = ++this.sequence;
    return new Promise((resolve, reject) => {
      const phone = method === 'phone';
      const local = method === 'local-model';
      const timer = setTimeout(() => this.fail(), local ? 170000 : phone ? (data.action === 'ask' ? 65000 : 25000) : 3000);
      const abort = () => {
        reject(new Error(local ? '本地模型生成已取消。' : '手机操作已取消；已发出的动作结果需重新观察，不能自动重试。' +
          (data.action === 'export' ? '导出可能已开始，授权目录可能残留不完整文件，请检查。' : '')));
        this.query(local ? 'local-cancel' : 'phone-cancel', { session: data.session, call: id }).catch(() => {});
      };
      this.pending.set(id, { resolve, reject, timer, phone, local, cleanup: () => signal?.removeEventListener('abort', abort) });
      this.send({ id, method, ...data });
      signal?.addEventListener('abort', abort, { once: true });
      if (signal?.aborted) abort();
    });
  }

  send(value) {
    if (!this.failure) this.socket.write(JSON.stringify(value) + '\n', error => { if (error) this.fail(); });
  }

  fail() {
    if (this.failure) return;
    this.failure = new Error('原生工具权限连接中断，当前调用已拒绝；请停止并重新启动工作区。');
    this.connected = false;
    for (const controller of this.memoryCalls.values()) controller.abort();
    this.memoryCalls.clear();
    this.stopMemory?.();
    for (const controller of this.knowledgeCalls.values()) controller.abort();
    this.knowledgeCalls.clear();
    this.stopKnowledge?.();
    this.sessions.clear();
    clearTimeout(this.timer);
    this.rejectReady?.(this.failure);
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.cleanup?.(); pending.reject(this.failure); }
    this.pending.clear();
    this.socket?.destroy();
  }

  cachedMode(session) {
    if (!this.configured) return 'workspace-write';
    return !this.failure && this.connected ? this.sessions.get(session) ?? 'unavailable' : 'unavailable';
  }

  async register(session, title, parent = null) {
    if (this.configured) await this.query('register', { session, title, parent });
    return this.cachedMode(session);
  }

  async mode(session) {
    if (this.configured && (typeof session !== 'string' || !session)) return 'unavailable';
    if (this.configured) await this.query('mode', { session });
    return this.cachedMode(session);
  }

  async end(session) {
    if (this.configured) await this.query('end', { session });
  }

  async finishPhone(session) {
    if (this.configured) await this.query('phone-finish', { session });
  }

  async phone(action, args, session, signal) {
    if (!this.configured || typeof session !== 'string' || !session) throw new Error('手机操作需要当前原生会话连接。');
    return this.query('phone', { action, args, session }, signal);
  }

  close() { this.fail(); }
}

export const policyHost = new PolicyHost({
  port: process.env.DSHX_POLICY_PORT,
  instance: process.env.DSHX_POLICY_INSTANCE,
  token: process.env.DSHX_POLICY_TOKEN,
});
