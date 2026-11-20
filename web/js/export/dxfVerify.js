// Relee un DXF generado (pares código/valor) y comprueba sus entidades y coordenadas contra el
// modelo del plano. Se usa en la app después de cada exportación y en las pruebas automáticas.
import { normCode } from '../core/numbers.js';

export function parseDxfEntities(text) {
  const lines = text.split(/\r?\n/);
  const pairs = [];
  for (let i = 0; i + 1 < lines.length; i += 2) pairs.push([Number(lines[i].trim()), lines[i + 1]]);
  const header = {};
  const entities = [];
  let section = null;
  let cur = null;
  let lastVar = null;
  for (let i = 0; i < pairs.length; i++) {
    const [c, v] = pairs[i];
    if (c === 0 && v === 'SECTION') { section = pairs[i + 1]?.[1]; i += 1; continue; }
    if (c === 0 && v === 'ENDSEC') { if (cur) entities.push(cur); cur = null; section = null; continue; }
    if (section === 'HEADER') {
      if (c === 9) { lastVar = v; header[v] = []; } else if (lastVar) header[lastVar].push(v);
      continue;
    }
    if (section !== 'ENTITIES') continue;
    if (c === 0) { if (cur) entities.push(cur); cur = { type: v, codes: [] }; continue; }
    cur?.codes.push([c, v]);
  }
  for (const e of entities) {
    const get = (code) => e.codes.find(([c]) => c === code)?.[1];
    e.layer = get(8);
    if (e.type === 'POINT') e.xyz = [Number(get(10)), Number(get(20)), Number(get(30))];
    if (e.type === 'LINE') { e.a = [Number(get(10)), Number(get(20))]; e.b = [Number(get(11)), Number(get(21))]; }
    if (e.type === 'TEXT') { e.text = get(1); e.at = [Number(get(10)), Number(get(20))]; }
    if (e.type === 'LWPOLYLINE') {
      e.vertices = [];
      let x = null;
      for (const [c, v] of e.codes) { if (c === 10) x = Number(v); if (c === 20) e.vertices.push([x, Number(v)]); }
      e.closed = (Number(get(70)) & 1) === 1;
      e.elevation = Number(get(38) ?? 0);
    }
  }
  return { header, entities };
}

/** @returns {{ok:boolean, lines:string[], maxDiff:number}} */
export function verifyDxf(text, plan) {
  const { header, entities } = parseDxfEntities(text);
  const out = [];
  let ok = true;
  const fail = (m) => { ok = false; out.push(`✗ ${m}`); };
  const version = header.$ACADVER?.[0];
  const units = header.$INSUNITS?.[0];
  if (version === 'AC1015') out.push('✓ Formato DXF R2000 (AC1015), compatible con AutoCAD.'); else fail(`Versión DXF inesperada: ${version}`);
  if (units === '6') out.push('✓ Unidades del dibujo: metros ($INSUNITS = 6).'); else fail(`Unidades no declaradas en metros ($INSUNITS = ${units}).`);
  const byLayer = {};
  for (const e of entities) { const k = `${e.layer}/${e.type}`; byLayer[k] = (byLayer[k] ?? 0) + 1; }
  const texts = entities.filter((e) => e.type === 'TEXT' && e.layer === 'CODIGOS');
  let maxDiff = 0;
  const check = (code, east, north, layer) => {
    const label = texts.find((t) => t.text === code);
    const pts = entities.filter((e) => e.type === 'POINT' && e.layer === layer);
    const best = pts.reduce((m, e) => Math.min(m, Math.hypot(e.xyz[0] - east, e.xyz[1] - north)), Infinity);
    maxDiff = Math.max(maxDiff, best);
    if (!label) fail(`Falta el rótulo del punto ${code}.`);
    if (!(best <= 0.0005)) fail(`El punto ${code} no está en (${east.toFixed(3)}, ${north.toFixed(3)}) en el DXF.`);
  };
  for (const s of plan.stations) check(s.code, s.east, s.north, 'ESTACION');
  for (const p of plan.points) check(p.code, p.east, p.north, 'PUNTOS');
  const nPts = (byLayer['PUNTOS/POINT'] ?? 0);
  if (nPts !== plan.points.length) fail(`Se esperaban ${plan.points.length} puntos y el DXF tiene ${nPts}.`);
  const nRays = byLayer['RADIACIONES/LINE'] ?? 0;
  if (nRays !== plan.rays.length) fail(`Se esperaban ${plan.rays.length} radiaciones y el DXF tiene ${nRays}.`);
  const nLines = byLayer['LINEAS/LWPOLYLINE'] ?? 0;
  if (nLines !== plan.lines.length) fail(`Se esperaban ${plan.lines.length} líneas de usuario y el DXF tiene ${nLines}.`);
  const nCont = byLayer['CURVAS_NIVEL/LWPOLYLINE'] ?? 0;
  const expCont = plan.contours ? plan.contours.polylines.length : 0;
  if (nCont !== expCont) fail(`Se esperaban ${expCont} curvas de nivel y el DXF tiene ${nCont}.`);
  if (ok) {
    out.push(`✓ ${plan.stations.length} estación(es) y ${nPts} puntos con sus códigos; diferencia máxima de coordenadas ${maxDiff.toFixed(4)} m.`);
    out.push(`✓ ${nRays} radiaciones, ${nLines} línea(s) definidas por el usuario${plan.contours ? `, ${byLayer['TRIANGULACION/LINE'] ?? 0} lados de triangulación y ${nCont} curvas de nivel` : '; sin curvas de nivel (no calculadas)'}.`);
  }
  const elevOk = plan.points.filter((p) => p.elevation !== null && p.elevation !== undefined).every((p) => {
    const e = entities.find((x) => x.type === 'POINT' && x.layer === 'PUNTOS' && Math.hypot(x.xyz[0] - p.east, x.xyz[1] - p.north) < 0.0005);
    return e && Math.abs(e.xyz[2] - p.elevation) < 0.0005;
  });
  if (elevOk) out.push('✓ La coordenada Z de cada punto con cota coincide con su cota.'); else fail('La Z de algún punto no coincide con su cota.');
  return { ok, lines: out, maxDiff, counts: byLayer, codes: texts.map((t) => normCode(t.text)) };
}
