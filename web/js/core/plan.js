// Modelo único del plano: lo usan la vista en pantalla (SVG), el PDF y el DXF.
// Solo contiene resultados ACEPTADOS con «Calcular» (vigentes): nunca datos provisionales.

import { isNum, normCode } from './numbers.js';
import { planLayout } from './scales.js';
import { planLines } from './model.js';

/**
 * @param {object} project
 * @param {object} ev resultado de evaluateProject(project)
 */
export function buildPlanModel(project, ev) {
  const model = {
    ok: false, reason: '', stations: [], points: [], rays: [], lines: [], badLines: [], triangles: [], contours: null,
    layout: null, coordinateSystem: project.coordinateSystem, warnings: [],
  };
  if (ev.radiation.status !== 'vigente' || !ev.radiation.accepted?.computable) {
    model.reason = ev.radiation.status === 'desactualizado'
      ? 'Los resultados del teodolito están desactualizados: pulse «Calcular» en el módulo Teodolito.'
      : 'Primero calcule la radiación en el módulo Teodolito (estación, orientación y lecturas).';
    return model;
  }
  const rad = ev.radiation.accepted;
  const survey = ev.survey;
  const elevByCode = new Map(survey.rows.map((r) => [normCode(r.code), r.elevation]));

  for (const s of rad.stations) {
    model.stations.push({ code: s.code, east: s.east, north: s.north, elevation: elevByCode.get(normCode(s.code)) ?? null });
    if (s.refAzMode === 'coordenadas' && isNum(s.refEast)) {
      model.points.push({ code: s.refCode, east: s.refEast, north: s.refNorth, elevation: elevByCode.get(normCode(s.refCode)) ?? null, kind: 'referencia', stationCode: s.code });
    }
  }
  for (const p of rad.points) {
    model.points.push({ code: p.code, east: p.east, north: p.north, elevation: elevByCode.get(normCode(p.code)) ?? null, kind: 'radiado', stationCode: p.stationCode, description: p.description });
    const st = rad.stations.find((s) => s.id === p.stationId);
    model.rays.push({ from: { east: st.east, north: st.north }, to: { east: p.east, north: p.north }, code: p.code, stationCode: st.code });
  }
  const lines = planLines(project, survey);
  model.lines = lines.filter((l) => l.ok);
  model.badLines = lines.filter((l) => !l.ok);
  for (const l of model.badLines) {
    model.warnings.push(`La línea «${l.name}» no se dibuja: ${l.missing.length ? `faltan coordenadas de ${l.missing.join(', ')}` : 'necesita al menos 2 puntos'}.`);
  }

  if (ev.contours.status === 'vigente' && ev.contours.accepted?.ok) {
    const c = ev.contours.accepted;
    model.contours = c;
    model.triangles = c.triangles.map(([i, j, k]) => [c.usedPoints[i], c.usedPoints[j], c.usedPoints[k]]);
  }

  const all = [...model.stations, ...model.points];
  if (all.length === 0) { model.reason = 'No hay puntos con coordenadas.'; return model; }
  model.layout = planLayout(all);
  model.ok = true;
  return model;
}

/** Aristas únicas de la malla de triángulos (para dibujar cada lado una sola vez). */
export function uniqueEdges(triangles) {
  const seen = new Set();
  const edges = [];
  for (const t of triangles) {
    for (let e = 0; e < 3; e++) {
      const a = t[e]; const b = t[(e + 1) % 3];
      const key = [a.code, b.code].sort().join('|');
      if (seen.has(key)) continue;
      seen.add(key);
      edges.push([a, b]);
    }
  }
  return edges;
}

/** Punto medio de una polilínea (para rotular la curva). */
export function polylineLabelPoint(points) {
  if (points.length === 1) return points[0];
  let total = 0;
  const seg = [];
  for (let i = 1; i < points.length; i++) {
    const d = Math.hypot(points[i].east - points[i - 1].east, points[i].north - points[i - 1].north);
    seg.push(d); total += d;
  }
  let acc = 0;
  for (let i = 1; i < points.length; i++) {
    if (acc + seg[i - 1] >= total / 2) {
      const t = seg[i - 1] ? (total / 2 - acc) / seg[i - 1] : 0;
      return { east: points[i - 1].east + t * (points[i].east - points[i - 1].east), north: points[i - 1].north + t * (points[i].north - points[i - 1].north) };
    }
    acc += seg[i - 1];
  }
  return points[points.length - 1];
}
