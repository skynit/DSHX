import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const require = createRequire(new URL('./lib/node_modules/package.json', import.meta.url));
export const limits = { entries: 500, jobs: 32, cursors: 256, blocked: 2000, bytes: 4 * 1024 * 1024 };
export const initialMemory = () => ({ version: 1, revision: 0, epoch: 0, settings: { enabled: false, autoSave: false }, entries: [], cursors: {}, jobs: [], blocked: [] });
export function memoryFailure(code) { return Object.assign(new Error(code), { code }); }
const validObject = value => value && typeof value === 'object' && !Array.isArray(value);
const integer = value => Number.isSafeInteger(value) && value >= 0;
export const normalize = text => text.normalize('NFKC').toLocaleLowerCase('en').replace(/\s+/gu, ' ').trim();
const size = value => [...value].length;
const text = (value, max) => typeof value === 'string' && value.trim().length > 0 && size(value) <= max && !value.includes('\0');
export function memoryScope(value) {
  if (!validObject(value) || !['user', 'workspace'].includes(value.kind) ||
      Object.keys(value).some(key => !['kind', 'workspace'].includes(key)) ||
      (value.kind === 'workspace' ? !text(value.workspace, 2048) || !value.workspace.startsWith('/') : value.workspace !== undefined)) throw memoryFailure('MEMORY_INPUT');
  return value.kind === 'user' ? { kind: 'user' } : { kind: 'workspace', workspace: value.workspace };
}
export const sameScope = (a, b) => a.kind === b.kind && a.workspace === b.workspace;
export const accessible = (entry, workspace, now = Date.now()) => entry.state === 'active' &&
  (entry.expiresAt === null || entry.expiresAt > now) && (entry.scope.kind === 'user' || entry.scope.workspace === workspace);
export function checkMemory(value) {
  if (!validObject(value) || value.version !== 1 || !integer(value.revision) || !integer(value.epoch) ||
      !validObject(value.settings) || typeof value.settings.enabled !== 'boolean' || typeof value.settings.autoSave !== 'boolean' ||
      !Array.isArray(value.entries) || !Array.isArray(value.jobs) || !Array.isArray(value.blocked) || !validObject(value.cursors)) throw memoryFailure('MEMORY_READ');
  if (value.entries.length > limits.entries || value.jobs.length > limits.jobs || value.blocked.length > limits.blocked ||
      Object.keys(value.cursors).length > limits.cursors || Buffer.byteLength(JSON.stringify({ unit: { name: 'memory', version: 1 }, global: value, tables: {} }, null, 2) + '\n') > limits.bytes) throw memoryFailure('MEMORY_LIMIT');
  const ids = new Set();
  const topics = new Set();
  for (const row of value.entries) {
    if (!validObject(row) || !text(row.id, 100) || ids.has(row.id) || !['preference', 'fact', 'experience'].includes(row.type) ||
        !text(row.key, 160) || row.key !== normalize(row.key) || !text(row.summary, 160) || !text(row.content, 2000) ||
        !['active', 'pending'].includes(row.state) || typeof row.locked !== 'boolean' || !integer(row.createdAt) || !integer(row.updatedAt) ||
        !(row.expiresAt === null || integer(row.expiresAt)) || !validObject(row.source) || !['manual', 'auto'].includes(row.source.kind)) throw memoryFailure('MEMORY_READ');
    memoryScope(row.scope); ids.add(row.id);
    if (row.state === 'active') {
      const topic = JSON.stringify([row.scope.kind, row.scope.workspace, row.key]);
      if (topics.has(topic)) throw memoryFailure('MEMORY_READ');
      topics.add(topic);
    }
    if (row.source.kind === 'auto' && (!text(row.source.session, 200) || !Array.isArray(row.source.events) || !row.source.events.length || !row.source.events.every(integer))) throw memoryFailure('MEMORY_READ');
    if (row.source.refs !== undefined && (!Array.isArray(row.source.refs) || row.source.refs.length > 32 ||
        !row.source.refs.every(ref => validObject(ref) && text(ref.session, 200) && Array.isArray(ref.events) &&
          ref.events.length <= 32 && ref.events.every(integer)))) throw memoryFailure('MEMORY_READ');
  }
  const jobIds = new Set();
  for (const job of value.jobs) {
    if (!validObject(job) || !text(job.id, 300) || jobIds.has(job.id) || !text(job.session, 200) || !integer(job.epoch) ||
        !integer(job.from) || !integer(job.to) || job.to < job.from || !integer(job.attempts) || job.attempts > 2 ||
        !['waiting', 'failed'].includes(job.state) || !text(job.workspace, 2048) || !validObject(job.route) ||
        !text(job.route.provider, 200) || !text(job.route.model, 200) ||
        job.ready !== undefined && typeof job.ready !== 'boolean' || job.urgent !== undefined && typeof job.urgent !== 'boolean' ||
        job.notBefore !== undefined && !integer(job.notBefore)) throw memoryFailure('MEMORY_READ');
    jobIds.add(job.id);
  }
  for (const [id, cursor] of Object.entries(value.cursors)) {
    if (!text(id, 200) || !validObject(cursor) || !integer(cursor.epoch) || !integer(cursor.start) || !integer(cursor.next) ||
        cursor.next < cursor.start || !text(cursor.workspace, 2048) || cursor.lastRun !== undefined && !integer(cursor.lastRun)) throw memoryFailure('MEMORY_READ');
  }
  for (const row of value.blocked) {
    if (!validObject(row) || !text(row.key, 160) || !integer(row.at) || !integer(row.epoch) || !Array.isArray(row.sources) ||
        !row.sources.every(source => text(source.session, 200) && Array.isArray(source.events) && source.events.every(integer))) throw memoryFailure('MEMORY_READ');
    memoryScope(row.scope);
  }
  return value;
}
export async function openMemory(ctx) {
  const { z } = await import(pathToFileURL(require.resolve('zod')));
  const { defineDomain } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-storage-domain')));
  const schema = z.custom(value => { try { checkMemory(value); return true; } catch { return false; } });
  const domain = await ctx.storageDomain.open(defineDomain({ name: 'memory', version: 1, layout: 'single', tables: {},
    global: { schema, initial: initialMemory() } }));
  return new MemoryStore(domain);
}

// The read and transform happen INSIDE this queue, before the one durable publication.
export class MemoryStore {
  constructor(domain) { this.domain = domain; this.queue = Promise.resolve(); this.closed = false; }
  read() { return structuredClone(checkMemory(this.domain.global.get())); }
  change(update, revision, signal) {
    if (this.closed) return Promise.reject(memoryFailure('MEMORY_STOPPED'));
    const result = this.queue.then(async () => {
      signal?.throwIfAborted();
      const next = this.read();
      if (revision !== undefined && (!integer(revision) || next.revision !== revision)) throw memoryFailure('MEMORY_CONFLICT');
      const outcome = update(next);
      if (outcome === false) return this.read();
      next.revision++;
      checkMemory(next);
      try { await this.domain.global.set(next); }
      catch { throw memoryFailure('MEMORY_WRITE'); }
      return structuredClone(next);
    });
    this.queue = result.catch(() => {});
    return result;
  }
  async close() { this.closed = true; await this.queue; await this.domain.close(); }
  save(data, signal) {
    return this.change(next => {
      const old = data.id === undefined ? undefined : next.entries.find(row => row.id === data.id);
      if (data.id !== undefined && !old) throw memoryFailure('MEMORY_MISSING');
      const scope = memoryScope(data.scope);
      if (!['preference', 'fact', 'experience'].includes(data.type) || !text(data.key, 160) || !text(data.summary, 160) ||
          !text(data.content, 2000) || !(data.expiresAt === undefined || data.expiresAt === null || integer(data.expiresAt))) throw memoryFailure('MEMORY_INPUT');
      const key = normalize(data.key);
      const duplicate = next.entries.find(row => row.id !== old?.id && row.state === 'active' && row.key === key && sameScope(row.scope, scope));
      if (old?.state === 'active' && duplicate) throw memoryFailure('MEMORY_CONFLICT');
      const previous = (old ?? duplicate)?.source;
      const source = previous?.kind === 'auto' ? { ...previous, confirmed: true } : { ...previous, kind: 'manual' };
      if (old?.state === 'pending' && duplicate) {
        const refs = [...(source.refs ?? [])];
        const primary = source.kind === 'auto' ? { session: source.session, events: source.events } : undefined;
        const same = (a, b) => a?.session === b.session && a.events.length === b.events.length && a.events.every(seq => b.events.includes(seq));
        for (const ref of [...(duplicate.source.kind === 'auto' ? [{ session: duplicate.source.session, events: duplicate.source.events }] : []), ...(duplicate.source.refs ?? [])]) {
          if (same(primary, ref) || refs.some(row => same(row, ref))) continue;
          if (refs.length >= 32) throw memoryFailure('MEMORY_LIMIT');
          refs.push(ref);
        }
        if (refs.length) source.refs = refs;
      }
      const now = Math.max(Date.now(), (old?.updatedAt ?? duplicate?.updatedAt ?? 0) + 1);
      const entry = { id: old?.id ?? duplicate?.id ?? randomUUID(), type: data.type, scope, key, summary: data.summary.trim(), content: data.content.trim(),
        source, state: 'active', locked: true,
        createdAt: old?.createdAt ?? duplicate?.createdAt ?? now, updatedAt: now, expiresAt: data.expiresAt ?? null };
      next.entries = next.entries.filter(row => row.id !== entry.id && !(old?.state === 'pending' && duplicate?.id === row.id) && row.id !== old?.id);
      next.entries.push(entry);
      next.blocked = next.blocked.filter(row => row.key !== key || !sameScope(row.scope, scope));
    }, data.revision, signal);
  }
  remove(data, signal) {
    return this.change(next => {
      const row = next.entries.find(row => row.id === data.id);
      if (!row) throw memoryFailure('MEMORY_MISSING');
      next.entries = next.entries.filter(entry => entry.id !== data.id);
      next.blocked.push({ key: row.key, scope: row.scope, at: Date.now(), epoch: next.epoch,
        sources: [...(row.source.kind === 'auto' ? [{ session: row.source.session, events: row.source.events }] : []), ...(row.source.refs ?? [])] });
    }, data.revision, signal);
  }
}
function terms(text) {
  const result = new Set(normalize(text).match(/[\p{L}\p{N}]+/gu) ?? []);
  for (const run of normalize(text).match(/[\p{Script=Han}]+/gu) ?? []) {
    const chars = [...run];
    for (let i = 0; i + 1 < chars.length; i++) result.add(chars[i] + chars[i + 1]);
  }
  return [...result].filter(term => /[^\p{Script=Han}]/u.test(term) || size(term) >= 2);
}
export function searchMemory(snapshot, query, workspace, limit = 10, preferences = false) {
  const phrase = normalize(query);
  const tokens = terms(query);
  return snapshot.entries.filter(row => accessible(row, workspace)).map(row => {
    const key = normalize(row.key + ' ' + row.summary), body = normalize(row.content);
    let score = phrase.length > 1 && (key.includes(phrase) || body.includes(phrase)) ? 100 : 0;
    for (const term of tokens) {
      const matches = field => /^[a-z0-9_]+$/i.test(term) ? field.split(/[^\p{L}\p{N}_]+/u).includes(term) : field.includes(term);
      if (matches(key)) score += 8;
      if (matches(body)) score += 2;
    }
    if (preferences && row.type === 'preference' && row.locked && row.scope.kind === 'user') score += 1;
    return { row, score };
  }).filter(item => item.score > 0).sort((a, b) => b.score - a.score || Number(b.row.locked) - Number(a.row.locked) ||
    b.row.updatedAt - a.row.updatedAt || a.row.id.localeCompare(b.row.id)).slice(0, limit).map(item => structuredClone(item.row));
}
