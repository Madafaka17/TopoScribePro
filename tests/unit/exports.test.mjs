// PRUEBA de las exportaciones en Node (sin navegador) con el CASO DIDÁCTICO de tests/fixtures.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { makeSampleProject } from '../fixtures/sampleProject.mjs';
import { acceptResults, evaluateProject } from '../../web/js/core/model.js';
import { buildPlanModel } from '../../web/js/core/plan.js';
import { fmt } from '../../web/js/core/numbers.js';
import { buildDxf } from '../../web/js/export/dxf.js';
import { verifyDxf } from '../../web/js/export/dxfVerify.js';
import { buildReportPdf } from '../../web/js/export/pdf.js';
import { buildTables } from '../../web/js/export/tables.js';
import { buildCsv, buildXlsx } from '../../web/js/export/xlsx.js';
import { readZip } from '../../web/js/export/zip.js';
import { parseProjectFile, serializeProjectFile } from '../../web/js/export/projectFile.js';

const require = createRequire(import.meta.url);
const { jsPDF } = require('jspdf');
const DxfParser = require('dxf-parser');

function calculated() {
  const p = makeSampleProject();
  let ev = evaluateProject(p);
  acceptResults(p, 'leveling', ev, 'prueba');
  acceptResults(p, 'radiation', ev, 'prueba');
  ev = evaluateProject(p);
  acceptResults(p, 'contours', ev, 'prueba');
  ev = evaluateProject(p);
  return { p, ev, plan: buildPlanModel(p, ev) };
}

test('PRUEBA DXF: capas, unidades y coordenadas coinciden con los resultados', () => {
  const { p, ev, plan } = calculated();
  const { text } = buildDxf(plan, p);
  const v = verifyDxf(text, plan);
  assert.equal(v.ok, true, v.lines.join('\n'));
  const parsed = new DxfParser().parseSync(text);
  assert.equal(parsed.header.$INSUNITS, 6);
  const p3 = ev.radiation.accepted.points.find((x) => x.code === 'P3');
  const pt = parsed.entities.find((e) => e.type === 'POINT' && e.layer === 'PUNTOS' && Math.abs(e.position.x - p3.east) < 1e-5);
  assert.ok(pt);
  assert.equal(fmt(pt.position.y), fmt(p3.north));
  assert.equal(parsed.entities.filter((e) => e.layer === 'CURVAS_NIVEL').length, ev.contours.accepted.polylines.length);
  // Sin curvas vigentes, el DXF no las incluye
  p.plan.equidistance = 0.25;
  const ev2 = evaluateProject(p);
  const plan2 = buildPlanModel(p, ev2);
  assert.equal(plan2.contours, null);
  assert.equal(new DxfParser().parseSync(buildDxf(plan2, p).text).entities.filter((e) => e.layer === 'CURVAS_NIVEL').length, 0);
});

test('PRUEBA PDF: 7 secciones en orden, valores iguales a los calculados, páginas numeradas', async () => {
  const { p, ev, plan } = calculated();
  const b = (f) => readFileSync(new URL(`../../web/vendor/fonts/${f}`, import.meta.url)).toString('binary');
  const out = buildReportPdf(jsPDF, { regular: b('DejaVuSans-subset.ttf'), bold: b('DejaVuSans-Bold-subset.ttf') }, p, ev, plan, new Map(), { generatedBy: 'prueba', appVersion: 'prueba' });
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  const doc = await pdfjs.getDocument({ data: out.bytes.slice() }).promise;
  assert.equal(doc.numPages, out.pages);
  let text = '';
  for (let i = 1; i <= doc.numPages; i++) text += `${(await (await doc.getPage(i)).getTextContent()).items.map((x) => x.str).join(' ')}\n`;
  let last = -1;
  for (const s of ['2. Memoria descriptiva', '3. Libreta de campo', '4. Memoria de cálculo', '5. Resultados gráficos', '6. Panel fotográfico', '7. Observaciones y limitaciones']) {
    const i = text.indexOf(s, last + 1);
    assert.ok(i > last, s);
    last = i;
  }
  for (const r of ev.leveling.accepted.rows) assert.ok(text.includes(fmt(r.elevation)), `cota ${r.code}`);
  for (const pt of ev.radiation.accepted.points) assert.ok(text.includes(fmt(pt.east)) && text.includes(fmt(pt.north)), `coordenadas ${pt.code}`);
  assert.match(text, /Sin fotografía registrada/);
  assert.match(text, new RegExp(`Página ${doc.numPages} de ${doc.numPages}`));
  assert.match(text, /\[PENDIENTE: marca y modelo del nivel\]/, 'los datos faltantes se señalan');
});

test('PRUEBA Excel y CSV: separan datos de campo y resultados; valores redondeados como en pantalla', () => {
  const { p, ev } = calculated();
  const tables = buildTables(p, ev);
  const files = readZip(buildXlsx(tables));
  const wb = new TextDecoder().decode(files.get('xl/workbook.xml'));
  for (const n of ['Nivel - libreta de campo', 'Teodolito - libreta de campo', 'Correcciones', 'Nivel - cálculo', 'Teodolito - cálculo', 'Coordenadas y cotas']) assert.ok(wb.includes(n), n);
  const csv = buildCsv(tables.find((t) => t.name === 'Teodolito - cálculo'), ',');
  assert.ok(csv.startsWith('﻿'));
  // RFC 4180: las comillas del símbolo de segundos se duplican dentro del campo
  assert.ok(csv.includes('2;EST-1;P2;"95°30\'00""";"95°30\'00""";95,500000;29,886;29,748;-2,864;529,748;997,136'), csv);
});

test('PRUEBA archivo de proyecto: guardar y reabrir conserva lecturas, resultados y fotografías', () => {
  const { p } = calculated();
  p.points[3].photo = { id: 'ph-1', caption: 'foto', mime: 'image/jpeg', width: 800, height: 600 };
  const b64 = readFileSync(new URL('../fixtures/foto-prueba-800x600.jpg', import.meta.url)).toString('base64');
  const text = serializeProjectFile(p, [{ id: 'ph-1', mime: 'image/jpeg', base64: b64 }], 'prueba');
  const back = parseProjectFile(text);
  assert.deepEqual(back.project.leveling.rows, p.leveling.rows);
  assert.deepEqual(back.project.results, p.results);
  assert.equal(back.photos[0].base64, b64);
  assert.deepEqual(back.missing, []);
  assert.equal(evaluateProject(back.project).leveling.status, 'vigente');
  assert.throws(() => parseProjectFile('{"format":"otro"}'), /no es un proyecto exportado/);
});
