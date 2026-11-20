#!/usr/bin/env node
// Regenera web/sw.js con la lista de archivos de la app y una versión (hash del contenido).
// Ejecútelo después de modificar cualquier archivo de web/:  npm run build:sw
import { createHash } from 'node:crypto';
import { readdir, readFile, writeFile } from 'node:fs/promises';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../web/', import.meta.url));
async function walk(dir) {
  const out = [];
  for (const e of await readdir(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...(await walk(p)));
    else out.push(p);
  }
  return out;
}
const files = (await walk(root))
  .map((f) => relative(root, f).split('\\').join('/'))
  .filter((f) => f !== 'sw.js' && !f.startsWith('vendor/licenses/') && !f.endsWith('.map'))
  .sort();
const hash = createHash('sha256');
for (const f of files) hash.update(f).update(await readFile(join(root, f)));
const version = hash.digest('hex').slice(0, 12);

const sw = `// GENERADO por tools/build-sw.mjs — no editar a mano.
// Guarda la aplicación en el dispositivo para que funcione sin conexión (en el campo).
const CACHE = 'toposcribe-${version}';
const FILES = ${JSON.stringify(['./', ...files], null, 2)};

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k.startsWith('toposcribe-') && k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;
  event.respondWith(caches.match(event.request, { ignoreSearch: true }).then((hit) => hit || fetch(event.request)));
});
`;
await writeFile(join(root, 'sw.js'), sw);
console.log(`sw.js generado: ${files.length} archivos, versión ${version}`);
