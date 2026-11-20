// PRUEBA de la app tal como la carga el APK de Android: origen https://appassets.androidplatform.net/assets/,
// tipos MIME iguales a los de MainActivity.java y un puente AndroidBridge simulado (el real guarda en Descargas).
// No reemplaza una prueba en un teléfono: verifica la parte web del contenedor.
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import { ROOT, addLevel, chromium, click, devices, newProject, pdfText, setBm, setUser, tid } from './helpers.mjs';

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.wasm': 'application/wasm', '.ttf': 'font/ttf', '.txt': 'text/plain' };
let browser;
before(async () => { browser = await chromium.launch(); });
after(async () => { await browser?.close(); });

test('PRUEBA contenedor Android: la app carga desde los assets del APK y entrega los archivos al puente nativo', { timeout: 120000 }, async () => {
  const context = await browser.newContext({ ...devices['Pixel 7'] });
  await context.route('https://appassets.androidplatform.net/assets/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/assets\//, '') || 'index.html';
    try {
      const body = await readFile(join(ROOT, 'web', path));
      await route.fulfill({ status: 200, contentType: MIME[extname(path)] ?? 'application/octet-stream', body });
    } catch {
      await route.fulfill({ status: 404, body: '' });
    }
  });
  await context.addInitScript(() => {
    window.__saved = [];
    window.AndroidBridge = { isAndroid: () => true, saveFile: (name, mime, b64) => { window.__saved.push({ name, mime, b64 }); return `Descargas/TopoScribePro/${name}`; } };
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto('https://appassets.androidplatform.net/assets/index.html');
  await page.waitForSelector(tid('current-project'));

  await setUser(page, 'Prueba APK');
  await newProject(page, { name: 'PRUEBA — contenedor Android' });
  await setBm(page, 'BM', '100.000');
  await addLevel(page, { code: 'BM', kind: 'BM', bs: '1.250', inProfile: false });
  await addLevel(page, { code: 'P1', kind: 'VD', fs: '0.850', inProfile: false });
  await click(page, 'nav-nivel');
  await click(page, 'btn-calcular-nivel');
  await click(page, 'nav-informe');
  await click(page, 'btn-generar-informe');
  await page.waitForFunction(() => window.__saved.length > 0, null, { timeout: 60000 });
  const saved = await page.evaluate(() => window.__saved[0]);
  assert.match(saved.name, /^Informe_.*\.pdf$/);
  assert.equal(saved.mime, 'application/pdf');
  const { text } = await pdfText(Buffer.from(saved.b64, 'base64'));
  assert.match(text, /Cota P1 = AI \(E1\) − VD = 101\.250 − 0\.850 = 100\.400 m/);
  assert.equal(await page.evaluate(async () => (await navigator.serviceWorker.getRegistrations()).length), 0, 'en el APK no se registra el service worker');
  assert.deepEqual(errors, []);
  await context.close();
});
