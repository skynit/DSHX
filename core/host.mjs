import { createConnection } from 'node:net';

// One authenticated, query-only connection to the native instance. Losing it
// clears every cached policy; reconnecting requires a new engine instance.
export class PolicyHost {
  constructor({ port, instance, token } = {}) {
    this.configured = [port, instance, token].some(value => value !== undefined);
    this.config = { port: Number(port), instance, token };
    this.sessions = new Map();
    this.pending = new Map();
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
      if (Buffer.byteLength(buffer) > 65536) return this.fail();
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
      this.pending.delete(message.id);
      pending.reject(new Error('原生会话查询被拒绝，会话可能已结束。'));
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
      this.pending.delete(message.id);
      pending.resolve();
    }
  }

  async query(method, data) {
    await this.connect();
    if (this.failure || !this.connected) throw this.failure ?? new Error('原生工具权限连接不可用。');
    const id = ++this.sequence;
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.fail(), 3000);
      this.pending.set(id, { resolve, reject, timer });
      this.send({ id, method, ...data });
    });
  }

  send(value) {
    if (!this.failure) this.socket.write(JSON.stringify(value) + '\n', error => { if (error) this.fail(); });
  }

  fail() {
    if (this.failure) return;
    this.failure = new Error('原生工具权限连接中断，当前调用已拒绝；请停止并重新启动工作区。');
    this.connected = false;
    this.sessions.clear();
    clearTimeout(this.timer);
    this.rejectReady?.(this.failure);
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(this.failure); }
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

  close() { this.fail(); }
}

export const policyHost = new PolicyHost({
  port: process.env.DSHX_POLICY_PORT,
  instance: process.env.DSHX_POLICY_INSTANCE,
  token: process.env.DSHX_POLICY_TOKEN,
});
