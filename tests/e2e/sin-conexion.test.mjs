// PRUEBA: después de abrir la app una vez, funciona sin conexión (trabajo de campo).
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium, click, newPage, startServer, tid } from './helpers.mjs';

let server;
let browser;
before(async () => { server = await startServer(); browser = await chromium.launch(); });
after(async () => { await browser?.close(); server?.stop(); });

test('PRUEBA la aplicación abre sin conexión tras la primera visita', { timeout: 60000 }, async () => {
  const { page, context, errors } = await newPage(browser, server.url);
  await page.waitForFunction(async () => (await navigator.serviceWorker.getRegistration())?.active?.state === 'activated', null, { timeout: 30000 });
  await page.waitForFunction(async () => (await caches.keys()).some((k) => k.startsWith('toposcribe-')));
  await page.waitForFunction(() => !!navigator.serviceWorker.controller, null, { timeout: 30000 });
  await context.setOffline(true);
  await page.reload();
  await page.locator(tid('current-project')).waitFor();
  await click(page, 'nav-libreta');
  await page.locator('main').getByText('Primero cree un proyecto').waitFor({ timeout: 10000 });
  assert.match(await page.textContent('main'), /Primero cree un proyecto/);
  assert.deepEqual(errors, []);
  await context.close();
});
