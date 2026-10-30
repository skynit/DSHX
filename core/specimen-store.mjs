import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const require = createRequire(new URL('./lib/node_modules/package.json', import.meta.url));
export const specimenFailure = message => new Error(message);
export const initialSpecimen = () => ({ version: 1, revision: 0, epoch: 0, runs: [], jobs: [], skills: [] });

export async function openSpecimen(ctx) {
  const { z } = await import(pathToFileURL(require.resolve('zod')));
  const { defineDomain } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-storage-domain')));
  const number = z.number().int().nonnegative(), id = z.string().min(1).max(300);
  const ref = z.object({ seq: number, text: z.string().min(1).max(2000) }).strict();
  const run = z.object({ id, session: id, workspace: z.string().startsWith('/').max(2048), from: number.nullable(), to: number.nullable(),
    execution: z.enum(['running', 'completed', 'failed', 'cancelled', 'incomplete']), result: z.enum(['not_checked', 'supported', 'failed', 'unknown']),
    startedAt: number, endedAt: number.nullable(), revision: number, requests: z.array(z.object({ id, provider: id, model: id,
      usage: z.record(z.string(), number).optional(), outcome: z.string().max(30).optional() }).strict()).max(64),
    reason: z.string().max(100).optional(), route: z.object({ provider: id, model: id }).strict().optional(),
    routes: z.array(z.object({ provider: id, model: id }).strict()).max(32).optional(),
    goal: id.optional(), goalHandled: z.boolean().optional(), uncertain: z.boolean().optional(),
    skill: id.optional(), skillDigest: z.string().length(64).optional(), checkText: z.string().min(1).max(200).optional(), checks: z.array(ref).max(12).optional(),
  }).strict().refine(row => row.to === null || row.from !== null && row.to > row.from);
  const skill = z.object({ id, run: id, name: z.string().regex(/^[a-z0-9]+(?:-[a-z0-9]+)*$/).max(80),
    title: z.string().min(1).max(100), description: z.string().min(1).max(160), body: z.string().min(1).max(16000),
    digest: z.string().length(64), revision: number, state: z.enum(['candidate', 'applied', 'retired']),
    createdAt: number, evidence: z.array(ref).min(1).max(16),
    uses: z.array(z.object({ run: id, digest: z.string().length(64), result: z.enum(['supported', 'failed', 'unknown', 'not_checked']) }).strict()).max(100),
    tools: z.array(z.string().min(1).max(100)).max(12).optional(),
    operation: id.optional(), receipt: z.object({ path: z.string().min(1).max(2048), digest: z.string().length(64) }).strict().optional(),
  }).strict();
  const goal = z.object({ id, text: z.string().min(1).max(2000), checkText: z.string().max(200), state: z.enum(['active', 'paused', 'completed']),
    maxRuns: number.min(1).max(100), attempts: number, noProgress: number, findings: z.array(z.string().max(300)).max(20),
    next: z.string().max(1000), reason: z.string().max(100), revision: number, blockers: z.array(id).max(32).default([]), seen: z.array(z.string().length(64)).max(20).default([]),
  }).strict();
  const schema = z.object({ version: z.literal(1), revision: number, epoch: number, goal: goal.nullable().default(null), runs: z.array(run).max(100),
    jobs: z.array(z.object({ id, run: id, epoch: number, state: z.enum(['waiting', 'failed']), attempts: number.max(2),
      message: z.string().max(220).optional() }).strict()).max(32), skills: z.array(skill).max(64),
  }).strict().refine(value => value.skills.filter(row => row.state === 'candidate').length <= 32 &&
    new Set(value.runs.map(row => row.id)).size === value.runs.length && new Set(value.jobs.map(row => row.id)).size === value.jobs.length &&
    new Set(value.skills.map(row => row.id)).size === value.skills.length && Buffer.byteLength(JSON.stringify(value)) <= 4 * 1024 * 1024);
  const domain = await ctx.storageDomain.open(defineDomain({ name: 'specimen', version: 1, layout: 'single', tables: {},
    global: { schema, initial: initialSpecimen() } }));
  let queue = Promise.resolve(), closed = false;
  const read = () => schema.parse(domain.global.get());
  return { read, change(update, revision) {
    const result = queue.then(async () => {
      if (closed) throw specimenFailure('培养记录已关闭。');
      const next = read();
      if (revision !== undefined && revision !== next.revision) throw specimenFailure('培养记录已经变化，请刷新。');
      if (update(next) === false) return read();
      next.revision++;
      if (!schema.safeParse(next).success) throw specimenFailure('培养数据无效或容量已满。');
      await domain.global.set(next);
      return read();
    });
    queue = result.catch(() => {});
    return result;
  }, async close() { await queue; closed = true; await domain.close(); } };
}
