import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { join } from 'node:path';
import { policyHost } from './host.mjs';

export const name = 'dshx-android-apps';
export const inject = ['tools', 'systemPrompt', 'sessionController', 'agents', 'storageDomain'];
const text = description => ({ type: 'string', required: true, description });
const number = { type: 'integer', required: true, description: 'app_status 返回的原样 revision，不能猜测。' };
const common = { operationId: text('一次操作的稳定编号，建议 UUID；超时按原编号查询，不换编号重做。'), expectedRevision: number };
const id = text('实际微应用编号。');
const object = description => ({ type: 'object', required: true, additionalProperties: true, description });
export const appTools = [
  { name: 'app_status', description: '读取真实微应用能力、安装包、配置和版本；可按原操作编号核实结果。', parameters: {
    id: { type: 'string', description: '可选微应用编号。' }, operationId: { type: 'string', description: '可选原操作编号，填写时查询操作回执。' } } },
  { name: 'app_check', description: '检查 web-v1 JSON 微应用草稿，不安装、不点击，返回五分钟有效的检查标识和内容摘要。', parameters: { package: object('完整 app.json 对象，最多1MiB，包含页面和脚本文件。') } },
  { name: 'app_install', description: '安装已检查的应用包；新增与更新均保持停用，用户选择范围后再启用。', parameters: { ...common, checkId: text('原样检查标识。'), hash: text('原样内容摘要。') } },
  { name: 'app_export', description: '在手机内将已安装的指定版本打包并签名为独立 APK，不启用应用。返回持久文件路径，随后用 present 交付给用户；打包成功不代表安装或功能验收。', parameters: { id, version: text('app_status 返回的当前版本，版本变化时重新查询。') } },
  { name: 'app_enable', description: '在用户已授权持续运行时启用微应用；适用应用必须来自真实列表。系统授权仍需用户完成。', parameters: { ...common, id, settings: object('全部用户配置值，app-list 必须为真实允许包名列表，不能为空范围。') } },
  { name: 'app_disable', description: '停用微应用并撤销未派发动作，不影响其他功能。', parameters: { ...common, id } },
  { name: 'app_logs', description: '读取微应用真实运行记录，区分点击返回、已确认、失败和结果未知。', parameters: { id, limit: { type: 'integer', description: '1至100，默认20。' } } },
  { name: 'app_rollback', description: '回到已经保存的旧版本并暂停，保留兼容配置；不重放历史动作。', parameters: { ...common, id, version: text('已安装过的版本号。') } },
  { name: 'app_remove', description: '移除微应用，先停止功能；仅按用户选择删除独立运行记录。', parameters: { ...common, id, deleteData: { type: 'boolean', required: true, description: '是否删除本应用独立记录。' } } },
];
export const appToolNames = new Set(appTools.map(tool => tool.name));

export async function callApp(host, action, args, exec) {
  exec.signal?.throwIfAborted();
  if (!exec.agent?.session?.id) throw new Error('微应用管理需要当前有效会话。');
  if (!appToolNames.has(`app_${action}`) || Buffer.byteLength(JSON.stringify(args)) > 1024 * 1024 + 8192) throw new Error('微应用管理参数无效或过大。');
  return host.query('apps', { action, args, session: exec.agent.session.id }, exec.signal);
}

export function appCreator(ctx, home = process.env.DSH_HOME, history) {
  const requests = new Map();
  return async data => {
    if (!data || Object.keys(data).some(key => !['operationId', 'prompt', 'session', 'output'].includes(key)) ||
        typeof data.operationId !== 'string' || !/^[a-zA-Z0-9-]{1,80}$/.test(data.operationId) ||
        typeof data.prompt !== 'string' || !data.prompt.trim() || data.prompt.length > 4000 || !home ||
        data.output !== undefined && !['micro', 'apk'].includes(data.output) ||
        data.session !== undefined && (typeof data.session !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(data.session))) throw new Error('创建需求或编号无效。');
    const previous = requests.get(data.operationId);
    const signature = JSON.stringify(data);
    if (previous) {
      if (previous.signature !== signature) throw new Error('创建编号已用于其他需求。');
      return previous.result;
    }
    if (requests.size >= 100) throw new Error('创建请求过多，请重启引擎后重试。');
    const result = (async () => {
      const saved = history?.get(data.operationId);
      if (saved) {
        if (saved.signature !== signature) throw new Error('创建编号已用于其他需求。');
        if (!saved.session) throw new Error('上次创建未确认，请先检查已有会话，不能自动重建。');
        return { session: saved.session, delivery: saved.stage };
      }
      const save = value => history?.set(data.operationId, { signature, ...value });
      await save({ stage: 'starting' });
      const session = data.session ?? (await ctx.sessionController.create({})).sessionId;
      await save({ stage: 'created', session });
      const agent = await ctx.sessionController.commands.resolveAgent(session);
      if (agent.status !== 'idle' || agent.inbox.hasPending || agent.session.header.parentSession || agent.session.header.origin === 'subagent') throw new Error('创建会话不是空闲普通会话。');
      await save({ stage: 'submitted', session });
      const delivery = data.output === 'apk'
        ? '用户选择直接交付 APK：安装并核实版本后，调用 app_export({id,version}) 在手机内打包，再用 present 交付返回的 path。必须取得真实 APK 文件，不能仅提供源码或让用户自行寻找打包入口；失败时说明当前步骤与恢复方式，不伪造成功。'
        : '用户选择先创建微应用：安装并核实版本后交付应用入口；如用户需求还要求 APK，继续调用 app_export({id,version}) 并用 present 交付文件。已安装的微应用也可在详情中打包。';
      await ctx.sessionController.commands.prompt({ sessionId: session, requestId: data.operationId, mode: 'queue', content: [{ type: 'text',
        text: `请先读取技能 ${join(home, 'skills/create-dshx-app/SKILL.md')} 及所需参考文件，再通过 app_status 查询真实能力。根据下面需求创建或修改微应用，生成 app.json，验证核心逻辑并检查后安装为停用状态。${delivery} 安装后由用户在设置查看能力并启用；仅屏幕功能需要选择适用应用，不要自行开启持续手机操作。先简短说明要做的应用与主要操作，制作中说明真实进展，缺少关键外部服务配置时明确提示。用户需求：\n\n${data.prompt.trim()}`,
      }] }, { kind: 'user', rpcId: data.operationId, clientTimeZone: 'Asia/Shanghai' });
      await save({ stage: 'accepted', session });
      return { session };
    })();
    requests.set(data.operationId, { signature, result });
    return result;
  };
}

export async function apply(ctx) {
  const require = createRequire(new URL('./lib/node_modules/package.json', import.meta.url));
  const { defineTool } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-tools')));
  const { defineDomain } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-storage-domain')));
  const { z } = require('zod');
  const schema = z.object({ requests: z.array(z.object({ id: z.string(), signature: z.string(), session: z.string().optional(),
    stage: z.enum(['starting', 'created', 'submitted', 'accepted']) }).strict()).max(100) }).strict();
  const domain = await ctx.storageDomain.open(defineDomain({ name: 'app_creation', version: 1, layout: 'single', tables: {},
    global: { schema, initial: { requests: [] } } }));
  let queue = Promise.resolve();
  const history = {
    get: id => schema.parse(domain.global.get()).requests.find(row => row.id === id),
    set: (id, value) => {
      const result = queue.then(async () => {
        const rows = schema.parse(domain.global.get()).requests.filter(row => row.id !== id);
        await domain.global.set({ requests: [...rows, { id, ...value }].slice(-100) });
      });
      queue = result.catch(() => {});
      return result;
    },
  };
  for (const definition of appTools) ctx.tools.register(defineTool({ ...definition, isConcurrencySafe: () => false,
    output: { schema: { type: 'object', additionalProperties: true }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
    execute: (args, exec) => callApp(policyHost, definition.name.slice(4), args, exec),
  }));
  const create = appCreator(ctx, process.env.DSH_HOME, history);
  policyHost.createApp = create;
  ctx.effect(() => async () => { if (policyHost.createApp === create) policyHost.createApp = undefined; await queue; await domain.close(); });
  ctx.systemPrompt.section({ name: 'android:apps', order: 106,
    text: 'DSHX 支持 web-v1 通用微应用，包含HTML页面、JavaScript逻辑、独立存储及可选后台入口。广告跳过只是一个示例，不把所有需求变成点击规则。创建或修改前读取 create-dshx-app 技能并用 app_status 核对真实能力，app_check 检查 app.json，app_install 安装为停用；用户在设置选择应用并启用。用户要求 APK 时，在核实安装版本后调用 app_export 并用 present 交付返回的持久文件，不能只交源码或声称已安装 APK。修改工具须传稳定 operationId 和当前 expectedRevision；断连或超时先按原编号查询，不能盲目重做。微应用本机监听与聊天会话独立，页面、存储和后台运行不依赖dsh模型持续在线；屏幕能力须声明并受用户选择的应用范围限制，持续操作须有用户明确授权。设置或文件声明不能代替系统授权。不能直接改 micro-apps 安装目录或伪造手机会话。日志 completed 只代表动作回执，不能将其当作业务成功确认，未知结果不重放。',
  });
}
