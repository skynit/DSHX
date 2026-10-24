import { createHash, randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { mkdir, open, readFile, rename, stat } from 'node:fs/promises';
import path from 'node:path';
import { setImmediate as yieldTask } from 'node:timers/promises';
import { createModelClient, embeddingInput, embeddingIdentity, modelFailure, normalizeEmbedding } from './knowledge-model.mjs';

export const chunkerVersion = 1;
export const tokenizerVersion = 1;
export const inputTemplateVersion = 1;
const hash = value => createHash('sha256').update(value).digest('hex');
const runtimeRequire = createRequire(new URL('./lib/node_modules/package.json', import.meta.url));
const localRequire = createRequire(import.meta.url);
const keywordCache = new Map();
const generationCache = new Map();
const activeJobs = new WeakMap();
let MiniSearch;
function searchClass() {
  if (MiniSearch) return MiniSearch;
  try { MiniSearch = runtimeRequire('minisearch'); }
  catch { MiniSearch = localRequire('minisearch'); }
  return MiniSearch.default ?? MiniSearch;
}
const check = signal => { if (signal?.aborted) throw modelFailure('CANCELLED'); };
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const normalize = text => String(text ?? '').normalize('NFKC').toLowerCase();
const headingText = heading => Array.isArray(heading) ? heading.join(' / ') : String(heading ?? '');
const rowId = row => `${row.kind ?? 'document'}:${row.sourceId ?? row.id}:${row.sourceVersion ?? ''}:${row.id}`;
const sourceKey = row => `${row.kind ?? 'document'}:${row.sourceId ?? row.id}`;

/** 偏移和行号均针对去除 BOM、统一为 LF 的正文；endOffset 为不含末尾的位置。 */
export function chunkText(text, { sourceId, version, format = 'txt' } = {}) {
  if (typeof text !== 'string' || !text.replace(/^\uFEFF/, '').trim() || typeof sourceId !== 'string' || !sourceId ||
      !['markdown', 'md', '.md', 'text', 'txt', '.txt', 'text/plain', 'text/markdown'].includes(format)) throw modelFailure('INPUT');
  text = text.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n');
  if (Buffer.byteLength(text) > 2 * 1024 * 1024) throw modelFailure('LIMIT');
  const chars = Array.from(text), lines = [], blocks = [];
  let offset = 0;
  for (const line of text.split('\n')) {
    lines.push({ text: line, start: offset, end: offset + Array.from(line).length });
    offset += Array.from(line).length + 1;
  }
  const markdown = ['markdown', 'md', '.md', 'text/markdown'].includes(format);
  const headings = [];
  let fence, start = 0, blockHeading = [], type;
  function push(end) {
    if (end > start) blocks.push({ start, end, heading: [...blockHeading], type: type ?? 'paragraph' });
    start = end; type = undefined;
  }
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i], end = Math.min(chars.length, line.end + 1);
    const marker = markdown && line.text.match(/^ {0,3}(`{3,}|~{3,})/);
    if (fence) {
      if (marker && marker[1][0] === fence[0] && marker[1].length >= fence.length && /^ {0,3}(?:`+|~+)\s*$/.test(line.text)) {
        fence = undefined; push(end);
      }
      continue;
    }
    if (marker) { push(line.start); blockHeading = headings.map(row => row.title); type = 'code'; fence = marker[1]; continue; }
    let title = markdown && line.text.match(/^ {0,3}(#{1,6})\s+(.+?)\s*#*\s*$/);
    const setext = markdown && i + 1 < lines.length && line.text.trim() && /^ {0,3}(?:=+|-+)\s*$/.test(lines[i + 1].text);
    if (title || setext) {
      push(line.start);
      const level = title ? title[1].length : lines[i + 1].text.trim()[0] === '=' ? 1 : 2;
      while (headings.length && headings.at(-1).level >= level) headings.pop();
      headings.push({ level, title: title ? title[2] : line.text.trim() });
      blockHeading = headings.map(row => row.title); type = 'heading';
      if (setext && !title) { push(Math.min(chars.length, lines[++i].end + 1)); } else push(end);
      continue;
    }
    const nextType = !line.text.trim() ? 'blank' : markdown && /^\s*\|.*\|\s*$/.test(line.text) ? 'table'
      : markdown && /^\s*(?:[-+*]|\d+[.)])\s/.test(line.text) ? 'list' : 'paragraph';
    if (type && nextType !== type) push(line.start);
    blockHeading = headings.map(row => row.title); type = nextType;
    if (nextType === 'blank') push(end);
  }
  push(chars.length);
  const ranges = [];
  for (const block of blocks) {
    let begin = block.start, part = 0;
    while (begin < block.end) {
      let end = Math.min(begin + 800, block.end);
      if (end < block.end) {
        for (let position = end; position >= begin + 300; position--) {
          if (/[\n。！？；.!?;]/u.test(chars[position - 1])) { end = position; break; }
        }
      }
      const previous = ranges.at(-1);
      if (!part && previous && !previous.continued && same(previous.heading, block.heading) && end - previous.start <= 600 &&
          previous.end - previous.start < 450 && block.type !== 'code' && previous.type !== 'code') previous.end = end;
      else ranges.push({ start: begin, end, heading: block.heading, type: block.type, continued: part > 0 });
      // 只在超长块内部重叠，偏移仍指向原文，尾部不重复产生空块。
      begin = end < block.end ? end - 48 : end;
      part++;
    }
  }
  const meaningful = ranges.filter(range => chars.slice(range.start, range.end).join('').trim());
  if (meaningful.length > 500) throw modelFailure('LIMIT');
  const lineAt = value => {
    let low = 0, high = lines.length;
    while (low + 1 < high) { const middle = (low + high) >> 1; if (lines[middle].start <= value) low = middle; else high = middle; }
    return low + 1;
  };
  const chunks = meaningful.map((range, index) => ({
    id: `chunk-${index + 1}`, sourceId, sourceVersion: version, heading: [...range.heading],
    startLine: lineAt(range.start), endLine: lineAt(Math.max(range.start, range.end - 1)),
    startOffset: range.start, endOffset: range.end, text: chars.slice(range.start, range.end).join(''),
    previous: index ? `chunk-${index}` : null, next: index + 1 < meaningful.length ? `chunk-${index + 2}` : null,
    ...(range.continued ? { continued: true } : {}),
  }));
  return chunks;
}

/** 中文双字及短词、完整技术标识和拆解形式共存，索引与查询共用同一版本。 */
export function tokenize(text) {
  const tokens = new Set();
  for (const match of normalize(text).matchAll(/[\p{Script=Han}]+|[\p{L}\p{N}]+(?:[._:+/#-][\p{L}\p{N}]+)*/gu)) {
    const word = match[0];
    if (/\p{Script=Han}/u.test(word)) {
      const chars = Array.from(word);
      if (chars.length <= 12) tokens.add(word);
      for (let i = 0; i + 1 < chars.length; i++) tokens.add(chars[i] + chars[i + 1]);
    } else {
      tokens.add(word);
      for (const part of word.split(/[._:+/#-]/)) if (part) tokens.add(part);
    }
  }
  return [...tokens];
}
const indexOptions = () => ({ fields: ['title', 'heading', 'text'], tokenize, processTerm: term => term,
  searchOptions: { boost: { title: 4, heading: 2, text: 1 }, prefix: term => /^[a-z]{3,}$/.test(term),
    fuzzy: term => /^[a-z]{5,}$/.test(term) ? 0.15 : false, combineWith: 'OR' } });

export function keywordSearch(rows, query, { limit = 20 } = {}) {
  if (!Array.isArray(rows) || typeof query !== 'string') throw modelFailure('INPUT');
  if (rows.length > 10000 || Array.from(query).length > 500) throw modelFailure('LIMIT');
  limit = Math.max(0, Math.min(20, Math.floor(limit)));
  const terms = tokenize(query), phrase = normalize(query).trim();
  if (!phrase || !rows.length || !limit) return [];
  const fingerprint = hash(JSON.stringify(rows.map(row => [rowId(row), row.title, row.heading, row.text])));
  let index = keywordCache.get(fingerprint), scores;
  try {
    if (!index) {
      const Search = searchClass();
      index = new Search(indexOptions());
      index.addAll(rows.map((row, id) => ({ id, title: row.title ?? '', heading: headingText(row.heading), text: row.text ?? '' })));
      if (keywordCache.size >= 2) keywordCache.delete(keywordCache.keys().next().value);
      keywordCache.set(fingerprint, index);
    }
    scores = new Map(index.search(query).map(result => [result.id, result.score]));
  } catch {
    // 派生索引损坏或依赖加载失败时，已过滤的有限正文仍可查找。
    scores = new Map();
    for (let i = 0; i < rows.length; i++) {
      const words = new Set(tokenize(`${rows[i].title ?? ''}\n${headingText(rows[i].heading)}\n${rows[i].text ?? ''}`));
      const score = terms.filter(term => words.has(term)).length;
      if (score) scores.set(i, score);
    }
  }
  // 单汉字及精确技术短语也参与匹配，不依赖模糊词扩展。
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i];
    const boost = (normalize(row.title).includes(phrase) ? 12 : 0) + (normalize(headingText(row.heading)).includes(phrase) ? 6 : 0)
      + (normalize(row.text).includes(phrase) ? 3 : 0);
    if (boost) scores.set(i, (scores.get(i) ?? 0) + boost);
  }
  return [...scores].map(([index, score]) => ({ row: rows[index], score })).sort((a, b) => b.score - a.score || rowId(a.row).localeCompare(rowId(b.row))).slice(0, limit);
}

export function vectorKey(row, config) {
  const normalized = normalizeEmbedding(config);
  return hash(JSON.stringify([row.kind ?? 'document', row.sourceId ?? row.id, row.sourceVersion,
    hash(embeddingInput(row, normalized)), embeddingIdentity(normalized), normalized.inputTemplateVersion]));
}
function vectorRecord(row, config, value) {
  if (!value || value.modelConfigId !== embeddingIdentity(config) || value.inputTemplateVersion !== config.inputTemplateVersion ||
      value.inputTextHash !== hash(embeddingInput(row, config)) || value.sourceId !== (row.sourceId ?? row.id) ||
      value.sourceVersion !== row.sourceVersion) return;
  const vector = value.vector;
  if (!(Array.isArray(vector) || vector instanceof Float32Array) || vector.length !== config.dimensions ||
      !Array.from(vector).every(Number.isFinite)) return;
  const norm = Math.sqrt(Array.from(vector).reduce((sum, number) => sum + number * number, 0));
  if (!Number.isFinite(norm) || norm === 0 || config.normalized && Math.abs(norm - 1) > 0.001) return;
  return vector;
}
function selectResults(results, limit) {
  const selected = [], sources = new Map();
  for (const candidate of results) {
    const key = sourceKey(candidate.row);
    if ((sources.get(key) ?? 0) >= 3) continue;
    const overlap = selected.some(old => {
      if (sourceKey(old.row) !== key || old.row.sourceVersion !== candidate.row.sourceVersion) return false;
      const a = old.row, b = candidate.row;
      if (Number.isFinite(a.startOffset) && Number.isFinite(b.startOffset)) {
        return Math.max(0, Math.min(a.endOffset, b.endOffset) - Math.max(a.startOffset, b.startOffset)) >= Math.min(a.endOffset - a.startOffset, b.endOffset - b.startOffset) * 0.5;
      }
      return a.id === b.id;
    });
    if (overlap) continue;
    selected.push(candidate); sources.set(key, (sources.get(key) ?? 0) + 1);
    if (selected.length >= limit) break;
  }
  return selected;
}

/** embedding={...配置,vectors:Map<缓存键,记录>,embedQuery(query,{signal})}。不接受裸向量冒充兼容缓存。 */
export async function hybridSearch(rows, query, { embedding, signal, limit = 20, ...options } = {}) {
  check(signal);
  const keyword = keywordSearch(rows, query, { limit: 20 });
  const fallback = (reason, indexed = 0) => ({ mode: 'keyword', coverage: { eligible: rows.length, indexed }, degradedReason: reason,
    results: selectResults(keyword.map(hit => ({ ...hit, matchedBy: ['keyword'] })), Math.max(1, Math.min(20, limit))) });
  if (!embedding?.enabled) return fallback('未启用语义检索');
  let config;
  try { config = normalizeEmbedding(embedding); } catch { return fallback('嵌入配置不可用'); }
  const vectors = [];
  let indexed = 0;
  for (const row of rows) {
    const value = embedding.vectors instanceof Map ? embedding.vectors.get(vectorKey(row, config)) : embedding.vectors?.[vectorKey(row, config)];
    const vector = config.kinds.includes(row.kind ?? 'document') && vectorRecord(row, config, value);
    vectors.push(vector); if (vector) indexed++;
  }
  if (indexed !== rows.length) return fallback('当前查询范围的语义索引尚未完整覆盖', indexed);
  if (!rows.length || !query.trim()) return fallback('当前范围没有可检索内容', indexed);
  if (typeof embedding.embedQuery !== 'function') return fallback('查询嵌入服务不可用', indexed);
  let queryVector;
  try { queryVector = await embedding.embedQuery(query, { ...options, signal }); }
  catch (error) { check(signal); return fallback(error?.code === 'KNOWLEDGE_LIMIT' ? '本次模型请求额度已用完' : '查询向量不可用，已使用关键词检索', indexed); }
  check(signal);
  if (!(Array.isArray(queryVector) || queryVector instanceof Float32Array) || queryVector.length !== config.dimensions ||
      !Array.from(queryVector).every(Number.isFinite)) return fallback('查询向量格式与索引不兼容', indexed);
  const norm = Math.sqrt(Array.from(queryVector).reduce((sum, value) => sum + value * value, 0));
  if (!norm || !Number.isFinite(norm)) return fallback('查询向量无效', indexed);
  const semantic = [];
  for (let i = 0; i < rows.length; i++) {
    if (i % 64 === 0) { await yieldTask(); check(signal); }
    let dot = 0, length = 0;
    for (let j = 0; j < queryVector.length; j++) { dot += queryVector[j] * vectors[i][j]; length += vectors[i][j] * vectors[i][j]; }
    semantic.push({ row: rows[i], score: dot / (Math.sqrt(length) * norm) });
  }
  semantic.sort((a, b) => b.score - a.score || rowId(a.row).localeCompare(rowId(b.row)));
  const merged = new Map();
  for (const [kind, hits] of [['keyword', keyword], ['semantic', semantic.slice(0, 20)]]) {
    hits.forEach((hit, index) => {
      const key = rowId(hit.row), old = merged.get(key) ?? { row: hit.row, score: 0, matchedBy: [] };
      old.score += 1 / (60 + index + 1); old.matchedBy.push(kind); merged.set(key, old);
    });
  }
  check(signal);
  return { mode: 'hybrid', coverage: { eligible: rows.length, indexed },
    results: selectResults([...merged.values()].sort((a, b) => b.score - a.score || rowId(a.row).localeCompare(rowId(b.row))), Math.max(1, Math.min(20, limit))) };
}

async function writeJSON(filename, value) {
  await mkdir(path.dirname(filename), { recursive: true, mode: 0o700 });
  const temporary = `${filename}.${randomUUID()}.tmp`;
  const file = await open(temporary, 'wx', 0o600);
  try { await file.writeFile(JSON.stringify(value)); await file.sync(); } finally { await file.close(); }
  await rename(temporary, filename);
  const directory = await open(path.dirname(filename), 'r');
  try { await directory.sync(); } finally { await directory.close(); }
}
async function readJSON(filename, maxBytes = 32 * 1024 * 1024) {
  if ((await stat(filename)).size > maxBytes) throw modelFailure('INDEX');
  return JSON.parse(await readFile(filename, 'utf8'));
}
function indexDirectory(store, generation) {
  if (typeof generation !== 'string' || !/^[a-zA-Z0-9_-]{1,100}$/.test(generation)) throw modelFailure('INDEX');
  return path.join(store.root, 'indexes', generation);
}
function published(source) { return source.version !== undefined && !['deleted', 'removed', 'missing', 'failed', 'unavailable'].includes(source.state); }
function documentStamp(snapshot) {
  return hash(JSON.stringify(snapshot.sources.filter(published).map(source => [source.id, source.version, source.revision, source.title, source.scope, source.collectionId]).sort((a, b) => String(a[0]).localeCompare(String(b[0])))));
}
function settingsStamp(snapshot, kind) {
  const value = snapshot[kind] ?? {};
  const { active, generation, activeGeneration, ...settings } = value;
  return hash(JSON.stringify([snapshot.enabled, settings]));
}
function rowStamp(rows) {
  return hash(JSON.stringify(rows.map(row => [rowId(row), row.title, row.heading, hash(row.text ?? '')]).sort((a, b) => a[0].localeCompare(b[0]))));
}
async function rowsForStore(store, options, signal) {
  check(signal);
  if (options.getRows) {
    const rows = await options.getRows({ signal, store });
    if (!Array.isArray(rows) || rows.length > 10000) throw modelFailure('LIMIT');
    return rows;
  }
  const rows = [], snapshot = store.read();
  for (const source of snapshot.sources.filter(published)) {
    check(signal);
    for (const chunk of await store.sourceChunks(source)) rows.push({ ...chunk, id: `${source.id}:${chunk.id}`, chunkId: chunk.id,
      sourceId: source.id, sourceVersion: source.version, kind: 'document', title: source.title, text: chunk.text,
      ref: `kb:document:${source.id}:v${source.version}:${chunk.id}` });
  }
  if (options.memoryView) {
    const workspaces = new Set([undefined, options.workspace ?? process.env.DSHX_WORKSPACE,
      ...snapshot.sources.map(source => source.scope?.workspace)].filter(value => value === undefined || typeof value === 'string'));
    const memories = new Map();
    for (const workspace of workspaces) {
      const view = options.memoryView(workspace);
      if (!view?.enabled) continue;
      for (const entry of view.entries ?? []) {
        if (entry.state !== 'active' || entry.expiresAt !== null && entry.expiresAt <= Date.now()) continue;
        memories.set(entry.id, entry);
      }
    }
    for (const row of memories.values()) rows.push({ id: `memory:${row.id}`, sourceId: row.id, sourceVersion: row.updatedAt,
      kind: 'memory', title: row.summary, heading: [], text: row.content, ref: `kb:memory:${row.id}:u${row.updatedAt}` });
  }
  if (rows.length > 10000) throw modelFailure('LIMIT');
  return rows;
}
function jobKind(job) {
  if (job.stage === 'summary') return 'summary';
  if (job.stage === 'embedding') return 'semantic';
  if (['semantic', 'embedding', 'summary', 'keyword'].includes(job.kind)) return job.kind === 'embedding' ? 'semantic' : job.kind;
  if (['semantic', 'embedding', 'summary', 'keyword'].includes(job.type)) return job.type === 'embedding' ? 'semantic' : job.type;
  if (['import', 'update', 'restore'].includes(job.type)) return 'semantic';
  throw modelFailure('INPUT');
}
function currentJob(snapshot, job, kind, expectedSettings, signal) {
  check(signal);
  const current = snapshot.jobs.find(row => row.id === job.id);
  if (!current || current.cancelRequested || current.state === 'cancelled') throw modelFailure('CANCELLED');
  if (current.state !== 'running') throw modelFailure('VERSION_CHANGED');
  if (!snapshot.enabled || kind !== 'keyword' && !snapshot[kind === 'semantic' ? 'embedding' : kind]?.enabled ||
      expectedSettings !== settingsStamp(snapshot, kind === 'semantic' ? 'embedding' : kind)) throw modelFailure('VERSION_CHANGED');
  const sourceId = job.target?.sourceId ?? (typeof job.target === 'string' && snapshot.sources.some(source => source.id === job.target) ? job.target : undefined);
  if (sourceId) {
    const source = snapshot.sources.find(row => row.id === sourceId && published(row));
    if (!source || job.baseVersion != null && source.version !== job.baseVersion || job.baseRevision != null && source.revision !== job.baseRevision ||
        job.input?.source?.scope && !same(source.scope, job.input.source.scope)) throw modelFailure('VERSION_CHANGED');
  }
  if (kind !== 'keyword' && job.configId != null && snapshot[kind === 'semantic' ? 'embedding' : kind]?.configId !== job.configId) throw modelFailure('VERSION_CHANGED');
  return current;
}
async function reserveRequest(store, job, kind, expectedSettings, limit, signal) {
  await store.change(next => {
    const current = currentJob(next, job, kind, expectedSettings, signal);
    const budget = current.modelBudget ?? { limit, used: 0 };
    if (!Number.isSafeInteger(budget.used) || !Number.isSafeInteger(budget.limit) || budget.used >= budget.limit) throw modelFailure('LIMIT');
    current.modelBudget = { limit: budget.limit, used: budget.used + 1 };
    current.stage = kind === 'semantic' ? 'embedding' : kind;
  }, signal);
}

/** 仅加载目录正式指向且完整、文件校验通过的批次；未切换的磁盘目录绝不自行启用。 */
export async function loadVectorGeneration(store, embedding) {
  const generation = embedding?.active?.generation ?? embedding?.activeGeneration ?? embedding?.generation ?? store.read().semanticIndex?.generation ?? store.read().semanticGeneration;
  if (!generation) return;
  const directory = indexDirectory(store, generation);
  const manifest = await readJSON(path.join(directory, 'manifest.json'), 2 * 1024 * 1024);
  if (manifest.schemaVersion !== 1 || manifest.generation !== generation || manifest.complete !== true ||
      !manifest.config || manifest.embeddingConfigId !== embeddingIdentity(manifest.config) || manifest.dimensions !== manifest.config.dimensions ||
      manifest.normalized !== manifest.config.normalized || !Number.isSafeInteger(manifest.count) || manifest.count > 10000) throw modelFailure('INDEX');
  const cacheKey = `${store.root}:${generation}:${hash(JSON.stringify(manifest))}`;
  const cached = generationCache.get(cacheKey);
  if (cached) return cached;
  const metadataBytes = await readFile(path.join(directory, 'records.json'));
  const expectedBytes = manifest.count * manifest.dimensions * 4;
  if (!Number.isSafeInteger(expectedBytes) || expectedBytes > 128 * 1024 * 1024 || (await stat(path.join(directory, 'vectors.f32'))).size !== expectedBytes || metadataBytes.length > 16 * 1024 * 1024) throw modelFailure('INDEX');
  const bytes = await readFile(path.join(directory, 'vectors.f32'));
  if (hash(metadataBytes) !== manifest.files?.['records.json']?.hash || hash(bytes) !== manifest.files?.['vectors.f32']?.hash) throw modelFailure('INDEX');
  const metadata = JSON.parse(metadataBytes.toString('utf8'));
  if (!Array.isArray(metadata) || metadata.length !== manifest.count) throw modelFailure('INDEX');
  const vectors = new Map();
  for (let i = 0; i < metadata.length; i++) {
    const item = metadata[i];
    if (!/^[a-f0-9]{64}$/.test(item.key) || vectors.has(item.key) || item.modelConfigId !== manifest.embeddingConfigId) throw modelFailure('INDEX');
    const vector = new Float32Array(manifest.dimensions);
    for (let j = 0; j < vector.length; j++) vector[j] = bytes.readFloatLE((i * vector.length + j) * 4);
    if (!Array.from(vector).every(Number.isFinite)) throw modelFailure('INDEX');
    vectors.set(item.key, { ...item, vector });
  }
  const result = { ...manifest, vectors };
  if (generationCache.size >= 1) generationCache.clear();
  generationCache.set(cacheKey, result);
  return result;
}
async function publishVectors(directory, generation, records, config, inputVersions) {
  const metadata = records.map(({ vector, ...row }) => row);
  const bytes = Buffer.allocUnsafe(records.length * config.dimensions * 4);
  for (let i = 0; i < records.length; i++) for (let j = 0; j < config.dimensions; j++) bytes.writeFloatLE(records[i].vector[j], (i * config.dimensions + j) * 4);
  const file = await open(path.join(directory, 'vectors.f32'), 'w', 0o600);
  try { await file.writeFile(bytes); await file.sync(); } finally { await file.close(); }
  await writeJSON(path.join(directory, 'records.json'), metadata);
  const metadataBytes = await readFile(path.join(directory, 'records.json'));
  const manifest = { schemaVersion: 1, generation, inputVersions, embeddingConfigId: embeddingIdentity(config), config,
    dimensions: config.dimensions, normalized: config.normalized, inputTemplateVersion: config.inputTemplateVersion,
    count: records.length, files: { 'records.json': { hash: hash(metadataBytes), bytes: metadataBytes.length },
      'vectors.f32': { hash: hash(bytes), bytes: bytes.length } }, complete: true };
  await writeJSON(path.join(directory, 'manifest.json'), manifest);
  return manifest;
}
function inputVersions(rows) {
  return [...new Map(rows.map(row => [`${row.kind ?? 'document'}:${row.sourceId ?? row.id}`, {
    kind: row.kind ?? 'document', sourceId: row.sourceId ?? row.id, sourceVersion: row.sourceVersion,
  }])).values()].sort((a, b) => `${a.kind}:${a.sourceId}`.localeCompare(`${b.kind}:${b.sourceId}`));
}

async function embedRows(rows, config, client, request, directory, options) {
  const bodySize = inputs => Buffer.byteLength(JSON.stringify({ model: config.model, input: inputs, encoding_format: 'float', ...(config.sendDimensions ? { dimensions: config.dimensions } : {}) }));
  const count = async text => options.countTokens ? options.countTokens(text, config) : Buffer.byteLength(text);
  const totals = rows.map(() => ({ vector: new Float64Array(config.dimensions), weight: 0 }));
  let batch = [];
  async function flush() {
    if (!batch.length) return;
    const entries = batch; batch = [];
    const key = hash(JSON.stringify([embeddingIdentity(config), entries.map(entry => hash(entry.text))]));
    const filename = path.join(directory, `pieces-${key}.json`);
    let vectors;
    try {
      const saved = await readJSON(filename, 16 * 1024 * 1024);
      if (saved.key === key && saved.hash === hash(JSON.stringify(saved.vectors)) && Array.isArray(saved.vectors) &&
          saved.vectors.length === entries.length && saved.vectors.every(vector => Array.isArray(vector) && vector.length === config.dimensions && vector.every(Number.isFinite))) vectors = saved.vectors;
    } catch { /* 逐次请求的完整结果可在批次中断后继续，不复用半份响应。 */ }
    if (!vectors) {
      vectors = (await client.embed(entries.map(entry => entry.text), config, request)).map(vector => Array.from(vector));
      await request.validate?.(); check(request.signal);
      await writeJSON(filename, { key, vectors, hash: hash(JSON.stringify(vectors)) });
    }
    for (let i = 0; i < entries.length; i++) {
      const target = totals[entries[i].index], weight = Array.from(entries[i].text).length;
      for (let j = 0; j < target.vector.length; j++) target.vector[j] += vectors[i][j] * weight;
      target.weight += weight;
    }
  }
  for (let index = 0; index < rows.length; index++) {
    const chars = Array.from(embeddingInput(rows[index], config));
    let offset = 0;
    while (offset < chars.length) {
      check(request.signal);
      let low = 1, high = chars.length - offset, size = 0;
      while (low <= high) {
        const middle = (low + high) >> 1, text = chars.slice(offset, offset + middle).join('');
        if (await count(text) <= config.maxInputTokens && bodySize([text]) <= config.maxRequestBytes) { size = middle; low = middle + 1; }
        else high = middle - 1;
      }
      if (!size) throw modelFailure('LIMIT');
      const text = chars.slice(offset, offset + size).join('');
      if (batch.length >= config.batchSize || batch.length && bodySize([...batch.map(entry => entry.text), text]) > config.maxRequestBytes) await flush();
      batch.push({ index, text }); offset += size;
    }
  }
  await flush();
  return totals.map(total => {
    if (!total.weight) throw modelFailure('MODEL');
    const mean = Array.from(total.vector, number => number / total.weight);
    const length = Math.sqrt(mean.reduce((sum, number) => sum + number * number, 0));
    if (!length || !Number.isFinite(length)) throw modelFailure('MODEL');
    return Float32Array.from(mean, number => config.normalized ? number / length : number);
  });
}

async function semanticJob(ctx, store, job, options, client, signal) {
  const snapshot = store.read(), stamp = settingsStamp(snapshot, 'embedding');
  currentJob(snapshot, job, 'semantic', stamp, signal);
  const resolved = await client.prepare(snapshot.embedding, signal), config = resolved.config;
  if (!config.enabled) throw modelFailure('VERSION_CHANGED');
  const generation = `g-${hash(`${job.id}:${resolved.identity}`).slice(0, 48)}`;
  const directory = indexDirectory(store, generation);
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const records = new Map();
  // 完整旧批次只提供同配置、同正文缓存复用，不改变新批次的覆盖检查。
  try {
    const old = await loadVectorGeneration(store, snapshot.embedding);
    if (old?.embeddingConfigId === resolved.identity) for (const [key, row] of old.vectors) records.set(key, row);
  } catch { /* 派生缓存损坏只导致重建，不修改正式原文。 */ }
  let stableRows, stableDocuments;
  const requestLimit = Math.max(1, Math.min(1000, options.maxRequests ?? job.requestLimit ?? 650));
  const budget = { limit: requestLimit, used: 0 };
  const validate = async () => { currentJob(store.read(), job, 'semantic', stamp, signal); await options.validate?.(job); };
  // 每轮在队列外比较最新目录；最多补两次差异，持续变化留待用户继续。
  for (let pass = 0; pass < 3; pass++) {
    await validate();
    const view = store.read(), documents = documentStamp(view);
    let rows = await rowsForStore(store, options, signal);
    rows = rows.filter(row => config.kinds.includes(row.kind ?? 'document'));
    if (rows.length * config.dimensions * 4 > 128 * 1024 * 1024) throw modelFailure('LIMIT');
    if (rows.some(row => typeof row.text !== 'string' || row.sourceVersion === undefined) || new Set(rows.map(rowId)).size !== rows.length) throw modelFailure('INPUT');
    const selected = new Map();
    let pending = [];
    async function batch() {
      if (!pending.length) return;
      await validate();
      const entries = pending; pending = [];
      const keys = entries.map(row => vectorKey(row, config));
      const checkpoint = path.join(directory, `batch-${hash(JSON.stringify(keys))}.json`);
      let restored;
      try {
        const saved = await readJSON(checkpoint, 16 * 1024 * 1024);
        if (saved.configId === resolved.identity && same(saved.keys, keys) && Array.isArray(saved.records) && saved.records.length === entries.length &&
            saved.hash === hash(JSON.stringify(saved.records)) && saved.records.every((record, index) => vectorRecord(entries[index], config, record))) restored = saved.records;
      } catch { /* 未完成或损坏批次不复用，其他完整批次继续保留。 */ }
      const result = restored ?? (await embedRows(entries, config, client, {
        signal, budget, identity: resolved.identity, validate,
        beforeAttempt: () => reserveRequest(store, job, 'semantic', stamp, requestLimit, signal),
      }, directory, options)).map((vector, index) => ({ key: keys[index], sourceId: entries[index].sourceId ?? entries[index].id,
        sourceVersion: entries[index].sourceVersion, kind: entries[index].kind ?? 'document', inputTextHash: hash(embeddingInput(entries[index], config)),
        modelConfigId: resolved.identity, inputTemplateVersion: config.inputTemplateVersion, vector: Array.from(vector) }));
      await validate();
      if (!restored) await writeJSON(checkpoint, { configId: resolved.identity, keys, records: result, hash: hash(JSON.stringify(result)) });
      for (let i = 0; i < result.length; i++) { records.set(keys[i], result[i]); selected.set(keys[i], result[i]); }
      await store.change(next => {
        const current = currentJob(next, job, 'semantic', stamp, signal);
        current.stage = 'embedding'; current.progress = { completed: rows.filter(row => selected.has(vectorKey(row, config))).length, total: rows.length }; current.generation = generation;
      }, signal);
      await yieldTask();
    }
    for (const row of rows) {
      const key = vectorKey(row, config), cached = records.get(key);
      if (vectorRecord(row, config, cached)) { selected.set(key, cached); continue; }
      const bodySize = list => Buffer.byteLength(JSON.stringify({ model: config.model, input: list.map(item => embeddingInput(item, config)), encoding_format: 'float', ...(config.sendDimensions ? { dimensions: config.dimensions } : {}) }));
      if (pending.length >= config.batchSize || pending.length && bodySize([...pending, row]) > config.maxRequestBytes) await batch();
      pending.push(row);
    }
    await batch(); await validate();
    const latest = await rowsForStore(store, options, signal);
    const eligible = latest.filter(row => config.kinds.includes(row.kind ?? 'document'));
    if (documents === documentStamp(store.read()) && rowStamp(rows) === rowStamp(eligible)) {
      stableRows = rows; stableDocuments = documents;
      for (const key of records.keys()) if (!selected.has(key)) records.delete(key);
      break;
    }
  }
  if (!stableRows) throw modelFailure('VERSION_CHANGED');
  await validate();
  const latestService = await client.prepare(config, signal);
  if (latestService.identity !== resolved.identity || latestService.credentialStamp !== resolved.credentialStamp) throw modelFailure('VERSION_CHANGED');
  if (stableRows.some(row => !vectorRecord(row, config, records.get(vectorKey(row, config))))) throw modelFailure('INDEX');
  const complete = [...new Map(stableRows.map(row => { const key = vectorKey(row, config); return [key, records.get(key)]; })).values()];
  const manifest = await publishVectors(directory, generation, complete, config, inputVersions(stableRows));
  // 先从磁盘完整读取并校验，再进入短提交；失败时旧有效指向保持不变。
  await loadVectorGeneration(store, { activeGeneration: generation });
  const finalRows = await rowsForStore(store, options, signal);
  if (rowStamp(stableRows) !== rowStamp(finalRows.filter(row => config.kinds.includes(row.kind ?? 'document')))) throw modelFailure('VERSION_CHANGED');
  await store.change(next => {
    const current = currentJob(next, job, 'semantic', stamp, signal);
    if (stableDocuments !== documentStamp(next) || options.isCurrent && !options.isCurrent(stableRows)) throw modelFailure('VERSION_CHANGED');
    next.semanticIndex = { generation, config, embeddingConfigId: manifest.embeddingConfigId, inputVersions: manifest.inputVersions };
    next.semanticGeneration = generation;
    for (const source of next.sources) if (published(source) && stableRows.some(row => row.kind === 'document' && row.sourceId === source.id)) source.embeddingState = 'ready';
    next.catalogRevision++;
    current.stage = 'embedding'; current.effect = 'switched'; current.progress = { completed: stableRows.length, total: stableRows.length };
    // 与正式指向同时落盘，避免切换后中断导致恢复任务重写已启用批次。
    current.completedStages = [...new Set([...(current.completedStages ?? []), 'embedding'])];
    const operation = next.operations?.find(row => row.operationId === job.operationId);
    if (operation) { operation.effect = current.effect; operation.result = { ...operation.result, generation, coverage: { eligible: stableRows.length, indexed: stableRows.length } }; }
  }, signal);
  return { complete: true, effect: 'switched', generation, coverage: { eligible: stableRows.length, indexed: stableRows.length } };
}

async function summarizeCollection(ctx, store, job, options, client, signal, collectionId) {
  const snapshot = store.read(), stamp = settingsStamp(snapshot, 'summary');
  currentJob(snapshot, job, 'summary', stamp, signal);
  const config = snapshot.summary;
  const targetId = typeof job.target === 'string' ? job.target : job.target?.collectionId ?? job.collectionId;
  const target = collectionId ?? snapshot.sources.find(source => source.id === targetId)?.collectionId ?? targetId;
  const collection = snapshot.collections.find(row => row.id === target);
  if (!collection) throw modelFailure('INPUT');
  const ids = new Set([collection.id]);
  for (let depth = 0; depth < 3; depth++) for (const row of snapshot.collections) if (ids.has(row.parentId)) ids.add(row.id);
  const sources = snapshot.sources.filter(source => published(source) && ids.has(source.collectionId));
  const memberVersions = summaryMembers(snapshot, collection);
  const documents = documentStamp(snapshot), parts = [];
  const configId = summaryIdentity(config);
  const directory = path.join(store.root, 'summaries', `c-${hash(collection.id).slice(0, 32)}`, `r-${hash(`${collection.revision}:${configId}:${JSON.stringify(memberVersions)}`).slice(0, 32)}`);
  const allRows = (await rowsForStore(store, options, signal)).filter(row => (row.kind ?? 'document') === 'document' && sources.some(source => source.id === row.sourceId));
  if (allRows.length !== sources.reduce((sum, source) => sum + source.chunkCount, 0) || sources.some(source => allRows.filter(row => row.sourceId === source.id && row.sourceVersion === source.version).length !== source.chunkCount) || new Set(allRows.map(rowId)).size !== allRows.length) throw modelFailure('VERSION_CHANGED');
  const modelInfo = await ctx.llm?.resolveModelInfo?.(config.provider, config.model, signal);
  const contextBytes = modelInfo?.context?.contextWindow === undefined ? 16000 : Math.max(1, modelInfo.context.contextWindow - 2048);
  const maxBytes = Math.max(1, Math.min(16000, config.maxInputBytes ?? 16000, contextBytes));
  let group = [], covered = 0;
  const groups = [];
  for (const row of allRows) {
    const input = `${row.title}\n${headingText(row.heading)}\n${row.text}`;
    let piece = '', bytes = 0;
    const pieces = [];
    for (const char of input) {
      const size = Buffer.byteLength(char);
      if (size > maxBytes) throw modelFailure('LIMIT');
      if (bytes + size > maxBytes) { pieces.push(piece); piece = ''; bytes = 0; }
      piece += char; bytes += size;
    }
    if (piece) pieces.push(piece);
    for (let index = 0; index < pieces.length; index++) {
      const text = pieces[index];
      if (group.length && Buffer.byteLength([...group.map(item => item.text), text].join('\n\n')) > maxBytes) { groups.push(group); group = []; }
      group.push({ row, text, last: index + 1 === pieces.length });
    }
  }
  if (group.length) groups.push(group);
  const validate = async () => {
    const now = store.read(); currentJob(now, job, 'summary', stamp, signal);
    if (documents !== documentStamp(now) || now.collections.find(row => row.id === collection.id)?.revision !== collection.revision) throw modelFailure('VERSION_CHANGED');
    await options.validate?.(job);
  };
  const limit = Math.max(1, Math.min(1000, options.maxRequests ?? job.requestLimit ?? 650));
  for (let i = 0; i < groups.length; i++) {
    await validate();
    const current = groups[i], text = current.map(item => item.text).join('\n\n');
    const filename = path.join(directory, `part-${hash(`${configId}:${text}`)}.json`);
    let output;
    try {
      const saved = await readJSON(filename, 65536);
      if (saved.inputHash === hash(text) && saved.configId === configId && typeof saved.text === 'string' && saved.text.trim() && saved.textHash === hash(saved.text)) output = saved.text;
    } catch { /* 保留并复用已经成功落盘的摘要批次。 */ }
    if (!output) {
      output = await client.summary(text, config, { signal, validate, beforeAttempt: () => reserveRequest(store, job, 'summary', stamp, limit, signal) });
      await validate();
      await writeJSON(filename, { inputHash: hash(text), configId, text: output, textHash: hash(output) });
    }
    covered += current.filter(item => item.last).length;
    parts.push({ text: output, refs: current.map(({ row }) => ({ sourceId: row.sourceId, sourceVersion: row.sourceVersion, chunkId: row.chunkId ?? row.id })) });
    await store.change(next => {
      const currentJobRow = currentJob(next, job, 'summary', stamp, signal);
      currentJobRow.progress = { completed: covered, total: allRows.length }; currentJobRow.stage = 'summary';
    }, signal);
  }
  await validate();
  const result = { schemaVersion: 1, generated: true, collectionId: collection.id, collectionRevision: collection.revision,
    memberVersions, modelConfigId: configId, promptVersion: 1, coveredCount: covered, totalCount: allRows.length, state: 'ready',
    title: collection.title, text: parts.map(part => part.text).join('\n\n'), parts, sources: memberVersions };
  if (Buffer.byteLength(JSON.stringify(result)) > 2 * 1024 * 1024) throw modelFailure('LIMIT');
  await writeJSON(path.join(directory, 'overview.json'), result);
  const fileHash = hash(await readFile(path.join(directory, 'overview.json')));
  await store.change(next => {
    const current = currentJob(next, job, 'summary', stamp, signal);
    if (documentStamp(next) !== documents) throw modelFailure('VERSION_CHANGED');
    const targetCollection = next.collections.find(row => row.id === collection.id);
    if (targetCollection?.revision !== collection.revision) throw modelFailure('VERSION_CHANGED');
    targetCollection.summaryState = 'ready';
    targetCollection.summary = { collectionRevision: collection.revision, memberVersions, modelConfigId: configId, promptVersion: 1,
      coveredCount: covered, totalCount: allRows.length, state: 'ready', fileHash, stamp: knowledgeOverviewStamp(result), path: path.relative(store.root, path.join(directory, 'overview.json')) };
    next.catalogRevision++; current.effect = 'switched'; current.stage = 'summary';
    const operation = next.operations?.find(row => row.operationId === job.operationId);
    if (operation) { operation.effect = current.effect; operation.result = { ...operation.result, collectionId: collection.id, coveredCount: covered, totalCount: allRows.length }; }
  }, signal);
  return { complete: true, effect: 'switched', summary: result };
}

async function summaryJob(ctx, store, job, options, client, signal) {
  const snapshot = store.read();
  const target = typeof job.target === 'string' ? job.target : job.target?.collectionId ?? job.collectionId;
  let collection = snapshot.collections.find(row => row.id === (snapshot.sources.find(source => source.id === target)?.collectionId ?? target));
  if (!collection) throw modelFailure('INPUT');
  const summaries = [], visited = new Set();
  while (collection && summaries.length < 3) {
    if (visited.has(collection.id)) throw modelFailure('INPUT');
    visited.add(collection.id);
    summaries.push((await summarizeCollection(ctx, store, job, options, client, signal, collection.id)).summary);
    collection = store.read().collections.find(row => row.id === collection.parentId);
  }
  return { complete: true, effect: 'switched', summary: summaries[0], summaries };
}

function summaryIdentity(config) {
  return hash(JSON.stringify([config.provider ?? config.providerId, config.model ?? config.modelId, config.configId ?? '', config.promptVersion ?? 1, 1]));
}
function summaryMembers(snapshot, collection) {
  const ids = new Set([collection.id]);
  for (let depth = 0; depth < 3; depth++) for (const row of snapshot.collections) if (ids.has(row.parentId)) ids.add(row.id);
  return snapshot.sources.filter(source => published(source) && ids.has(source.collectionId)).map(source => ({
    sourceId: source.id, sourceVersion: source.version, revision: source.revision, scope: source.scope, collectionId: source.collectionId,
  })).sort((a, b) => a.sourceId.localeCompare(b.sourceId));
}
/** 摘要引用应绑定此指纹；同一集合重算的不同产物也不会共用旧引用。 */
export function knowledgeOverviewStamp(overview) {
  return hash(JSON.stringify([overview.collectionId, overview.collectionRevision, overview.modelConfigId, overview.promptVersion,
    overview.memberVersions, hash(overview.text ?? '')]));
}

/** 返回完整验证的生成概览；缓存损坏、成员变化或用途关闭均回退真实目录导航。 */
export async function readKnowledgeOverview(store, collection, { signal } = {}) {
  check(signal);
  try {
    const snapshot = store.read(), current = snapshot.collections.find(row => row.id === collection.id);
    if (!snapshot.enabled || !snapshot.summary?.enabled || current?.revision !== collection.revision || current.summaryState !== 'ready') return null;
    const record = current.summary;
    if (!record || record.state !== 'ready' || record.collectionRevision !== current.revision || record.modelConfigId !== summaryIdentity(snapshot.summary) ||
        record.promptVersion !== 1 || record.coveredCount !== record.totalCount || !/^[a-f0-9]{64}$/.test(record.fileHash ?? '') ||
        !/^summaries\/c-[a-f0-9]{32}\/r-[a-f0-9]{32}\/overview\.json$/.test(record.path ?? '')) return null;
    const members = summaryMembers(snapshot, current);
    if (!same(record.memberVersions, members)) return null;
    // Store.file 检查各级目录及 O_NOFOLLOW，拒绝符号链接与越界。
    let bytes;
    if (typeof store.file === 'function') bytes = await store.file(record.path, 2 * 1024 * 1024);
    else {
      const { lstat, realpath } = await import('node:fs/promises');
      const target = path.join(store.root, record.path);
      for (const part of [store.root, path.dirname(path.dirname(target)), path.dirname(target), target]) if ((await lstat(part)).isSymbolicLink()) return null;
      if (await realpath(target) !== target || (await stat(target)).size > 2 * 1024 * 1024) return null;
      bytes = await readFile(target);
    }
    check(signal);
    if (hash(bytes) !== record.fileHash) return null;
    const overview = JSON.parse(bytes.toString('utf8'));
    if (overview.schemaVersion !== 1 || overview.generated !== true || overview.state !== 'ready' || overview.collectionId !== current.id ||
        overview.collectionRevision !== current.revision || overview.modelConfigId !== record.modelConfigId || overview.promptVersion !== 1 ||
        overview.coveredCount !== record.coveredCount || overview.totalCount !== record.totalCount || overview.coveredCount !== overview.totalCount ||
        typeof overview.text !== 'string' || !same(overview.memberVersions, members) || !same(overview.sources, members) ||
        knowledgeOverviewStamp(overview) !== record.stamp) return null;
    const latest = store.read(), latestCollection = latest.collections.find(row => row.id === current.id);
    if (!latest.enabled || !latest.summary?.enabled || summaryIdentity(latest.summary) !== record.modelConfigId || latestCollection?.summaryState !== 'ready' ||
        latestCollection.revision !== current.revision || latestCollection.summary?.stamp !== record.stamp || !same(summaryMembers(latest, latestCollection), members)) return null;
    return { ...overview, stamp: record.stamp, fileHash: record.fileHash };
  } catch { check(signal); return null; }
}

/** 存储工作队列调用此入口；本函数只更新加工进度和有效指向，终态由存储队列统一记录。 */
export async function processKnowledgeJob(ctx, store, job, options = {}) {
  if (!store?.root || !job?.id) throw modelFailure('INPUT');
  let running = activeJobs.get(store);
  if (!running) { running = new Set(); activeJobs.set(store, running); }
  if (running.has(job.id)) throw modelFailure('BUSY');
  running.add(job.id);
  const client = options.client ?? createModelClient(ctx, { ...options, usageDirectory: options.usageDirectory ?? process.env.DSHX_ASSISTANT_DIR ?? store.root });
  try {
    const signals = [options.signal, job.signal].filter(Boolean);
    const kind = jobKind(job), signal = signals.length ? AbortSignal.any(signals) : undefined;
    if (kind === 'semantic') return await semanticJob(ctx, store, job, options, client, signal);
    if (kind === 'summary') return await summaryJob(ctx, store, job, options, client, signal);
    const snapshot = store.read(), stamp = settingsStamp(snapshot, 'keyword');
    currentJob(snapshot, job, 'keyword', stamp, signal);
    const documents = documentStamp(snapshot), rows = await rowsForStore(store, options, signal);
    const Search = searchClass(), index = new Search(indexOptions());
    index.addAll(rows.map((row, id) => ({ id, title: row.title ?? '', heading: headingText(row.heading), text: row.text })));
    const generation = `k-${hash(`${job.id}:${rowStamp(rows)}`).slice(0, 48)}`;
    const filename = path.join(indexDirectory(store, generation), 'keyword.json');
    const saved = { schemaVersion: 1, tokenizerVersion, inputVersions: inputVersions(rows), fingerprint: rowStamp(rows), index: index.toJSON() };
    const restored = Search.loadJSON(JSON.stringify(saved.index), indexOptions());
    if (restored.documentCount !== rows.length) throw modelFailure('INDEX');
    await writeJSON(filename, saved);
    await store.change(next => {
      const current = currentJob(next, job, 'keyword', stamp, signal);
      if (documents !== documentStamp(next)) throw modelFailure('VERSION_CHANGED');
      current.stage = 'keyword'; current.progress = { completed: rows.length, total: rows.length };
    }, signal);
    return { complete: true, generation, coverage: { eligible: rows.length, indexed: rows.length } };
  } finally { running.delete(job.id); if (!options.client) client.close(); }
}

export function createKnowledgeSearch(ctx, store, options = {}) {
  let closed = false;
  const controller = new AbortController();
  const client = createModelClient(ctx, { ...options, usageDirectory: options.usageDirectory ?? process.env.DSHX_ASSISTANT_DIR ?? store.root });
  const pending = new Set(), off = [];
  let revision = 0, serviceController = new AbortController();
  for (const event of ['llm/adapters-updated', 'loader/volatile-update', 'credentials/reference-updated', 'credentials/record-updated']) {
    if (ctx.on) off.push(ctx.on(event, () => { revision++; serviceController.abort(); serviceController = new AbortController(); }));
  }
  function signalOf(signal) { return AbortSignal.any([controller.signal, serviceController.signal, ...(signal ? [signal] : [])]); }
  function track(work) { pending.add(work); work.finally(() => pending.delete(work)).catch(() => {}); return work; }
  return {
    search(rows, query, request = {}) {
      return track((async () => {
        const signal = signalOf(request.signal); check(signal);
        if (closed) throw modelFailure('CANCELLED');
        const snapshot = store.read(), configuration = snapshot.embedding, docs = documentStamp(snapshot), settings = settingsStamp(snapshot, 'embedding'), epoch = revision;
        const validate = async () => {
          check(signal);
          const latest = store.read();
          if (closed || revision !== epoch || docs !== documentStamp(latest) || settings !== settingsStamp(latest, 'embedding')) throw modelFailure('VERSION_CHANGED');
          await request.validate?.();
        };
        let embedding = configuration;
        if (configuration?.enabled) {
          try {
            const active = await loadVectorGeneration(store, configuration);
            if (active) {
              const resolved = await client.prepare(configuration, signal);
              if (resolved.identity === active.embeddingConfigId) embedding = { ...resolved.config, vectors: active.vectors,
                embedQuery: (text, input) => client.embedQuery(text, resolved.config, { ...input, validate, budget: request.budget }) };
            }
          } catch { check(signal); /* 关键词回退仍保留所有允许候选。 */ }
        }
        const result = await hybridSearch(rows, query, { ...request, embedding, signal });
        await validate();
        return result;
      })());
    },
    overview(collection, request = {}) {
      return track(readKnowledgeOverview(store, collection, { ...request, signal: signalOf(request.signal) }));
    },
    process(job, jobStore = store, request = {}) {
      const signals = [controller.signal, serviceController.signal, options.signal, request.signal, job.signal].filter(Boolean), epoch = revision;
      const validate = async () => {
        if (closed || revision !== epoch) throw modelFailure('VERSION_CHANGED');
        await options.validate?.(job); await request.validate?.(job);
      };
      return track(processKnowledgeJob(ctx, jobStore, job, { ...options, ...request, client, validate, signal: AbortSignal.any(signals) }));
    },
    async close() {
      closed = true; controller.abort(); client.close();
      for (const dispose of off) if (typeof dispose === 'function') dispose();
      await Promise.allSettled([...pending]);
    },
  };
}
