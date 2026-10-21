// Run only after the host has stopped sibling processes and verified this core.
import { createRequire } from 'node:module';
import { lstat, mkdir, open, readFile, readdir, rename, unlink, link } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { randomUUID } from 'node:crypto';
const require = createRequire(new URL('./lib/node_modules/package.json', import.meta.url));
const { parse, parseDocument, Document } = require('yaml');
const { Context } = await import(require.resolve('@deepseek-ai/cordis'));
const { default: BasicCompaction } = await import(require.resolve('@deepseek-ai/dsh-compaction-basic'));
const { default: ToolResultPruner } = await import(require.resolve('@deepseek-ai/dsh-compaction-tool-result-pruner'));
const { deepSeekConfigFields } = await import(require.resolve('@deepseek-ai/dsh-llm-deepseek'));
const { resolveAdapterOptions } = await import(require.resolve('@deepseek-ai/dsh-llm-deepseek-api-key'));
const native = process.platform === 'android' ? createRequire(import.meta.url)('./native.node') : null;

const home = process.env.HOME;
const dsh = process.env.DSH_HOME;
if (!home || dsh !== join(home, '.dsh')) throw new Error('恢复目录未配置。');
const files = dirname(home);
const pending = join(files, '.model-recovery.json');
const archive = join(files, 'recovery', randomUUID());
const permitted = new Map([
  ['llm-pi-ai', '@deepseek-ai/dsh-llm-pi-ai'],
  ['llm-deepseek', '@deepseek-ai/dsh-llm-deepseek-api-key'],
  ['agent-default-model', '@deepseek-ai/dsh-agent-default-model'],
  ['ui-settings-general', '@deepseek-ai/dsh-client-ui-settings-general'],
  ['system-prompt', '@deepseek-ai/dsh-system-prompt'],
  ['agent-preset-registry', '@deepseek-ai/dsh-agent-preset-registry'],
  ...['standard', 'minimal', 'cordis', 'ptc'].map(id => ['preset-' + id, '@deepseek-ai/dsh-agent-preset']),
]);

async function exists(path) {
  try { return await lstat(path); } catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}
async function read(path) {
  const info = await exists(path);
  if (!info) return [];
  if (!info.isFile() || info.size > 1024 * 1024) throw new Error('模型配置不是普通文件或过大，请查看恢复诊断。');
  return parse(await readFile(path, 'utf8'), { maxAliasCount: 50 }) ?? [];
}
// Restore only declarative user preferences. Executable plugin definitions
// always come from the verified bundle, including their original YAML tags.
const preferenceFields = new Map([
  ['persona', ['prefix', 'suffix', 'complete', 'includeRuntimeContext']],
  ['compaction-basic', ['thresholdRatio', 'headroomTokens', 'retainRatio', 'retainTokens',
    'summarizationProvider', 'summarizationModel', 'maxTokens', 'compactionRetries', 'maxOverflowRetries', 'auto', 'modelPolicies']],
  ['tool-result-pruner', ['thresholdChars', 'headChars', 'tailChars']],
]);
const preferenceNames = new Map([
  ['persona', '@deepseek-ai/dsh-persona'],
  ['compaction-basic', '@deepseek-ai/dsh-compaction-basic'],
  ['tool-result-pruner', '@deepseek-ai/dsh-compaction-tool-result-pruner'],
]);
function declarative(value) {
  return JSON.parse(JSON.stringify(value), (key, item) => {
    if (['__proto__', 'prototype', 'constructor', '__jsExpr'].includes(key)) throw new Error('配置包含可执行表达式或无效字段。');
    return item;
  });
}
async function validPreference(id, config) {
  declarative(config);
  if (id === 'persona') {
    for (const key of ['prefix', 'suffix']) if (config[key] !== undefined && typeof config[key] !== 'string') throw new Error('人设必须为文本。');
    for (const key of ['complete', 'includeRuntimeContext']) if (config[key] !== undefined && typeof config[key] !== 'boolean') throw new Error('人设开关必须为布尔值。');
    return;
  }
  if (config.auto !== undefined && typeof config.auto !== 'boolean') throw new Error('压缩开关必须为布尔值。');
  // Use the pinned upstream validators, including cross-field and nested rules.
  // Automatic compaction is disabled only in this temporary validation context.
  const ctx = new Context();
  try {
    if (id === 'compaction-basic') new BasicCompaction(ctx, { ...config, auto: false });
    else new ToolResultPruner(ctx, config);
  } finally { await ctx.fiber.dispose(); }
}
async function preferences(rows) {
  if (!Array.isArray(rows)) return [];
  const result = [];
  for (const row of rows) {
    if (row?.group === true && (row.name === undefined || row.name === 'cordis:group')) { result.push(...await preferences(row.config)); continue; }
    if (!preferenceFields.has(row?.id) || row.name !== undefined && row.name !== preferenceNames.get(row.id)) continue;
    const config = Object.fromEntries(preferenceFields.get(row.id)
      .filter(key => row.config?.[key] !== undefined).map(key => [key, row.config[key]]));
    if (!Object.keys(config).length) continue;
    try { await validPreference(row.id, config); }
    catch { console.log(`已隔离无效的 ${row.id} 偏好，使用内置默认设置；原配置保留在备份。`); continue; }
    result.push({ id: row.id, name: preferenceNames.get(row.id), config });
  }
  return result;
}
async function render(rows) {
  const output = new Document([]);
  for (const row of rows) {
    if (!row.id.startsWith('preset-')) { output.contents.add(row); continue; }
    const id = row.id.slice('preset-'.length);
    const template = parseDocument(await readFile(new URL(`./lib/node_modules/@deepseek-ai/dsh-web-app/presets/${id}.patch.yml`, import.meta.url), 'utf8'));
    if (template.errors.length) throw new Error('内置会话预设无效。');
    const preset = template.getIn([0, 'insert', 0]);
    const visit = plugins => {
      for (const plugin of plugins.items) {
        if (plugin.get('group') === true && plugin.get('name') === 'cordis:group') visit(plugin.get('config'));
        const saved = row.config.plugins.find(item => item.id === plugin.get('id') && item.name === plugin.get('name'));
        if (saved) plugin.set('config', { ...(plugin.get('config')?.toJSON() ?? {}), ...saved.config });
      }
    };
    visit(preset.getIn(['config', 'plugins']));
    output.contents.add(preset);
  }
  return String(output);
}
async function clean(rows) {
  if (!Array.isArray(rows)) throw new Error('模型配置格式无效，原文件已保留。');
  const selected = new Map();
  for (const row of rows) {
    if (!row || !permitted.has(row.id) || row.name !== undefined && row.name !== permitted.get(row.id) || !row.config || typeof row.config !== 'object' || Array.isArray(row.config)) continue;
    let config;
    if (row.id.startsWith('preset-')) {
      if (row.config.id !== row.id.slice('preset-'.length)) continue;
      config = { id: row.config.id, plugins: await preferences(row.config.plugins) };
    } else if (row.id === 'system-prompt') config = Object.fromEntries(
      ['personaPrefix', 'personaSuffix', 'includeHarnessIdentity', 'includeRuntimeContext', 'toolOrder']
        .filter(key => row.config[key] !== undefined).map(key => [key, row.config[key]]));
    else if (row.id === 'agent-preset-registry') config = Object.fromEntries(
      ['default', 'selectedDefault'].filter(key => ['standard', 'minimal', 'cordis', 'ptc'].includes(row.config[key])).map(key => [key, row.config[key]]));
    else if (row.id === 'llm-pi-ai') config = { providers: row.config.providers ?? {} };
    else if (row.id === 'llm-deepseek') config = Object.fromEntries(
      [...Object.keys(deepSeekConfigFields), 'apiKeyEnv'].filter(key => row.config[key] !== undefined).map(key => [key, row.config[key]]));
    else config = Object.fromEntries((row.id === 'agent-default-model' ? ['provider', 'model', 'reasoningEffort'] : ['welcomeNoticeVersion'])
      .filter(key => typeof row.config[key] === 'string').map(key => [key, row.config[key]]));
    try {
      config = declarative(config);
      if (row.id === 'llm-deepseek') resolveAdapterOptions(config);
      if (row.id === 'system-prompt') {
        for (const key of ['personaPrefix', 'personaSuffix']) if (config[key] !== undefined && typeof config[key] !== 'string') throw new Error();
        for (const key of ['includeHarnessIdentity', 'includeRuntimeContext']) if (config[key] !== undefined && typeof config[key] !== 'boolean') throw new Error();
        if (config.toolOrder !== undefined && (!Array.isArray(config.toolOrder) || config.toolOrder.some(item => typeof item !== 'string') || new Set(config.toolOrder).size !== config.toolOrder.length || !config.toolOrder.includes('<unlisted-tools>'))) throw new Error();
      }
    } catch { console.log(`已隔离无效的 ${row.id} 配置，使用内置默认设置；原配置保留在备份。`); continue; }
    selected.set(row.id, { id: row.id, name: permitted.get(row.id), config });
  }
  return [...selected.values()];
}
async function save(path, text) {
  const temporary = path + '.' + randomUUID() + '.tmp';
  const handle = await open(temporary, 'wx', 0o600);
  try {
    try { await handle.writeFile(text); await handle.sync(); } finally { await handle.close(); }
    if (native) native.publish(temporary, path);
    else await link(temporary, path);
    const directory = await open(dirname(path), 'r');
    try { await directory.sync(); } finally { await directory.close(); }
  } finally { await unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error; }); }
}

// Check data locations without following links into other trees. Never delete
// sessions, attachments, credentials or workspaces as part of trust recovery.
for (const path of [home, dsh]) {
  const info = await exists(path);
  if (info && !info.isDirectory()) throw new Error('用户目录异常，恢复已停止。');
  await mkdir(path, { recursive: true });
}
for (const name of ['sessions', 'attachments', 'storages', '.credentials.yaml']) {
  const info = await exists(join(dsh, name));
  if (info && (name === '.credentials.yaml' ? !info.isFile() : !info.isDirectory())) throw new Error('用户数据目录异常，恢复已停止。');
}
let retained;
if (await exists(pending)) retained = await clean(await read(pending));
else {
  for (const path of [join(dsh, 'profiles'), join(dsh, 'profiles/web')]) {
    const info = await exists(path);
    if (info && !info.isDirectory()) throw new Error('启动配置目录异常，恢复已停止并保留原数据。');
  }
  retained = await clean([
    ...await read(join(dsh, 'profiles/web/cordis.patch.yml')),
    ...await read(join(dsh, 'cordis.patch.yml')),
  ]);
  await save(pending, JSON.stringify(retained));
}
await mkdir(archive, { recursive: true });
const move = async (base, name, label) => rename(join(base, name), join(archive, label));
for (const name of await readdir(home)) if (name !== '.dsh') await move(home, name, `home-${name}`);
for (const name of await readdir(dsh)) {
  if (!['sessions', 'attachments', 'storages', '.credentials.yaml'].includes(name)) await move(dsh, name, `dsh-${name}`);
}
// Parent-level Node package loaders may have been created by arbitrary code.
for (const name of ['node_modules', 'package.json', 'package-lock.json', 'pnpm-workspace.yaml']) {
  if (await exists(join(files, name))) await move(files, name, `files-${name}`);
}
const profile = join(dsh, 'profiles/web');
await mkdir(profile, { recursive: true });
await save(join(profile, 'package.json'), JSON.stringify({ name: 'dsh-profile-web', private: true,
  dependencies: {}, dsh: { profile: { bundles: ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'] } } }));
await save(join(profile, 'cordis.patch.yml'), await render(retained));
await unlink(pending);
console.log('启动配置已重建；模型、人设与压缩设置、会话、附件和工作文件已保留，旧配置移至恢复备份。');
