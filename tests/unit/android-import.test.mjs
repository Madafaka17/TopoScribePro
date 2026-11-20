// PRUEBA de la migración desde la base Room de Android (datos sintéticos creados por
// tests/fixtures/make_android_fixture.py en una carpeta temporal).
import { before, test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { applyWal, convertAndroidProject, readAndroidDb, summarizeAndroid } from '../../web/js/import/androidRoom.js';
import { evaluateProject, acceptResults } from '../../web/js/core/model.js';
import { fmt } from '../../web/js/core/numbers.js';

const require = createRequire(import.meta.url);
let SQL;
let dir;
before(async () => {
  SQL = await require('sql.js')();
  dir = mkdtempSync(join(tmpdir(), 'toposcribe-android-'));
  execFileSync('python3', [fileURLToPath(new URL('../fixtures/make_android_fixture.py', import.meta.url)), dir]);
});

const load = (withWal = true) => {
  const db = readFileSync(join(dir, 'toposcribe.db'));
  const wal = withWal ? readFileSync(join(dir, 'toposcribe.db-wal')) : null;
  return applyWal(db, wal);
};

test('PRUEBA Android: el archivo -wal aporta los cambios no consolidados', () => {
  const sinWal = readAndroidDb(SQL, load(false).bytes);
  assert.equal(sinWal.projects.length, 0, 'sin el -wal no se ven los proyectos');
  const { bytes, info } = load(true);
  assert.ok(info.walUsed && info.framesApplied > 0);
  const raw = readAndroidDb(SQL, bytes);
  assert.equal(raw.projects.length, 3);
  const s = summarizeAndroid(raw);
  assert.equal(s.find((x) => x.id === 3).isDemo, true);
});

test('PRUEBA Android: nivelación y radiación de teodolito pasan al flujo; se conserva el dato original', () => {
  const raw = readAndroidDb(SQL, load().bytes);
  const { project: p, photoRefs, notes } = convertAndroidProject(raw, 1, { sourceFile: 'toposcribe.db' }, 'prueba');
  assert.deepEqual(p.leveling.bm, { code: 'BM-1', elevation: 100 });
  assert.deepEqual(p.leveling.rows.map((r) => [r.code, r.bs, r.is, r.fs]), [
    ['BM-1', 1.5, null, null], ['P1', null, 1.2, null], ['PC1', 1.1, null, 1.8], ['P2', null, 0.9, null], ['BM-1', null, null, 0.799],
  ]);
  assert.ok(p.leveling.rows.every((r) => r.original), 'cada lectura conserva su dato original');
  assert.equal(p.theodolite.convention.origin, 'referencia');
  assert.equal(p.theodolite.stations[0].orientation.refCode, 'R-1');
  assert.equal(p.theodolite.stations[0].orientation.refAzimuth, '45°00\'00"');
  assert.equal(p.theodolite.readings[1].distType, 'inclinada');
  assert.equal(p.theodolite.readings[1].vAngle, '85°00\'00"');
  assert.deepEqual(photoRefs.map((r) => r.fileName), ['P1_1731900000000.jpg']);
  assert.ok(notes.some((n) => n.includes('L_ref')), 'avisa que falta la lectura en la referencia');
  // La nivelación importada se calcula; el teodolito queda bloqueado hasta completar L_ref (no se inventa)
  const ev = evaluateProject(p);
  assert.equal(ev.leveling.current.computable, true, JSON.stringify(ev.leveling.current.errors));
  acceptResults(p, 'leveling', ev, 'prueba');
  const ev2 = evaluateProject(p);
  assert.equal(fmt(ev2.leveling.accepted.closure.error), '0.001');
  assert.equal(ev2.radiation.current.computable, false);
  assert.match(ev2.radiation.current.errors.map((e) => e.message).join(' '), /lectura horizontal en la referencia/);
});

test('PRUEBA Android: estación total, poligonal, Bowditch, GNSS y nivelación trigonométrica quedan fuera del flujo pero conservados', () => {
  const raw = readAndroidDb(SQL, load().bytes);
  const { project: p } = convertAndroidProject(raw, 2, {}, 'prueba');
  assert.equal(p.theodolite.readings.length, 0, 'no se mezclan en la radiación');
  const t = p.legacy.tables;
  assert.equal(t['poligonales (fuera de alcance)'].length, 1);
  assert.equal(t['compensaciones de Bowditch (fuera de alcance)'].length, 1);
  assert.equal(t['nivelación trigonométrica (fuera de alcance)'].length, 1);
  assert.equal(t['puntos GNSS/RTK (fuera de alcance)'].length, 1);
  assert.equal(t['observaciones planimétricas de Estación total (fuera del flujo)'].length, 2);
  assert.equal(t['resultados calculados por la versión Android'].length, 1);
  // A pedido del usuario, las de estación total pueden incorporarse (salvo las de poligonal)
  const { project: q } = convertAndroidProject(raw, 2, { totalStationAsTheodolite: true }, 'prueba');
  assert.equal(q.theodolite.readings.length, 1);
  assert.equal(q.theodolite.convention.origin, 'norte');
  assert.equal(q.legacy.tables['observaciones de poligonal (fuera de alcance)'].length, 1);
});
