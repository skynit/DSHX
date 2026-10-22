export const name = 'dshx-android-guard';
export const inject = ['tools', 'systemPrompt', 'agents'];
import { toolReason } from './policy.mjs';
import { policyHost } from './host.mjs';
import { trackActions, forgetActions } from './assistant.mjs';
import { trackPhone } from './phone.mjs';

// Mirror the final-entry restriction for ordinary root plugin composition.
// Actual path checks live in the file provider and search invocation.
export async function apply(ctx) {
  await policyHost.connect();
  trackActions(ctx);
  trackPhone(ctx);
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
  ctx.on('agent/disposed', ({ agent }) => {
    forgetActions(agent.session.id);
    return policyHost.end(agent.session.id).catch(() => {});
  });
  ctx.on('agent/request', async ({ agent }, next) => { await register(agent); return next(); });
  ctx.on('agent/status', ({ agent, status }) => {
    if (status === 'idle') {
      forgetActions(agent.session.id);
      policyHost.finishPhone(agent.session.id).catch(() => {});
    }
  });
  ctx.effect(() => () => policyHost.close());
  ctx.tools.guard(toolReason);
  ctx.systemPrompt.section({
    name: 'android:tools', order: 100,
    text: 'Android 当前默认完全权限，已适配 read、write、edit、glob、grep、前台 bash 和 phone_* 手机工具；可访问本应用拥有系统访问权限的文件，工作区只是默认目录。手机工具仍需原生手机操作开关；屏幕能力需要无障碍服务，其他能力按需检查独立开关和系统授权。无需用户逐会话开启 Full，不提供会话沙箱隔离，也不会因此获得手机 root 或额外系统权限。命令执行后的文件和配置正常保留。插件安装管理使用网页设置入口；其他模型工具尚未适配，不支持后台命令和 sandbox_permissions 参数。'
  });
}
