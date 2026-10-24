import * as fs from 'node:fs/promises';
import { constants } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { homedir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { gzip, gunzip } from 'node:zlib';
import { promisify } from 'node:util';

const require = createRequire(new URL('./lib/node_modules/package.json', import.meta.url));
const compress = promisify(gzip), decompress = promisify(gunzip);
const day = 24 * 60 * 60 * 1000;
const activeStates = new Set(['waiting', 'running', 'waitingRetry']);
const finalStates = new Set(['succeeded', 'failed', 'cancelled']);
const idPattern = /^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/;
const hashPattern = /^[a-f0-9]{64}$/;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const integer = value => Number.isSafeInteger(value) && value >= 0;
const hash = value => createHash('sha256').update(value).digest('hex');
const clone = value => structuredClone(value);
const sameScope = (a, b) => a?.kind === b?.kind && a?.workspace === b?.workspace;
const sameVersion = (a, b) => a && b && a.id === b.id && a.version === b.version && a.revision === b.revision && sameScope(a.scope, b.scope);
const stamp = row => Math.max(Date.now(), (row?.updatedAt ?? 0) + 1);

export const limits = Object.freeze({
  sources: 100, collections: 100, depth: 3, sourceBytes: 2 * 1024 * 1024,
  totalBytes: 50 * 1024 * 1024, chunks: 5000, sourceChunks: 500,
  jobs: 512, operations: 2048, tickets: 128, catalogBytes: 8 * 1024 * 1024,
  archiveBytes: 256 * 1024 * 1024, archiveEntries: 2048, readChars: 3000,
  retryWindow: day, clockSkew: 5 * 60 * 1000,
});

export function knowledgeFailure(code) {
  const full = String(code).startsWith('KNOWLEDGE_') ? String(code) : `KNOWLEDGE_${code}`;
  return Object.assign(new Error(full), { code: full });
}
function fail(code) { throw knowledgeFailure(code); }
function checkId(value) { if (typeof value !== 'string' || !idPattern.test(value)) fail('INPUT'); return value; }
function string(value, max, empty = false) {
  if (typeof value !== 'string' || (!empty && !value.trim()) || [...value].length > max || value.includes('\0')) fail('INPUT');
  return value;
}
function fields(value, allowed) {
  if (!object(value) || Object.keys(value).some(key => !allowed.includes(key))) fail('INPUT');
}
function scope(value) {
  fields(value, ['kind', 'workspace']);
  if (value.kind === 'user' && value.workspace === undefined) return { kind: 'user' };
  if (value.kind !== 'workspace' || !isAbsolute(string(value.workspace, 2048))) fail('INPUT');
  return { kind: 'workspace', workspace: resolve(value.workspace) };
}
function workspacePaths(values = []) {
  if (!Array.isArray(values)) fail('INPUT');
  return values.map(value => typeof value === 'string' ? value : value?.workspace ?? value?.path ?? value?.cwd).filter(value => typeof value === 'string').map(value => resolve(value));
}
function allowedScope(value, workspaces) {
  const result = scope(value);
  if (result.kind === 'workspace' && !workspacePaths(workspaces).includes(result.workspace)) fail('INPUT');
  return result;
}
function format(value) {
  const result = value === 'md' ? 'markdown' : value === 'txt' || value === 'plain' ? 'text' : value;
  if (!['markdown', 'text'].includes(result)) fail('INPUT');
  return result;
}
function normalizedText(bytes) {
  if (!bytes.length || bytes.length > limits.sourceBytes) fail(bytes.length ? 'LIMIT' : 'INPUT');
  let result;
  try { result = new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch { fail('INPUT'); }
  result = result.replace(/^\uFEFF/u, '').replace(/\r\n?/gu, '\n');
  if (!result.trim() || result.includes('\0')) fail('INPUT');
  return result;
}
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (object(value)) return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
function noSecrets(value) {
  if (!object(value) && !Array.isArray(value)) return;
  for (const [key, child] of Object.entries(value)) {
    if (/^(api[-_]?key|secret|password|authorization|access[-_]?token|refresh[-_]?token|credentials?)$/iu.test(key)) fail('INPUT');
    noSecrets(child);
  }
}
function modelSettings(value, kind) {
  fields(value, ['enabled', 'configId', 'provider', 'model', 'dimensions', 'normalized', 'normalize', 'inputTemplateVersion', 'templateVersion', 'promptVersion', 'kinds', 'sourceKinds', 'providerId', 'modelId',
    'protocol', 'batchSize', 'maxInputTokens', 'maxRequestBytes', 'queryPrefix', 'documentPrefix', 'sendDimensions', 'endpointHash', 'maxInputBytes',
    'active', 'generation', 'activeGeneration']);
  if (typeof value.enabled !== 'boolean') fail('INPUT');
  noSecrets(value);
  for (const key of ['configId', 'provider', 'model', 'providerId', 'modelId']) if (value[key] !== undefined) string(value[key], 256);
  for (const key of ['inputTemplateVersion', 'templateVersion', 'promptVersion']) if (value[key] !== undefined && (!integer(value[key]) || value[key] < 1)) fail('INPUT');
  if (value.dimensions !== undefined && (!integer(value.dimensions) || value.dimensions < 1 || value.dimensions > 8192)) fail('INPUT');
  if (value.normalized !== undefined && typeof value.normalized !== 'boolean') fail('INPUT');
  for (const key of ['normalize', 'sendDimensions']) if (value[key] !== undefined && typeof value[key] !== 'boolean') fail('INPUT');
  if (value.protocol !== undefined && !['openai', 'openai-embeddings'].includes(value.protocol)) fail('INPUT');
  for (const [key, max] of [['batchSize', 16], ['maxInputTokens', 131072], ['maxRequestBytes', 128 * 1024], ['maxInputBytes', 16000]]) {
    if (value[key] !== undefined && (!integer(value[key]) || value[key] < 1 || value[key] > max)) fail('INPUT');
  }
  for (const key of ['queryPrefix', 'documentPrefix']) if (value[key] !== undefined && (typeof value[key] !== 'string' || Buffer.byteLength(value[key]) > 1024)) fail('INPUT');
  if (value.endpointHash !== undefined && !hashPattern.test(value.endpointHash)) fail('INPUT');
  for (const key of ['generation', 'activeGeneration']) if (value[key] !== undefined) checkId(value[key]);
  if (value.active !== undefined) {
    fields(value.active, ['generation', 'config', 'embeddingConfigId', 'inputVersions']); checkId(value.active.generation);
    if (!hashPattern.test(value.active.embeddingConfigId) || !object(value.active.config) || !Array.isArray(value.active.inputVersions)) fail('INPUT');
    noSecrets(value.active);
  }
  for (const key of ['kinds', 'sourceKinds']) if (value[key] !== undefined && (!Array.isArray(value[key]) || !value[key].length || value[key].some(item => !['document', 'memory'].includes(item)))) fail('INPUT');
  if (value.enabled && !value.configId && (!value.provider || !value.model)) fail('MODEL');
  if (value.enabled && kind === 'embedding' && value.provider && value.dimensions === undefined) fail('INPUT');
  if (kind === 'summary' && value.dimensions !== undefined) fail('INPUT');
  return clone(value);
}

export const initialKnowledge = () => ({
  schemaVersion: 1, enabled: false, catalogRevision: 0, settingsRevision: 0,
  embedding: { enabled: false }, summary: { enabled: false },
  collections: [], sources: [], jobs: [], operations: [], tickets: [], clockAt: 0,
});

function collectionTree(rows) {
  const byId = new Map(rows.map(row => [row.id, row]));
  for (const row of rows) {
    let current = row, depth = 0;
    const visited = new Set();
    while (current) {
      if (visited.has(current.id) || ++depth > limits.depth) fail('INPUT');
      visited.add(current.id);
      if (!sameScope(current.scope, row.scope)) fail('INPUT');
      if (current.parentId === null) break;
      current = byId.get(current.parentId);
      if (!current) fail('INPUT');
    }
  }
}

export function checkKnowledge(value) {
  try {
    if (!object(value) || value.schemaVersion !== 1 || typeof value.enabled !== 'boolean' ||
        !integer(value.catalogRevision) || !integer(value.settingsRevision) ||
        !['collections', 'sources', 'jobs', 'operations'].every(key => Array.isArray(value[key]))) fail('READ');
    modelSettings(value.embedding, 'embedding'); modelSettings(value.summary, 'summary');
    if (value.collections.length > limits.collections || value.jobs.length > limits.jobs || value.operations.length > limits.operations ||
        (value.tickets?.length ?? 0) > limits.tickets || Buffer.byteLength(JSON.stringify({ unit: { name: 'knowledge', version: 1 }, global: value, tables: {} }, null, 2)) > limits.catalogBytes) fail('LIMIT');
    for (const key of ['collections', 'sources', 'jobs']) {
      const ids = new Set();
      for (const row of value[key]) {
        if (!object(row)) fail('READ');
        checkId(row.id);
        if (ids.has(row.id)) fail('READ');
        ids.add(row.id);
      }
    }
    for (const row of value.collections) {
      string(row.title, 200); scope(row.scope);
      if (!integer(row.revision) || row.revision < 1 || !(row.parentId === null || typeof row.parentId === 'string')) fail('READ');
    }
    collectionTree(value.collections);
    const collections = new Map(value.collections.map(row => [row.id, row]));
    for (const row of value.sources) {
      if (!['active', 'deleted', 'unavailable'].includes(row.state) || !integer(row.revision) || row.revision < 1 ||
          !integer(row.version) || row.version < 1 || !integer(row.bytes) || row.bytes > limits.sourceBytes ||
          !integer(row.chunkCount) || row.chunkCount > limits.sourceChunks || !hashPattern.test(row.hash) ||
          !integer(row.createdAt) || !integer(row.updatedAt)) fail('READ');
      string(row.title, 200); string(row.note ?? '', 2000, true); format(row.format); scope(row.scope);
      if (row.state !== 'deleted' && (!collections.has(row.collectionId) || !sameScope(row.scope, collections.get(row.collectionId).scope))) fail('READ');
    }
    const live = value.sources.filter(row => row.state !== 'deleted');
    if (live.length > limits.sources || live.reduce((sum, row) => sum + row.bytes, 0) > limits.totalBytes || live.reduce((sum, row) => sum + row.chunkCount, 0) > limits.chunks) fail('LIMIT');
    const operationIds = new Set();
    for (const operation of value.operations) {
      checkId(operation.operationId);
      if (operationIds.has(operation.operationId) || !integer(operation.createdAt) || !hashPattern.test(operation.requestHash) ||
          !['none', 'published', 'removed', 'switched', 'restored'].includes(operation.effect) || !object(operation.result)) fail('READ');
      operationIds.add(operation.operationId);
      if (operation.jobId !== null && operation.jobId !== undefined) checkId(operation.jobId);
    }
    for (const job of value.jobs) {
      checkId(job.operationId);
      if (!operationIds.has(job.operationId) || ![...activeStates, ...finalStates].includes(job.state) ||
          !['none', 'published', 'removed', 'switched', 'restored'].includes(job.effect) || !integer(job.attempts) || typeof job.cancelRequested !== 'boolean' ||
          !['prepare', 'publish', 'keyword', 'embedding', 'summary', 'cleanup', 'validate', 'switch', 'export', 'restore'].includes(job.stage)) fail('READ');
    }
    if (value.tickets !== undefined && !Array.isArray(value.tickets)) fail('READ');
    const tickets = new Set();
    for (const row of value.tickets ?? []) {
      checkId(row.ticket); checkId(row.operationId); string(row.instance, 200);
      if (tickets.has(row.ticket) || !integer(row.bytes) || row.bytes < 1 || row.bytes > limits.archiveBytes || !hashPattern.test(row.hash) || !integer(row.createdAt)) fail('READ');
      if (row.jobId !== null) checkId(row.jobId);
      tickets.add(row.ticket);
    }
    noSecrets(value);
    return value;
  } catch (cause) {
    if (cause.code === 'KNOWLEDGE_LIMIT') throw cause;
    throw knowledgeFailure('READ');
  }
}

// sourceDirectory 的参数必须来自正式目录；该函数只生成路径，不授予读取资格。
export function sourceDirectory(root, source) {
  if (object(root)) [source, root] = [root, source];
  checkId(source?.id);
  if (!integer(source.version) || source.version < 1 || typeof root !== 'string' || !isAbsolute(root)) fail('INPUT');
  return join(root, 'sources', source.id, String(source.version));
}

export async function openKnowledge(ctx, options = {}) {
  const { z } = await import(pathToFileURL(require.resolve('zod')));
  const { defineDomain } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-storage-domain')));
  const schema = z.custom(value => { try { checkKnowledge(value); return true; } catch { return false; } });
  const domain = await ctx.storageDomain.open(defineDomain({ name: 'knowledge', version: 1, layout: 'single', tables: {},
    global: { schema, initial: initialKnowledge() } }));
  const root = options.root ?? join(options.dshHome ?? process.env.DSH_HOME ?? join(process.env.HOME ?? homedir(), '.dsh'), 'storages', 'knowledge');
  const processor = options.process ?? (async (job, store) => {
    const { processKnowledgeJob } = await import('./knowledge-search.mjs');
    return processKnowledgeJob(ctx, store, job, { signal: job.signal });
  });
  const store = new KnowledgeStore(domain, root, { ...options, process: processor });
  try { await store.ready; } catch (cause) { await domain.close(); throw cause; }
  return store;
}

export class KnowledgeStore {
  constructor(domain, root, options = {}) {
    if (!domain?.global?.get || !domain?.global?.set || !isAbsolute(root)) fail('INPUT');
    this.domain = domain; this.root = resolve(root); this.options = options;
    this.queue = Promise.resolve(); this.running = new Map(); this.controllers = new Map();
    this.pins = new Map(); this.preparing = new Set(); this.closed = false; this.closing = false;
    this.ready = this.recover();
    this.ready.catch(() => {});
  }

  read() { return clone(checkKnowledge(this.domain.global.get())); }

  change(update, signal) {
    if (this.closed) return Promise.reject(knowledgeFailure('STOPPED'));
    const result = this.queue.then(async () => {
      signal?.throwIfAborted();
      const next = this.read(), outcome = update(next);
      if (outcome && typeof outcome.then === 'function') fail('INPUT');
      if (outcome === false) return this.read();
      checkKnowledge(next);
      signal?.throwIfAborted();
      try { await this.domain.global.set(next); } catch { fail('WRITE'); }
      return clone(next);
    });
    this.queue = result.catch(() => {});
    return result;
  }

  path(name) {
    if (typeof name !== 'string' || name.includes('\\') || name.split('/').some(part => !/^[A-Za-z0-9][A-Za-z0-9_.-]*$/u.test(part) || part === '..')) fail('INPUT');
    const result = resolve(this.root, name), rest = relative(this.root, result);
    if (!rest || rest.startsWith(`..${sep}`) || isAbsolute(rest)) fail('INPUT');
    return result;
  }

  async parents(name) {
    const target = this.path(name);
    let current = this.root;
    const parts = relative(this.root, dirname(target)).split(sep).filter(Boolean);
    for (const part of ['', ...parts]) {
      if (part) current = join(current, part);
      const info = await fs.lstat(current);
      if (!info.isDirectory() || info.isSymbolicLink()) fail('INPUT');
    }
    return target;
  }

  async directory(name) {
    const parts = name.split('/');
    for (let i = 1; i <= parts.length; i++) {
      const current = parts.slice(0, i).join('/'), target = this.path(current);
      await this.parents(current);
      try { await fs.mkdir(target, { mode: 0o700 }); } catch (cause) { if (cause.code !== 'EEXIST') throw cause; }
      const info = await fs.lstat(target);
      if (!info.isDirectory() || info.isSymbolicLink()) fail('INPUT');
    }
    return this.path(name);
  }

  async file(name, max) {
    const target = await this.parents(name);
    let handle;
    try {
      handle = await fs.open(target, constants.O_RDONLY | constants.O_NOFOLLOW);
      const info = await handle.stat();
      if (!info.isFile() || info.size > max) fail(info.size > max ? 'LIMIT' : 'INPUT');
      const pieces = []; let total = 0;
      while (true) {
        const buffer = Buffer.allocUnsafe(Math.min(64 * 1024, max - total + 1));
        const { bytesRead } = await handle.read(buffer, 0, buffer.length, null);
        if (!bytesRead) break;
        total += bytesRead;
        if (total > max) fail('LIMIT');
        pieces.push(buffer.subarray(0, bytesRead));
      }
      return Buffer.concat(pieces, total);
    } finally { await handle?.close(); }
  }

  async write(name, bytes) {
    const target = await this.parents(name);
    const handle = await fs.open(target, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try { await handle.writeFile(bytes); await handle.sync(); } finally { await handle.close(); }
  }

  async sync(name) {
    const target = name ? await this.parents(`${name}/sync`) : this.root;
    const handle = await fs.open(name ? dirname(target) : target, constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW);
    try { await handle.sync(); } catch (cause) { if (!['EINVAL', 'ENOTSUP'].includes(cause.code)) throw cause; } finally { await handle.close(); }
  }

  async space(bytes) {
    if (!fs.statfs) return;
    const info = await fs.statfs(this.root);
    if (Number(info.bavail) * Number(info.bsize) < bytes + 1024 * 1024) fail('LIMIT');
  }

  async removePath(name) {
    const target = await this.parents(name);
    // rm 本身不跟随末级链接；中间目录也必须保持在固定根内。
    await fs.rm(target, { recursive: true, force: true });
  }

  async recover() {
    this.read();
    await fs.mkdir(this.root, { recursive: true, mode: 0o700 });
    const info = await fs.lstat(this.root);
    if (!info.isDirectory() || info.isSymbolicLink() || await fs.realpath(this.root) !== this.root) fail('READ');
    for (const name of ['inbox', 'staging', 'sources', 'summaries', 'indexes', 'exports']) await this.directory(name);
    const bad = new Set(), missingChunks = new Set();
    for (const source of this.read().sources.filter(row => row.state === 'active')) {
      try {
        const prefix = this.versionPath(source);
        const original = await this.file(`${prefix}/original`, limits.sourceBytes);
        const text = await this.file(`${prefix}/text.txt`, limits.sourceBytes);
        if (hash(original) !== source.hash || source.bytes !== original.length || normalizedText(original) !== text.toString('utf8') || source.textHash && source.textHash !== hash(text)) fail('READ');
        try { await this.readChunks(source); } catch { missingChunks.add(source.id); }
      } catch { bad.add(source.id); }
    }
    await this.change(next => {
      let changed = false;
      next.tickets ??= []; next.clockAt ??= 0;
      for (const source of next.sources) {
        if (bad.has(source.id)) { source.state = 'unavailable'; source.errorCode = 'KNOWLEDGE_READ'; changed = true; }
        if (missingChunks.has(source.id)) { source.keywordState = 'missing'; changed = true; }
      }
      if (bad.size) next.catalogRevision++;
      for (const job of next.jobs) {
        if (job.type === 'remove' && job.effect === 'removed' && job.state !== 'succeeded') {
          job.state = 'waiting'; job.cancelRequested = false; job.errorCode = null; changed = true; continue;
        }
        if (!['waiting', 'running'].includes(job.state)) continue;
        job.state = job.type === 'remove' && job.effect === 'removed' ? 'waiting' : 'waitingRetry';
        job.errorCode = job.state === 'waitingRetry' ? 'KNOWLEDGE_NOT_READY' : null;
        changed = true;
      }
      return changed || !Array.isArray(this.domain.global.get().tickets) ? undefined : false;
    });
    await this.collect();
    await this.pruneFiles();
    for (const job of this.read().jobs) if (job.type === 'remove' && job.effect === 'removed' && job.state !== 'succeeded') this.launch(job.id);
  }

  publicSource(source) { const { note, ...row } = source; return clone(row); }
  page(rows, maxBytes, maxRows) {
    const result = []; let bytes = 2;
    for (const row of rows) {
      const length = Buffer.byteLength(JSON.stringify(row)) + 1;
      if (result.length >= maxRows || bytes + length > maxBytes) break;
      result.push(row); bytes += length;
    }
    return result;
  }
  status() {
    const snapshot = this.read();
    const tasks = [...snapshot.jobs].sort((a, b) => Number(activeStates.has(b.state)) - Number(activeStates.has(a.state)) || b.updatedAt - a.updatedAt).map(job => this.publicJob(job));
    const jobs = this.page(tasks, 48 * 1024, 64);
    const operations = this.page([...snapshot.operations].reverse().map(row => ({ operationId: row.operationId, createdAt: row.createdAt, action: row.action, jobId: row.jobId, effect: row.effect })), 12 * 1024, 32);
    const sources = this.page(snapshot.sources.filter(row => row.state !== 'deleted').map(row => this.publicSource(row)), 96 * 1024, limits.sources);
    const collections = this.page(snapshot.collections.map(({ summary, ...row }) => row), 48 * 1024, limits.collections);
    const { active, ...embedding } = snapshot.embedding;
    return { schemaVersion: 1, ready: !this.closed && !this.closing, enabled: snapshot.enabled,
      catalogRevision: snapshot.catalogRevision, settingsRevision: snapshot.settingsRevision,
      embedding, summary: snapshot.summary,
      collections, sources, jobs, operations,
      hasMore: { collections: collections.length < snapshot.collections.length, sources: sources.length < snapshot.sources.filter(row => row.state !== 'deleted').length,
        jobs: jobs.length < snapshot.jobs.length, operations: operations.length < snapshot.operations.length },
      counts: { collections: snapshot.collections.length, sources: snapshot.sources.filter(row => row.state === 'active').length,
        bytes: snapshot.sources.filter(row => row.state !== 'deleted').reduce((sum, row) => sum + row.bytes, 0),
        chunks: snapshot.sources.filter(row => row.state === 'active').reduce((sum, row) => sum + row.chunkCount, 0) },
      capabilities: { keyword: true, embedding: snapshot.enabled && snapshot.embedding.enabled && typeof this.options.process === 'function',
        summary: snapshot.enabled && snapshot.summary.enabled && typeof this.options.process === 'function' } };
  }

  versionPath(source) { sourceDirectory(this.root, source); return `sources/${source.id}/${source.version}`; }
  sourceDirectory(source) { return sourceDirectory(this.root, source); }
  publicJob(job) { if (!job) return null; const { input, ...result } = job; return clone(result); }

  current(source, snapshot = this.read()) {
    const row = snapshot.sources.find(item => item.id === source?.id);
    if (!row || row.state === 'deleted') fail('MISSING');
    if (row.state !== 'active') fail('READ');
    if (!sameVersion(row, source)) fail('VERSION_CHANGED');
    return row;
  }

  pin(sources) {
    for (const source of sources) {
      const key = `${source.id}/${source.version}`;
      this.pins.set(key, (this.pins.get(key) ?? 0) + 1);
    }
    return () => {
      for (const source of sources) {
        const key = `${source.id}/${source.version}`, count = this.pins.get(key) - 1;
        if (count) this.pins.set(key, count); else this.pins.delete(key);
      }
      if (!this.closing && !this.closed) for (const job of this.read().jobs) if (job.type === 'remove' && job.stage === 'cleanup' && job.state === 'waiting') this.launch(job.id);
    };
  }

  async sourceFile(source, name, max) {
    this.current(source);
    const release = this.pin([source]);
    try {
      const bytes = await this.file(`${this.versionPath(source)}/${name}`, max);
      this.current(source);
      return bytes;
    } finally { release(); }
  }

  async sourceOriginal(source) {
    const bytes = await this.sourceFile(source, 'original', limits.sourceBytes);
    if (bytes.length !== source.bytes || hash(bytes) !== source.hash) fail('READ');
    return bytes;
  }

  async sourceText(source) {
    const bytes = await this.sourceFile(source, 'text.txt', limits.sourceBytes);
    if (source.textHash && hash(bytes) !== source.textHash) fail('READ');
    try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch { fail('READ'); }
  }

  checkChunks(chunks, source, text) {
    if (!Array.isArray(chunks) || !chunks.length || chunks.length > limits.sourceChunks) fail('INDEX');
    const ids = new Set(), points = text === undefined ? null : [...text];
    for (const chunk of chunks) {
      if (!object(chunk) || typeof chunk.id !== 'string' || chunk.id.length > 200 || ids.has(chunk.id) || chunk.sourceId !== source.id ||
          (chunk.sourceVersion ?? chunk.version) !== source.version || !Array.isArray(chunk.heading) ||
          !integer(chunk.startOffset) || !integer(chunk.endOffset) || chunk.endOffset <= chunk.startOffset ||
          !integer(chunk.startLine) || chunk.startLine < 1 || !integer(chunk.endLine) || chunk.endLine < chunk.startLine ||
          typeof chunk.text !== 'string' || [...chunk.text].length > 800 || chunk.text.includes('\0')) fail('INDEX');
      if (points && (chunk.endOffset > points.length || points.slice(chunk.startOffset, chunk.endOffset).join('') !== chunk.text)) fail('INDEX');
      ids.add(chunk.id);
    }
    for (let i = 0; i < chunks.length; i++) if ((chunks[i].previous ?? null) !== (chunks[i - 1]?.id ?? null) || (chunks[i].next ?? null) !== (chunks[i + 1]?.id ?? null)) fail('INDEX');
    return chunks;
  }

  async readChunks(source) {
    const bytes = await this.file(`${this.versionPath(source)}/chunks.json`, limits.sourceBytes * 5);
    if (source.chunksHash && source.chunksHash !== hash(bytes)) fail('INDEX');
    let chunks; try { chunks = JSON.parse(bytes); } catch { fail('INDEX'); }
    this.checkChunks(chunks, source);
    if (chunks.length !== source.chunkCount) fail('INDEX');
    return chunks;
  }

  async sourceChunks(source) {
    this.current(source);
    const release = this.pin([source]);
    try {
      let chunks;
      try { chunks = await this.readChunks(source); }
      catch {
        const { chunks: rebuilt, chunkerVersion } = await this.chunk(await this.sourceText(source), source);
        if (source.chunkerVersion !== chunkerVersion || rebuilt.length !== source.chunkCount) fail('INDEX');
        chunks = rebuilt;
      }
      this.current(source);
      return chunks;
    } finally { release(); }
  }

  async chunk(text, source) {
    const module = await import('./knowledge-search.mjs');
    const result = await module.chunkText(text, { sourceId: source.id, version: source.version, format: source.format });
    const chunks = Array.isArray(result) ? result : result.chunks;
    const chunkerVersion = result.chunkerVersion ?? module.chunkerVersion ?? module.CHUNKER_VERSION ?? '1';
    this.checkChunks(chunks, source, text);
    return { chunks, chunkerVersion };
  }

  operation(next, action, data) {
    checkId(data.operationId);
    if (!integer(data.createdAt)) fail('INPUT');
    const now = Date.now();
    if (data.createdAt > now + limits.clockSkew || now - data.createdAt > day) fail('OPERATION_EXPIRED');
    if ((next.clockAt ?? 0) > now + limits.clockSkew) fail('NOT_READY');
    const requestHash = hash(JSON.stringify(canonical({ action, data })));
    const old = next.operations.find(row => row.operationId === data.operationId);
    if (old) {
      if (old.requestHash !== requestHash) fail('CONFLICT');
      return { operation: old, exists: true };
    }
    const retained = new Set(next.jobs.filter(row => activeStates.has(row.state) || row.type === 'remove' && row.effect === 'removed' && row.state !== 'succeeded').map(row => row.operationId));
    next.operations = next.operations.filter(row => now - row.createdAt <= day || retained.has(row.operationId));
    const operations = new Set(next.operations.map(row => row.operationId));
    next.jobs = next.jobs.filter(row => operations.has(row.operationId));
    const referenced = new Set(next.operations.map(row => row.result.source?.id).filter(Boolean));
    next.sources = next.sources.filter(row => row.state !== 'deleted' || row.cleanupState !== 'done' || now - row.updatedAt <= day || referenced.has(row.id));
    if (next.operations.length >= limits.operations) fail('LIMIT');
    next.clockAt = Math.max(now, next.clockAt ?? 0);
    const operation = { operationId: data.operationId, createdAt: data.createdAt, requestHash, action, jobId: null, effect: 'none', result: {} };
    next.operations.push(operation);
    return { operation, exists: false };
  }

  response(operation, snapshot = this.read()) {
    return { ...clone(operation.result), operation: clone(operation),
      ...(operation.jobId ? { job: this.publicJob(snapshot.jobs.find(row => row.id === operation.jobId)) } : {}),
      catalogRevision: snapshot.catalogRevision, settingsRevision: snapshot.settingsRevision };
  }

  async mutate(action, data, update, signal) {
    let id;
    const snapshot = await this.change(next => {
      const accepted = this.operation(next, action, data); id = accepted.operation.operationId;
      if (accepted.exists) return false;
      update(next, accepted.operation);
    }, signal);
    return this.response(snapshot.operations.find(row => row.operationId === id), snapshot);
  }

  revision(row, expected) {
    if (!integer(expected)) fail('INPUT');
    if (row.revision !== expected) fail('CONFLICT');
  }

  source(next, id) {
    checkId(id);
    const source = next.sources.find(row => row.id === id && row.state !== 'deleted');
    if (!source) fail('MISSING');
    return source;
  }

  collection(next, id, createDefault = false) {
    if (id === undefined && createDefault) {
      let root = next.collections.find(row => row.parentId === null && row.scope.kind === 'user');
      if (!root) {
        root = { id: randomUUID(), parentId: null, title: '通用资料', scope: { kind: 'user' }, revision: 1, summaryState: 'disabled', createdAt: Date.now(), updatedAt: Date.now() };
        next.collections.push(root); next.catalogRevision++;
      }
      return root;
    }
    checkId(id);
    const result = next.collections.find(row => row.id === id);
    if (!result) fail('MISSING');
    return result;
  }

  invalidate(next, ids) {
    const visited = new Set();
    for (let id of ids) while (id && !visited.has(id)) {
      visited.add(id);
      const row = next.collections.find(item => item.id === id);
      if (!row) break;
      row.summaryState = next.summary.enabled ? 'stale' : 'disabled';
      delete row.overviewRef;
      id = row.parentId;
    }
  }

  busy(next, sourceId, except) {
    if (next.jobs.some(job => job.id !== except && job.target === sourceId && activeStates.has(job.state) &&
        (job.type === 'update' || job.type === 'rebuild' && job.kind === 'chunks'))) fail('BUSY');
  }

  async ticket(data, instance, signal) {
    fields(data, ['ticket', 'operationId', 'createdAt', 'bytes', 'hash', 'format', 'title']);
    checkId(data.ticket); checkId(data.operationId); string(instance, 200);
    const archive = ['gzip', 'archive', 'dshx-knowledge', 'dshx-knowledge-gzip'].includes(data.format);
    const kind = archive ? 'archive' : format(data.format);
    if (!integer(data.bytes) || !data.bytes || data.bytes > (archive ? limits.archiveBytes : limits.sourceBytes) || !hashPattern.test(data.hash)) fail('INPUT');
    if (data.title !== undefined) string(data.title, 200);
    const known = this.read().tickets?.find(row => row.ticket === data.ticket);
    if (known) {
      if (known.operationId !== data.operationId || known.instance !== instance || known.hash !== data.hash || known.bytes !== data.bytes || known.format !== kind || known.title !== (data.title ?? '导入资料')) fail('CONFLICT');
      if (Date.now() - known.createdAt > day) fail('OPERATION_EXPIRED');
      return { ticket: known.ticket, bytes: known.bytes, hash: known.hash, format: known.format, title: known.title };
    }
    const bytes = await this.file(`inbox/${data.ticket}/input`, archive ? limits.archiveBytes : limits.sourceBytes);
    if (bytes.length !== data.bytes || hash(bytes) !== data.hash) fail('INPUT');
    if (!archive) normalizedText(bytes);
    let ticket;
    await this.change(next => {
      next.tickets ??= [];
      const old = next.tickets.find(row => row.ticket === data.ticket);
      if (old) {
        if (old.operationId !== data.operationId || old.instance !== instance || old.hash !== data.hash || old.bytes !== data.bytes || old.format !== kind || old.title !== (data.title ?? '导入资料')) fail('CONFLICT');
        ticket = clone(old); return false;
      }
      next.tickets = next.tickets.filter(row => Date.now() - row.createdAt <= day || row.jobId && next.jobs.some(job => job.id === row.jobId && activeStates.has(job.state)));
      if (next.tickets.length >= limits.tickets) fail('LIMIT');
      ticket = { ticket: data.ticket, operationId: data.operationId, createdAt: data.createdAt ?? Date.now(), instance,
        bytes: data.bytes, hash: data.hash, format: kind, title: data.title ?? '导入资料', jobId: null };
      if (!integer(ticket.createdAt) || ticket.createdAt > Date.now() + limits.clockSkew || Date.now() - ticket.createdAt > day) fail('OPERATION_EXPIRED');
      next.tickets.push(ticket);
    }, signal);
    return { ticket: ticket.ticket, bytes: ticket.bytes, hash: ticket.hash, format: ticket.format, title: ticket.title };
  }

  ticketFor(next, data, instance, archive = false) {
    checkId(data.ticket);
    const ticket = next.tickets?.find(row => row.ticket === data.ticket);
    if (!ticket || ticket.operationId !== data.operationId || ticket.instance !== instance) fail('INPUT');
    if (Date.now() - ticket.createdAt > day) fail('OPERATION_EXPIRED');
    if (ticket.jobId !== null || (ticket.format === 'archive') !== archive) fail('CONFLICT');
    return ticket;
  }

  newJob(next, operation, type, input, target, extra = {}) {
    if (next.jobs.length >= limits.jobs) fail('LIMIT');
    const now = Date.now();
    const job = { id: randomUUID(), operationId: operation.operationId, type, target: target ?? null,
      baseVersion: null, baseRevision: null, configId: null, settingsRevision: next.settingsRevision,
      state: 'waiting', stage: 'prepare', effect: 'none', progress: { completed: 0, total: 1 }, attempts: 0,
      cancelRequested: false, errorCode: null, createdAt: now, updatedAt: now, input, ...extra };
    next.jobs.push(job); operation.jobId = job.id;
    return job;
  }

  async manage(action, data = {}, { signal, instance, workspaces = [] } = {}) {
    await this.ready; signal?.throwIfAborted();
    if (this.closed || this.closing) fail('STOPPED');
    if (!object(data)) fail('INPUT');
    if (action === 'status') {
      fields(data, ['operationId', 'jobId']);
      const snapshot = this.read(), result = this.status();
      if (data.operationId !== undefined) {
        checkId(data.operationId);
        result.operation = snapshot.operations.find(row => row.operationId === data.operationId) ?? null;
        if (result.operation) Object.assign(result, this.response(result.operation, snapshot));
      }
      if (data.jobId !== undefined) {
        checkId(data.jobId); const job = snapshot.jobs.find(row => row.id === data.jobId);
        result.job = this.publicJob(job);
        if (job) {
          const operation = snapshot.operations.find(row => row.operationId === job.operationId);
          result.job.result = clone(operation.result);
        }
      }
      return result;
    }
    if (action === 'list') {
      fields(data, ['collectionId', 'query', 'includeDeleted', 'offset', 'limit', 'expectedRevision']);
      const snapshot = this.read();
      const offset = data.offset ?? 0, limit = data.limit ?? 20;
      if (!integer(offset) || !integer(limit) || limit < 1 || limit > 100) fail('INPUT');
      if (data.expectedRevision !== undefined && data.expectedRevision !== snapshot.catalogRevision) fail('CONFLICT');
      if (data.collectionId !== undefined) this.collection(snapshot, data.collectionId);
      if (data.query !== undefined) string(data.query, 500, true);
      if (data.includeDeleted !== undefined && typeof data.includeDeleted !== 'boolean') fail('INPUT');
      const query = data.query?.normalize('NFKC').toLocaleLowerCase('en');
      const collections = snapshot.collections.filter(row => !data.collectionId || row.parentId === data.collectionId).map(({ summary, ...row }) => ({ kind: 'collection', value: row }));
      const sources = snapshot.sources.filter(row => (data.includeDeleted || row.state !== 'deleted') && (!data.collectionId || row.collectionId === data.collectionId) && (!query || row.title.normalize('NFKC').toLocaleLowerCase('en').includes(query))).map(row => ({ kind: 'source', value: this.publicSource(row) }));
      const rows = [...collections, ...sources];
      if (offset > rows.length) fail('INPUT');
      const page = this.page(rows.slice(offset), 60 * 1024, limit), nextOffset = offset + page.length < rows.length ? offset + page.length : null;
      return { catalogRevision: snapshot.catalogRevision, collections: page.filter(row => row.kind === 'collection').map(row => row.value),
        sources: page.filter(row => row.kind === 'source').map(row => row.value), offset, nextOffset, total: rows.length, hasMore: nextOffset !== null };
    }
    if (action === 'read') {
      fields(data, ['sourceId', 'id', 'offset', 'maxChars']);
      const source = this.source(this.read(), data.sourceId ?? data.id);
      const offset = data.offset ?? 0, max = data.maxChars ?? 1200;
      if (!integer(offset) || !integer(max) || max < 1 || max > limits.readChars) fail('INPUT');
      const text = [...await this.sourceText(source)];
      if (offset > text.length) fail('INPUT');
      const end = Math.min(text.length, offset + max);
      return { source, text: text.slice(offset, end).join(''), offset, nextOffset: end < text.length ? end : null, totalChars: text.length, truncated: end < text.length };
    }
    if (action === 'ticket') return this.ticket(data, instance, signal);
    const common = ['operationId', 'createdAt', 'expectedRevision'];
    const allowed = {
      settings: ['settings', 'patch', 'enabled', 'embedding', 'summary'],
      collection: ['type', 'action', 'id', 'collectionId', 'parentId', 'title', 'scope', 'patch'],
      import: ['text', 'ticket', 'title', 'note', 'format', 'collectionId'],
      update: ['sourceId', 'id', 'patch', 'text', 'ticket'], remove: ['sourceId', 'id'],
      rebuild: ['sourceId', 'collectionId', 'kind', 'configId'], cancel: ['jobId', 'id'], retry: ['jobId', 'id'],
      export: ['sourceIds', 'collectionId'], restore: ['ticket', 'workspaceMap', 'scopeMap', 'scope'],
    };
    if (!allowed[action]) fail('INPUT');
    fields(data, [...common, ...allowed[action]]);
    if (action === 'settings') return this.settings(data, signal);
    if (action === 'collection') return this.manageCollection(data, workspaces, signal);
    if (action === 'update' && data.text === undefined && data.ticket === undefined && data.patch?.text === undefined && data.patch?.ticket === undefined) return this.updateMetadata(data, signal);
    if (action === 'remove') return this.remove(data, signal);
    if (action === 'cancel' || action === 'retry') return this.control(action, data, signal);
    return this.accept(action, data, { signal, instance, workspaces });
  }

  async settings(data, signal) {
    const patch = data.settings ?? data.patch ?? Object.fromEntries(['enabled', 'embedding', 'summary'].filter(key => data[key] !== undefined).map(key => [key, data[key]]));
    fields(patch, ['enabled', 'embedding', 'summary']);
    if (!Object.keys(patch).length) fail('INPUT');
    if (patch.enabled !== undefined && typeof patch.enabled !== 'boolean') fail('INPUT');
    if (patch.embedding !== undefined) modelSettings(patch.embedding, 'embedding');
    if (patch.summary !== undefined) modelSettings(patch.summary, 'summary');
    for (const key of ['embedding', 'summary']) if (patch[key] !== undefined && ['active', 'generation', 'activeGeneration'].some(field => patch[key][field] !== undefined)) fail('INPUT');
    const cancelled = [];
    const result = await this.mutate('settings', data, (next, operation) => {
      if (!integer(data.expectedRevision)) fail('INPUT');
      if (next.settingsRevision !== data.expectedRevision) fail('CONFLICT');
      Object.assign(next, clone(patch));
      for (const key of ['embedding', 'summary']) if (next[key].enabled && !next[key].configId) {
        const { enabled, active, generation, activeGeneration, ...configuration } = next[key];
        next[key].configId = hash(JSON.stringify(canonical(configuration)));
      }
      next.settingsRevision++; next.catalogRevision++;
      for (const job of next.jobs) {
        if (!activeStates.has(job.state) || !['embedding', 'summary'].includes(job.stage) && !(job.type === 'rebuild' && ['semantic', 'summary'].includes(job.kind))) continue;
        job.cancelRequested = true; job.state = 'cancelled'; job.errorCode = 'KNOWLEDGE_CANCELLED'; job.updatedAt = Date.now(); cancelled.push(job.id);
      }
      for (const collection of next.collections) collection.summaryState = next.summary.enabled ? 'stale' : 'disabled';
      operation.result = { enabled: next.enabled, embedding: clone(next.embedding), summary: clone(next.summary) };
    }, signal);
    for (const id of cancelled) this.controllers.get(id)?.abort();
    return result;
  }

  async manageCollection(data, workspaces, signal) {
    const type = data.type ?? data.action;
    if (!['create', 'update', 'remove'].includes(type)) fail('INPUT');
    const patch = data.patch ?? Object.fromEntries(['title', 'parentId', 'scope'].filter(key => data[key] !== undefined).map(key => [key, data[key]]));
    fields(patch, ['title', 'parentId', 'scope']);
    return this.mutate('collection', data, (next, operation) => {
      if (type === 'create') {
        if (next.collections.length >= limits.collections) fail('LIMIT');
        const parent = patch.parentId == null ? null : this.collection(next, patch.parentId);
        const ownScope = parent ? clone(parent.scope) : allowedScope(patch.scope ?? { kind: 'user' }, workspaces);
        if (parent && patch.scope && !sameScope(scope(patch.scope), parent.scope)) fail('INPUT');
        const row = { id: randomUUID(), parentId: parent?.id ?? null, title: string(patch.title, 200).trim(), scope: ownScope,
          revision: 1, summaryState: next.summary.enabled ? 'stale' : 'disabled', createdAt: Date.now(), updatedAt: Date.now() };
        next.collections.push(row); collectionTree(next.collections); next.catalogRevision++;
        this.invalidate(next, [parent?.id]); operation.effect = 'published'; operation.result = { collection: clone(row) }; return;
      }
      const row = this.collection(next, data.collectionId ?? data.id); this.revision(row, data.expectedRevision);
      if (type === 'remove') {
        if (next.collections.some(item => item.parentId === row.id) || next.sources.some(item => item.collectionId === row.id && item.state !== 'deleted')) fail('BUSY');
        next.collections = next.collections.filter(item => item.id !== row.id);
        this.invalidate(next, [row.parentId]); operation.effect = 'removed'; operation.result = { collectionId: row.id }; next.catalogRevision++; return;
      }
      if (!Object.keys(patch).length) fail('INPUT');
      const oldParent = row.parentId;
      if (patch.scope && !sameScope(scope(patch.scope), row.scope)) fail('INPUT');
      if (patch.title !== undefined) row.title = string(patch.title, 200).trim();
      if (patch.parentId !== undefined) {
        const parent = patch.parentId === null ? null : this.collection(next, patch.parentId);
        if (parent && !sameScope(parent.scope, row.scope)) fail('INPUT');
        row.parentId = parent?.id ?? null;
      }
      collectionTree(next.collections); row.revision++; row.updatedAt = stamp(row); next.catalogRevision++;
      this.invalidate(next, [row.id, oldParent]); operation.effect = 'published'; operation.result = { collection: clone(row) };
    }, signal);
  }

  metadata(next, row, patch) {
    fields(patch, ['title', 'note', 'collectionId', 'format']);
    const oldCollection = row.collectionId;
    if (patch.title !== undefined) row.title = string(patch.title, 200).trim();
    if (patch.note !== undefined) row.note = string(patch.note, 2000, true);
    if (patch.collectionId !== undefined) {
      const target = this.collection(next, patch.collectionId);
      row.collectionId = target.id; row.scope = clone(target.scope);
    }
    if (patch.format !== undefined) row.format = format(patch.format);
    row.revision++; row.updatedAt = stamp(row); row.embeddingState = 'stale';
    this.invalidate(next, [oldCollection, row.collectionId]); next.catalogRevision++;
  }

  async updateMetadata(data, signal) {
    fields(data.patch, ['title', 'note', 'collectionId']);
    if (!Object.keys(data.patch).length) fail('INPUT');
    return this.mutate('update', data, (next, operation) => {
      const row = this.source(next, data.sourceId ?? data.id);
      this.revision(row, data.expectedRevision); this.busy(next, row.id);
      this.metadata(next, row, data.patch);
      operation.effect = 'published'; operation.result = { source: clone(row) };
    }, signal);
  }

  async remove(data, signal) {
    const stopped = [];
    const result = await this.mutate('remove', data, (next, operation) => {
      const row = this.source(next, data.sourceId ?? data.id); this.revision(row, data.expectedRevision);
      row.state = 'deleted'; row.revision++; row.updatedAt = stamp(row); next.catalogRevision++;
      this.invalidate(next, [row.collectionId]);
      const indexes = [...new Set([next.keywordGeneration, next.semanticGeneration, next.semanticIndex?.generation, next.embedding.activeGeneration, next.embedding.active?.generation].filter(Boolean))];
      const summaries = next.collections.filter(collection => collection.summary?.memberVersions?.some(member => member.sourceId === row.id)).map(collection => collection.summary.path);
      delete next.keywordGeneration; delete next.semanticGeneration; delete next.semanticIndex;
      delete next.embedding.activeGeneration; delete next.embedding.generation; delete next.embedding.active;
      for (const collection of next.collections) if (collection.summary?.memberVersions?.some(member => member.sourceId === row.id)) delete collection.summary;
      for (const job of next.jobs) if ((job.target === row.id || ['embedding', 'summary'].includes(job.stage)) && activeStates.has(job.state)) {
        job.cancelRequested = true; job.state = 'cancelled'; job.errorCode = 'KNOWLEDGE_CANCELLED'; stopped.push(job.id);
        if (job.generation && !indexes.includes(job.generation)) indexes.push(job.generation);
      }
      const job = this.newJob(next, operation, 'remove', { indexes, summaries }, row.id, { effect: 'removed', stage: 'cleanup', baseVersion: row.version, baseRevision: row.revision });
      row.cleanupState = 'pending'; operation.effect = 'removed'; operation.result = { source: clone(row) }; job.effect = 'removed';
    }, signal);
    for (const id of stopped) this.controllers.get(id)?.abort();
    this.launch(result.job.id); return result;
  }

  async control(action, data, signal) {
    let target;
    const result = await this.mutate(action, data, (next, operation) => {
      checkId(data.jobId ?? data.id);
      const job = next.jobs.find(row => row.id === (data.jobId ?? data.id));
      if (!job) fail('MISSING');
      target = job.id;
      if (action === 'cancel') {
        if (!finalStates.has(job.state) && !(job.type === 'remove' && job.effect === 'removed')) {
          job.cancelRequested = true; job.state = 'cancelled'; job.errorCode = 'KNOWLEDGE_CANCELLED'; job.updatedAt = Date.now();
        }
      } else {
        if (job.state === 'succeeded') { operation.result = { job: this.publicJob(job) }; return; }
        if (!['failed', 'waitingRetry'].includes(job.state)) fail('BUSY');
        if (job.cancelRequested) fail('CANCELLED');
        if (job.attempts >= 3) fail('LIMIT');
        if (job.type === 'rebuild' && ['semantic', 'summary'].includes(job.kind) || ['embedding', 'summary'].includes(job.stage)) {
          const config = job.kind === 'summary' || job.stage === 'summary' ? next.summary : next.embedding;
          if (!next.enabled || !config.enabled || config.configId !== job.configId || typeof this.options.process !== 'function') fail('MODEL');
          job.settingsRevision = next.settingsRevision;
        }
        if (job.target && ['update', 'rebuild'].includes(job.type) && job.kind !== 'summary') {
          const source = this.source(next, job.target);
          if (job.baseVersion !== source.version || job.effect === 'none' && job.baseRevision !== source.revision) fail('CONFLICT');
          this.busy(next, source.id, job.id);
        }
        job.state = 'waiting'; job.errorCode = null; job.updatedAt = Date.now();
      }
      operation.effect = job.effect; operation.result = { job: this.publicJob(job) };
    }, signal);
    if (target && action === 'cancel') this.controllers.get(target)?.abort();
    if (target && action === 'retry') this.launch(target);
    for (const job of this.read().jobs) if (job.type === 'remove' && job.state === 'waiting' && !this.hasReaders(job.target)) this.launch(job.id);
    return result;
  }

  async accept(action, data, { signal, instance, workspaces }) {
    // 先查原操作，避免通信重试依赖已经消费或清理的暂存文件。
    const previous = this.read();
    const checked = this.operation(clone(previous), action, data);
    if (checked.exists) return this.response(checked.operation, previous);
    let raw, ticket, patch;
    if (action === 'import' || action === 'update') {
      patch = action === 'update' ? clone(data.patch ?? {}) : {};
      fields(patch, ['title', 'note', 'collectionId', 'format', 'text', 'ticket']);
      const text = data.text ?? patch.text, token = data.ticket ?? patch.ticket;
      if (data.text !== undefined && patch.text !== undefined || data.ticket !== undefined && patch.ticket !== undefined || (text !== undefined) === (token !== undefined)) fail('INPUT');
      if (text !== undefined) {
        if (typeof text !== 'string' || Buffer.byteLength(text) > limits.sourceBytes) fail('LIMIT');
        raw = Buffer.from(text); normalizedText(raw);
      } else {
        ticket = this.ticketFor(previous, { ...data, ticket: token }, instance);
        raw = await this.file(`inbox/${ticket.ticket}/input`, limits.sourceBytes);
        if (raw.length !== ticket.bytes || hash(raw) !== ticket.hash) fail('INPUT');
        normalizedText(raw);
      }
      delete patch.text; delete patch.ticket;
      if (action === 'import') {
        format(data.format ?? ticket?.format ?? 'text');
        string(data.title ?? ticket?.title ?? '粘贴文本', 200);
        if (data.note !== undefined) string(data.note, 2000, true);
      }
    }
    if (action === 'restore') {
      ticket = this.ticketFor(previous, data, instance, true);
      raw = await this.file(`inbox/${ticket.ticket}/input`, limits.archiveBytes);
      if (raw.length !== ticket.bytes || hash(raw) !== ticket.hash) fail('INPUT');
      // 实际展开量在解压时限制；完整校验和范围映射由后台任务执行。
    }
    const inputId = randomUUID();
    if (raw) {
      await this.space(raw.length * 2); await this.directory(`staging/${inputId}`);
      try { await this.write(`staging/${inputId}/input`, raw); await this.sync(`staging/${inputId}`); await this.sync('staging'); }
      catch (cause) { await this.removePath(`staging/${inputId}`).catch(() => {}); throw cause; }
    }
    let retained = false;
    try {
      const result = await this.mutate(action, data, (next, operation) => {
        let job;
        const input = { ...(raw ? { inputId, inputHash: hash(raw), inputBytes: raw.length } : {}) };
        if (action === 'import') {
          const collection = this.collection(next, data.collectionId, true);
          Object.assign(input, { collectionId: collection.id, scope: clone(collection.scope), title: data.title ?? ticket?.title ?? '粘贴文本',
            note: data.note ?? '', format: format(data.format ?? ticket?.format ?? 'text') });
          job = this.newJob(next, operation, action, input, null);
        } else if (action === 'update') {
          const source = this.source(next, data.sourceId ?? data.id);
          this.revision(source, data.expectedRevision); this.busy(next, source.id);
          if (patch.collectionId !== undefined) this.collection(next, patch.collectionId);
          if (patch.title !== undefined) string(patch.title, 200);
          if (patch.note !== undefined) string(patch.note, 2000, true);
          if (patch.format !== undefined) format(patch.format);
          Object.assign(input, { patch, source: clone(source), format: format(patch.format ?? ticket?.format ?? source.format) });
          job = this.newJob(next, operation, action, input, source.id, { baseVersion: source.version, baseRevision: source.revision });
        } else if (action === 'rebuild') {
          if (!['keyword', 'semantic', 'summary', 'chunks'].includes(data.kind)) fail('INPUT');
          if (data.sourceId && data.collectionId) fail('INPUT');
          const source = data.sourceId ? this.source(next, data.sourceId) : null;
          const collection = data.collectionId ? this.collection(next, data.collectionId) : null;
          if (data.kind === 'chunks' && !source) fail('INPUT');
          if (source) {
            this.revision(source, data.expectedRevision); this.busy(next, source.id);
            input.source = clone(source);
          } else if (collection) this.revision(collection, data.expectedRevision);
          if (data.kind === 'summary' && !collection) fail('INPUT');
          input.collectionId = collection?.id ?? null;
          const model = data.kind === 'summary' ? next.summary : next.embedding;
          if (['semantic', 'summary'].includes(data.kind) && (!next.enabled || !model.enabled || data.configId && data.configId !== model.configId)) fail('MODEL');
          job = this.newJob(next, operation, action, input, source?.id ?? collection?.id ?? null,
            { kind: data.kind, baseVersion: source?.version ?? null, baseRevision: source?.revision ?? collection?.revision ?? null,
              configId: ['semantic', 'summary'].includes(data.kind) ? model.configId : null });
        } else if (action === 'export') {
          if (data.sourceIds !== undefined && (!Array.isArray(data.sourceIds) || data.sourceIds.length > limits.sources)) fail('INPUT');
          const sources = data.sourceIds ? [...new Set(data.sourceIds)].map(id => this.source(next, id)) : next.sources.filter(row => row.state === 'active');
          if (data.collectionId !== undefined) this.collection(next, data.collectionId);
          const selected = sources.filter(row => !data.collectionId || this.inCollection(next, row.collectionId, data.collectionId));
          if (selected.some(row => row.state !== 'active')) fail('READ');
          input.sources = clone(selected); input.collections = clone(next.collections);
          job = this.newJob(next, operation, action, input, null, { stage: 'export' });
        } else if (action === 'restore') {
          input.workspaceMap = clone(data.workspaceMap ?? data.scopeMap ?? {});
          if (!object(input.workspaceMap)) fail('INPUT');
          if (data.scope !== undefined) input.scope = allowedScope(data.scope, workspaces);
          input.workspaces = workspacePaths(workspaces);
          job = this.newJob(next, operation, action, input, null, { stage: 'validate' });
        }
        if (ticket) {
          const current = this.ticketFor(next, { ...data, ticket: ticket.ticket }, instance, action === 'restore');
          current.jobId = job.id; input.ticket = current.ticket;
        }
        retained = true;
      }, signal);
      // mutate 可能返回并发请求已登记的同一操作；仅保留真正被任务引用的输入。
      retained = !!result.job && this.read().jobs.find(job => job.id === result.job.id)?.input.inputId === inputId;
      if (result.job) this.launch(result.job.id);
      return result;
    } finally { if (raw && !retained) await this.removePath(`staging/${inputId}`).catch(() => {}); }
  }

  inCollection(snapshot, id, parent) {
    while (id) {
      if (id === parent) return true;
      id = snapshot.collections.find(row => row.id === id)?.parentId;
    }
    return false;
  }

  launch(id) {
    if (this.closed || this.closing || this.running.has(id)) return;
    const controller = new AbortController();
    this.controllers.set(id, controller);
    const promise = Promise.resolve().then(() => this.run(id, controller.signal)).catch(() => {}).finally(() => {
      this.running.delete(id); this.controllers.delete(id);
      if (!this.closing && !this.closed) for (const job of this.read().jobs) {
        if (job.type === 'remove' && job.state === 'waiting' && !this.hasReaders(job.target)) this.launch(job.id);
      }
    });
    this.running.set(id, promise);
  }

  async wait(id) {
    await this.ready; checkId(id);
    while (this.running.has(id)) await this.running.get(id);
    await this.queue;
    const job = this.read().jobs.find(row => row.id === id);
    if (!job) fail('MISSING');
    return job;
  }

  async idle() {
    await this.ready;
    while (this.running.size) await Promise.allSettled([...this.running.values()]);
    await this.queue;
    return this.status();
  }

  job(next, id, signal) {
    signal?.throwIfAborted();
    const job = next.jobs.find(row => row.id === id);
    if (!job || job.cancelRequested || job.state !== 'running') fail('CANCELLED');
    return job;
  }

  async commitJob(id, update, signal) {
    return this.change(next => {
      const job = this.job(next, id, signal);
      if (['embedding', 'summary'].includes(job.stage)) this.modelReady(next, job);
      update(next, job, next.operations.find(row => row.operationId === job.operationId));
    }, signal);
  }

  modelReady(snapshot, job) {
    const kind = job.stage === 'summary' || job.kind === 'summary' ? 'summary' : 'embedding', config = snapshot[kind];
    if (!snapshot.enabled || !config.enabled || config.configId !== job.configId || snapshot.settingsRevision !== job.settingsRevision) fail('NOT_READY');
    if (job.target && job.input.source) {
      const source = this.source(snapshot, job.target);
      if (source.state !== 'active' || job.baseVersion !== source.version || job.baseRevision !== source.revision || !sameScope(source.scope, job.input.source.scope)) fail('VERSION_CHANGED');
    }
    if (kind === 'summary' && job.target && !job.input.source) {
      const collection = this.collection(snapshot, job.target);
      if (job.baseRevision !== null && collection.revision !== job.baseRevision) fail('VERSION_CHANGED');
    }
  }

  async run(id, signal) {
    let started = false;
    try {
      await this.change(next => {
        const job = next.jobs.find(row => row.id === id);
        if (!job || job.cancelRequested || job.state !== 'waiting') return false;
        job.state = 'running'; job.attempts++; job.updatedAt = Date.now(); job.errorCode = null; started = true;
      });
      if (!started) return;
      let job = this.read().jobs.find(row => row.id === id);
      if (job.type === 'remove') await this.cleanup(job, signal);
      else if (job.type === 'export') await this.export(job, signal);
      else if (job.type === 'restore') await this.restore(job, signal);
      else if (job.type === 'rebuild' && job.kind === 'keyword') await this.keyword(job, signal);
      else if (job.type === 'rebuild' && ['semantic', 'summary'].includes(job.kind)) await this.process(job, job.kind === 'summary' ? 'summary' : 'embedding', signal);
      else {
        if (job.effect === 'none') await this.publish(job, signal);
        job = this.read().jobs.find(row => row.id === id);
        if (job.type !== 'rebuild' || job.kind !== 'chunks') {
          for (const kind of ['embedding', 'summary']) {
            const current = this.read();
            if (current.enabled && current[kind].enabled) await this.process(current.jobs.find(row => row.id === id), kind, signal);
          }
        }
      }
      await this.change(next => {
        const job = next.jobs.find(row => row.id === id);
        if (!job || job.state !== 'running') return false;
        job.state = 'succeeded'; job.progress = { completed: 1, total: 1 }; job.errorCode = null; job.updatedAt = Date.now();
        const operation = next.operations.find(row => row.operationId === job.operationId);
        operation.effect = job.effect;
      });
    } catch (cause) {
      const code = /^KNOWLEDGE_[A-Z_]+$/u.test(cause?.code ?? '') ? cause.code : cause?.name === 'AbortError' ? 'KNOWLEDGE_CANCELLED' : 'KNOWLEDGE_WRITE';
      await this.change(next => {
        const job = next.jobs.find(row => row.id === id);
        if (!job || job.state === 'cancelled' || job.state === 'succeeded') return false;
        job.state = code === 'KNOWLEDGE_CANCELLED' ? this.closing ? 'waitingRetry' : 'cancelled' :
          ['embedding', 'summary'].includes(job.stage) || code === 'KNOWLEDGE_CONFLICT' ? 'waitingRetry' : 'failed';
        job.errorCode = code; job.updatedAt = Date.now();
        const operation = next.operations.find(row => row.operationId === job.operationId);
        operation.effect = job.effect;
      }).catch(() => {});
    } finally {
      if (started) await this.cleanJob(id).catch(() => {});
    }
  }

  async input(job, max = limits.sourceBytes) {
    checkId(job.input.inputId);
    const bytes = await this.file(`staging/${job.input.inputId}/input`, max);
    if (bytes.length !== job.input.inputBytes || hash(bytes) !== job.input.inputHash) fail('INPUT');
    return bytes;
  }

  async prepare(job, source, original) {
    const text = normalizedText(original), { chunks, chunkerVersion } = await this.chunk(text, source);
    const body = Buffer.from(text), chunkBytes = Buffer.from(JSON.stringify(chunks));
    const keyword = Buffer.from(JSON.stringify({ schemaVersion: 1, sourceId: source.id, sourceVersion: source.version,
      chunkerVersion, chunks: chunks.map(chunk => ({ id: chunk.id, heading: chunk.heading, text: chunk.text })) }));
    await this.space(original.length + body.length + chunkBytes.length + keyword.length);
    const stage = `staging/${job.id}/${randomUUID()}`;
    await this.directory(stage);
    for (const [name, bytes] of [['original', original], ['text.txt', body], ['chunks.json', chunkBytes], ['keyword.json', keyword]]) await this.write(`${stage}/${name}`, bytes);
    await this.sync(stage);
    const target = this.versionPath(source), key = `${source.id}/${source.version}`;
    this.preparing.add(key);
    try {
      await this.directory(`sources/${source.id}`);
      try { await fs.lstat(this.path(target)); fail('CONFLICT'); } catch (cause) { if (cause.code !== 'ENOENT') throw cause; }
      await fs.rename(this.path(stage), this.path(target)); await this.sync(`sources/${source.id}`); await this.sync('sources');
      return { bytes: original.length, hash: hash(original), textHash: hash(body), chunksHash: hash(chunkBytes), chunkCount: chunks.length,
        chunkerVersion, keywordState: 'ready', embeddingState: 'missing' };
    } catch (cause) { this.preparing.delete(key); throw cause; }
  }

  async publish(job, signal) {
    const old = job.input.source;
    const original = job.type === 'rebuild' ? await this.sourceOriginal(old) : await this.input(job);
    const candidate = { id: old?.id ?? randomUUID(), version: (old?.version ?? 0) + 1,
      format: job.input.format ?? old?.format ?? 'text' };
    let prepared;
    try {
      signal.throwIfAborted(); prepared = await this.prepare(job, candidate, original); signal.throwIfAborted();
      await this.commitJob(job.id, (next, current, operation) => {
        current.stage = 'publish';
        let row;
        if (old) {
          row = this.source(next, old.id);
          if (!sameVersion(row, old)) fail('CONFLICT');
          this.busy(next, row.id, job.id);
          this.metadata(next, row, { ...(job.input.patch ?? {}), format: candidate.format });
          Object.assign(row, candidate, prepared); row.state = 'active'; delete row.errorCode;
        } else {
          const collection = this.collection(next, job.input.collectionId);
          if (!sameScope(collection.scope, job.input.scope)) fail('CONFLICT');
          const duplicate = next.sources.find(source => source.state === 'active' && sameScope(source.scope, collection.scope) && source.hash === prepared.hash);
          if (duplicate) {
            operation.effect = 'published'; operation.result = { source: clone(duplicate), duplicate: true }; current.effect = 'published';
            current.target = duplicate.id; current.baseVersion = duplicate.version; current.baseRevision = duplicate.revision; current.input.source = clone(duplicate);
            return;
          }
          row = { ...candidate, ...prepared, collectionId: collection.id, scope: clone(collection.scope),
            title: job.input.title.trim(), note: job.input.note, state: 'active', revision: 1, createdAt: Date.now(), updatedAt: Date.now() };
          next.sources.push(row); next.catalogRevision++; this.invalidate(next, [collection.id]);
        }
        current.target = row.id; current.baseVersion = row.version; current.baseRevision = row.revision;
        current.input.source = clone(row); current.effect = 'published'; current.progress = { completed: 1, total: 1 }; current.stage = 'keyword';
        operation.effect = 'published'; operation.result = { source: clone(row) };
      }, signal);
    } finally {
      this.preparing.delete(`${candidate.id}/${candidate.version}`);
      if (prepared && !this.read().sources.some(row => row.id === candidate.id && row.version === candidate.version && row.state !== 'deleted')) await this.removePath(this.versionPath(candidate)).catch(() => {});
    }
  }

  async keyword(job, signal) {
    const snapshot = this.read();
    const sources = snapshot.sources.filter(row => row.state === 'active' && (!job.input.source || row.id === job.input.source.id) &&
      (!job.input.collectionId || this.inCollection(snapshot, row.collectionId, job.input.collectionId)));
    const release = this.pin(sources), generation = randomUUID(), directory = `indexes/${generation}`;
    let switched = false;
    try {
      await this.directory(directory);
      const files = [];
      for (const source of sources) {
        signal.throwIfAborted();
        const chunks = await this.sourceChunks(source);
        const name = `${source.id}.json`, bytes = Buffer.from(JSON.stringify({ sourceId: source.id, version: source.version, title: source.title, chunks }));
        await this.write(`${directory}/${name}`, bytes); files.push({ path: name, bytes: bytes.length, hash: hash(bytes) });
      }
      const manifest = { schemaVersion: 1, generation, kind: 'keyword', inputVersions: sources.map(source => ({ id: source.id, version: source.version, revision: source.revision })),
        embeddingConfigId: null, dimensions: null, normalized: false, files, complete: true };
      await this.write(`${directory}/manifest.json`, JSON.stringify(manifest)); await this.sync(directory); await this.sync('indexes');
      await this.commitJob(job.id, (next, current, operation) => {
        if (next.catalogRevision !== snapshot.catalogRevision || sources.some(source => !sameVersion(next.sources.find(row => row.id === source.id && row.state === 'active'), source))) fail('CONFLICT');
        current.stage = 'switch'; current.effect = 'switched'; current.progress = { completed: sources.length, total: sources.length };
        next.keywordGeneration = generation; next.catalogRevision++;
        for (const source of sources) next.sources.find(row => row.id === source.id).keywordState = 'ready';
        operation.effect = 'switched'; operation.result = { generation, sourceCount: sources.length, chunkCount: sources.reduce((sum, source) => sum + source.chunkCount, 0) };
        switched = true;
      }, signal);
    } finally { release(); if (!switched) await this.removePath(directory).catch(() => {}); }
  }

  async process(job, kind, signal) {
    if (job.completedStages?.includes(kind)) return;
    await this.commitJob(job.id, (next, current) => {
      current.stage = kind; current.configId = next[kind].configId ?? null;
      this.modelReady(next, current);
    }, signal);
    if (typeof this.options.process !== 'function') fail('MODEL');
    const current = this.read().jobs.find(row => row.id === job.id);
    const timeout = AbortSignal.timeout(this.options.processTimeout ?? 10 * 60 * 1000);
    const combined = AbortSignal.any([signal, timeout]);
    let stop;
    const stopped = new Promise((_, reject) => {
      stop = () => reject(knowledgeFailure(signal.aborted ? 'CANCELLED' : 'MODEL'));
      combined.addEventListener('abort', stop, { once: true });
      if (combined.aborted) stop();
    });
    const callbackJob = { ...current, kind: kind === 'summary' ? 'summary' : 'semantic', signal: combined };
    if (kind === 'summary' && current.input.source) {
      callbackJob.target = current.input.source.collectionId;
      callbackJob.baseRevision = this.collection(this.read(), callbackJob.target).revision;
    }
    const guarded = new Proxy(this, { get: (store, key) => {
      if (key === 'change') return (update, extraSignal) => store.change(next => {
        const active = store.job(next, job.id, combined);
        store.modelReady(next, { ...active, stage: kind });
        const outcome = update(next);
        if (outcome === false) return false;
        if (active.effect === 'switched' && active.stage === 'switch') {
          active.completedStages = [...new Set([...(active.completedStages ?? []), kind])];
          const operation = next.operations.find(row => row.operationId === active.operationId);
          operation.effect = active.effect;
        }
        return outcome;
      }, extraSignal ? AbortSignal.any([combined, extraSignal]) : combined);
      const value = Reflect.get(store, key);
      return typeof value === 'function' ? value.bind(store) : value;
    } });
    let result;
    try { result = await Promise.race([Promise.resolve().then(() => this.options.process(callbackJob, guarded, { signal: combined })), stopped]); }
    finally { combined.removeEventListener('abort', stop); }
    await this.commitJob(job.id, (next, active, operation) => {
      this.modelReady(next, { ...active, stage: kind });
      if (!object(result) || result.complete !== true && !(result.effect === 'switched' && active.effect === 'switched' && active.completedStages?.includes(kind))) fail('NOT_READY');
      if (result.effect !== undefined && !['published', 'switched'].includes(result.effect)) fail('INPUT');
      if (result.effect) { active.effect = result.effect; operation.effect = result.effect; }
      active.completedStages = [...new Set([...(active.completedStages ?? []), kind])];
      active.progress = { completed: 1, total: 1 };
    }, signal);
  }

  async cleanup(job, signal) {
    const source = this.read().sources.find(row => row.id === job.target);
    if (!source || source.state !== 'deleted') fail('CONFLICT');
    if (this.hasReaders(source.id)) {
      await this.commitJob(job.id, (next, current) => { current.state = 'waiting'; current.stage = 'cleanup'; }, signal);
      return;
    }
    await this.removePath(`sources/${source.id}`);
    for (const generation of job.input.indexes ?? []) {
      checkId(generation);
      const snapshot = this.read();
      if ([snapshot.keywordGeneration, snapshot.semanticGeneration, snapshot.semanticIndex?.generation].includes(generation)) fail('BUSY');
      await this.removePath(`indexes/${generation}`);
    }
    for (const file of job.input.summaries ?? []) {
      if (!/^summaries\/[A-Za-z0-9_-]+\/[A-Za-z0-9_-]+\/overview\.json$/u.test(file)) fail('INPUT');
      await this.removePath(file.slice(0, -'/overview.json'.length));
    }
    await this.commitJob(job.id, (next, current, operation) => {
      const row = next.sources.find(item => item.id === source.id);
      if (!row || row.state !== 'deleted') fail('CONFLICT');
      row.cleanupState = 'done'; current.stage = 'cleanup'; operation.result = { source: clone(row) };
    }, signal);
  }

  // 归档格式：gzip(JSON)，format=dshx-knowledge、version=1；entries 仅允许正式版本的三个文件。
  // 每个条目包含 path、bytes、SHA-256 hash、base64 data；不含设置、记忆、密钥、任务或派生索引。
  async export(job, signal) {
    let snapshot, release;
    await this.commitJob(job.id, (next, current) => {
      // 在同一短提交中取得版本快照和读取占用；删除随后仍立即生效。
      // 接受导出时已经在短提交内取得正式快照；活动导出任务也构成清理占用。
      // 之后的删除或更新不改变这份快照，文件完整性在下面逐项核验。
      const sources = clone(current.input.sources);
      snapshot = { sources, collections: clone(current.input.collections) }; release = this.pin(sources);
    }, signal);
    try {
      const entries = [];
      for (const source of snapshot.sources) for (const name of ['original', 'text.txt', 'chunks.json']) {
        signal.throwIfAborted();
        const path = `${this.versionPath(source)}/${name}`, bytes = await this.file(path, name === 'chunks.json' ? limits.sourceBytes * 5 : limits.sourceBytes);
        if (name === 'original' && (bytes.length !== source.bytes || hash(bytes) !== source.hash)) fail('READ');
        if (name === 'text.txt' && source.textHash && hash(bytes) !== source.textHash) fail('READ');
        if (name === 'chunks.json') {
          if (source.chunksHash && source.chunksHash !== hash(bytes)) fail('INDEX');
          this.checkChunks(JSON.parse(bytes), source);
        }
        entries.push({ path, bytes: bytes.length, hash: hash(bytes), data: bytes.toString('base64') });
      }
      const archive = { format: 'dshx-knowledge', version: 1, createdAt: Date.now(),
        collections: snapshot.collections.map(row => ({ id: row.id, parentId: row.parentId, title: row.title, scope: row.scope, revision: row.revision, createdAt: row.createdAt, updatedAt: row.updatedAt })),
        sources: snapshot.sources.map(row => ({ id: row.id, collectionId: row.collectionId, scope: row.scope, title: row.title, note: row.note,
          format: row.format, version: row.version, revision: row.revision, hash: row.hash, bytes: row.bytes, state: 'active',
          chunkCount: row.chunkCount, chunkerVersion: row.chunkerVersion, textHash: row.textHash, chunksHash: row.chunksHash, createdAt: row.createdAt, updatedAt: row.updatedAt })), entries };
      const body = Buffer.from(JSON.stringify(archive));
      if (body.length > limits.archiveBytes || entries.length > limits.archiveEntries) fail('LIMIT');
      signal.throwIfAborted();
      const bytes = await compress(body);
      if (bytes.length > limits.archiveBytes) fail('LIMIT');
      await this.space(bytes.length); await this.directory(`exports/${job.operationId}`);
      const path = `exports/${job.operationId}/archive.dshx`;
      // 同一操作重试只能重建自己的固定导出文件，不接收外部路径。
      await this.removePath(path);
      await this.write(path, bytes); await this.sync(`exports/${job.operationId}`); await this.sync('exports');
      await this.commitJob(job.id, (next, current, operation) => {
        current.stage = 'export'; current.progress = { completed: 1, total: 1 };
        const result = { path, ticket: job.operationId, bytes: bytes.length, hash: hash(bytes), format: 'gzip', version: 1 };
        operation.result = { ...result, archive: result, sourceCount: archive.sources.length };
      }, signal);
    } finally { release?.(); }
  }

  async parseArchive(bytes) {
    let archive;
    try {
      const body = await decompress(bytes, { maxOutputLength: limits.archiveBytes });
      archive = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body));
    } catch (cause) { fail(cause.code === 'ERR_BUFFER_TOO_LARGE' ? 'LIMIT' : 'INPUT'); }
    fields(archive, ['format', 'version', 'createdAt', 'collections', 'sources', 'entries']);
    if (archive.format !== 'dshx-knowledge' || archive.version !== 1 || !integer(archive.createdAt) || !Array.isArray(archive.entries) || archive.entries.length > limits.archiveEntries ||
        !Array.isArray(archive.collections) || !Array.isArray(archive.sources)) fail('INPUT');
    for (const row of archive.collections) fields(row, ['id', 'parentId', 'title', 'scope', 'revision', 'createdAt', 'updatedAt']);
    for (const row of archive.sources) fields(row, ['id', 'collectionId', 'scope', 'title', 'note', 'format', 'version', 'revision', 'hash', 'bytes', 'state',
      'chunkCount', 'chunkerVersion', 'textHash', 'chunksHash', 'createdAt', 'updatedAt']);
    const catalog = { ...initialKnowledge(), collections: archive.collections.map(row => ({ ...row, summaryState: 'disabled' })), sources: archive.sources };
    checkKnowledge(catalog);
    if (catalog.sources.some(source => source.state !== 'active')) fail('INPUT');
    const expected = new Set(catalog.sources.flatMap(source => ['original', 'text.txt', 'chunks.json'].map(name => `${this.versionPath(source)}/${name}`)));
    const entries = new Map(); let total = 0;
    for (const entry of archive.entries) {
      fields(entry, ['path', 'bytes', 'hash', 'data']);
      if (!expected.has(entry.path) || entries.has(entry.path) || !integer(entry.bytes) || entry.bytes > limits.sourceBytes * 5 || !hashPattern.test(entry.hash) ||
          typeof entry.data !== 'string' || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/u.test(entry.data) ||
          entry.data.length !== Math.ceil(entry.bytes / 3) * 4) fail('INPUT');
      const body = Buffer.from(entry.data, 'base64'); total += body.length;
      if (total > limits.archiveBytes || body.length !== entry.bytes || hash(body) !== entry.hash || body.toString('base64') !== entry.data) fail('INPUT');
      entries.set(entry.path, body);
    }
    if (entries.size !== expected.size) fail('INPUT');
    for (const source of catalog.sources) {
      const prefix = this.versionPath(source), original = entries.get(`${prefix}/original`), textBytes = entries.get(`${prefix}/text.txt`), chunksBytes = entries.get(`${prefix}/chunks.json`);
      const text = normalizedText(original);
      if (source.hash !== hash(original) || source.bytes !== original.length || textBytes.toString('utf8') !== text || source.textHash && source.textHash !== hash(textBytes) || source.chunksHash && source.chunksHash !== hash(chunksBytes)) fail('INPUT');
      let chunks; try { chunks = JSON.parse(chunksBytes); } catch { fail('INPUT'); }
      this.checkChunks(chunks, source, text);
      if (chunks.length !== source.chunkCount) fail('INPUT');
    }
    return { ...archive, entries };
  }

  mappedScope(value, input) {
    if (input.scope) return clone(input.scope);
    if (value.kind === 'user') return { kind: 'user' };
    if (!Object.hasOwn(input.workspaceMap, value.workspace)) fail('INPUT');
    const mapped = input.workspaceMap[value.workspace];
    if (mapped === null || mapped === 'user') return { kind: 'user' };
    return allowedScope(typeof mapped === 'string' ? { kind: 'workspace', workspace: mapped } : mapped, input.workspaces);
  }

  async restore(job, signal) {
    const archive = await this.parseArchive(await this.input(job, limits.archiveBytes));
    signal.throwIfAborted();
    const before = this.read(), draft = clone(before), collectionIds = new Map(), sourceIds = new Map(), created = [], skipped = [], collisions = [];
    const pending = [...archive.collections];
    while (pending.length) {
      const index = pending.findIndex(row => row.parentId === null || collectionIds.has(row.parentId));
      if (index < 0) fail('INPUT');
      const old = pending.splice(index, 1)[0], ownScope = this.mappedScope(old.scope, job.input), parentId = old.parentId === null ? null : collectionIds.get(old.parentId);
      const found = draft.collections.find(row => sameScope(row.scope, ownScope) && row.parentId === parentId && row.title === old.title);
      if (found) { collectionIds.set(old.id, found.id); continue; }
      const id = draft.collections.some(row => row.id === old.id) ? randomUUID() : old.id;
      collectionIds.set(old.id, id);
      draft.collections.push({ id, parentId, title: old.title, scope: ownScope, revision: 1,
        summaryState: draft.summary.enabled ? 'stale' : 'disabled', createdAt: Date.now(), updatedAt: Date.now() });
    }
    for (const old of archive.sources) {
      const ownScope = this.mappedScope(old.scope, job.input);
      const duplicate = draft.sources.find(row => row.state !== 'deleted' && sameScope(row.scope, ownScope) && row.hash === old.hash);
      if (duplicate) { skipped.push(old.id); sourceIds.set(old.id, duplicate.id); continue; }
      const id = draft.sources.some(row => row.id === old.id) ? randomUUID() : old.id;
      if (draft.sources.some(row => row.state !== 'deleted' && sameScope(row.scope, ownScope) && row.title === old.title)) collisions.push(old.title);
      const row = { ...clone(old), id, collectionId: collectionIds.get(old.collectionId), scope: ownScope,
        revision: 1, state: 'active', keywordState: 'ready', embeddingState: 'missing', updatedAt: Date.now() };
      sourceIds.set(old.id, id); draft.sources.push(row); created.push({ old, row });
    }
    draft.catalogRevision++; collectionTree(draft.collections); checkKnowledge(draft);
    const prepared = [];
    try {
      for (const { old, row } of created) {
        signal.throwIfAborted();
        // ID 重分配时重写块 ID 与相邻引用；正文区间和已验证的分块内容保持原样。
        const prefix = this.versionPath(old), chunks = JSON.parse(archive.entries.get(`${prefix}/chunks.json`));
        const chunkIds = new Map(chunks.map((chunk, index) => [chunk.id, row.id === old.id ? chunk.id : `${row.id}-${row.version}-${index}`]));
        const rewritten = chunks.map(chunk => ({ ...chunk, id: chunkIds.get(chunk.id), sourceId: row.id, sourceVersion: row.version,
          ...(chunk.version === undefined ? {} : { version: row.version }), previous: chunk.previous == null ? null : chunkIds.get(chunk.previous), next: chunk.next == null ? null : chunkIds.get(chunk.next) }));
        const stage = `staging/${job.id}/${randomUUID()}`, target = this.versionPath(row), key = `${row.id}/${row.version}`;
        const original = archive.entries.get(`${prefix}/original`), text = archive.entries.get(`${prefix}/text.txt`), body = Buffer.from(JSON.stringify(rewritten));
        await this.space(original.length + text.length + body.length * 2); await this.directory(stage);
        for (const [name, bytes] of [['original', original], ['text.txt', text], ['chunks.json', body], ['keyword.json', body]]) await this.write(`${stage}/${name}`, bytes);
        await this.sync(stage); await this.directory(`sources/${row.id}`);
        // 与未被正式目录引用的孤立旧版本也不能静默覆盖。
        try { await fs.lstat(this.path(target)); fail('CONFLICT'); } catch (cause) { if (cause.code !== 'ENOENT') throw cause; }
        this.preparing.add(key); prepared.push(row);
        await fs.rename(this.path(stage), this.path(target)); await this.sync(`sources/${row.id}`); await this.sync('sources');
        row.textHash = hash(text); row.chunksHash = hash(body);
      }
      await this.commitJob(job.id, (next, current, operation) => {
        if (next.catalogRevision !== before.catalogRevision) fail('CONFLICT');
        next.collections = draft.collections; next.sources = draft.sources; next.catalogRevision++;
        this.invalidate(next, created.map(item => item.row.collectionId));
        current.stage = 'restore'; current.effect = 'restored'; current.progress = { completed: created.length, total: created.length };
        operation.effect = 'restored'; operation.result = { restored: created.length, skipped: skipped.length, conflicts: collisions,
          sourceIds: Object.fromEntries(sourceIds), collectionIds: Object.fromEntries(collectionIds) };
      }, signal);
    } finally {
      for (const row of prepared) {
        this.preparing.delete(`${row.id}/${row.version}`);
        if (!this.read().sources.some(source => source.id === row.id && source.version === row.version && source.state !== 'deleted')) await this.removePath(this.versionPath(row)).catch(() => {});
      }
    }
  }

  async cleanJob(id) {
    const job = this.read().jobs.find(row => row.id === id);
    if (!job) return;
    if (['succeeded', 'cancelled'].includes(job.state) || job.effect !== 'none') {
      if (job.input.inputId) await this.removePath(`staging/${job.input.inputId}`);
      await this.removePath(`staging/${job.id}`);
      if (job.input.ticket) await this.removePath(`inbox/${job.input.ticket}`);
    }
    await this.collect();
  }

  hasReaders(sourceId) {
    if ([...this.pins.keys(), ...this.preparing].some(key => key.startsWith(`${sourceId}/`))) return true;
    return this.read().jobs.some(job => job.type === 'export' && activeStates.has(job.state) && job.input.sources.some(source => source.id === sourceId));
  }

  async pruneFiles() {
    const snapshot = this.read(), now = Date.now();
    const staging = new Set();
    for (const job of snapshot.jobs) if (!['succeeded', 'cancelled'].includes(job.state)) {
      staging.add(job.id);
      if (job.effect === 'none' && job.input?.inputId) staging.add(job.input.inputId);
    }
    const exports = new Set(snapshot.operations.filter(row => now - row.createdAt <= day || row.jobId && snapshot.jobs.some(job => job.id === row.jobId && activeStates.has(job.state))).map(row => row.operationId));
    const tickets = new Set((snapshot.tickets ?? []).filter(row => now - row.createdAt <= day || row.jobId && snapshot.jobs.some(job => job.id === row.jobId && activeStates.has(job.state))).map(row => row.ticket));
    for (const [parent, used] of [['staging', staging], ['exports', exports], ['inbox', tickets]]) {
      for (const entry of await fs.readdir(this.path(parent), { withFileTypes: true })) {
        if (!entry.isDirectory() || !idPattern.test(entry.name) || used.has(entry.name)) continue;
        if (parent === 'inbox' && now - (await fs.lstat(this.path(`${parent}/${entry.name}`))).mtimeMs < day) continue;
        await this.removePath(`${parent}/${entry.name}`);
      }
    }
  }

  async collect() {
    const snapshot = this.read();
    const used = new Set(snapshot.sources.filter(row => row.state !== 'deleted').map(row => `${row.id}/${row.version}`));
    for (const job of snapshot.jobs) if (activeStates.has(job.state) && job.type === 'export') for (const source of job.input.sources) used.add(`${source.id}/${source.version}`);
    for (const item of await fs.readdir(this.path('sources'), { withFileTypes: true })) {
      if (!item.isDirectory() || !idPattern.test(item.name)) continue;
      const directory = `sources/${item.name}`;
      let versions;
      try { versions = await fs.readdir(this.path(directory), { withFileTypes: true }); }
      catch (cause) { if (cause.code === 'ENOENT') continue; throw cause; }
      for (const version of versions) {
        if (!version.isDirectory() || !/^[1-9]\d*$/u.test(version.name)) continue;
        const key = `${item.name}/${version.name}`;
        if (!used.has(key) && !this.pins.has(key) && !this.preparing.has(key)) {
          // 最后同步复核，避免队列提交后仍依据旧的扫描快照清理。
          const current = this.read();
          const held = current.jobs.some(job => job.type === 'export' && activeStates.has(job.state) && job.input.sources.some(source => source.id === item.name && String(source.version) === version.name));
          if (!held && !current.sources.some(row => row.id === item.name && String(row.version) === version.name && row.state !== 'deleted') && !this.pins.has(key) && !this.preparing.has(key)) await this.removePath(`${directory}/${version.name}`);
        }
      }
    }
  }

  async close() {
    if (this.closed) return;
    if (this.closePromise) return this.closePromise;
    this.closing = true;
    for (const controller of this.controllers.values()) controller.abort();
    this.closePromise = (async () => {
      await this.ready.catch(() => {});
      while (this.running.size) await Promise.allSettled([...this.running.values()]);
      await this.queue; this.closed = true;
      await this.domain.close?.();
    })();
    return this.closePromise;
  }
}
