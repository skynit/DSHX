import { fileURLToPath, pathToFileURL } from 'node:url';
import { join } from 'node:path';
import { packageManager } from './plugins.mjs';

const core = fileURLToPath(new URL('.', import.meta.url));
const { runCli } = await import(pathToFileURL(join(core, 'lib/node_modules/@deepseek-ai/dsh/lib/bin.js')));
await runCli({ packageManager: packageManager(core) });
