import { createRequire, syncBuiltinESMExports } from 'node:module';
import fs from 'node:fs';
import promises from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { policyHost } from './host.mjs';

// Finish authentication before loading the full official profile. Its cold
// module initialization can otherwise delay socket callbacks past the native
// handshake deadline while every model call correctly remains unavailable.
if (policyHost.configured) await policyHost.connect();

const native = createRequire(import.meta.url)('./native.node');
const originalLink = promises.link.bind(promises);

// A copied inode is published atomically and exclusively. A crash can leave only
// an unreferenced temporary file, never a partly written destination log.
promises.link = async (source, target) => {
  try { return await originalLink(source, target); }
  catch (error) {
    if (!['EPERM', 'EACCES', 'ENOSYS', 'EXDEV'].includes(error.code)) throw error;
  }
  const staging = join(dirname(target), `.dshx-${randomUUID()}.tmp`);
  try {
    await promises.copyFile(source, staging, fs.constants.COPYFILE_EXCL);
    // copyFile preserves a read-only source's mode. fsync only needs a read
    // descriptor; requesting write access would break immutable attachments.
    const handle = await promises.open(staging, 'r');
    try { await handle.sync(); } finally { await handle.close(); }
    native.publish(staging, target);
  } finally { await promises.rm(staging, { force: true }); }
};
syncBuiltinESMExports();
