import { randomUUID, createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { policyHost } from './host.mjs';
import { memoryView } from './memory.mjs';
import { memorySources } from './memory-extract.mjs';
import { readBudget } from './read-budget.mjs';
import { openKnowledge, knowledgeFailure } from './knowledge-store.mjs';
import { keywordSearch, createKnowledgeSearch } from './knowledge-search.mjs';

export const name = 'dshx-android-knowledge';
export const inject = ['storageDomain', 'sessions', 'workingDirectory', 'tools', 'agents', 'llm'];
const require = createRequire(new URL('./lib/node_modules/package.json', import.meta.url));
const names = new Set(['knowledge_list', 'knowledge_search', 'knowledge_read']);
const kinds = new Set(['all', 'document', 'memory']);
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const chars = value => [...value];
const bytes = value => Buffer.byteLength(JSON.stringify(value));
const digest = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
let owner;

export const isKnowledgeTool = tool => names.has(tool);
export const knowledgeToolAvailable = (tool, agent) => isKnowledgeTool(tool) && Boolean(owner?.active(agent));
export function knowledgeError(error) {
  return ({
    KNOWLEDGE_INPUT: '知识库请求格式或文件内容无效，请检查后重试。',
    KNOWLEDGE_NOT_READY: '知识库尚未就绪或来源已关闭，请先启动引擎并检查设置。',
    KNOWLEDGE_CONFLICT: '资料已发生变化，请刷新后重新编辑；当前草稿可以保留。',
    KNOWLEDGE_MISSING: '资料已删除或当前无法访问，请重新查询。',
    KNOWLEDGE_VERSION_CHANGED: '资料、范围或分页已更新，请重新查询。',
    KNOWLEDGE_BUSY: '这份资料已有任务正在处理，请稍后重试。',
    KNOWLEDGE_LIMIT: '已达到知识库容量或本次问题的读取额度，请缩小范围或提出新的问题。',
    KNOWLEDGE_WRITE: '知识库保存失败，当前资料已保留，请检查可用空间。',
    KNOWLEDGE_READ: '知识库数据无法校验，原文件已保留，请检查备份。',
    KNOWLEDGE_INDEX: '知识索引不可用，资料保留，可重建索引后重试。',
    KNOWLEDGE_MODEL: '所选知识处理服务不可用，请检查模型和用途设置。',
    KNOWLEDGE_CANCELLED: '本次等待或任务已取消，请查询操作状态确认实际结果。',
    KNOWLEDGE_OPERATION_EXPIRED: '操作已超过重试期限，请先核对当前状态。',
  })[error?.code] ?? '知识库请求未完成，请查看任务状态后重试。';
}

function check(value, allowed) {
  if (!object(value) || Object.keys(value).some(key => !allowed.includes(key))) throw knowledgeFailure('KNOWLEDGE_INPUT');
}
function integer(value, fallback, maximum) {
  if (value === undefined) return fallback;
  if (!Number.isSafeInteger(value) || value < 1 || value > maximum) throw knowledgeFailure('KNOWLEDGE_INPUT');
  return value;
}
function text(value, maximum = 500) {
  if (typeof value !== 'string' || !value.trim() || chars(value).length > maximum || value.includes('\0')) throw knowledgeFailure('KNOWLEDGE_INPUT');
  return value.trim();
}
const allowedScope = (scope, workspace) => scope?.kind === 'user' || scope?.kind === 'workspace' && scope.workspace === workspace;
const documentRef = (source, chunk) => `kb:document:${source.id}:v${source.version}:${chunk?.id ?? 'text'}`;
const collectionRef = collection => `kb:collection:${collection.id}:r${collection.revision}`;
const memoryRef = row => `kb:memory:${row.id}:u${row.updatedAt}`;
const liveSource = source => !['deleted', 'removed', 'missing', 'unavailable'].includes(source.state);

export function knowledgeSession(ctx, options = {}) {
  let store, runtime, error, closePromise, closed = false, serviceRevision = 0;
  const pages = new Map(), pending = new Set(), pagination = new WeakMap();
  const controller = new AbortController();
  const memory = options.memory ?? { view: memoryView };
  const off = [];
  const ready = (async () => {
    try {
      store = options.store ?? await openKnowledge(ctx, { ...options,
        process: (job, jobStore, request) => runtime.process(job, jobStore, request) });
      runtime = createKnowledgeSearch(ctx, store, { ...options, memoryView: workspace => memory.view?.(workspace) });
      await store.ready;
    }
    catch (cause) { error = cause; }
  })();
  function track(signal, run) {
    if (closed) return Promise.reject(knowledgeFailure('KNOWLEDGE_NOT_READY'));
    const combined = signal ? AbortSignal.any([controller.signal, signal]) : controller.signal;
    const work = run(combined);
    pending.add(work);
    work.then(() => pending.delete(work), () => pending.delete(work));
    return work;
  }
  const budget = readBudget(ctx, { capacity: options.capacity, failure: code => knowledgeFailure(`KNOWLEDGE_${code}`) });
  const sessionFor = budget.sessionFor;
  function memorySnapshot(workspace) {
    const view = memory.view?.(workspace);
    return view ?? { enabled: false, revision: 0, entries: [] };
  }
  function active(agent) {
    if (closed || error || !store) return false;
    try {
      const workspace = agent ? sessionFor(agent).workspace : process.env.DSHX_WORKSPACE;
      return store.read().enabled || memorySnapshot(workspace).enabled;
    } catch { return false; }
  }
  function view(workspace, kind = 'all', management = false) {
    if (!kinds.has(kind)) throw knowledgeFailure('KNOWLEDGE_INPUT');
    const snapshot = store.read(), memories = memorySnapshot(workspace);
    const documents = management || snapshot.enabled;
    return {
      snapshot, memories,
      sources: kind !== 'memory' && documents ? snapshot.sources.filter(row => liveSource(row) && (management || allowedScope(row.scope, workspace))) : [],
      collections: kind !== 'memory' && documents ? snapshot.collections.filter(row => management || allowedScope(row.scope, workspace)) : [],
      entries: kind !== 'document' && memories.enabled ? memories.entries.filter(row => row.state === 'active' && (row.expiresAt === null || row.expiresAt > Date.now()) && allowedScope(row.scope, workspace)) : [],
      disabledKinds: [...documents ? [] : ['document'], ...memories.enabled ? [] : ['memory']],
    };
  }
  function stamp(current) {
    return digest([current.snapshot.enabled, current.snapshot.catalogRevision, current.snapshot.settingsRevision, current.memories.revision,
      current.memories.enabled, current.entries.map(row => [row.id, row.updatedAt, row.expiresAt]),
      current.snapshot.embedding, current.snapshot.summary, serviceRevision]);
  }
  function narrow(current, ref) {
    if (!ref) return current;
    const collection = current.collections.find(row => collectionRef(row) === ref);
    const source = current.sources.find(row => documentRef(row) === ref);
    if (source) return { ...current, sources: [source], collections: [], entries: [] };
    if (!collection) throw knowledgeFailure('KNOWLEDGE_VERSION_CHANGED');
    const ids = new Set([collection.id]);
    for (let depth = 0; depth < 3; depth++) for (const row of current.collections) if (ids.has(row.parentId)) ids.add(row.id);
    return { ...current, sources: current.sources.filter(row => ids.has(row.collectionId)), collections: current.collections.filter(row => ids.has(row.id)), entries: [] };
  }
  function navigationRef(collection, current) {
    const sources = narrow(current, collectionRef(collection)).sources;
    return `${collectionRef(collection)}:overview:n${digest(sources.map(row => [row.id, row.version, row.revision]))}`;
  }
  const overviewRef = (collection, overview) => `kb:overview:${collection.id}:r${collection.revision}:s${overview.stamp}`;
  async function rowsFor(current, signal) {
    const rows = [];
    for (const source of current.sources) {
      signal?.throwIfAborted();
      const chunks = await store.sourceChunks(source);
      for (const chunk of chunks) rows.push({ ...chunk, id: `${source.id}:${chunk.id}`, chunkId: chunk.id, sourceId: source.id,
        sourceVersion: source.version, kind: 'document', title: source.title, note: source.note ?? '',
        ref: documentRef(source, chunk), text: chunk.text, updatedAt: source.updatedAt });
    }
    for (const row of current.entries) rows.push({ id: `memory:${row.id}`, sourceId: row.id, sourceVersion: row.updatedAt,
      kind: 'memory', title: row.summary, heading: [], text: row.content, ref: memoryRef(row), updatedAt: row.updatedAt });
    return rows;
  }
  function ensure(workspace, kind, expected, signal, management) {
    signal?.throwIfAborted();
    if (closed) throw knowledgeFailure('KNOWLEDGE_NOT_READY');
    if (stamp(view(workspace, kind, management)) !== expected) throw knowledgeFailure('KNOWLEDGE_VERSION_CHANGED');
  }
  function pageFor(args, scope, revision, tool) {
    const now = Date.now();
    for (const [id, page] of pages) if (page.expiresAt <= now) pages.delete(id);
    const query = digest([tool, { ...args, cursor: undefined }]);
    if (args.cursor !== undefined) {
      const page = pages.get(args.cursor);
      if (!page || page.scope !== scope || page.revision !== revision || page.query !== query) throw knowledgeFailure('KNOWLEDGE_VERSION_CHANGED');
      return page;
    }
    return { scope, revision, query, offset: 0, expiresAt: now + 5 * 60 * 1000 };
  }
  function paginate(items, position, limit, metadata) {
    if (!position.items) position = { ...position, items: structuredClone(items), metadata: structuredClone(metadata) };
    const { offset, items: snapshot } = position;
    const result = snapshot.slice(offset, offset + limit);
    const hasMore = offset + result.length < snapshot.length;
    let cursor;
    if (hasMore) {
      if (pages.size >= 128) pages.delete(pages.keys().next().value);
      cursor = randomUUID();
      pages.set(cursor, { ...position, offset: offset + result.length });
    }
    return { result, hasMore, position, ...cursor ? { cursor } : {} };
  }
  function pageResult(field, page) {
    const result = { ...page.position.metadata, [field]: page.result, hasMore: page.hasMore,
      ...page.cursor ? { cursor: page.cursor } : {}, truncated: false };
    pagination.set(result, page.position);
    return result;
  }
  async function list(args, workspace, sessionId, signal, management = false) {
    check(args, ['kind', 'parentRef', 'query', 'cursor', 'limit']);
    const kind = args.kind ?? 'all', current = view(workspace, kind, management), revision = stamp(current);
    const selected = narrow(current, args.parentRef);
    const query = args.query === undefined ? '' : text(args.query).normalize('NFKC').toLowerCase();
    const limit = integer(args.limit, 10, 20);
    const position = pageFor(args, `${sessionId}:${workspace}`, revision, 'knowledge_list');
    if (position.items) {
      ensure(workspace, kind, revision, signal, management);
      return pageResult('entries', paginate(undefined, position, limit));
    }
    const parent = args.parentRef ? selected.collections.find(row => collectionRef(row) === args.parentRef) : undefined;
    const collectionItems = [];
    for (const row of selected.collections.filter(row => (row.parentId ?? null) === (parent?.id ?? null))) {
      const overview = await runtime.overview(row, { signal });
      collectionItems.push({ id: row.id, ref: collectionRef(row), kind: 'collection', title: row.title, revision: row.revision,
        summaryState: overview ? 'ready' : row.summaryState === 'ready' ? 'stale' : row.summaryState ?? 'disabled',
        overviewRef: overview ? overviewRef(row, overview) : navigationRef(row, current) });
    }
    const items = [
      ...collectionItems,
      ...selected.sources.filter(row => !parent || row.collectionId === parent.id).map(row => ({ id: row.id, ref: documentRef(row), kind: 'document', title: row.title,
        version: row.version, revision: row.revision, updatedAt: row.updatedAt, chunkCount: row.chunkCount, collectionId: row.collectionId })),
      ...selected.entries.map(row => ({ id: row.id, ref: memoryRef(row), kind: 'memory', title: row.summary, updatedAt: row.updatedAt, revision: row.updatedAt })),
    ].filter(row => !query || row.title.normalize('NFKC').toLowerCase().includes(query));
    const page = paginate(items, position, limit, { disabledKinds: current.disabledKinds });
    ensure(workspace, kind, revision, signal, management);
    return pageResult('entries', page);
  }
  async function search(args, workspace, sessionId, signal, management = false) {
    check(args, ['kind', 'query', 'withinRef', 'limit', 'cursor']);
    const query = text(args.query), kind = args.kind ?? 'all';
    const current = view(workspace, kind, management), revision = stamp(current), selected = narrow(current, args.withinRef);
    const limit = integer(args.limit, 6, 10);
    const position = pageFor(args, `${sessionId}:${workspace}`, revision, 'knowledge_search');
    if (position.items) {
      ensure(workspace, kind, revision, signal, management);
      return pageResult('results', paginate(undefined, position, limit));
    }
    const rows = await rowsFor(selected, signal);
    const found = options.search ? await options.search(rows, query, { store, embedding: current.snapshot.embedding, signal })
      : await runtime.search(rows, query, { signal });
    const candidates = found.results ?? keywordSearch(rows, query, { limit: 20 });
    const selectedRows = [], perSource = new Map();
    for (const hit of candidates) {
      const row = hit.row;
      const count = perSource.get(row.sourceId) ?? 0;
      if (count >= 3) continue;
      if (selectedRows.some(previous => previous.sourceId === row.sourceId && row.startOffset !== undefined &&
        previous.startOffset !== undefined && row.startOffset < previous.endOffset && previous.startOffset < row.endOffset)) continue;
      perSource.set(row.sourceId, count + 1);
      selectedRows.push({ ref: row.ref, sourceId: row.sourceId, sourceVersion: row.sourceVersion, kind: row.kind, title: row.title,
        heading: row.heading ?? [], startLine: row.startLine, endLine: row.endLine, startOffset: row.startOffset, endOffset: row.endOffset,
        snippet: chars(row.text).slice(0, 200).join(''), matchedBy: hit.matchedBy ?? ['keyword'] });
    }
    const metadata = { mode: found.mode ?? 'keyword', coverage: found.coverage ?? { eligible: rows.length, indexed: 0 },
      ...found.degradedReason ? { degradedReason: found.degradedReason } : {}, disabledKinds: current.disabledKinds };
    const page = paginate(selectedRows, position, limit, metadata);
    ensure(workspace, kind, revision, signal, management);
    return pageResult('results', page);
  }
  async function read(args, workspace, signal, management = false) {
    check(args, ['ref', 'offset', 'maxChars']);
    const ref = text(args.ref, 500), offset = args.offset ?? 0;
    if (!Number.isSafeInteger(offset) || offset < 0) throw knowledgeFailure('KNOWLEDGE_INPUT');
    const maxChars = integer(args.maxChars, 1200, 3000), current = view(workspace, 'all', management), revision = stamp(current);
    let result;
    const source = current.sources.find(row => ref.startsWith(`kb:document:${row.id}:v${row.version}:`));
    if (source) {
      const chunks = await store.sourceChunks(source);
      const chunk = chunks.find(row => documentRef(source, row) === ref);
      const body = ref === documentRef(source) ? await store.sourceText(source) : chunk?.text;
      if (typeof body !== 'string') throw knowledgeFailure('KNOWLEDGE_MISSING');
      const full = chars(body);
      if (offset > full.length) throw knowledgeFailure('KNOWLEDGE_INPUT');
      const content = full.slice(offset, offset + maxChars).join('');
      const before = full.slice(0, offset).join('');
      const startLine = (chunk?.startLine ?? 1) + (before.match(/\n/g)?.length ?? 0);
      const adjacent = id => chunks.find(row => row.id === id);
      result = { ref, kind: 'document', sourceId: source.id, sourceVersion: source.version, title: source.title, heading: chunk?.heading ?? [],
        text: content, startLine, endLine: startLine + (content.match(/\n/g)?.length ?? 0), offset,
        nextOffset: offset + chars(content).length < full.length ? offset + chars(content).length : null,
        previousRef: chunk?.previous && adjacent(chunk.previous) ? documentRef(source, adjacent(chunk.previous)) : null,
        nextRef: chunk?.next && adjacent(chunk.next) ? documentRef(source, adjacent(chunk.next)) : null,
        truncated: offset + chars(content).length < full.length, generated: false };
    } else {
      const entry = current.entries.find(row => memoryRef(row) === ref);
      if (entry) {
        const full = chars(entry.content);
        if (offset > full.length) throw knowledgeFailure('KNOWLEDGE_INPUT');
        result = { ref, kind: 'memory', sourceId: entry.id, sourceVersion: entry.updatedAt, title: entry.summary,
          text: full.slice(offset, offset + maxChars).join(''), source: structuredClone(entry.source), offset,
          nextOffset: offset + maxChars < full.length ? offset + maxChars : null, truncated: offset + maxChars < full.length,
          sourceEvidenceAvailable: entry.source?.kind === 'manual' };
        if (entry.source?.kind === 'auto') {
          try {
            const persistence = typeof ctx.get === 'function' ? ctx.get('sessionPersistence') : ctx.sessionPersistence;
            const handle = await persistence.open(entry.source.session, 'read', { signal });
            try {
              if (entry.source.origin === 'specimen') {
                const page = await handle.read(undefined, undefined, { signal });
                const sources = memorySources(page.events);
                result.sourceEvidenceAvailable = entry.source.events.every(seq => sources.get(seq)?.origin === 'specimen');
              } else {
                result.sourceEvidenceAvailable = true;
                for (const seq of entry.source.events) {
                  const page = await handle.read(seq, 1, { signal });
                  if (page.events?.[0]?.seq !== seq || page.events[0].type !== 'user/message') result.sourceEvidenceAvailable = false;
                }
              }
            } finally { await handle.close(); }
          } catch { result.sourceEvidenceAvailable = false; }
        }
      } else {
        let collection = current.collections.find(row => navigationRef(row, current) === ref);
        let overview;
        if (!collection && ref.startsWith('kb:overview:')) {
          for (const row of current.collections) {
            if (!ref.startsWith(`kb:overview:${row.id}:r${row.revision}:s`)) continue;
            const saved = await runtime.overview(row, { signal });
            if (saved && overviewRef(row, saved) === ref) { collection = row; overview = saved; break; }
          }
        }
        if (!collection) throw knowledgeFailure('KNOWLEDGE_VERSION_CHANGED');
        const selected = narrow(current, collectionRef(collection));
        const body = overview?.text ?? (selected.sources.map(row => `${row.title}（${row.chunkCount} 段）`).join('\n') || '此集合暂无文档。');
        const full = chars(body);
        if (offset > full.length) throw knowledgeFailure('KNOWLEDGE_INPUT');
        result = { ref, kind: 'overview', title: collection.title, revision: collection.revision, offset, text: full.slice(offset, offset + maxChars).join(''),
          generated: Boolean(overview), navigation: true,
          coverage: overview ? { coveredCount: overview.coveredCount, totalCount: overview.totalCount } : { coveredCount: selected.sources.length, totalCount: selected.sources.length },
          sources: selected.sources.map(row => ({ ref: documentRef(row), version: row.version })),
          nextOffset: offset + maxChars < full.length ? offset + maxChars : null, truncated: offset + maxChars < full.length };
      }
    }
    ensure(workspace, 'all', revision, signal, management);
    return result;
  }
  function fit(result, limit) {
    const value = structuredClone(result);
    while (bytes(value) > limit) {
      const snippets = value.results?.filter(row => typeof row.snippet === 'string' && chars(row.snippet).length > 20);
      if (value.sources?.length > 1) {
        value.sources.pop(); value.sourcesTruncated = true;
      } else if (snippets?.length) {
        for (const row of snippets) row.snippet = chars(row.snippet).slice(0, Math.max(20, Math.floor(chars(row.snippet).length / 2))).join('');
      } else if (typeof value.text === 'string' && chars(value.text).length > 10) {
        value.text = chars(value.text).slice(0, Math.floor(chars(value.text).length / 2)).join('');
        value.nextOffset = (value.offset ?? 0) + chars(value.text).length;
        if (value.startLine !== undefined) value.endLine = value.startLine + (value.text.match(/\n/g)?.length ?? 0);
      } else if ((value.results ?? value.entries)?.length > 1) {
        (value.results ?? value.entries).pop();
        value.hasMore = true;
        const position = pagination.get(result);
        if (position) {
          if (!value.cursor) value.cursor = randomUUID();
          if (pages.size >= 128 && !pages.has(value.cursor)) pages.delete(pages.keys().next().value);
          pages.set(value.cursor, { ...position, offset: position.offset + (value.results ?? value.entries).length });
        }
      } else throw knowledgeFailure('KNOWLEDGE_LIMIT');
      value.truncated = true;
    }
    return value;
  }
  async function execute(tool, args, exec) {
    await ready;
    if (error) throw error;
    if (closed || !names.has(tool)) throw knowledgeFailure('KNOWLEDGE_NOT_READY');
    exec.signal?.throwIfAborted();
    if (!active(exec.agent)) throw knowledgeFailure('KNOWLEDGE_NOT_READY');
    let revision;
    const kind = tool === 'knowledge_read' ? 'all' : args?.kind ?? 'all';
    return budget.execute(tool, exec, (workspace, session) => {
      revision = stamp(view(workspace, kind));
      return tool === 'knowledge_search' ? search(args, workspace, session.id, exec.signal)
        : tool === 'knowledge_list' ? list(args, workspace, session.id, exec.signal) : read(args, workspace, exec.signal);
    }, fit, workspace => ensure(workspace, kind, revision, exec.signal));
  }

  if (ctx.on) {
    off.push(ctx.on('agent/disposed', ({ agent }) => {
      for (const [id, page] of pages) if (page.scope.startsWith(`${agent.session.id}:`)) pages.delete(id);
    }));
    for (const event of ['llm/adapters-updated', 'loader/volatile-update', 'credentials/reference-updated', 'credentials/record-updated']) {
      off.push(ctx.on(event, () => { serviceRevision++; pages.clear(); }));
    }
  }
  async function call(action, data, signal) {
    await ready;
    signal.throwIfAborted();
    if (closed) throw knowledgeFailure('KNOWLEDGE_NOT_READY');
    if (action === 'status' && error) return { ready: false, message: knowledgeError(error) };
    if (error) throw error;
    const workspaces = [...new Set([process.env.DSHX_WORKSPACE, ...(ctx.agents?.list?.() ?? []).map(row => ctx.workingDirectory?.get(row.session) ?? row.session.header?.cwd)].filter(Boolean))];
    if (action === 'search' || action === 'read' && data.ref) {
      const workspace = process.env.DSHX_WORKSPACE, kind = action === 'read' ? 'all' : data?.kind ?? 'all';
      const revision = stamp(view(workspace, kind, true));
      const result = action === 'search' ? await search(data, workspace, 'native', signal, true) : await read(data, workspace, signal, true);
      ensure(workspace, kind, revision, signal, true);
      return result;
    }
    const result = await store.manage(action, data, { signal, instance: policyHost.config.instance ?? 'host', workspaces });
    signal.throwIfAborted();
    if (closed) throw knowledgeFailure('KNOWLEDGE_NOT_READY');
    return result;
  }
  return {
    ready, active,
    execute(tool, args, exec) {
      return track(exec.signal, signal => execute(tool, args, { ...exec, signal }));
    },
    call(action, data = {}, signal) {
      return track(signal, combined => call(action, data, combined));
    },
    close() {
      if (closePromise) return closePromise;
      closed = true;
      closePromise = (async () => {
        await ready;
        await Promise.allSettled([runtime?.close(), ...pending]);
        await store?.close();
      })();
      controller.abort(knowledgeFailure('KNOWLEDGE_CANCELLED'));
      for (const dispose of off) dispose?.();
      pages.clear();
      return closePromise;
    },
  };
}

export async function apply(ctx) {
  const knowledge = knowledgeSession(ctx);
  owner = knowledge;
  policyHost.knowledge = knowledge.call;
  policyHost.stopKnowledge = () => { void knowledge.close(); };
  ctx.effect(() => async () => {
    await knowledge.close();
    if (owner === knowledge) owner = undefined;
    if (policyHost.knowledge === knowledge.call) policyHost.knowledge = undefined;
  });
  const { defineTool } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-tools')));
  const fields = {
    kind: { type: 'string', enum: ['all', 'document', 'memory'], description: '来源类别，默认 all。' },
    query: { type: 'string', description: '中文或技术关键词，最多500个字符。' },
    cursor: { type: 'string', description: '上次同一查询返回的分页凭据。' },
    limit: { type: 'number', description: '本次返回条目上限。' },
  };
  const definitions = [
    { name: 'knowledge_list', description: '浏览本机知识集合、文档和当前有效记忆的目录；query仅筛选标题，不返回正文。',
      parameters: { ...fields, parentRef: { type: 'string', description: '已返回的集合引用，仅缩小当前范围。' } } },
    { name: 'knowledge_search', description: '查找本机文档和当前有效记忆，返回真实片段和引用；按mode区分关键词或混合检索。关键结论需继续knowledge_read核对原文。',
      parameters: { ...fields, query: { ...fields.query, required: true }, withinRef: { type: 'string', description: '已返回的集合或文档引用，仅缩小范围。' } } },
    { name: 'knowledge_read', description: '按知识引用追读原文、集合导航或有效记忆；旧版本和已删除来源会拒绝。不会修改资料。',
      parameters: { ref: { type: 'string', required: true, description: '知识工具返回的完整ref。' }, offset: { type: 'number', description: '按完整Unicode字符计数的偏移。' }, maxChars: { type: 'number', description: '默认1200字符，最多3000字符。' } } },
  ];
  for (const definition of definitions) ctx.tools.register(defineTool({ ...definition, isConcurrencySafe: () => true,
    execute: (args, exec) => knowledge.execute(definition.name, args, exec),
    output: { schema: { type: 'object', additionalProperties: true }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] } }));
  await knowledge.ready;
}
