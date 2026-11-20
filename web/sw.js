// GENERADO por tools/build-sw.mjs — no editar a mano.
// Guarda la aplicación en el dispositivo para que funcione sin conexión (en el campo).
const CACHE = 'toposcribe-35ba49b83b67';
const FILES = [
  "./",
  "css/app.css",
  "icons/icon-192.png",
  "icons/icon-512.png",
  "icons/icon.svg",
  "index.html",
  "js/core/angles.js",
  "js/core/contours.js",
  "js/core/leveling.js",
  "js/core/model.js",
  "js/core/numbers.js",
  "js/core/plan.js",
  "js/core/radiation.js",
  "js/core/scales.js",
  "js/export/dxf-template.js",
  "js/export/dxf.js",
  "js/export/dxfVerify.js",
  "js/export/pdf.js",
  "js/export/projectFile.js",
  "js/export/tables.js",
  "js/export/xlsx.js",
  "js/export/zip.js",
  "js/import/androidRoom.js",
  "js/main.js",
  "js/storage/db.js",
  "js/ui/androidImport.js",
  "js/ui/app.js",
  "js/ui/components.js",
  "js/ui/files.js",
  "js/ui/mutations.js",
  "js/ui/svg.js",
  "js/ui/views/informe.js",
  "js/ui/views/libreta.js",
  "js/ui/views/nivel.js",
  "js/ui/views/plano.js",
  "js/ui/views/proyecto.js",
  "js/ui/views/teodolito.js",
  "manifest.webmanifest",
  "vendor/fonts/DejaVuSans-Bold-subset.ttf",
  "vendor/fonts/DejaVuSans-subset.ttf",
  "vendor/jspdf.umd.min.js",
  "vendor/pdfjs/pdf.min.mjs",
  "vendor/pdfjs/pdf.worker.min.mjs",
  "vendor/preact-htm.mjs",
  "vendor/sqljs/sql-wasm-browser.js",
  "vendor/sqljs/sql-wasm-browser.wasm"
];

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
