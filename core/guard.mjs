export const name = 'dshx-android-guard';
export const inject = ['tools', 'systemPrompt', 'agents'];
import { toolReason } from './policy.mjs';
import { policyHost } from './host.mjs';

// Mirror the final-entry restriction for ordinary root plugin composition.
// Actual path checks live in the file provider and search invocation.
export async function apply(ctx) {
  await policyHost.connect();
  const register = async agent => {
    // The official registry owns live parentage. Durable fork history can
    // resume as an independent root and must not require an inactive parent.
    const parent = ctx.agents.list().find(candidate => ctx.agents.isOwnedBy(agent.id, candidate));
    if (parent) await register(parent);
    return policyHost.register(agent.session.id,
      ctx.get('sessionTitle')?.get(agent.session)?.title ?? '未命名会话', parent?.session.id ?? null);
  };
  for (const agent of ctx.agents.list()) await register(agent);
  ctx.on('agent/created', async ({ agent }) => { await register(agent); });
  ctx.on('agent/disposed', ({ agent }) => policyHost.end(agent.session.id).catch(() => {}));
  ctx.on('agent/request', async ({ agent }, next) => { await register(agent); return next(); });
  ctx.effect(() => () => policyHost.close());
  ctx.tools.guard(toolReason);
  ctx.systemPrompt.section({
    name: 'android:tools', order: 100,
    text: 'Android 当前默认完全权限，已适配 read、write、edit、glob、grep 和前台 bash；可访问本应用拥有系统访问权限的文件，工作区只是默认目录。无需用户逐会话开启 Full，不提供会话沙箱隔离，也不会因此获得手机 root 或额外系统权限。命令执行后的文件和配置正常保留。其他模型工具及插件管理尚未适配；不支持后台命令和 sandbox_permissions 参数。'
  });
}
