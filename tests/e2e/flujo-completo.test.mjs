// FLUJO COMPLETO con un CASO DIDÁCTICO DE PRUEBA (datos sintéticos, navegador con almacenamiento
// vacío): libreta → nivel → teodolito → foto → plano/curvas → DXF → PDF → Excel → corrección →
// reabrir → exportar/importar proyecto. Verifica que PDF y DXF coinciden con lo mostrado en pantalla.
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { FIXTURES, OUT, addLevel, addRad, addStation, chromium, click, download, fill, newPage, newProject, pdfText, setBm, setConvention, setUser, startServer, tableValues, tid } from './helpers.mjs';
import { readZip } from '../../web/js/export/zip.js';

const require = createRequire(import.meta.url);
const DxfParser = require('dxf-parser');

let server;
let browser;
before(async () => { server = await startServer(); browser = await chromium.launch(); await mkdir(OUT, { recursive: true }); });
after(async () => { await browser?.close(); server?.stop(); });

test('PRUEBA flujo completo: cálculos, foto, curvas, DXF, PDF, Excel, correcciones y persistencia', { timeout: 300000 }, async () => {
  const { page, errors, context } = await newPage(browser, server.url, { viewport: { width: 1366, height: 900 } });
  await setUser(page, 'Ana Prueba');
  await newProject(page, { name: 'PRUEBA E2E — Levantamiento didáctico', location: 'Caso de prueba', responsibles: 'Ana Prueba', crs: 'Local arbitrario (EST-1 = 500, 1000)', objective: 'Verificar la aplicación' });

  // ---------- Nivelación con cambio de estación y distancias acumuladas
  await setBm(page, 'BM-1', '100.000', { code: 'BM-1', elevation: '100.000' });
  await addLevel(page, { code: 'BM-1', kind: 'BM', bs: '1.432', inProfile: false });
  await addLevel(page, { code: 'EST-1', kind: 'VI', is: '1.105', cumulative: '0', desc: 'Estación del teodolito' });
  await addLevel(page, { code: 'P1', kind: 'VI', is: '0.870', partial: '15' });
  await addLevel(page, { code: 'P2', kind: 'VI', is: '1.540', partial: '12.5' });
  await addLevel(page, { code: 'PC1', kind: 'PC', fs: '1.968', bs: '1.215', partial: '10' });
  await addLevel(page, { code: 'P3', kind: 'VI', is: '0.954', partial: '14' });
  await addLevel(page, { code: 'P4', kind: 'VI', is: '1.402', cumulative: '63' });
  await addLevel(page, { code: 'P5', kind: 'VI', is: '1.780', partial: '9.5' });
  await addLevel(page, { code: 'BM-1', kind: 'VD', fs: '0.676', inProfile: false });
  await click(page, 'nav-nivel');
  await click(page, 'btn-calcular-nivel');
  const lev = await tableValues(page, 'level-results', ['cota']);
  assert.equal(lev.P1.cota, '100.562');
  assert.equal(lev.PC1.cota, '99.464');
  assert.equal(lev.P5.cota, '98.899');
  assert.equal((await page.locator(tid('closure-value')).textContent()).trim(), '+0.003 m');
  await click(page, 'btn-ver-perfil');
  await page.locator(tid('profile-svg')).waitFor();
  assert.match(await page.locator(tid('profile-card')).textContent(), /Escala horizontal 1:500/);

  // ---------- Radiación con orientación por coordenadas y una distancia inclinada
  await setConvention(page, 'sexagesimal', 'horario', 'referencia');
  await addStation(page, { code: 'EST-1', coordType: 'locales', east: '500', north: '1000', refCode: 'R-1', azMode: 'coordenadas', refEast: '500', refNorth: '1050', refReading: '0 00 00' });
  await addRad(page, { code: 'P1', hz: '30°00\'00"', distance: '25' });
  await addRad(page, { code: 'P2', hz: '95 30 00', distType: 'inclinada', vType: 'cenital', vAngle: '85°00\'00"', distance: '30' });
  await addRad(page, { code: 'P3', hz: '150°15\'30"', distance: '28' });
  await addRad(page, { code: 'P4', hz: '210', distance: '22.5' });
  await addRad(page, { code: 'P5', hz: '300-45-00', distance: '26' });
  await click(page, 'nav-teodolito');
  await click(page, 'btn-calcular-teodolito');
  const rad = await tableValues(page, 'rad-results', ['az', 'east', 'north']);
  assert.deepEqual(rad.P2, { az: '95°30\'00"', east: '529.748', north: '997.136' });
  assert.deepEqual(rad.P5, { az: '300°45\'00"', east: '477.655', north: '1013.294' });

  // ---------- Fotografía del punto P1
  await click(page, 'nav-libreta');
  await click(page, 'sub-puntos');
  await click(page, 'btn-foto-P1');
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), click(page, 'photo-file')]);
  await chooser.setFiles(`${FIXTURES}foto-prueba-800x600.jpg`);
  await page.locator(tid('photo-preview')).waitFor();
  await fill(page, 'photo-caption', 'Vista hacia el norte (foto de prueba)');
  await click(page, 'photo-guardar');
  await page.locator(tid('photo-dialog')).waitFor({ state: 'detached' });
  await page.locator('tr[data-code="P1"] img.thumb').waitFor();
  assert.match(await page.locator('tr[data-code="P2"] [data-testid="photo-cell"]').textContent(), /Sin fotografía registrada/);

  // ---------- Plano, líneas y curvas de nivel
  await click(page, 'nav-plano');
  await click(page, 'btn-anadir-linea');
  await fill(page, 'ln-name', 'Lindero');
  await fill(page, 'ln-codes', 'P1, P2, P3, P4, P5');
  await page.locator(tid('ln-closed')).check();
  await click(page, 'ln-guardar');
  await fill(page, 'eq-input', '0.5');
  await click(page, 'eq-guardar');
  await click(page, 'btn-calcular-curvas');
  await page.locator(tid('contours-ok')).waitFor();
  assert.match(await page.locator(tid('contours-ok')).textContent(), /6 puntos, 5 triángulos, 4 curvas \(99\.00, 99\.50, 100\.00, 100\.50 m\)/);
  assert.match(await page.locator(tid('no-elevation')).textContent(), /R-1/);
  await page.locator(tid('plan-svg')).waitFor();
  assert.equal(await page.locator('[data-layer="curvas"] polyline').count(), 4);
  await page.screenshot({ path: `${OUT}flujo-plano.png`, fullPage: true });

  // ---------- DXF: descarga real, verificación de la app y lectura independiente con dxf-parser
  const dxf = await download(page, 'btn-exportar-dxf');
  await writeFile(`${OUT}prueba.dxf`, dxf.bytes);
  assert.match(await page.locator(tid('dxf-verification')).textContent(), /AC1015.*metros.*diferencia máxima de coordenadas 0\.0000 m/s);
  const parsed = new DxfParser().parseSync(dxf.bytes.toString('utf8'));
  assert.equal(parsed.header.$INSUNITS, 6);
  const layers = Object.keys(parsed.tables.layer.layers);
  for (const l of ['ESTACION', 'PUNTOS', 'RADIACIONES', 'LINEAS', 'TRIANGULACION', 'CURVAS_NIVEL']) assert.ok(layers.includes(l), `capa ${l}`);
  const pts = parsed.entities.filter((e) => e.type === 'POINT' && e.layer === 'PUNTOS');
  for (const code of ['P1', 'P2', 'P3', 'P4', 'P5']) {
    const label = parsed.entities.find((e) => e.type === 'TEXT' && e.layer === 'CODIGOS' && e.text === code);
    assert.ok(label, `rótulo ${code}`);
    const near = pts.find((e) => Math.abs(e.position.x - Number(rad[code].east)) < 0.0006 && Math.abs(e.position.y - Number(rad[code].north)) < 0.0006);
    assert.ok(near, `el punto ${code} del DXF coincide con la pantalla (${rad[code].east}, ${rad[code].north})`);
    assert.equal(near.position.z.toFixed(3), lev[code].cota, `Z de ${code} = cota en pantalla`);
  }
  assert.equal(parsed.entities.filter((e) => e.layer === 'CURVAS_NIVEL').length, 4);

  // ---------- Informe: vista previa y PDF; el texto del PDF contiene los valores de la pantalla
  await click(page, 'nav-informe');
  await click(page, 'btn-vista-previa');
  await page.locator(`${tid('pdf-preview')}[data-rendered]`).waitFor({ timeout: 60000 });
  const previewPages = Number(await page.locator(tid('pdf-preview')).getAttribute('data-rendered'));
  const pdf = await download(page, 'btn-generar-informe');
  await writeFile(`${OUT}informe-prueba.pdf`, pdf.bytes);
  const { numPages, text } = await pdfText(pdf.bytes);
  assert.equal(numPages, previewPages, 'la vista previa tiene las mismas páginas que el PDF');
  const order = ['1. Portada', '2. Memoria descriptiva', '3. Libreta de campo', '4. Memoria de cálculo', '5. Resultados gráficos', '6. Panel fotográfico', '7. Observaciones y limitaciones'];
  let last = -1;
  for (const s of order.slice(1)) { const i = text.indexOf(s, last + 1); assert.ok(i > last, `sección «${s}» en orden`); last = i; }
  for (const code of ['P1', 'P2', 'P3', 'P4', 'P5']) {
    assert.ok(text.includes(lev[code].cota), `cota de ${code} (${lev[code].cota}) en el PDF`);
    assert.ok(text.includes(rad[code].east) && text.includes(rad[code].north), `coordenadas de ${code} en el PDF`);
  }
  assert.match(text, /AI \(E2\) = Cota PC1 \+ VA = 99\.464 \+ 1\.215 = 100\.679 m/);
  assert.match(text, /Error de cierre = cota calculada − cota conocida = 100\.003 − 100\.000 = \+0\.003 m/);
  assert.match(text, /Dh = Di × sen\(Z\) = 30\.000 × sen\(85°00'00"\) = 29\.886 m/);
  assert.match(text, /Escala horizontal 1:500/);
  assert.match(text, /Triangulación de Delaunay/);
  assert.match(text, /Vista hacia el norte \(foto de prueba\)/);
  assert.match(text, /Sin fotografía registrada/);
  assert.match(text, /Página 1 de \d+/);

  // ---------- Excel: hojas de datos de campo y de resultados
  const xlsx = await download(page, 'btn-exportar-excel');
  await writeFile(`${OUT}prueba.xlsx`, xlsx.bytes);
  const files = readZip(new Uint8Array(xlsx.bytes));
  const wb = new TextDecoder().decode(files.get('xl/workbook.xml'));
  assert.match(wb, /Nivel - libreta de campo/);
  assert.match(wb, /Nivel - cálculo/);
  const sheets = [...files.keys()].filter((k) => k.startsWith('xl/worksheets/')).map((k) => new TextDecoder().decode(files.get(k))).join('');
  assert.ok(sheets.includes('<v>100.562</v>') && sheets.includes('<v>529.748</v>'), 'el Excel contiene los valores redondeados de la pantalla');

  // ---------- Corrección de una lectura: dato original conservado, resultados señalados
  await click(page, 'nav-libreta');
  await click(page, 'sub-nivelacion');
  await page.locator(`${tid('level-table')} tbody tr`).nth(2).getByRole('button', { name: 'Editar' }).click();
  await fill(page, 'lv-is', '0.880');
  await fill(page, 'lv-reason', 'Error de dictado en campo');
  await click(page, 'lv-guardar');
  await click(page, 'nav-nivel');
  await page.locator(tid('level-status')).getByText('Cota de P1 (lectura n.° 3): 100.562 → 100.552 m').waitFor({ timeout: 10000 });
  const status = await page.locator(tid('level-status')).textContent();
  assert.match(status, /Cota de P1 \(lectura n\.° 3\): 100\.562 → 100\.552 m/);
  await click(page, 'nav-informe');
  assert.match(await page.locator(tid('report-blockers')).textContent(), /Nivelación desactualizada/);
  assert.equal(await page.locator(tid('btn-generar-informe')).getAttribute('aria-disabled'), 'true');
  await click(page, 'nav-libreta');
  await click(page, 'sub-correcciones');
  const auditText = await page.locator(tid('audit-table')).textContent();
  assert.match(auditText, /Ana Prueba/);
  assert.match(auditText, /Vista intermedia \(m\):\s*0\.870\s*→\s*0\.880/);
  assert.match(auditText, /Error de dictado en campo/);
  await click(page, 'nav-nivel');
  await click(page, 'btn-calcular-nivel');
  assert.equal((await tableValues(page, 'level-results', ['cota'])).P1.cota, '100.552');

  // ---------- Guardar, recargar y reabrir con la fotografía
  await click(page, 'btn-guardar');
  await page.getByText('guardado en este dispositivo y verificado').waitFor();
  await page.reload();
  await page.locator(tid('current-project'), { hasText: 'PRUEBA E2E' }).waitFor();
  await click(page, 'nav-libreta');
  await click(page, 'sub-puntos');
  await page.locator('tr[data-code="P1"] img.thumb').waitFor();
  const natural = await page.locator('tr[data-code="P1"] img.thumb').evaluate((img) => img.naturalWidth);
  assert.equal(natural, 800, 'la foto se reabre con su tamaño original (sin deformar)');

  // ---------- Exportar el proyecto (.json) e importarlo en otro "dispositivo" (almacenamiento vacío)
  await click(page, 'nav-proyecto');
  const json = await download(page, 'btn-exportar-json');
  await writeFile(`${OUT}proyecto-prueba.toposcribe.json`, json.bytes);
  assert.deepEqual(errors, []);
  await context.close();

  const other = await newPage(browser, server.url, { viewport: { width: 1366, height: 900 } });
  await setUser(other.page, 'Otro equipo');
  const [chooser2] = await Promise.all([other.page.waitForEvent('filechooser'), click(other.page, 'btn-abrir-archivo')]);
  await chooser2.setFiles(`${OUT}proyecto-prueba.toposcribe.json`);
  await other.page.locator(tid('current-project'), { hasText: 'PRUEBA E2E' }).waitFor();
  await click(other.page, 'nav-teodolito');
  assert.deepEqual((await tableValues(other.page, 'rad-results', ['east', 'north'])).P3, { east: rad.P3.east, north: rad.P3.north });
  await click(other.page, 'nav-libreta');
  await click(other.page, 'sub-puntos');
  await other.page.locator('tr[data-code="P1"] img.thumb').waitFor();
  assert.deepEqual(other.errors, []);
  await other.context.close();
});
