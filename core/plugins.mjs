import { spawn } from 'node:child_process';
import { lstat, open, readFile, readdir, realpath, stat } from 'node:fs/promises';
import { join, relative, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';

// The upstream profile owns installation, cancellation, locking and activation.
// Supply an executable invocation so Android never needs a shell shebang or Corepack download.
export function packageManager(core) {
  return {
    command: process.execPath,
    args: [join(core, 'plugins.mjs')],
    env: {
      PATH: `${join(core, 'bin')}:${process.env.PATH ?? '/system/bin'}`,
      LD_LIBRARY_PATH: join(core, 'lib'),
      NODE_EXTRA_CA_CERTS: join(core, 'etc/tls/cert.pem'),
      SSL_CERT_FILE: join(core, 'etc/tls/cert.pem'),
      GIT_EXEC_PATH: join(core, 'libexec/git-core'),
      GIT_TEMPLATE_DIR: join(core, 'share/git-core/templates'),
      GIT_SSL_CAINFO: join(core, 'etc/tls/cert.pem'),
      GIT_TERMINAL_PROMPT: '0',
      GIT_PAGER: 'cat'
    }
  };
}

// Inspect only the profile's downloaded modules, including transitive packages.
// Never follow a link into the bundled core or change a downloaded file.
export async function checkPlugins(modules) {
  let root;
  try {
    if ((await lstat(modules)).isSymbolicLink()) throw new Error('插件安装目录不能是外部链接');
    root = await realpath(modules);
  }
  catch (error) { if (error.code === 'ENOENT') return; throw error; }
  const seen = new Set();
  async function visit(path) {
    const target = await realpath(path);
    const name = relative(root, target);
    if (name === '..' || name.startsWith('../') || isAbsolute(name)) {
      throw new Error(`插件包含安装目录之外的链接，当前不支持：${relative(root, path)}`);
    }
    if (seen.has(target)) return;
    seen.add(target);
    const info = await stat(target);
    if (info.isDirectory()) {
      // Follow each selected pnpm package's sibling dependencies, but not the
      // entire virtual store: it may contain leftovers from a rejected install.
      if (path !== target && name.startsWith('.pnpm/')) {
        const packageRoot = join(root, '.pnpm', name.split('/')[1], 'node_modules');
        await visit(packageRoot);
      }
      for (const entry of await readdir(target)) {
        if (entry === '.pnpm' || entry === '.bin' || entry.startsWith('.ignored')) continue;
        await visit(join(target, entry));
      }
      return;
    }
    if (!info.isFile()) throw new Error(`插件包含不支持的文件：${name}`);
    if (/\.(node|so(?:\.\d+)*|dll|dylib|exe)$/i.test(name) || /(^|\/)binding\.gyp$/.test(name)) {
      throw new Error(`Android 插件暂不支持原生依赖：${name}`);
    }
    if (name.endsWith('/package.json') || name === 'package.json') {
      const manifest = JSON.parse(await readFile(target, 'utf8'));
      if (manifest.gypfile === true || Object.hasOwn(manifest, '_pnpmPlaceholder') ||
          ['preinstall', 'install', 'postinstall'].some(key => /node-gyp|prebuild-install|node-pre-gyp/.test(manifest.scripts?.[key] ?? ''))) {
        throw new Error(`插件需要原生构建或尚未安装完整：${manifest.name ?? name}`);
      }
    }
    const handle = await open(target, 'r');
    try {
      const header = Buffer.alloc(4);
      await handle.read(header, 0, 4, 0);
      if (['7f454c46', 'feedface', 'cefaedfe', 'feedfacf', 'cffaedfe', 'cafebabe', 'bebafeca'].includes(header.toString('hex')) ||
          header.subarray(0, 2).toString() === 'MZ') {
        throw new Error(`Android 插件暂不支持原生二进制：${name}`);
      }
    } finally { await handle.close(); }
  }
  await visit(root);
}

export async function runPnpm(core, args) {
  const command = args[0];
  if (!['add', 'remove', 'install', 'view', 'config'].includes(command) ||
      command === 'config' && (args.length !== 3 || args[1] !== 'get' || args[2] !== 'registry')) {
    throw new Error('Android 插件管理仅支持安装、卸载和读取包信息');
  }
  if (command === 'add' && args.slice(1).some(arg => !arg.startsWith('-') &&
      !/^(?:@[a-z0-9._-]+\/)?[a-z0-9._-]+(?:@[^\s/:]+)?$/i.test(arg) &&
      !/^(?:github:|(?:git\+)?https:\/\/github\.com\/)[a-z0-9-]+\/[a-z0-9_.-]+\/?(?:#[^\s]+)?$/i.test(arg) &&
      !/^https:\/\/github\.com\/[a-z0-9-]+\/[a-z0-9_.-]+\/archive\/[^/?#\s]+\.tar\.gz$/i.test(arg) &&
      !arg.startsWith('file:') && !isAbsolute(arg))) {
    throw new Error('当前支持 npm 发布包、GitHub 仓库和本地已构建的包；其他 Git 来源、远程压缩包和链接安装尚未适配');
  }
  // Keep the engine's private connection facts and Node injection flags out of pnpm.
  const env = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
    !/^(DSHX_|DSH_|NODE_OPTIONS$|NODE_PATH$)/i.test(key) && !/KEY|TOKEN|SECRET|PASSWORD/i.test(key)));
  const fixed = [
    '--config.ignore-scripts=true',
    '--config.ignore-pnpmfile=true',
    '--config.manage-package-manager-versions=false',
    '--config.package-manager-strict=false',
    '--config.package-import-method=copy',
    '--config.side-effects-cache=false',
    '--config.auto-install-peers=false',
    '--config.verify-store-integrity=true',
    '--config.update-notifier=false'
  ];
  if (command === 'add') fixed.push('--save-exact');
  // pnpm delegates `view` to npm. Invoke its pinned JS entry with the same
  // Android Node so no missing npm executable or foreign shebang is involved.
  const entry = command === 'view' ? 'npm/bin/npm-cli.js' : 'pnpm/bin/pnpm.cjs';
  const child = spawn(process.execPath, [join(core, 'lib/node_modules', entry), ...args, ...fixed], {
    env, stdio: 'inherit'
  });
  const signals = ['SIGTERM', 'SIGINT', 'SIGHUP'];
  const handlers = signals.map(signal => () => child.kill(signal));
  signals.forEach((signal, index) => process.on(signal, handlers[index]));
  let code;
  try {
    code = await new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('exit', code => resolve(code ?? 1));
    });
  } finally { signals.forEach((signal, index) => process.off(signal, handlers[index])); }
  if (code === 0 && ['add', 'install', 'remove'].includes(command)) await checkPlugins(join(process.cwd(), 'node_modules'));
  return code;
}

if (import.meta.main) {
  try { process.exitCode = await runPnpm(fileURLToPath(new URL('.', import.meta.url)), process.argv.slice(2)); }
  catch (error) { console.error(`DSHX 插件管理：${error.message}`); process.exitCode = 1; }
}
