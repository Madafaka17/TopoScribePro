// Pruebas del núcleo de cálculo con CASOS DIDÁCTICOS DE PRUEBA.
// Estos datos viven solo aquí: nunca se guardan en proyectos reales.
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { fmt, parseNumber, fmtChainage } from '../../web/js/core/numbers.js';
import { parseAngle, formatDms, normalizeAzimuth, formatAngle } from '../../web/js/core/angles.js';
import { computeLeveling } from '../../web/js/core/leveling.js';
import { computeRadiation } from '../../web/js/core/radiation.js';
import { computeContours, delaunay } from '../../web/js/core/contours.js';
import { profileLayout, planLayout, chooseScale } from '../../web/js/core/scales.js';
import { newProject, linkSurvey, evaluateProject, acceptResults } from '../../web/js/core/model.js';

const close = (a, b, tol = 0.0005, msg) => assert.ok(Math.abs(a - b) <= tol, `${msg ?? ''} esperado ${b}, obtenido ${a}`);
const row = (id, code, bs = null, is = null, fs = null, partial = null, cumulative = null, inProfile = true) => ({ id, code, bs, is, fs, partial, cumulative, inProfile });

// ------------------------------------------------------------------ Números y ángulos
test('PRUEBA números: punto o coma decimal, rechaza formatos ambiguos', () => {
  assert.equal(parseNumber('1.250').value, 1.25);
  assert.equal(parseNumber('1,250').value, 1.25);
  assert.equal(parseNumber(' -3 ').value, -3);
  assert.equal(parseNumber('1.000,5').ok, false);
  assert.equal(parseNumber('abc').ok, false);
  assert.equal(parseNumber('').empty, true);
  assert.equal(fmt(-0.0001), '0.000');
  assert.equal(fmt(100.4), '100.400');
  assert.equal(fmtChainage(1234.5), '1+234.500');
});

test('PRUEBA ángulos: formatos válidos e incorrectos', () => {
  close(parseAngle('45°30\'15"').deg, 45.504166667, 1e-9);
  close(parseAngle('45 30 15').deg, 45.504166667, 1e-9);
  close(parseAngle('45-30-15').deg, 45.504166667, 1e-9);
  close(parseAngle('90').deg, 90, 1e-12);
  close(parseAngle('45,5').deg, 45.5, 1e-12);
  close(parseAngle('100', 'centesimal').deg, 90, 1e-12);
  assert.equal(parseAngle('45°75\'').ok, false, 'minutos ≥ 60');
  assert.equal(parseAngle('45°30\'61"').ok, false, 'segundos ≥ 60');
  assert.equal(parseAngle('45.5°30\'').ok, false, 'grados decimales con minutos');
  assert.equal(parseAngle('cuarenta').ok, false);
  assert.equal(parseAngle('1 2 3 4').ok, false);
  assert.equal(formatDms(45.504166667), '45°30\'15"');
  assert.equal(formatDms(89.99999999), '90°00\'00"', 'acarreo de 60"');
  assert.equal(formatAngle(90, 'centesimal'), '100.0000g');
});

test('PRUEBA normalización de azimuts a [0°, 360°)', () => {
  assert.equal(normalizeAzimuth(370), 10);
  assert.equal(normalizeAzimuth(-15), 345);
  assert.equal(normalizeAzimuth(360), 0);
  assert.equal(normalizeAzimuth(720.5), 0.5);
});

// ------------------------------------------------------------------ Nivelación
test('PRUEBA nivelación (caso del docente): BM 100.000, VA 1.250, VD 0.850', () => {
  const r = computeLeveling({ bm: { code: 'BM-1', elevation: 100 }, rows: [row('a', 'BM-1', 1.25), row('b', 'P-1', null, null, 0.85)] });
  assert.equal(r.computable, true, JSON.stringify(r.errors));
  assert.equal(fmt(r.rows[0].hiNew), '101.250', 'altura de instrumento');
  assert.equal(fmt(r.rows[1].elevation), '100.400', 'nueva cota');
  assert.equal(r.rows[0].steps[1], 'AI (E1) = Cota BM-1 + VA = 100.000 + 1.250 = 101.250 m');
  assert.equal(r.rows[1].steps[0], 'Cota P-1 = AI (E1) − VD = 101.250 − 0.850 = 100.400 m');
  assert.equal(r.sums.applicable, true);
  assert.equal(r.sums.ok, true);
  assert.equal(fmt(r.sums.diff), '0.400');
  assert.equal(r.closure.applicable, false, 'sin BM final no hay cierre');
  assert.match(r.closure.explanation, /no puede comprobarse el cierre/);
});

const changeOfStation = () => ({
  bm: { code: 'BM-1', elevation: 100 },
  endBm: { code: 'P3', elevation: 99.395 },
  rows: [
    row('1', 'BM-1', 1.5, null, null, null, 0),
    row('2', 'P1', null, 1.2, null, 20),
    row('3', 'PC1', 1.1, null, 1.8, 20),
    row('4', 'P2', null, 0.9, null, null, 60),
    row('5', 'P3', null, null, 1.4, 25),
  ],
});

test('PRUEBA cambio de estación del nivel y distancias acumuladas', () => {
  const r = computeLeveling(changeOfStation());
  assert.equal(r.computable, true, JSON.stringify(r.errors));
  const el = r.rows.map((x) => fmt(x.elevation));
  assert.deepEqual(el, ['100.000', '100.300', '99.700', '99.900', '99.400']);
  assert.equal(fmt(r.rows[2].hiUsed), '101.500');
  assert.equal(fmt(r.rows[2].hiNew), '100.800', 'AI de la estación 2 usa la cota del punto de cambio');
  assert.equal(r.rows[2].type, 'PC');
  assert.equal(r.rows[2].station, 'E1 → E2');
  assert.equal(r.rows[3].station, 'E2');
  assert.deepEqual(r.rows.map((x) => fmt(x.cumulative)), ['0.000', '20.000', '40.000', '60.000', '85.000']);
  assert.deepEqual(r.rows.map((x) => x.cumulativeSource), ['ingresada', 'calculada', 'calculada', 'ingresada', 'calculada']);
  assert.equal(fmt(r.rows[3].partial), '20.000', 'parcial calculada desde progresivas');
  assert.equal(fmt(r.sums.sumBs), '2.600');
  assert.equal(fmt(r.sums.sumFs), '3.200');
  assert.equal(r.sums.ok, true);
  assert.equal(r.closure.applicable, true);
  assert.equal(fmt(r.closure.error), '0.005');
  assert.equal(r.profile.ok, true);
  assert.equal(r.profile.points.length, 5);
});

test('PRUEBA perfil: no se dibuja si faltan distancias, y explica qué falta', () => {
  const d = changeOfStation();
  d.rows[1].partial = null; // P1 sin distancia
  const r = computeLeveling(d);
  assert.equal(r.computable, true);
  assert.equal(r.profile.ok, false);
  assert.match(r.profile.missing[0].message, /P1.*falta la distancia/);
});

test('PRUEBA perfil: distancia parcial que contradice la progresiva', () => {
  const d = changeOfStation();
  d.rows[3].partial = 25; // progresiva 60 − 40 = 20 ≠ 25
  const r = computeLeveling(d);
  assert.equal(r.profile.ok, false);
  assert.match(r.profile.errors[0].message, /no coincide/);
});

test('PRUEBA nivelación: errores de libreta impiden calcular cotas', () => {
  const noBs = computeLeveling({ bm: { code: 'BM-1', elevation: 100 }, rows: [row('1', 'BM-1', null, null, 1.0)] });
  assert.equal(noBs.computable, false);
  const wrongBm = computeLeveling({ bm: { code: 'BM-1', elevation: 100 }, rows: [row('1', 'X', 1.0), row('2', 'P', null, null, 1)] });
  assert.equal(wrongBm.computable, false);
  assert.match(wrongBm.errors[0].message, /BM «BM-1»/);
  const afterClose = computeLeveling({ bm: { code: 'BM-1', elevation: 100 }, rows: [row('1', 'BM-1', 1), row('2', 'A', null, null, 1), row('3', 'B', null, 1)] });
  assert.equal(afterClose.computable, false);
  assert.match(afterClose.errors[0].message, /falta la vista atrás/);
  const negative = computeLeveling({ bm: { code: 'BM-1', elevation: 100 }, rows: [row('1', 'BM-1', 1), row('2', 'A', null, null, -1)] });
  assert.equal(negative.computable, false);
  assert.ok(negative.rows.every((x) => x.elevation === null), 'no publica cotas con errores');
});

// ------------------------------------------------------------------ Radiación
const conv = (direction = 'horario', origin = 'referencia') => ({ unit: 'sexagesimal', direction, origin });

test('PRUEBA radiación (caso del docente): E 500, N 1000, Dh 10, Az 90° → E 510, N 1000', () => {
  const r = computeRadiation({
    convention: conv('horario', 'norte'),
    stations: [{ id: 's', code: 'EST-1', east: 500, north: 1000, coordType: 'locales', orientation: { northNote: 'Brújula, norte magnético' } }],
    readings: [{ id: 'r1', stationId: 's', code: 'P-1', hz: '90°00\'00"', distType: 'horizontal', distance: 10 }],
  });
  assert.equal(r.computable, true, JSON.stringify(r.errors));
  const p = r.points[0];
  assert.equal(fmt(p.east), '510.000');
  assert.equal(fmt(p.north), '1000.000');
  assert.equal(formatDms(p.az), '90°00\'00"');
  assert.deepEqual(p.coordSteps, [
    'ΔE = Dh × sen(Az) = 10.000 × sen(90°00\'00") = 10.000 m',
    'ΔN = Dh × cos(Az) = 10.000 × cos(90°00\'00") = 0.000 m',
    'E = E(EST-1) + ΔE = 500.000 + 10.000 = 510.000 m',
    'N = N(EST-1) + ΔN = 1000.000 + 0.000 = 1000.000 m',
  ]);
});

test('PRUEBA orientación del teodolito con referencia por coordenadas, normalización y sentido', () => {
  const st = { id: 's', code: 'E1', east: 500, north: 1000, coordType: 'conocidas', orientation: { refCode: 'R', azMode: 'coordenadas', refEast: 510, refNorth: 1010, refReading: '10°00\'00"' } };
  const r = computeRadiation({
    convention: conv('horario'),
    stations: [st],
    readings: [
      { id: 'a', stationId: 's', code: 'A', hz: '100°00\'00"', distType: 'horizontal', distance: 20 },
      { id: 'b', stationId: 's', code: 'B', hz: '5°00\'00"', distType: 'horizontal', distance: 20 },
    ],
  });
  assert.equal(r.computable, true, JSON.stringify(r.errors));
  close(r.stations[0].az0, 45, 1e-9, 'Az_ref por coordenadas');
  close(r.points[0].az, 135, 1e-9, 'Az = 45 + (100 − 10)');
  close(r.points[1].az, 40, 1e-9, 'Az = 45 + (5 − 10) = 40');
  // Antihorario y normalización: Az = 45 − (100 − 10) = −45 → 315
  const r2 = computeRadiation({ convention: conv('antihorario'), stations: [st], readings: [{ id: 'a', stationId: 's', code: 'A', hz: '100', distType: 'horizontal', distance: 20 }] });
  close(r2.points[0].az, 315, 1e-9);
  assert.match(r2.points[0].azSteps[1], /Normalización/);
  // Normalización por exceso: Az = 45 + (350 − 10) = 385 → 25
  const r3 = computeRadiation({ convention: conv('horario'), stations: [st], readings: [{ id: 'a', stationId: 's', code: 'A', hz: '350', distType: 'horizontal', distance: 20 }] });
  close(r3.points[0].az, 25, 1e-9);
  assert.match(r3.points[0].azSteps[1], /− 360°/);
});

test('PRUEBA reducción de distancia inclinada (cenital y vertical)', () => {
  const st = { id: 's', code: 'E1', east: 0, north: 0, coordType: 'locales', orientation: { refCode: 'R', azMode: 'ingresado', refAzimuth: '0', refReading: '0' } };
  const r = computeRadiation({
    convention: conv(),
    stations: [st],
    readings: [
      { id: 'a', stationId: 's', code: 'A', hz: '0', distType: 'inclinada', distance: 50, vType: 'cenital', vAngle: '80°00\'00"' },
      { id: 'b', stationId: 's', code: 'B', hz: '90', distType: 'inclinada', distance: 50, vType: 'vertical', vAngle: '-10' },
    ],
  });
  assert.equal(r.computable, true, JSON.stringify(r.errors));
  assert.equal(fmt(r.points[0].dh), '49.240');
  assert.equal(fmt(r.points[1].dh), '49.240');
  assert.equal(fmt(r.points[0].north), '49.240');
  assert.equal(r.points[0].dhSteps[0], 'Dh = Di × sen(Z) = 50.000 × sen(80°00\'00") = 49.240 m');
  // Inclinada sin ángulo → error (no se trata como horizontal)
  const bad = computeRadiation({ convention: conv(), stations: [st], readings: [{ id: 'a', stationId: 's', code: 'A', hz: '0', distType: 'inclinada', distance: 50, vType: 'cenital', vAngle: '' }] });
  assert.equal(bad.computable, false);
  assert.match(bad.errors[0].message, /reducirla a horizontal/);
});

test('PRUEBA validaciones del teodolito: duplicados, orientación ausente, referencia inexistente, distancias y ángulos', () => {
  const st = { id: 's', code: 'E1', east: 0, north: 0, coordType: 'locales', orientation: {} };
  const r = computeRadiation({
    convention: conv(),
    stations: [st],
    readings: [
      { id: 'a', stationId: 's', code: 'P1', hz: '10', distType: 'horizontal', distance: 5 },
      { id: 'b', stationId: 's', code: 'p1', hz: '20', distType: 'horizontal', distance: 5 },
      { id: 'c', stationId: 'no-existe', code: 'P2', hz: '20', distType: 'horizontal', distance: 5 },
      { id: 'd', stationId: 's', code: 'P3', hz: '20', distType: 'horizontal', distance: 0 },
      { id: 'e', stationId: 's', code: 'P4', hz: '20°70\'', distType: 'horizontal', distance: 5 },
      { id: 'f', stationId: 's', code: 'P5', hz: '365', distType: 'horizontal', distance: 5 },
    ],
  });
  assert.equal(r.computable, false);
  const msgs = r.errors.map((e) => e.message).join('\n');
  assert.match(msgs, /punto de referencia de orientación/);
  assert.match(msgs, /ya fue radiado/);
  assert.match(msgs, /estación indicada no existe/);
  assert.match(msgs, /mayor que cero/);
  assert.match(msgs, /minutos deben ser menores que 60/);
  assert.match(msgs, /entre 0 y 360/);
  assert.ok(r.points.every((p) => p.east === null), 'no publica coordenadas con errores');
  const noConv = computeRadiation({ convention: { unit: '', direction: '', origin: '' }, stations: [], readings: [] });
  assert.ok(noConv.errors.length >= 4);
});

// ------------------------------------------------------------------ Triangulación y curvas
test('PRUEBA triangulación e interpolación de curvas sobre un plano inclinado z = 100 + 0.2·E', () => {
  const pts = [];
  for (let i = 0; i <= 4; i++) for (let j = 0; j <= 4; j++) pts.push({ code: `G${i}${j}`, east: 1000 + i * 10, north: 2000 + j * 10, elevation: 100 + 0.2 * i * 10 });
  const r = computeContours(pts, 1);
  assert.equal(r.ok, true, r.reason);
  assert.equal(r.triangles.length, 32, '5×5 puntos → 32 triángulos');
  assert.deepEqual(r.levels, [100, 101, 102, 103, 104, 105, 106, 107, 108]);
  // Cada vértice de cada curva debe estar exactamente donde z = nivel: E = 1000 + (nivel − 100)/0.2
  for (const pl of r.polylines) for (const p of pl.points) close(p.east, 1000 + (pl.level - 100) / 0.2, 1e-6, `curva ${pl.level}`);
  const lvl101 = r.polylines.filter((p) => p.level === 101);
  assert.equal(lvl101.length, 1, 'la curva 101 es una sola polilínea continua');
  assert.ok(Math.abs(lvl101[0].points[0].north - lvl101[0].points.at(-1).north) >= 40 - 1e-6, 'recorre todo el lado norte-sur');
});

test('PRUEBA interpolación lineal sobre una arista', () => {
  const pts = [
    { code: 'A', east: 0, north: 0, elevation: 100 },
    { code: 'B', east: 10, north: 0, elevation: 102 },
    { code: 'C', east: 0, north: 10, elevation: 100 },
    { code: 'D', east: 10, north: 10, elevation: 102 },
  ];
  const r = computeContours(pts, 1);
  assert.equal(r.ok, true, r.reason);
  const c = r.polylines.find((p) => p.level === 101);
  for (const p of c.points) close(p.east, 5, 1e-9);
});

test('PRUEBA curvas no se generan con datos insuficientes, alineados o duplicados con cotas distintas', () => {
  assert.equal(computeContours([{ code: 'A', east: 0, north: 0, elevation: 1 }, { code: 'B', east: 1, north: 0, elevation: 2 }, { code: 'C', east: 0, north: 1, elevation: 3 }], 1).ok, false);
  const aligned = computeContours([0, 1, 2, 3, 4].map((i) => ({ code: `L${i}`, east: i * 10, north: i * 10, elevation: i })), 1);
  assert.equal(aligned.ok, false);
  assert.match(aligned.reason, /alineados/);
  const dup = computeContours([
    { code: 'A', east: 0, north: 0, elevation: 1 }, { code: 'A2', east: 0, north: 0.0001, elevation: 5 },
    { code: 'B', east: 10, north: 0, elevation: 2 }, { code: 'C', east: 0, north: 10, elevation: 3 }, { code: 'D', east: 10, north: 10, elevation: 4 },
  ], 1);
  assert.equal(dup.ok, false);
  assert.match(dup.errors[0], /misma posición/);
  const flat = computeContours([{ code: 'A', east: 0, north: 0, elevation: 100.1 }, { code: 'B', east: 10, north: 0, elevation: 100.2 }, { code: 'C', east: 0, north: 10, elevation: 100.3 }, { code: 'D', east: 10, north: 10, elevation: 100.4 }], 1);
  assert.equal(flat.ok, false);
  assert.match(flat.reason, /no alcanza ninguna curva/);
});

test('PRUEBA Delaunay con coordenadas UTM grandes no pierde triángulos', () => {
  const pts = [];
  for (let i = 0; i < 6; i++) for (let j = 0; j < 6; j++) pts.push({ east: 678543.21 + i * 5, north: 9123456.78 + j * 5 });
  assert.equal(delaunay(pts).length, 50);
});

// ------------------------------------------------------------------ Vinculación y flujo completo
test('PRUEBA vinculación por código idéntico; punto radiado sin cota no genera curvas', () => {
  const pr = newProject({ name: 'PRUEBA vinculación' }, 'prueba');
  pr.points = ['BM-1', 'P1', 'P1A', 'P2', 'P3'].map((c) => ({ id: c, code: c, description: '', type: 'otro', instrument: 'ambos' }));
  pr.leveling.bm = { code: 'BM-1', elevation: 100 };
  pr.leveling.rows = [row('1', 'BM-1', 1.5), row('2', 'P1A', null, 1.0), row('3', 'P1', null, null, 1.2)];
  pr.theodolite.convention = conv('horario', 'norte');
  pr.theodolite.stations = [{ id: 's', code: 'EST', east: 0, north: 0, coordType: 'locales', orientation: { northNote: 'brújula' } }];
  pr.theodolite.readings = [
    { id: 'a', stationId: 's', code: 'P1', hz: '0', distType: 'horizontal', distance: 10 },
    { id: 'b', stationId: 's', code: 'P2', hz: '90', distType: 'horizontal', distance: 10 },
    { id: 'c', stationId: 's', code: 'P3', hz: '180', distType: 'horizontal', distance: 10 },
  ];
  pr.plan.equidistance = 0.5;
  let ev = evaluateProject(pr);
  assert.equal(ev.leveling.status, 'sin-calcular');
  acceptResults(pr, 'leveling', ev, 'prueba');
  acceptResults(pr, 'radiation', ev, 'prueba');
  ev = evaluateProject(pr);
  assert.equal(ev.leveling.status, 'vigente');
  const s = new Map(ev.survey.rows.map((r) => [r.code, r]));
  assert.equal(fmt(s.get('P1').elevation), '100.300', 'P1 recibe la cota de P1 (código idéntico)');
  assert.equal(s.get('P2').elevation, null, 'P2 no recibe cota de P1A ni de nadie por parecido');
  assert.equal(s.get('P1A').east, null, 'P1A no hereda coordenadas de P1');
  assert.equal(ev.contours.current.ok, false, 'sin 4 puntos con cota no hay curvas');
  // Al editar una lectura usada, el resultado queda desactualizado y se señala qué cambia
  pr.leveling.rows[2].fs = 1.25;
  ev = evaluateProject(pr);
  assert.equal(ev.leveling.status, 'desactualizado');
  assert.ok(ev.leveling.changes.some((c) => c.includes('Cota de P1') && c.includes('100.300 → 100.250')));
});

test('PRUEBA escalas normalizadas del perfil y del plano', () => {
  assert.equal(chooseScale(85, 237), 500);
  const lay = profileLayout([{ chainage: 0, elevation: 100 }, { chainage: 85, elevation: 99.4 }]);
  assert.equal(lay.hScale, 500);
  assert.ok(lay.vScale <= lay.hScale && lay.vScale >= lay.hScale / 10);
  assert.ok(lay.y(99.4) <= lay.height && lay.y(100) >= 0, 'el perfil cabe en la zona del gráfico');
  const pl = planLayout([{ east: 500, north: 1000 }, { east: 510, north: 1000 }, { east: 505, north: 1020 }]);
  assert.ok(pl.x(510) - pl.x(500) > 0 && Math.abs((pl.x(510) - pl.x(500)) - (10000 / pl.scale)) < 1e-9, 'misma escala en E');
});
