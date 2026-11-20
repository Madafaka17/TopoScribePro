// PRUEBAS de validación (datos incompletos, duplicados, formatos incorrectos, puntos sin cota) y de
// la importación de la base Android desde la interfaz. Datos sintéticos, almacenamiento vacío.
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { FIXTURES, OUT, addRad, addStation, chromium, click, download, fill, newPage, newProject, setConvention, setUser, startServer, tid } from './helpers.mjs';

let server;
let browser;
before(async () => { server = await startServer(); browser = await chromium.launch(); await mkdir(OUT, { recursive: true }); });
after(async () => { await browser?.close(); server?.stop(); });

test('PRUEBA validaciones: duplicados, datos incompletos, ángulos incorrectos y puntos sin cota no producen curvas', { timeout: 180000 }, async () => {
  const { page, errors, context } = await newPage(browser, server.url, { viewport: { width: 1280, height: 900 } });
  await setUser(page, 'Prueba validaciones');
  await newProject(page, { name: 'PRUEBA — validaciones' });

  // Sin radiación calculada: plano y DXF explican por qué no están disponibles
  await click(page, 'nav-plano');
  assert.equal(await page.locator(tid('btn-exportar-dxf')).getAttribute('aria-disabled'), 'true');
  assert.match(await page.locator(tid('plan-card')).textContent(), /Primero calcule la radiación/);

  // Sin convenio angular no se puede registrar la estación
  await click(page, 'nav-libreta');
  await click(page, 'sub-radiacion');
  assert.match(await page.locator(tid('stations-card')).textContent(), /Primero defina y guarde el convenio angular/);
  await setConvention(page, 'sexagesimal', 'horario', 'norte');
  await addStation(page, { code: 'E1', coordType: 'locales', east: '0', north: '0', northNote: 'brújula (prueba)' });
  for (const [code, hz, d] of [['A', '0', '10'], ['B', '90', '10'], ['C', '180', '10'], ['D', '270', '10']]) await addRad(page, { code, hz, distance: d });

  // Código duplicado: el formulario no deja guardar y explica el motivo
  await click(page, 'btn-anadir-lectura-teodolito');
  await fill(page, 'rd-code', 'a');
  await fill(page, 'rd-hz', '45');
  await fill(page, 'rd-distance', '5');
  assert.equal(await page.locator(tid('rd-guardar')).getAttribute('aria-disabled'), 'true');
  assert.match(await page.locator(tid('rad-dialog')).textContent(), /ya fue radiado/);
  // Distancia inclinada sin ángulo vertical: tampoco
  await fill(page, 'rd-code', 'F');
  await page.locator(tid('rd-disttype')).getByText('Inclinada', { exact: false }).click();
  assert.match(await page.locator(tid('rd-guardar')).getAttribute('title'), /ángulo vertical/);
  await page.locator(tid('rad-dialog')).getByRole('button', { name: 'Cancelar' }).click();

  // Formato angular incorrecto: se guarda el dato tal cual, pero bloquea el cálculo con el error
  await addRad(page, { code: 'G', hz: '45°75\'', distance: '5' });
  assert.match(await page.locator(tid('rad-errors')).textContent(), /minutos deben ser menores que 60/);
  await click(page, 'nav-teodolito');
  assert.equal(await page.locator(tid('btn-calcular-teodolito')).getAttribute('aria-disabled'), 'true');
  assert.match(await page.locator(tid('rad-calc-errors')).textContent(), /G.*minutos/);
  // Se elimina la lectura errónea (con confirmación) y se calcula
  await click(page, 'nav-libreta');
  await click(page, 'sub-radiacion');
  await page.locator(`${tid('rad-table')} tbody tr`).nth(4).getByRole('button', { name: 'Eliminar' }).click();
  await fill(page, 'confirm-reason', 'Lectura mal anotada (prueba)');
  await click(page, 'confirm-ok');
  await click(page, 'nav-teodolito');
  await click(page, 'btn-calcular-teodolito');

  // Puntos radiados SIN cota: no hay curvas, y se explica que deben nivelarse
  await click(page, 'nav-plano');
  assert.match(await page.locator(tid('no-elevation')).textContent(), /A, B, C, D.*primero deben nivelarse/s);
  await fill(page, 'eq-input', '0.5');
  await click(page, 'eq-guardar');
  const btn = page.locator(tid('btn-calcular-curvas'));
  await page.waitForFunction(() => document.querySelector('[data-testid="btn-calcular-curvas"]')?.getAttribute('title')?.includes('al menos 4 puntos'));
  assert.equal(await btn.getAttribute('aria-disabled'), 'true');
  assert.equal(await page.locator('[data-layer="curvas"]').count(), 0);
  // El DXF sí se exporta (sin curvas) y su verificación lo indica
  const dxf = await download(page, 'btn-exportar-dxf');
  assert.ok(dxf.bytes.length > 1000);
  assert.match(await page.locator(tid('dxf-verification')).textContent(), /sin curvas de nivel \(no calculadas\)/);
  assert.ok(!dxf.bytes.toString().includes('\nCURVAS_NIVEL\r\n100\r\nAcDbPolyline'), 'sin entidades de curvas');
  // El registro de correcciones guarda la lectura eliminada con su motivo
  await click(page, 'nav-libreta');
  await click(page, 'sub-correcciones');
  assert.match(await page.locator(tid('audit-table')).textContent(), /eliminar.*Lectura mal anotada \(prueba\)/s);
  assert.deepEqual(errors, []);
  await context.close();
});

test('PRUEBA importación de la base de la app Android desde la interfaz', { timeout: 180000 }, async () => {
  const dir = join(OUT, 'android-ui');
  execFileSync('python3', [join(FIXTURES, 'make_android_fixture.py'), dir]);
  const { page, errors, context } = await newPage(browser, server.url, { viewport: { width: 1280, height: 900 } });
  await setUser(page, 'Prueba migración');
  await click(page, 'btn-importar-android');
  const [c1] = await Promise.all([page.waitForEvent('filechooser'), click(page, 'and-elegir-db')]);
  await c1.setFiles([join(dir, 'toposcribe.db'), join(dir, 'toposcribe.db-wal')]);
  const [c2] = await Promise.all([page.waitForEvent('filechooser'), page.locator(tid('android-dialog')).getByRole('button', { name: 'Elegir fotografías (opcional)' }).click()]);
  await c2.setFiles([join(dir, 'P1_1731900000000.jpg')]);
  assert.match(await page.locator(tid('and-files')).textContent(), /toposcribe\.db-wal.*1 archivo/s);
  await click(page, 'and-analizar');
  const table = page.locator(tid('and-projects'));
  await table.waitFor();
  assert.match(await page.locator(tid('android-dialog')).textContent(), /Se aplicaron \d+ página\(s\) del archivo WAL/);
  assert.equal(await table.locator('tbody tr').count(), 3);
  assert.equal(await table.locator('tbody tr').nth(2).locator('input[type="checkbox"]').isChecked(), false, 'el proyecto de demostración no se marca por defecto');
  await click(page, 'and-importar');
  await page.locator(tid('and-result')).waitFor();
  assert.match(await page.locator(tid('and-result')).textContent(), /Se importaron 2 proyecto\(s\)/);
  await page.locator(tid('android-dialog')).getByRole('button', { name: 'Cerrar' }).first().click();

  const openByName = async (text) => {
    if ((await page.locator(tid('current-project')).textContent()).includes(text)) return;
    await page.locator(`${tid('project-list')} tbody tr`, { hasText: text }).getByRole('button', { name: 'Abrir' }).click();
    await page.locator(tid('current-project'), { hasText: text }).waitFor();
  };
  // Proyecto de estación total: datos conservados en el archivo heredado, fuera del flujo
  await openByName('Poligonal con estación total');
  const legacyText = await page.locator(tid('legacy-card')).textContent();
  assert.match(legacyText, /No se borraron/);
  assert.match(legacyText, /compensaciones de Bowditch \(fuera de alcance\) \(1 registro\)/);
  assert.match(legacyText, /puntos GNSS\/RTK \(fuera de alcance\) \(1 registro\)/);
  // Proyecto de teodolito: lecturas y foto migradas
  await openByName('Práctica teodolito');
  await click(page, 'nav-libreta');
  await click(page, 'sub-puntos');
  await page.locator('tr[data-code="P1"] img.thumb').waitFor();
  await click(page, 'sub-nivelacion');
  await page.locator(`${tid('level-table')} tbody tr`).nth(4).waitFor();
  assert.equal(await page.locator(`${tid('level-table')} tbody tr`).count(), 5);
  await click(page, 'nav-nivel');
  await click(page, 'btn-calcular-nivel');
  assert.equal((await page.locator(tid('closure-value')).textContent()).trim(), '+0.001 m');
  await click(page, 'nav-teodolito');
  assert.match(await page.locator(tid('rad-calc-errors')).textContent(), /lectura horizontal en la referencia/);
  assert.deepEqual(errors, []);
  await context.close();
});
