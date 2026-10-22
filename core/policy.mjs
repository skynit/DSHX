import { AsyncLocalStorage } from 'node:async_hooks';
import { lstat, realpath } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { policyHost } from './host.mjs';
import { phoneToolNames } from './phone.mjs';

// The workspace is the default directory, not a sandbox for Full sessions.
const workspace = process.env.DSHX_WORKSPACE;
const fileTools = new Set(['read', 'write', 'edit', 'glob', 'grep']);
const access = new AsyncLocalStorage();

export function inFileTool() { return access.getStore() !== undefined; }
export function fileMode() { return access.getStore()?.androidMode; }

export async function runTool(exec, callback) {
  let scope = access.getStore();
  if (fileTools.has(exec.name) || exec.name === 'bash') {
    const mode = await policyHost.mode(exec.agent?.session?.id);
    if (mode === 'unavailable' || exec.name === 'bash' && mode !== 'full' || mode === 'read-only' && ['write', 'edit'].includes(exec.name)) {
      throw Object.assign(new Error('原生会话权限不允许当前文件操作。'), { code: 'FS_SANDBOX_DENIED' });
    }
    if (exec.signal?.aborted) throw exec.signal.reason ?? new Error('当前调用已取消。');
    scope = { ...exec, androidMode: mode === 'read-only' || scope?.androidMode === 'read-only' ? 'read-only' : mode };
    if (scope.androidMode === 'read-only' && ['write', 'edit'].includes(exec.name)) throw denied();
    if (exec.name === 'bash' && scope.androidMode !== 'full') throw denied();
  }
  return access.run(scope, callback);
}

// Only structured references already admitted into this agent's visible
// session grant access. Model text, draft uploads and other sessions do not.
export async function modelPath(path, cwd = workspace, signal) {
  if (fileMode() === 'full') return fullPath(path, cwd);
  try { return await workspacePath(path, cwd); }
  catch (error) { if (error.code !== 'FS_SANDBOX_DENIED') throw error; }
  const scope = access.getStore();
  const agent = scope?.agent;
  if (scope?.name !== 'read' || typeof path !== 'string' || !path.trim() || path.includes('\0') || !cwd) throw denied();
  const attachments = agent?.ctx?.get('attachments');
  const messages = agent?.session?.deriveMessages?.() ?? [];
  if (!attachments) throw denied();
  const requested = resolve(cwd, path);
  for (const message of messages) {
    if (message.role !== 'user' || !Array.isArray(message.content)) continue;
    for (const part of message.content) {
      if (part.type !== 'file' && part.type !== 'image') continue;
      const ref = part.attachment;
      const target = part.type === 'file' ? attachments.fileHostPath(ref) : attachments.imageHostPath(ref);
      if (!target || requested !== resolve(target)) continue;
      const info = await lstat(target);
      if (!info.isFile()) throw denied();
      if (part.type === 'image') await attachments.readImage(ref, signal);
      else for await (const chunk of attachments.readFileStream(ref, signal)) { /* verify the complete official stream */ }
      return realpath(target);
    }
  }
  throw denied();
}

export function toolAvailable(name, agent) {
  const mode = policyHost.cachedMode(agent?.session?.id);
  return Boolean(workspace) && (fileTools.has(name) || (name === 'bash' || phoneToolNames.has(name)) && mode === 'full') && mode !== 'unavailable' &&
    !(mode === 'read-only' && ['write', 'edit'].includes(name));
}

export function toolReason(exec) {
  if (!workspace) return 'Android 工作区尚未配置，工具不可用。';
  if (!fileTools.has(exec.name) && exec.name !== 'bash' && !phoneToolNames.has(exec.name)) return '此 Android 开发版尚未适配该模型工具。';
  if (!toolAvailable(exec.name, exec.agent)) return '当前会话尚未连接，工具暂不可用。';
  if (exec.arguments?.sandbox_permissions !== undefined) return 'Android 当前默认完全权限，不支持单次沙箱提权参数。';
  if (exec.name === 'bash' && exec.arguments?.run_in_background === true) return 'Android 当前仅开放前台命令。';
}

function denied() {
  return Object.assign(new Error('Android 文件与搜索仅允许访问本应用的工作区。'), { code: 'FS_SANDBOX_DENIED' });
}

// Android creates and owns this durable application directory. Attachment
// publication must sync new entries inside it, without opening /data/user/0.
export function homeBoundary(home) {
  const root = process.env.DSHX_DATA_ROOT;
  if (!root || !isAbsolute(root) || resolve(root) === sep) throw new Error('Android 应用目录尚未配置。');
  const tail = relative(resolve(root), resolve(home));
  if (!tail || tail === '..' || tail.startsWith(`..${sep}`) || isAbsolute(tail)) throw new Error('附件目录必须位于 Android 应用目录内。');
  return resolve(root);
}

export async function workspacePath(path, cwd = workspace) {
  if (fileMode() === 'full') return fullPath(path, cwd);
  if (!workspace || !isAbsolute(workspace) || typeof path !== 'string' || !path.trim() || path.includes('\0') || !cwd) throw denied();
  const root = await realpath(workspace);
  // Do not normalize before realpath: link/../file must follow the same
  // physical traversal that the operating system actually uses.
  let ancestor = isAbsolute(path) ? path : `${cwd}/${path}`;
  const missing = [];
  let canonical;
  for (;;) {
    try { canonical = join(await realpath(ancestor), ...missing); break; }
    catch (error) {
      if (error.code !== 'ENOENT') throw error;
      let info;
      try { info = await lstat(ancestor); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
      // A dangling link is not an absent ordinary filename. Do not turn it
      // into a lexical in-workspace path while its destination is outside.
      if (info?.isSymbolicLink()) throw denied();
      const parent = dirname(ancestor);
      if (parent === ancestor) throw denied();
      missing.unshift(basename(ancestor));
      if (missing.includes('..')) throw denied();
      ancestor = parent;
    }
  }
  const tail = relative(root, canonical);
  if (tail === '..' || tail.startsWith(`..${sep}`) || isAbsolute(tail)) throw denied();
  return canonical;
}

function fullPath(path, cwd) {
  if (typeof path !== 'string' || !path.trim() || path.includes('\0') || !cwd) throw denied();
  return isAbsolute(path) ? path : `${cwd}/${path}`;
}
