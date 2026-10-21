// Run on Android with DSHX_CORE pointing at the installed, verified core.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdtemp, open, writeFile, readFile, rm, link, stat, mkdir, symlink, rename, chmod, realpath } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const core = process.env.DSHX_CORE;
assert.ok(core, 'DSHX_CORE must name the installed core');
const require = createRequire(join(core, 'lib/node_modules/package.json'));
const native = require(join(core, 'native.node'));
const { runTool, inFileTool } = await import(pathToFileURL(join(core, 'policy.mjs')));
const { PolicyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
const load = name => import(pathToFileURL(require.resolve(name)));
const directory = await mkdtemp(join(tmpdir(), 'dshx-native-test-'));
after(() => rm(directory, { recursive: true, force: true }));
const workspace = await mkdtemp(join(process.env.DSHX_WORKSPACE, 'dshx-test-'));
after(() => rm(workspace, { recursive: true, force: true }));

// The fixture imitates the query-only wire protocol. Kotlin's real authority
// and native-only mode changes are exercised separately in CoreTest.kt.
async function policyServer() {
  const sockets = new Set();
  const sessions = new Map();
  let revision = 1;
  const server = createServer(socket => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => {});
    socket.setEncoding('utf8');
    let buffer = '';
    socket.on('data', chunk => {
      buffer += chunk;
      let newline;
      while ((newline = buffer.indexOf('\n')) !== -1) {
        const request = JSON.parse(buffer.slice(0, newline));
        buffer = buffer.slice(newline + 1);
        if (request.version !== undefined && (request.token !== 'test-token' || request.instance !== 'test-instance')) {
          socket.destroy(); return;
        }
        if (request.method === 'register') {
          sessions.set(request.session, { id: request.session, mode: request.parent ? sessions.get(request.parent).mode :
            request.session === 'read-session' ? 'read-only' : request.session === 'full-session' ? 'full' : 'workspace-write' });
          revision++;
        }
        if (request.method === 'end') { sessions.delete(request.session); revision++; }
        if (request.method === 'mode' && request.session === 'lost-session') { socket.destroy(); return; }
        const ok = request.method !== 'mode' || sessions.has(request.session) && request.session !== 'denied-session';
        socket.write(JSON.stringify({ id: request.id ?? 0, ok, ...(ok ? { state: {
          instance: 'test-instance', connected: true, revision, sessions: [...sessions.values()]
        } } : { error: 'unknown session' }) }) + '\n');
      }
    });
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return {
    config: { port: server.address().port, instance: 'test-instance', token: 'test-token' },
    close: async () => { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); }
  };
}

test('native policy client rejects partial configuration and bad authentication', async () => {
  const incomplete = new PolicyHost({ instance: 'test-instance' });
  await assert.rejects(incomplete.connect(), /原生工具权限连接中断/);
  assert.equal(incomplete.cachedMode('any'), 'unavailable');
  const fixture = await policyServer();
  const wrong = new PolicyHost({ ...fixture.config, token: 'wrong-token' });
  try {
    await assert.rejects(wrong.connect(), /原生工具权限连接中断/);
    assert.equal(wrong.cachedMode('any'), 'unavailable');
  } finally { wrong.close(); await fixture.close(); }
});

test('unknown or ended session queries stay closed without breaking other sessions', async () => {
  const fixture = await policyServer();
  const host = new PolicyHost(fixture.config);
  try {
    assert.equal(await host.register('read-session', 'Read'), 'read-only');
    assert.equal(await host.register('write-session', 'Write'), 'workspace-write');
    assert.equal(await host.mode(undefined), 'unavailable');
    await assert.rejects(host.mode('unknown'), /查询被拒绝/);
    assert.equal(await host.mode('read-session'), 'read-only');
    await host.end('read-session');
    await assert.rejects(host.mode('read-session'), /查询被拒绝/);
    assert.equal(await host.mode('write-session'), 'workspace-write');
    await fixture.close();
    await assert.rejects(host.mode('write-session'), /原生工具权限连接中断/);
    assert.equal(host.cachedMode('write-session'), 'unavailable');
  } finally { host.close(); if (fixture) await fixture.close(); }
});

test('configured native modes constrain real catalogs, final execution and nested file writes', async () => {
  const fixture = await policyServer();
  const script = `
    import assert from 'node:assert/strict';
    import { createRequire } from 'node:module';
    import { pathToFileURL } from 'node:url';
    import { join } from 'node:path';
    const core = process.env.DSHX_CORE;
    const require = createRequire(join(core, 'lib/node_modules/package.json'));
    const load = name => import(pathToFileURL(require.resolve(name)));
    const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
    assert.equal(policyHost.connected, true, 'the real preload must authenticate before official module initialization');
    const { runTool } = await import(pathToFileURL(join(core, 'policy.mjs')));
    const { Context } = await load('@deepseek-ai/cordis');
    const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
    const { default: Tools } = await load('@deepseek-ai/dsh-tools');
    const { default: Agents } = await load('@deepseek-ai/dsh-agent');
    const Guard = await import(pathToFileURL(join(core, 'guard.mjs')));
    const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
    const ctx = new Context();
    const full = { mode: 'danger-full-access', workspaceRoot: process.env.DSHX_WORKSPACE };
    ctx.provide('sandboxPolicy', { defaultMode: 'danger-full-access', resolve: () => full });
    try {
      await policyHost.register('read-session', 'Read');
      await policyHost.register('write-session', 'Write');
      await policyHost.register('full-session', 'Full');
      await ctx.plugin(SystemPrompt); await ctx.plugin(Tools); await ctx.plugin(Agents);
      await ctx.plugin(AndroidFiles, { cwd: process.env.DSHX_WORKSPACE });
      let writes = 0;
      for (const name of ['read', 'write', 'edit', 'glob', 'grep', 'bash']) ctx.tools.register({
        name, description: name, parameters: { type: 'object', properties: {} },
        output: { schema: { type: 'string' }, render: (_, value) => [{ type: 'text', text: value }] },
        execute: async () => { writes++; return 'done'; }
      });
      const read = { id: 'read-session', session: { id: 'read-session', header: {} } };
      const write = { id: 'write-session', session: { id: 'write-session', header: { parentSession: 'inactive-history-parent' } } };
      ctx.agents.enter(read); ctx.agents.enter(write);
      await ctx.plugin(Guard);
      const childAgent = { id: 'child-session', session: { id: 'child-session', header: {} } };
      const detachChild = ctx.agents.enter(childAgent, read);
      let announced = 0;
      ctx.on('agent/created', () => { announced++; });
      await ctx.agents.announce(childAgent, 'create');
      assert.equal(announced, 1, 'policy registration must not bail out of official creation listeners');
      assert.equal(policyHost.cachedMode(childAgent.id), 'read-only', 'live child inherits its creator mode');
      for (const method of ['schemas', 'sdkSchemas']) {
        assert.deepEqual(ctx.tools[method](read).map(t => t.name).sort(), ['glob', 'grep', 'read']);
        assert.deepEqual(ctx.tools[method](write).map(t => t.name).sort(), ['edit', 'glob', 'grep', 'read', 'write']);
      }
      assert.deepEqual(ctx.tools.wireSchemas(read).schemas.map(t => t.name).sort(), ['glob', 'grep', 'read']);
      const call = agent => ctx.tools.execute({ agent, name: 'write', arguments: {}, signal: new AbortController().signal, callId: 'native-policy-test' });
      await call(read); assert.equal(writes, 0);
      await call(write); assert.equal(writes, 1);
      await runTool({ name: 'read', agent: read }, async () => {
        const target = await ctx.fs.resolve('native-readonly-must-not-exist.txt');
        await assert.rejects(ctx.fs.writeText(target, 'blocked', undefined, undefined, full), { code: 'FS_SANDBOX_DENIED' });
        await assert.rejects(runTool({ name: 'write', agent: write }, async () => { throw new Error('must not execute'); }), { code: 'FS_SANDBOX_DENIED' });
      });
      detachChild();
      const deadline = Date.now() + 1000;
      while (policyHost.cachedMode(childAgent.id) !== 'unavailable' && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 10));
      assert.equal(policyHost.cachedMode(childAgent.id), 'unavailable');
      await policyHost.end(read.id);
      assert.deepEqual(ctx.tools.schemas(read), []);
      await call(read); assert.equal(writes, 1);
      await call(write); assert.equal(writes, 2);
      const denied = { id: 'denied-session', session: { id: 'denied-session' } };
      await policyHost.register(denied.id, 'Query denied');
      assert.equal(policyHost.cachedMode(denied.id), 'workspace-write');
      const rejection = await call(denied);
      assert.match(JSON.stringify(rejection), /查询被拒绝/); assert.equal(writes, 2);
      await call(write); assert.equal(writes, 3, 'a rejected query must not stop another session');
      const fullAgent = { id: 'full-session', session: { id: 'full-session', header: {} } };
      assert.equal(policyHost.cachedMode(fullAgent.id), 'full');
      assert.ok(ctx.tools.schemas(fullAgent).some(t => t.name === 'bash'));
      assert.ok(!ctx.tools.schemas(write).some(t => t.name === 'bash'));
      const command = agent => ctx.tools.execute({ agent, name: 'bash', arguments: {}, signal: new AbortController().signal, callId: 'full-policy-test' });
      await command(read); assert.equal(writes, 3);
      await command(write); assert.equal(writes, 3);
      await command(fullAgent); assert.equal(writes, 4);
      writes--;
      const lost = { id: 'lost-session', session: { id: 'lost-session' } };
      await policyHost.register(lost.id, 'Disconnect during execution');
      assert.equal(policyHost.cachedMode(lost.id), 'workspace-write');
      const disconnected = await call(lost);
      assert.match(JSON.stringify(disconnected), /原生工具权限连接中断/); assert.equal(writes, 3);
      assert.deepEqual(ctx.tools.schemas(write), []);
      await call(write); assert.equal(writes, 3);
    } finally { policyHost.close(); await ctx.fiber.dispose(); }
  `;
  const child = spawn(process.execPath, ['--expose-internals', '--import', join(core, 'compat.mjs'), '--input-type=module', '-e', script], { env: { ...process.env,
    DSHX_POLICY_PORT: String(fixture.config.port), DSHX_POLICY_INSTANCE: fixture.config.instance, DSHX_POLICY_TOKEN: fixture.config.token
  }, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', data => { output += data; }); child.stderr.on('data', data => { output += data; });
  const timer = setTimeout(() => child.kill('SIGKILL'), 20000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    assert.equal(code, 0, output);
  } finally { clearTimeout(timer); child.kill(); await fixture.close(); }
});

test('official bash and external files work in default Full despite legacy web restrictions', async () => {
  const fixture = await policyServer();
  const target = join(workspace, 'official-bash-proof.txt');
  const script = `
    import assert from 'node:assert/strict';
    import { readFile, stat } from 'node:fs/promises';
    import { createRequire } from 'node:module';
    import { join } from 'node:path';
    import { pathToFileURL } from 'node:url';
    const core = process.env.DSHX_CORE;
    const require = createRequire(join(core, 'lib/node_modules/package.json'));
    const load = name => import(pathToFileURL(require.resolve(name)));
    const { policyHost } = await import(pathToFileURL(join(core, 'host.mjs')));
    const { Context } = await load('@deepseek-ai/cordis');
    const ctx = new Context();
    const { default: SandboxPolicy } = await load('@deepseek-ai/dsh-sandbox-policy');
    const sandboxPolicy = { defaultMode: 'danger-full-access', workspaceRoot: ${JSON.stringify(workspace)},
      overrideOf: () => 'read-only' };
    sandboxPolicy.resolve = request => SandboxPolicy.prototype.resolve.call(sandboxPolicy, request);
    assert.equal(sandboxPolicy.resolve({ session: { id: 'old-session', header: { cwd: ${JSON.stringify(workspace)} } }, mode: 'read-only' }).mode, 'danger-full-access');
    let confined = 0;
    ctx.provide('sandboxPolicy', sandboxPolicy);
    ctx.provide('sandbox', { wrap: async () => { confined++; throw new Error('Android confinement unavailable'); } });
    ctx.provide('workingDirectory', { ensure: async () => ${JSON.stringify(workspace)} });
    try {
      for (const name of ['system-prompt', 'tools', 'subprocess-local', 'shell-env', 'bash-sandbox', 'tool-bash', 'tool-fs']) {
        const plugin = await load('@deepseek-ai/dsh-' + name);
        await ctx.plugin(plugin.default ?? plugin);
      }
      const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
      await ctx.plugin(AndroidFiles, { cwd: ${JSON.stringify(workspace)} });
      await policyHost.register('full-session', 'Full');
      await policyHost.register('write-session', 'Write');
      const call = session => ctx.tools.execute({
        name: 'bash', agent: { id: session, session: { id: session, header: { id: session } } },
        arguments: { description: 'Verify official Android bash', command: ${JSON.stringify("printf 'OFFICIAL_BASH_OK' > '" + target + "'; cat '" + target + "'")} },
        callId: 'official-bash-proof', signal: new AbortController().signal
      });
      await call('write-session');
      await assert.rejects(stat(${JSON.stringify(target)}), { code: 'ENOENT' });
      const result = await call('full-session');
      assert.match(JSON.stringify(result), /OFFICIAL_BASH_OK/);
      assert.equal(await readFile(${JSON.stringify(target)}, 'utf8'), 'OFFICIAL_BASH_OK');
      assert.equal(confined, 0);
      const outside = ${JSON.stringify(join(directory, 'full-outside-workspace.txt'))};
      const agent = { id: 'full-session', session: { id: 'full-session', header: { cwd: ${JSON.stringify(workspace)} } } };
      const write = await ctx.tools.execute({ name: 'write', agent, arguments: { file_path: outside, content: 'FULL_FILE_OK' }, callId: 'full-external-file', signal: new AbortController().signal });
      assert.equal(await readFile(outside, 'utf8'), 'FULL_FILE_OK', JSON.stringify(write));
      const read = await ctx.tools.execute({ name: 'read', agent, arguments: { file_path: outside }, callId: 'full-external-read', signal: new AbortController().signal });
      assert.match(JSON.stringify(read), /FULL_FILE_OK/);
    } finally { policyHost.close(); await ctx.fiber.dispose(); }
  `;
  const child = spawn(process.execPath, ['--expose-internals', '--import', join(core, 'compat.mjs'), '--input-type=module', '-e', script], {
    env: { ...process.env, DSHX_POLICY_PORT: String(fixture.config.port), DSHX_POLICY_INSTANCE: fixture.config.instance, DSHX_POLICY_TOKEN: fixture.config.token },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let output = '';
  child.stdout.on('data', data => { output += data; }); child.stderr.on('data', data => { output += data; });
  const timer = setTimeout(() => child.kill('SIGKILL'), 20000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    assert.equal(code, 0, output);
  } finally { clearTimeout(timer); child.kill(); await fixture.close(); }
});

test('trust recovery rebuilds executable profiles while preserving model settings and user data', async () => {
  const root = join(directory, 'recovery');
  const home = join(root, 'home');
  const dsh = join(home, '.dsh');
  const profile = join(dsh, 'profiles/web');
  await mkdir(profile, { recursive: true });
  for (const name of ['sessions', 'attachments', 'storages']) {
    await mkdir(join(dsh, name)); await writeFile(join(dsh, name, 'keep'), name);
  }
  await writeFile(join(dsh, '.credentials.yaml'), 'credential-retained');
  await writeFile(join(home, '.bashrc'), 'exit 99');
  await writeFile(join(profile, 'package.json'), '{"dependencies":{"arbitrary":"file:../../"}}');
  await writeFile(join(profile, 'cordis.patch.yml'), JSON.stringify([
    { id: 'malicious', name: 'file:arbitrary.mjs', config: {} },
    { id: 'llm-pi-ai', name: '@deepseek-ai/dsh-llm-pi-ai', config: { providers: { example: { baseURL: 'https://example.test/v1' } }, dangerous: true } },
    { id: 'agent-default-model', name: '@deepseek-ai/dsh-agent-default-model', config: { provider: 'example', model: 'test', dangerous: true } },
    { id: 'system-prompt', config: { personaPrefix: '你是我的手机助手', personaSuffix: '回答简洁', dangerous: true } },
    { id: 'preset-standard', name: '@deepseek-ai/dsh-agent-preset', config: { id: 'standard', plugins: [
      { id: 'persona', name: '@deepseek-ai/dsh-persona', config: { prefix: '你叫小石', suffix: '用中文回答', complete: false, dangerous: true } },
      { id: 'compaction', name: 'cordis:group', group: true, config: [
        { id: 'compaction-basic', name: '@deepseek-ai/dsh-compaction-basic', config: { headroomTokens: 1024, maxTokens: 512, summarizationProvider: 'local', summarizationModel: 'small', dangerous: true } },
      ] },
      { id: 'tool-bash', name: 'file:arbitrary.mjs', config: {} },
    ] } },
    { id: 'preset-minimal', config: { id: 'minimal', plugins: [
      { id: 'persona', config: { prefix: { __jsExpr: '(ctx.reviewFlag = true, "unsafe")' } } },
      { id: 'compaction-basic', config: { headroomTokens: -1, summarizationModel: 'missing-provider' } },
    ] } },
    { id: 'llm-deepseek', config: { maxTokens: 512, thinking: 'disabled', reasoningEffort: 'off', apiKeyEnv: 'DEEPSEEK_API_KEY', dangerous: true } },
  ]));
  const result = spawnSync(process.execPath, [join(core, 'recover.mjs')], { env: { ...process.env, HOME: home, DSH_HOME: dsh }, timeout: 30_000, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  const manifest = JSON.parse(await readFile(join(profile, 'package.json'), 'utf8'));
  assert.deepEqual(manifest.dependencies, {});
  const patch = require('yaml').parse(await readFile(join(profile, 'cordis.patch.yml'), 'utf8'));
  assert.deepEqual(patch.map(row => row.id), ['llm-pi-ai', 'agent-default-model', 'system-prompt', 'preset-standard', 'preset-minimal', 'llm-deepseek']);
  assert.equal(patch[0].config.providers.example.baseURL, 'https://example.test/v1');
  assert.equal(patch[1].config.model, 'test');
  assert.equal(patch[1].config.dangerous, undefined);
  assert.equal(patch[2].config.personaPrefix, '你是我的手机助手');
  assert.equal(patch[2].config.dangerous, undefined);
  const plugins = patch[3].config.plugins;
  assert.equal(plugins.find(row => row.id === 'persona').config.prefix, '你叫小石');
  assert.equal(plugins.find(row => row.id === 'persona').config.dangerous, undefined);
  assert.equal(plugins.find(row => row.id === 'tool-bash').name, '@deepseek-ai/dsh-tool-bash');
  assert.equal(plugins.find(row => row.id === 'tool-bash').disabled, 'process.platform === \'win32\'');
  const compact = plugins.find(row => row.id === 'compaction').config.find(row => row.id === 'compaction-basic');
  assert.equal(compact.config.summarizationModel, 'small');
  assert.equal(compact.config.headroomTokens, 1024);
  assert.equal(compact.config.dangerous, undefined);
  assert.ok((await readFile(join(profile, 'cordis.patch.yml'), 'utf8')).includes('!!js'));
  assert.ok(!(await readFile(join(profile, 'cordis.patch.yml'), 'utf8')).includes('__jsExpr'));
  const minimal = patch[4].config.plugins;
  assert.equal(minimal.find(row => row.id === 'persona').config.prefix, 'You are a helpful software engineer assistant.');
  assert.equal(minimal.find(row => row.id === 'compaction').config.find(row => row.id === 'compaction-basic').config, undefined);
  assert.equal(patch[5].config.maxTokens, 512);
  assert.equal(patch[5].config.apiKeyEnv, 'DEEPSEEK_API_KEY');
  assert.equal(patch[5].config.dangerous, undefined);
  for (const name of ['sessions', 'attachments', 'storages']) assert.equal(await readFile(join(dsh, name, 'keep'), 'utf8'), name);
  assert.equal(await readFile(join(dsh, '.credentials.yaml'), 'utf8'), 'credential-retained');
  await assert.rejects(stat(join(home, '.bashrc')), { code: 'ENOENT' });
  const backup = (await import('node:fs/promises')).readdir;
  assert.equal((await backup(join(root, 'recovery'))).length, 1);
  const again = spawnSync(process.execPath, [join(core, 'recover.mjs')], { env: { ...process.env, HOME: home, DSH_HOME: dsh }, timeout: 30_000, encoding: 'utf8' });
  assert.equal(again.status, 0, again.stderr);
  const repeated = require('yaml').parse(await readFile(join(profile, 'cordis.patch.yml'), 'utf8'));
  assert.deepEqual(repeated, patch, 'cold recovery must retain the same preferences on every restart');
  await writeFile(join(profile, 'cordis.patch.yml'), JSON.stringify([
    { id: 'llm-pi-ai', config: { providers: { injected: { apiKey: { __jsExpr: 'ctx.reviewFlag = true' } } } } },
  ]));
  const injected = spawnSync(process.execPath, [join(core, 'recover.mjs')], { env: { ...process.env, HOME: home, DSH_HOME: dsh }, timeout: 30_000, encoding: 'utf8' });
  assert.equal(injected.status, 0, injected.stderr);
  assert.ok(!(await readFile(join(profile, 'cordis.patch.yml'), 'utf8')).includes('__jsExpr'));
});

test('real upstream profile composition disables the original files and mounts the Android provider', async () => {
  const { composeEntries, loadOverlayPatches } = await load('@deepseek-ai/dsh-app-boot');
  const androidPatch = join(directory, 'android.patch.yml');
  await writeFile(androidPatch, (await readFile(join(core, 'android.patch.yml'), 'utf8')).replaceAll('@CORE@', core));
  const patches = [
    join(core, 'lib/node_modules/@deepseek-ai/dsh-base/cordis.patch.yml'),
    join(core, 'lib/node_modules/@deepseek-ai/dsh-web-app/cordis.patch.yml'),
    androidPatch
  ].map(path => loadOverlayPatches('dshx-test', path));
  const warnings = [];
  const entries = composeEntries(patches, message => warnings.push(message));
  assert.deepEqual(warnings, [], 'Android boot patches must not be skipped');
  assert.equal(entries.find(row => row.id === 'fs-sandbox')?.disabled, true);
  assert.equal(entries.find(row => row.id === 'dshx-android-files')?.name, `file:${core}/files.mjs`);
});

test('Android model catalogs expose only available tools and preserve known history names', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools } = await load('@deepseek-ai/dsh-tools');
  for (const mode of ['native', 'ptc']) {
    const ctx = new Context();
    try {
      await ctx.plugin(SystemPrompt);
      await ctx.plugin(Tools, { mode });
      for (const name of ['read', 'write', 'edit', 'glob', 'grep', 'bash', 'web_fetch', 'test_effect']) {
        ctx.tools.register({ name, description: name,
          parameters: { type: 'object', properties: {} },
          output: { schema: { type: 'string' }, render: (_, value) => [{ type: 'text', text: value }] },
          execute: async () => 'test result' });
      }
      const expected = ['edit', 'glob', 'grep', 'read', 'write'];
      for (const agent of [undefined, { id: 'catalog-child' }]) {
        assert.deepEqual(ctx.tools.schemas(agent).map(tool => tool.name).sort(), expected);
        const wire = ctx.tools.wireSchemas(agent);
        assert.deepEqual(wire.schemas.map(tool => tool.name).sort(), expected);
        assert.ok(wire.knownNames.includes('bash'), 'saved tool history remains a known name');
        assert.deepEqual(ctx.tools.sdkSchemas(agent).map(tool => tool.name).sort(), expected);
        assert.equal(ctx.tools.modeFor(agent), 'native', 'unavailable program execution must not hide available file tools');
      }
    } finally { await ctx.fiber.dispose(); }
  }
});

test('official instruction discovery can inspect parent markers and host configuration before a model request', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { loadBaselineInstructions } = await load('@deepseek-ai/dsh-agent-instructions');
  const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
  const ctx = new Context();
  ctx.provide('sandboxPolicy', { defaultMode: 'workspace-write', resolve: () => ({ mode: 'workspace-write', workspaceRoot: workspace }) });
  try {
    await ctx.plugin(AndroidFiles, { cwd: workspace });
    const seen = [];
    const fs = new Proxy(ctx.fs, { get(target, key) {
      if (key === 'resolve') return async (path, options) => {
        seen.push(path);
        try { return await target.resolve(path, options); }
        catch (error) { error.message += ` (instruction probe: ${path})`; throw error; }
      };
      const value = Reflect.get(target, key);
      return typeof value === 'function' ? value.bind(target) : value;
    } });
    await loadBaselineInstructions({ cwd: workspace, maxBytes: 4096 }, fs);
    assert.ok(seen.some(path => !path.startsWith(process.env.DSHX_WORKSPACE + '/')), 'upstream discovery must inspect host paths');
  } finally { await ctx.fiber.dispose(); }
});

test('Android file policy enforces physical workspace paths even under Full and stale aliases', () => runTool({ name: 'read' }, async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
  const ctx = new Context();
  const full = { mode: 'danger-full-access', workspaceRoot: workspace };
  // This test deliberately supplies the widest upstream policy. The Android
  // boundary must remain effective even if the web page records that choice.
  ctx.provide('sandboxPolicy', { defaultMode: 'workspace-write', resolve: () => full });
  try {
    await ctx.plugin(AndroidFiles, { cwd: workspace });
    const target = await ctx.fs.resolve('allowed.txt');
    await ctx.fs.writeText(target, 'allowed', { kind: 'createIfAbsent' }, undefined, full);
    assert.equal(await ctx.fs.readText(target), 'allowed');
    await assert.rejects(ctx.fs.resolve(join(directory, 'outside.txt')), { code: 'FS_SANDBOX_DENIED' });
    await assert.rejects(ctx.fs.resolve('../'.repeat(20) + 'system/build.prop'), { code: 'FS_SANDBOX_DENIED' });
    await symlink(directory, join(workspace, 'escape'));
    await assert.rejects(ctx.fs.resolve('escape/new.txt'), { code: 'FS_SANDBOX_DENIED' });
    await assert.rejects(ctx.fs.resolve('escape/../new.txt'), { code: 'FS_SANDBOX_DENIED' });
    await symlink(join(directory, 'not-created'), join(workspace, 'dangling'));
    await assert.rejects(ctx.fs.resolve('dangling/new.txt'), { code: 'FS_SANDBOX_DENIED' });
    await symlink(join(workspace, 'allowed.txt'), join(workspace, 'alias'));
    const alias = await ctx.fs.resolve('alias');
    await rm(join(workspace, 'alias'));
    await symlink(join(directory, 'outside.txt'), join(workspace, 'alias'));
    // The provider uses the checked canonical target, not a newly followed
    // model spelling. Changing the alias cannot redirect this read/write.
    assert.equal(await ctx.fs.readText(alias), 'allowed');
    await ctx.fs.writeText(alias, 'still inside', undefined, undefined, full);
    await assert.rejects(stat(join(directory, 'outside.txt')), { code: 'ENOENT' });
    const parent = join(workspace, 'parent');
    await mkdir(parent);
    const pending = await ctx.fs.resolve('parent/new.txt');
    await rename(parent, parent + '-old');
    await symlink(directory, parent);
    await assert.rejects(ctx.fs.writeText(pending, 'blocked', undefined, undefined, full), { code: 'FS_SANDBOX_DENIED' });
    await assert.rejects(ctx.fs.writeText(target, 'blocked', undefined, undefined, { mode: 'read-only', workspaceRoot: workspace }), { code: 'FS_SANDBOX_DENIED' });
  } finally { await ctx.fiber.dispose(); }
}));

test('concurrent model reads stay bounded while host configuration reads remain available', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
  const ctx = new Context();
  ctx.provide('sandboxPolicy', { defaultMode: 'workspace-write', resolve: () => ({ mode: 'workspace-write', workspaceRoot: workspace }) });
  const path = join(directory, 'host-config.txt');
  await writeFile(path, 'HOST_CONFIG_ONLY');
  try {
    await ctx.plugin(AndroidFiles, { cwd: workspace });
    await Promise.all([
      runTool({ name: 'read' }, async () => {
        await delay(15);
        assert.equal(inFileTool(), true);
        await assert.rejects(ctx.fs.resolve(path), { code: 'FS_SANDBOX_DENIED' });
        await runTool({ name: 'nested' }, () => assert.rejects(ctx.fs.resolve(path), { code: 'FS_SANDBOX_DENIED' }));
        await assert.rejects(runTool({ name: 'edit' }, async () => { throw new Error('scope cleanup'); }), /scope cleanup/);
        assert.equal(inFileTool(), true);
      }),
      (async () => {
        await delay(5);
        assert.equal(inFileTool(), false);
        assert.equal(await ctx.fs.readText(await ctx.fs.resolve(path)), 'HOST_CONFIG_ONLY');
      })()
    ]);
    assert.equal(inFileTool(), false);
    const outside = await ctx.fs.resolve(path);
    await assert.rejects(ctx.fs.writeText(outside, 'blocked'), { code: 'FS_SANDBOX_DENIED' });
    assert.equal(await readFile(path, 'utf8'), 'HOST_CONFIG_ONLY');
  } finally { await ctx.fiber.dispose(); }
});

test('official file provider creates, reads, edits and rejects stale writes on Android', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: LocalFileSystem } = await load('@deepseek-ai/dsh-fs-local');
  const ctx = new Context();
  try {
    await ctx.plugin(LocalFileSystem, { cwd: directory });
    const target = await ctx.fs.resolve('中文 文件.txt');
    const created = await ctx.fs.writeText(target, 'first\n中文内容\n', { kind: 'createIfAbsent' });
    await assert.rejects(ctx.fs.writeText(target, 'overwrite', { kind: 'createIfAbsent' }), { code: 'FS_NOT_OBSERVED' });
    const read = await ctx.fs.readText(target);
    assert.equal(read, 'first\n中文内容\n');
    const edited = await ctx.fs.editText(target, { oldString: 'first', newString: 'second', replaceAll: false }, { version: created.version });
    assert.equal(edited.after, 'second\n中文内容\n');
    await assert.rejects(ctx.fs.writeText(target, 'stale', { kind: 'replaceIfVersion', version: created.version }), { code: 'FS_STALE_VERSION' });
    assert.equal(await ctx.fs.readText(target), edited.after);
    const binary = await ctx.fs.resolve('binary.dat');
    await writeFile(binary.targetKey, Buffer.from([0, 1, 2]));
    await assert.rejects(ctx.fs.readText(binary), { code: 'FS_NOT_TEXT' });
    const controller = new AbortController();
    controller.abort();
    await assert.rejects(ctx.fs.writeText(target, 'cancelled', undefined, controller.signal), { code: 'FS_ABORTED' });
    assert.equal(await ctx.fs.readText(target), edited.after);
  } finally { await ctx.fiber.dispose(); }
});

test('official model file tools work through final dispatch and refuse outside paths and escalation', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
  const { default: Tools } = await load('@deepseek-ai/dsh-tools');
  const FileTools = await load('@deepseek-ai/dsh-tool-fs');
  const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
  const ctx = new Context();
  ctx.provide('sandboxPolicy', { defaultMode: 'workspace-write', resolve: () => ({ mode: 'workspace-write', workspaceRoot: workspace }) });
  ctx.provide('workingDirectory', { ensure: async () => workspace });
  const signal = new AbortController().signal;
  const call = (name, args, agent) => ctx.tools.execute({ name, arguments: args, signal, callId: `file-test-${name}`, ...(agent ? { agent } : {}) });
  try {
    await ctx.plugin(SystemPrompt);
    await ctx.plugin(Tools);
    await ctx.plugin(AndroidFiles, { cwd: workspace });
    await ctx.plugin(FileTools, { readImage: false });
    const path = join(workspace, 'model.txt');
    const created = await call('write', { file_path: path, content: 'model-written' });
    assert.notEqual(created.isError, true, JSON.stringify(created));
    assert.equal(await readFile(path, 'utf8'), 'model-written');
    const read = await call('read', { file_path: path });
    assert.match(JSON.stringify(read), /model-written/);
    const edited = await call('edit', { file_path: path, old_string: 'model-written', new_string: 'model-edited' });
    assert.notEqual(edited.isError, true, JSON.stringify(edited));
    assert.equal(await readFile(path, 'utf8'), 'model-edited');
    const outside = join(directory, 'model-outside.txt');
    const denied = await call('write', { file_path: outside, content: 'blocked' });
    assert.match(JSON.stringify(denied), /Android 文件与搜索仅允许访问/);
    await assert.rejects(stat(outside), { code: 'ENOENT' });
    const escalated = await call('write', { file_path: path, content: 'blocked', sandbox_permissions: 'danger-full-access', justification: 'test' });
    assert.match(JSON.stringify(escalated), /不能通过网页权限设置扩大/);
    assert.equal(await readFile(path, 'utf8'), 'model-edited');
    const child = await call('read', { file_path: join(directory, 'secret.txt') }, { id: 'child-file-test', session: {} });
    assert.match(JSON.stringify(child), /Android 文件与搜索仅允许访问/);
  } finally { await ctx.fiber.dispose(); }
});

test('official search runs the Android binary with Unicode, no-match and invalid-pattern outcomes', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: LocalSubprocess } = await load('@deepseek-ai/dsh-subprocess-local');
  const { runRipgrep, buildGlobCommand, buildGrepCommand, parseGrepMatches, resolveRgPath } = await load('@deepseek-ai/dsh-tool-fs-search');
  const ctx = new Context();
  const root = join(workspace, 'search');
  await mkdir(root);
  await writeFile(join(root, '中文 文件.txt'), '第一行\n测试内容\n');
  const signal = new AbortController().signal;
  const run = (name, argv, callSignal = signal) => runRipgrep(ctx, { signal: callSignal }, name, argv, 1024 * 1024, 1000, 4096);
  try {
    await ctx.plugin(LocalSubprocess);
    assert.equal(await realpath(await resolveRgPath()), await realpath(join(core, 'bin/rg')));
    const found = await run('grep', buildGrepCommand({ pattern: '测试内容', path: root }));
    assert.equal(found.noMatches, false);
    const matches = parseGrepMatches(found.stdout);
    assert.equal(matches.length, 1);
    assert.equal(matches[0].lineNumber, 2);
    assert.equal(matches[0].line, '测试内容');
    const files = await run('glob', buildGlobCommand({ pattern: '**/*.txt', path: root }));
    assert.match(files.stdout, /中文 文件\.txt/);
    assert.equal((await run('grep', buildGrepCommand({ pattern: 'absent-text', path: root }))).noMatches, true);
    await assert.rejects(run('grep', buildGrepCommand({ pattern: '[', path: root })), { code: 'SEARCH_INVALID_PATTERN' });
    await symlink(directory, join(root, 'escape'));
    await symlink(join(directory, 'not-created'), join(root, 'dangling'));
    for (const path of [directory, join(root, 'escape'), join(root, 'dangling')]) {
      await assert.rejects(run('grep', buildGrepCommand({ pattern: '.', path })), /Android 文件与搜索仅允许访问/);
    }
    await writeFile(join(directory, 'secret.txt'), 'OUTSIDE_SECRET');
    await symlink(directory, join(root, 'outside-link'));
    const hidden = await run('grep', buildGrepCommand({ pattern: 'OUTSIDE_SECRET', path: root }));
    assert.equal(hidden.noMatches, true);
    const controller = new AbortController();
    controller.abort();
    await assert.rejects(run('grep', buildGrepCommand({ pattern: '.', path: root }), controller.signal), { code: 'SEARCH_ABORTED' });
  } finally { await ctx.fiber.dispose(); }
});

test('current session attachments are verified and readable without granting writes or other sessions', async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { Session } = await load('@deepseek-ai/dsh-session');
  const { default: Attachments } = await load('@deepseek-ai/dsh-attachment-local');
  const { default: AndroidFiles } = await import(pathToFileURL(join(core, 'files.mjs')));
  const ctx = new Context();
  const full = { mode: 'danger-full-access', workspaceRoot: workspace };
  ctx.provide('sandboxPolicy', { defaultMode: 'workspace-write', resolve: () => full });
  try {
    await ctx.plugin(Attachments, { dshHome: join(directory, 'attachment-home') });
    await ctx.plugin(AndroidFiles, { cwd: workspace });
    const ref = await ctx.attachments.saveFile({ data: Buffer.from('附加文件：只读\n'), name: '资料.txt' });
    const other = await ctx.attachments.saveFile({ data: Buffer.from('OTHER_SESSION'), name: 'other.txt' });
    const path = ctx.attachments.fileHostPath(ref);
    const otherPath = ctx.attachments.fileHostPath(other);
    const session = Session.create('attachment-session');
    const event = session.append('user/message', { id: 'attachment-message', role: 'user', source: { kind: 'user' },
      content: [{ type: 'file', attachment: ref }] }, { surfaceOp: 'append' });
    const agent = { id: session.id, ctx, session };
    const empty = { id: 'empty-session', ctx, session: Session.create('empty-session') };
    await runTool({ name: 'read', agent }, async () => {
      const target = await ctx.fs.resolve(path);
      assert.equal(await ctx.fs.readText(target), '附加文件：只读\n');
      await assert.rejects(ctx.fs.writeText(target, 'blocked', undefined, undefined, full), { code: 'FS_SANDBOX_DENIED' });
      await assert.rejects(ctx.fs.resolve(otherPath), { code: 'FS_SANDBOX_DENIED' });
      await assert.rejects(ctx.fs.resolve(join(path, '..')), { code: 'FS_SANDBOX_DENIED' });
      await runTool({ name: 'read', agent: empty }, () => assert.rejects(ctx.fs.resolve(path), { code: 'FS_SANDBOX_DENIED' }));
      assert.equal(await ctx.fs.readText(target), '附加文件：只读\n');
    });
    for (const name of ['read', 'write', 'edit', 'glob', 'grep']) {
      await runTool({ name, ...(name === 'read' ? {} : { agent }) }, () => assert.rejects(ctx.fs.resolve(path), { code: 'FS_SANDBOX_DENIED' }));
    }
    const restored = Session.create('attachment-restored', [event]);
    await runTool({ name: 'read', agent: { id: restored.id, ctx, session: restored } }, async () => {
      assert.equal(await ctx.fs.readText(await ctx.fs.resolve(path)), '附加文件：只读\n');
    });
    await chmod(path, 0o600);
    await writeFile(path, 'CORRUPTED_ATTACHMENT');
    await runTool({ name: 'read', agent }, () => assert.rejects(ctx.fs.resolve(path), { code: 'ATTACHMENT_CORRUPT' }));
    await rm(path);
    await symlink(otherPath, path);
    await runTool({ name: 'read', agent }, () => assert.rejects(ctx.fs.resolve(path), { code: 'FS_SANDBOX_DENIED' }));
  } finally { await ctx.fiber.dispose(); }
});

test('official image store normalizes, transforms and verifies images with Android sharp', async () => {
  const sharp = require('sharp');
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: Attachments } = await load('@deepseek-ai/dsh-attachment-local');
  const ctx = new Context();
  try {
    await ctx.plugin(Attachments, { dshHome: join(directory, 'image-home'), normalizedImageMaxPixels: 4096 });
    const source = await sharp({ create: { width: 120, height: 80, channels: 3, background: { r: 42, g: 100, b: 180 } } }).png().toBuffer();
    const [ref] = await ctx.attachments.saveImages([{ data: source, mediaType: 'image/png', name: '图片.png' }]);
    assert.ok(ref.width * ref.height <= 4096);
    const stored = await ctx.attachments.readImage(ref);
    const metadata = await sharp(stored.data).metadata();
    assert.equal(metadata.width, ref.width);
    assert.equal(metadata.height, ref.height);
    assert.equal(metadata.space, 'srgb');
    const variant = await ctx.attachments.readImageRequest(ref, { width: 24, height: 16, maxBytes: 8192 });
    const transformed = await sharp(variant.data).metadata();
    assert.ok(transformed.width <= 24 && transformed.height <= 16);
    const repeat = await ctx.attachments.readImageRequest(ref, { width: 24, height: 16, maxBytes: 8192 });
    assert.deepEqual(repeat.data, variant.data);
    await assert.rejects(ctx.attachments.saveImages([{ data: Buffer.from('not an image'), mediaType: 'image/png' }]));
    await assert.rejects(ctx.attachments.saveImages([{ data: source, mediaType: 'image/jpeg' }]));
    await chmod(ctx.attachments.imageHostPath(ref), 0o600);
    await writeFile(ctx.attachments.imageHostPath(ref), 'CORRUPTED_IMAGE');
    await assert.rejects(ctx.attachments.readImage(ref), { code: 'ATTACHMENT_CORRUPT' });
  } finally { await ctx.fiber.dispose(); }
});

async function waitFor(check, message) {
  const end = Date.now() + 10_000;
  while (Date.now() < end) {
    if (await check()) return;
    await delay(25);
  }
  assert.fail(message);
}

test('official terminal writes, resizes and terminates real Android PTYs for five cycles', { timeout: 90_000 }, async () => {
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: LocalSubprocess } = await load('@deepseek-ai/dsh-subprocess-local');
  const ctx = new Context();
  try {
    await ctx.plugin(LocalSubprocess);
    for (let cycle = 0; cycle < 5; cycle++) {
      const terminal = await ctx.subprocess.spawnTerminal({
        argv: [join(core, 'bin/bash'), '--noprofile', '--norc', '-i'],
        cwd: directory, rows: 24, cols: 80, terminalType: 'xterm-256color', graceMs: 1000,
        env: { TERMINFO: join(core, 'share/terminfo'), PS1: 'dshx-test> ' }
      });
      let output = '';
      terminal.output.on('data', chunk => { output += chunk.toString('utf8'); });
      try {
        await waitFor(() => output.includes('dshx-test> '), 'interactive bash did not become ready');
        assert.equal((await terminal.inspectForeground())?.processGroupId, terminal.pid);
        await terminal.resize(100, 35);
        await terminal.write("printf '\\u4e2d\\u6587:%s:%s\\n' \"$COLUMNS\" \"$LINES\"\r");
        await waitFor(() => output.includes('中文:100:35'), 'PTY input or dimensions were not applied');
        await terminal.write("sleep 30 & printf 'CHILD:%s\\n' \"$!\"; wait\r");
        await waitFor(() => /CHILD:\d+/.test(output), 'terminal did not create the cleanup fixture');
        const child = Number(output.match(/CHILD:(\d+)/)[1]);
        await stat(`/proc/${child}/stat`);
        await terminal.terminate();
        await terminal.done;
        await terminal.terminate();
        await assert.rejects(readFile(`/proc/${terminal.pid}/stat`), { code: 'ENOENT' });
        // A killed job may briefly remain as a reparented zombie. It cannot
        // execute; a live or stopped survivor must still fail this check.
        await waitFor(async () => {
          try { return /\) [ZXx] /.test(await readFile(`/proc/${child}/stat`, 'utf8')); }
          catch (error) { if (error.code === 'ENOENT') return true; throw error; }
        }, 'a terminal background job survived termination');
      } finally { await terminal.terminate(); }
    }
  } finally { await ctx.fiber.dispose(); }
});

test('uses Android Bionic Node with the pinned major version', () => {
  assert.equal(process.platform, 'android');
  assert.equal(Number(process.versions.node.split('.')[0]), 24);
});

test('native locking rejects a second live file descriptor', async () => {
  const file = join(directory, 'lock-a');
  const first = await open(file, 'w');
  const second = await open(file, 'r+');
  try {
    native.tryLockExclusive(first.fd);
    assert.throws(() => native.tryLockExclusive(second.fd), { code: 'EAGAIN' });
  } finally { await first.close(); await second.close(); }
});

test('closing the holder releases the kernel lock', async () => {
  const file = join(directory, 'lock-b');
  const first = await open(file, 'w');
  native.tryLockExclusive(first.fd);
  await first.close();
  const second = await open(file, 'r+');
  try { native.tryLockExclusive(second.fd); } finally { await second.close(); }
});

test('another process cannot acquire an owned session lock', async () => {
  const file = join(directory, 'lock-c');
  const holder = await open(file, 'w');
  native.tryLockExclusive(holder.fd);
  try {
    const child = spawnSync(process.execPath, ['-e', `
      const fs = require('node:fs');
      const n = require(process.argv[1]);
      const fd = fs.openSync(process.argv[2], 'r+');
      try { n.tryLockExclusive(fd); process.exitCode = 2; }
      catch (e) { process.exitCode = e.code === 'EAGAIN' ? 0 : 3; }
      finally { fs.closeSync(fd); }
    `, join(core, 'native.node'), file], { encoding: 'utf8' });
    assert.equal(child.status, 0, child.stderr);
  } finally { await holder.close(); }
});

test('invalid lock arguments fail visibly', () => {
  assert.throws(() => native.tryLockExclusive(-1), TypeError);
});

test('atomic publication preserves a pre-existing destination', async () => {
  const source = join(directory, 'publish-source');
  const target = join(directory, 'publish-target');
  await writeFile(source, 'new');
  await writeFile(target, 'old');
  assert.throws(() => native.publish(source, target), { code: 'EEXIST' });
  assert.equal(await readFile(target, 'utf8'), 'old');
  assert.equal(await readFile(source, 'utf8'), 'new');
});

test('atomic publication moves a complete staged file', async () => {
  const source = join(directory, 'publish-new');
  const target = join(directory, 'published');
  await writeFile(source, 'complete data');
  native.publish(source, target);
  assert.equal(await readFile(target, 'utf8'), 'complete data');
  await assert.rejects(stat(source), { code: 'ENOENT' });
});

test('compatibility link leaves the source intact and refuses overwrite', async () => {
  await import(pathToFileURL(join(core, 'compat.mjs')));
  const source = join(directory, 'link-source');
  const target = join(directory, 'link-target');
  await writeFile(source, 'persistent session');
  await link(source, target);
  assert.equal(await readFile(source, 'utf8'), 'persistent session');
  assert.equal(await readFile(target, 'utf8'), 'persistent session');
  await assert.rejects(link(source, target), { code: 'EEXIST' });
  await chmod(source, 0o400);
  const readonly = join(directory, 'link-readonly-target');
  await link(source, readonly);
  assert.equal(await readFile(readonly, 'utf8'), 'persistent session');
  assert.equal((await stat(readonly)).mode & 0o777, 0o400);
  await assert.rejects(link(source, readonly), { code: 'EEXIST' });
  assert.equal(await readFile(source, 'utf8'), 'persistent session');
});

test('official flock entry actually uses the Android kernel binding', async () => {
  const file = join(directory, 'official-lock');
  const { tryLockExclusive } = await import(pathToFileURL(require.resolve('@deepseek-ai/node-addon-system/flock')));
  const a = await open(file, 'w');
  const b = await open(file, 'r+');
  try {
    await tryLockExclusive(a.fd);
    await assert.rejects(tryLockExclusive(b.fd), { code: 'EAGAIN' });
  } finally { await a.close(); await b.close(); }
});

test('official loader receives real Node internals on Android', () => {
  const api = require('node-addon-require-builtin');
  assert.equal(typeof api.requireBuiltin('internal/modules/esm/loader').getOrInitializeCascadedLoader, 'function');
});

for (const scenario of ['direct', 'later allow listener', 'removed plugin guard', 'subagent']) {
  test(`final Android tool refusal covers ${scenario}`, async () => {
    const { Context } = await load('@deepseek-ai/cordis');
    const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
    const { default: Tools } = await load('@deepseek-ai/dsh-tools');
    const ctx = new Context();
    let calls = 0;
    try {
      await ctx.plugin(SystemPrompt);
      await ctx.plugin(Tools);
      ctx.tools.register({
        name: 'test_effect', description: 'test only',
        parameters: { type: 'object', properties: {} },
        output: { schema: { type: 'string' }, render: (_, value) => [{ type: 'text', text: value }] },
        execute: async () => { calls++; return 'unexpected execution'; }
      });
      if (scenario === 'later allow listener') ctx.on('tools/pre-execute', async () => ({ kind: 'allow' }), { prepend: true });
      if (scenario === 'removed plugin guard') { const remove = ctx.tools.guard(() => 'temporary'); remove(); }
      const result = await ctx.tools.execute({
        name: 'test_effect', callId: 'android-test', arguments: {}, signal: new AbortController().signal,
        ...(scenario === 'subagent' ? { agent: { id: 'child-test' } } : {})
      });
      assert.equal(calls, 0);
      assert.match(JSON.stringify(result), /其他模型工具尚未开放/);
    } finally { await ctx.fiber.dispose(); }
  });
}
