import { mkdir, copyFile, realpath, stat } from 'node:fs/promises';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

// Only known frontend assets are copied. No dependency downloads or recursive deletion.
const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const root = await realpath(frontend);
const destination = join(root, 'dist');
await mkdir(destination, { recursive: true });
const resolvedDestination = await realpath(destination);
if (resolvedDestination !== destination || !resolvedDestination.startsWith(root + sep)) {
  throw new Error('Refusing to write outside the frontend/dist directory.');
}
const files = ['index.html', 'styles.css', 'app.js', 'api.js', 'validation.js'];
for (const name of files) {
  const target = join(destination, name);
  // Reject an existing symlink target rather than following it outside dist.
  try {
    if (await realpath(target) !== target) throw new Error(`Unsafe asset target: ${name}`);
    if (!(await stat(target)).isFile()) throw new Error(`Asset target is not a file: ${name}`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  await copyFile(join(root, 'src', name), target);
}
console.log(`Built ${files.length} frontend assets in ${destination}`);
