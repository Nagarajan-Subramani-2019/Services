import { mkdir, copyFile, realpath, stat } from 'node:fs/promises';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = await realpath(resolve(dirname(fileURLToPath(import.meta.url)), '..'));
const destination = join(root, 'dist');
await mkdir(destination, { recursive: true });
if (await realpath(destination) !== destination || !destination.startsWith(root + sep)) throw new Error('Unsafe frontend destination.');
const files = [
  'js/components/transactions/loader.js',
  'js/components/transactions/styles.css',
  'js/components/transactions/manifest.json',
  'js/components/resources/cs-mapping.json'
];
for (const name of files) {
  const target = join(destination, name);
  let assetDirectory = destination;
  for (const segment of name.split('/').slice(0, -1)) {
    assetDirectory = join(assetDirectory, segment);
    try { await mkdir(assetDirectory); }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
    if (await realpath(assetDirectory) !== assetDirectory || !(await stat(assetDirectory)).isDirectory()) {
      throw new Error(`Unsafe asset directory: ${name}`);
    }
  }
  try {
    if (await realpath(target) !== target || !(await stat(target)).isFile()) throw new Error(`Unsafe asset target: ${name}`);
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  await copyFile(join(root, 'src', name), target);
}
console.log('Built component-server resources under dist/js/components.');
