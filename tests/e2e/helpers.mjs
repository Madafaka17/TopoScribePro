// Utilidades de las pruebas de extremo a extremo (Playwright + servidor local).
// Cada prueba usa un navegador con almacenamiento VACÍO y propio: los casos de prueba nunca tocan
// los proyectos reales del usuario.
import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
export const { chromium, devices } = require('playwright');

export const ROOT = fileURLToPath(new URL('../../', import.meta.url));
export const FIXTURES = fileURLToPath(new URL('../fixtures/', import.meta.url));
export const OUT = fileURLToPath(new URL('../../test-results/', import.meta.url));

export function startServer() {
  return new Promise((resolve, reject) => {
    const proc = spawn(process.execPath, ['tools/serve.mjs', '0'], { cwd: ROOT, stdio: ['ignore', 'pipe', 'inherit'] });
    proc.stdout.on('data', (d) => {
      const m = /http:\/\/localhost:(\d+)/.exec(String(d));
      if (m) resolve({ url: `http://localhost:${m[1]}/`, stop: () => proc.kill() });
    });
    proc.on('error', reject);
    setTimeout(() => reject(new Error('El servidor no arrancó')), 10000);
  });
}

export async function newPage(browser, url, options = {}) {
  const context = await browser.newContext({ acceptDownloads: true, ...options });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  await page.goto(url);
  await page.waitForSelector('[data-testid="current-project"]');
  return { context, page, errors };
}

export const tid = (id) => `[data-testid="${id}"]`;

/** Pulsa un botón por data-testid comprobando que NO esté deshabilitado. */
export async function click(page, id) {
  const btn = page.locator(tid(id)).first();
  await btn.waitFor({ state: 'visible' });
  const disabled = await btn.getAttribute('aria-disabled');
  if (disabled === 'true') throw new Error(`El botón ${id} está deshabilitado: ${await btn.getAttribute('title')}`);
  await btn.click();
}

export async function fill(page, id, value) {
  const el = page.locator(tid(id)).first();
  await el.fill(String(value));
}

export async function select(page, id, value) {
  await page.locator(tid(id)).first().selectOption(value);
}

export async function setUser(page, name) {
  await click(page, 'nav-proyecto');
  await click(page, 'btn-usuario');
  await fill(page, 'user-name', name);
  await click(page, 'user-save');
  await page.locator(tid('user-label'), { hasText: name }).waitFor();
}

export async function newProject(page, fields) {
  await click(page, 'nav-proyecto');
  await click(page, 'btn-nuevo-proyecto');
  await fill(page, 'np-name', fields.name);
  if (fields.location) await fill(page, 'np-location', fields.location);
  if (fields.responsibles) await fill(page, 'np-responsibles', fields.responsibles);
  if (fields.crs) await fill(page, 'np-crs', fields.crs);
  if (fields.objective) await fill(page, 'np-objective', fields.objective);
  await click(page, 'np-guardar');
  await page.locator(tid('current-project'), { hasText: fields.name }).waitFor();
}

export async function setBm(page, code, elevation, end = null) {
  await click(page, 'nav-libreta');
  await click(page, 'sub-nivelacion');
  await fill(page, 'bm-code', code);
  await fill(page, 'bm-elev', elevation);
  if (end) {
    await page.locator(tid('bm-end-enabled')).check();
    await fill(page, 'bm-end-code', end.code);
    await fill(page, 'bm-end-elev', end.elevation);
  }
  await click(page, 'bm-guardar');
  await page.locator(tid('bm-card')).getByText('Sin guardar').waitFor({ state: 'detached' });
}

/** Añade una fila de nivelación. kind: BM | VI | VD | PC */
export async function addLevel(page, { code, kind, bs, is, fs, partial, cumulative, inProfile = true, desc }) {
  await click(page, 'btn-anadir-lectura-nivel');
  const dlg = page.locator(tid('level-dialog'));
  await dlg.waitFor();
  if (kind !== 'BM') {
    await fill(page, 'lv-code', code);
    await dlg.locator(tid('lv-kind')).getByText({ VI: 'Vista intermedia', VD: 'Vista adelante', PC: 'Punto de cambio (VD + VA)' }[kind], { exact: true }).click();
  }
  if (desc && await page.locator(tid('lv-desc')).count()) await fill(page, 'lv-desc', desc);
  if (bs !== undefined) await fill(page, 'lv-bs', bs);
  if (is !== undefined) await fill(page, 'lv-is', is);
  if (fs !== undefined) await fill(page, 'lv-fs', fs);
  const cb = page.locator(tid('lv-inprofile'));
  if ((await cb.isChecked()) !== inProfile) await cb.click();
  if (inProfile) {
    if (partial !== undefined) await fill(page, 'lv-partial', partial);
    if (cumulative !== undefined) await fill(page, 'lv-cumulative', cumulative);
  }
  await click(page, 'lv-guardar');
  await dlg.waitFor({ state: 'detached' });
}

export async function setConvention(page, unit, direction, origin) {
  await click(page, 'nav-libreta');
  await click(page, 'sub-radiacion');
  await select(page, 'conv-unit', unit);
  await select(page, 'conv-direction', direction);
  await select(page, 'conv-origin', origin);
  await click(page, 'conv-guardar');
  await page.locator(tid('convention-card')).getByText('Definido').waitFor();
}

export async function addStation(page, st) {
  await click(page, 'btn-anadir-estacion');
  const dlg = page.locator(tid('station-dialog'));
  await dlg.waitFor();
  await fill(page, 'st-code', st.code);
  await select(page, 'st-coordtype', st.coordType);
  await fill(page, 'st-east', st.east);
  await fill(page, 'st-north', st.north);
  if (st.northNote) await fill(page, 'st-northnote', st.northNote);
  if (st.refCode) {
    await fill(page, 'st-refcode', st.refCode);
    await select(page, 'st-azmode', st.azMode);
    if (st.azMode === 'coordenadas') { await fill(page, 'st-refeast', st.refEast); await fill(page, 'st-refnorth', st.refNorth); } else await fill(page, 'st-refaz', st.refAz);
    await fill(page, 'st-refreading', st.refReading);
  }
  await click(page, 'st-guardar');
  await dlg.waitFor({ state: 'detached' });
}

export async function addRad(page, r) {
  await click(page, 'btn-anadir-lectura-teodolito');
  const dlg = page.locator(tid('rad-dialog'));
  await dlg.waitFor();
  await fill(page, 'rd-code', r.code);
  if (r.desc && await page.locator(tid('rd-desc')).count()) await fill(page, 'rd-desc', r.desc);
  await fill(page, 'rd-hz', r.hz);
  if (r.distType === 'inclinada') {
    await dlg.locator(tid('rd-disttype')).getByText('Inclinada', { exact: false }).click();
    await select(page, 'rd-vtype', r.vType);
    await fill(page, 'rd-vangle', r.vAngle);
  }
  await fill(page, 'rd-distance', r.distance);
  await click(page, 'rd-guardar');
  await dlg.waitFor({ state: 'detached' });
}

/** Valores de una tabla de resultados: { código: {campo: texto} } usando data-code y data-testid de celdas. */
export async function tableValues(page, tableId, cells) {
  await page.locator(`${tid(tableId)} tbody tr[data-code]`).first().waitFor();
  return page.locator(`${tid(tableId)} tbody tr[data-code]`).evaluateAll((rows, cellsArg) => Object.fromEntries(rows.map((r) => [r.dataset.code, Object.fromEntries(cellsArg.map((c) => [c, r.querySelector(`[data-testid="${c}"]`)?.textContent.trim()]))])), cells);
}

export async function download(page, buttonId) {
  const [dl] = await Promise.all([page.waitForEvent('download'), click(page, buttonId)]);
  const path = await dl.path();
  return { name: dl.suggestedFilename(), bytes: await readFile(path) };
}

export async function pdfText(bytes) {
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  const doc = await pdfjs.getDocument({ data: new Uint8Array(bytes), useSystemFonts: false }).promise;
  const pages = [];
  for (let i = 1; i <= doc.numPages; i++) {
    const tc = await (await doc.getPage(i)).getTextContent();
    pages.push(tc.items.map((it) => it.str).join(' '));
  }
  return { numPages: doc.numPages, pages, text: pages.join('\n') };
}
