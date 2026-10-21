import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { workspacePath, modelPath, inFileTool, fileMode } from './policy.mjs';

const require = createRequire(new URL('./lib/node_modules/package.json', import.meta.url));
const { default: SandboxedFileSystem } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-fs-sandbox')));
const { FsError } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-fs')));

// Keep official atomic writes and stale-version checks. Full sessions use
// ordinary application file access, including files outside the workspace.
export default class AndroidFiles extends SandboxedFileSystem {
  async resolve(path, options) {
    try { return await super.resolve(inFileTool() ? await modelPath(path, options?.cwd ?? this.config.cwd, options?.signal) : path, options); }
    catch (error) {
      if (error.code === 'FS_SANDBOX_DENIED') throw new FsError(error.message, error.code);
      throw error;
    }
  }

  async checkedTarget(target, policy) {
    if (fileMode() === 'full') return super.checkedTarget(target, policy);
    if (fileMode() === 'read-only') throw new FsError('原生会话已设为只读，不能修改文件。', 'FS_SANDBOX_DENIED');
    const path = await workspacePath(target.displayPath, this.config.cwd);
    return super.checkedTarget(await super.resolve(path), policy);
  }

  async stat(target, signal) { return super.stat(await this.resolve(target.displayPath, { signal }), signal); }
  async lstat(path, options, signal) { return super.lstat(inFileTool() ? await modelPath(path, options?.cwd ?? this.config.cwd, signal) : path, options, signal); }
  async readText(target, signal) { return super.readText(await this.resolve(target.displayPath, { signal }), signal); }
  async streamText(target, signal) { return super.streamText(await this.resolve(target.displayPath, { signal }), signal); }
  async readBytes(target, signal, maxBytes) { return super.readBytes(await this.resolve(target.displayPath, { signal }), signal, maxBytes); }
  async readByteRange(target, range, signal) { return super.readByteRange(await this.resolve(target.displayPath, { signal }), range, signal); }
  async listDir(target, signal) { return super.listDir(await this.resolve(target.displayPath, { signal }), signal); }
  async watch(target, changed, signal) { return super.watch(await this.resolve(target.displayPath, { signal }), changed, signal); }
}
