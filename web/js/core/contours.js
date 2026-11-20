// Triangulación de Delaunay (Bowyer–Watson incremental) y curvas de nivel por interpolación lineal
// sobre los lados de los triángulos.
//
// Método declarado en pantalla y en el informe (TRIANGULATION_METHOD / INTERPOLATION_METHOD).

import { fmt, isNum } from './numbers.js';

export const TRIANGULATION_METHOD = 'Triangulación de Delaunay (algoritmo incremental de Bowyer–Watson) sobre las coordenadas Este y Norte de los puntos con cota.';
export const INTERPOLATION_METHOD = 'Interpolación lineal de la cota a lo largo de cada lado del triángulo: t = (Zc − Za) / (Zb − Za); P = A + t · (B − A), donde Zc es la cota de la curva.';

const DUP_TOL = 0.001; // dos puntos a menos de 1 mm se consideran el mismo lugar
const MIN_POINTS = 4;
const MAX_LEVELS = 500;

/**
 * Triangula puntos {id, east, north}. Devuelve triángulos como tríos de índices [i, j, k] (antihorario).
 * Las coordenadas se trasladan al centroide para no perder precisión con coordenadas grandes (UTM).
 */
export function delaunay(points) {
  const n = points.length;
  if (n < 3) return [];
  let cx = 0; let cy = 0;
  for (const p of points) { cx += p.east; cy += p.north; }
  cx /= n; cy /= n;
  const xs = points.map((p) => p.east - cx);
  const ys = points.map((p) => p.north - cy);
  let minX = Infinity; let minY = Infinity; let maxX = -Infinity; let maxY = -Infinity;
  for (let i = 0; i < n; i++) {
    minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
    minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
  }
  const span = Math.max(maxX - minX, maxY - minY, 1);
  const mx = (minX + maxX) / 2;
  const my = (minY + maxY) / 2;
  // Súper-triángulo holgado (índices n, n+1, n+2)
  xs.push(mx - 100 * span, mx, mx + 100 * span);
  ys.push(my - 100 * span, my + 100 * span, my - 100 * span);

  const orient = (a, b, c) => (xs[b] - xs[a]) * (ys[c] - ys[a]) - (xs[c] - xs[a]) * (ys[b] - ys[a]);
  const makeTri = (a, b, c) => {
    if (orient(a, b, c) < 0) [b, c] = [c, b];
    // circuncentro y radio² para la prueba de incírculo
    const ax = xs[a]; const ay = ys[a]; const bx = xs[b]; const by = ys[b]; const qx = xs[c]; const qy = ys[c];
    const d = 2 * (ax * (by - qy) + bx * (qy - ay) + qx * (ay - by));
    const a2 = ax * ax + ay * ay; const b2 = bx * bx + by * by; const c2 = qx * qx + qy * qy;
    const ux = (a2 * (by - qy) + b2 * (qy - ay) + c2 * (ay - by)) / d;
    const uy = (a2 * (qx - bx) + b2 * (ax - qx) + c2 * (bx - ax)) / d;
    const r2 = (ax - ux) ** 2 + (ay - uy) ** 2;
    return { v: [a, b, c], ux, uy, r2 };
  };

  let tris = [makeTri(n, n + 1, n + 2)];
  for (let i = 0; i < n; i++) {
    const px = xs[i]; const py = ys[i];
    const bad = [];
    const keep = [];
    for (const t of tris) {
      const d2 = (px - t.ux) ** 2 + (py - t.uy) ** 2;
      if (d2 < t.r2 * (1 - 1e-12)) bad.push(t); else keep.push(t);
    }
    if (bad.length === 0) continue;
    // Borde del hueco poligonal: aristas que pertenecen a un solo triángulo "malo"
    const edgeCount = new Map();
    for (const t of bad) {
      for (let e = 0; e < 3; e++) {
        const a = t.v[e]; const b = t.v[(e + 1) % 3];
        const key = a < b ? `${a},${b}` : `${b},${a}`;
        const prev = edgeCount.get(key);
        edgeCount.set(key, prev ? { a, b, count: prev.count + 1 } : { a, b, count: 1 });
      }
    }
    tris = keep;
    for (const { a, b, count } of edgeCount.values()) {
      if (count === 1 && Math.abs(orient(a, b, i)) > 1e-12 * span * span) tris.push(makeTri(a, b, i));
    }
  }
  return tris.filter((t) => t.v.every((k) => k < n)).map((t) => t.v);
}

/**
 * Prepara los puntos para la triangulación: descarta los duplicados (misma posición) y detecta
 * conflictos (misma posición con cotas distintas).
 */
export function preparePoints(points) {
  const errors = [];
  const warnings = [];
  const unique = [];
  for (const p of points) {
    if (!isNum(p.east) || !isNum(p.north) || !isNum(p.elevation)) continue;
    const dup = unique.find((q) => Math.hypot(q.east - p.east, q.north - p.north) < DUP_TOL);
    if (dup) {
      if (Math.abs(dup.elevation - p.elevation) >= 0.0005) {
        errors.push(`Los puntos ${dup.code} y ${p.code} ocupan la misma posición (E ${fmt(p.east)}, N ${fmt(p.north)}) con cotas distintas (${fmt(dup.elevation)} y ${fmt(p.elevation)} m). Corrija uno de ellos.`);
      } else {
        warnings.push(`El punto ${p.code} coincide en posición y cota con ${dup.code}; se usa una sola vez en la triangulación.`);
      }
      continue;
    }
    unique.push(p);
  }
  return { unique, errors, warnings };
}

function triangleArea(a, b, c) {
  return ((b.east - a.east) * (c.north - a.north) - (c.east - a.east) * (b.north - a.north)) / 2;
}

/**
 * Triangulación + curvas de nivel.
 * @param {Array<{code, east, north, elevation}>} points puntos con E, N y cota válidos
 * @param {number} equidistance equidistancia entre curvas (m)
 */
export function computeContours(points, equidistance) {
  const result = {
    ok: false, reason: '', errors: [], warnings: [], method: TRIANGULATION_METHOD, interpolation: INTERPOLATION_METHOD,
    equidistance, usedPoints: [], triangles: [], levels: [], polylines: [], minZ: null, maxZ: null,
  };
  const fail = (reason) => { result.reason = reason; return result; };

  const prep = preparePoints(points);
  result.errors.push(...prep.errors);
  result.warnings.push(...prep.warnings);
  const pts = prep.unique;
  result.usedPoints = pts;
  if (prep.errors.length) return fail('Hay puntos duplicados en la misma posición con cotas distintas.');
  if (pts.length < MIN_POINTS) {
    return fail(`Se necesitan al menos ${MIN_POINTS} puntos con Este, Norte y cota válidos (hay ${pts.length}). Con menos puntos no se obtiene una triangulación útil.`);
  }

  // Colinealidad: todos los puntos sobre una recta no forman superficie
  const span = Math.max(...pts.map((p) => p.east)) - Math.min(...pts.map((p) => p.east)) + Math.max(...pts.map((p) => p.north)) - Math.min(...pts.map((p) => p.north));
  const p0 = pts[0];
  const far = pts.reduce((best, p) => (Math.hypot(p.east - p0.east, p.north - p0.north) > Math.hypot(best.east - p0.east, best.north - p0.north) ? p : best), p0);
  const maxOff = Math.max(...pts.map((p) => Math.abs(triangleArea(p0, far, p)) * 2 / Math.max(Math.hypot(far.east - p0.east, far.north - p0.north), 1e-9)));
  if (maxOff < Math.max(0.01, span * 1e-4)) return fail('Los puntos están prácticamente alineados: no forman una superficie que se pueda triangular.');

  const triIdx = delaunay(pts);
  // Se descartan triángulos degenerados (área casi nula respecto de su tamaño)
  const tris = [];
  let degenerate = 0;
  for (const [i, j, k] of triIdx) {
    const a = pts[i]; const b = pts[j]; const c = pts[k];
    const area = Math.abs(triangleArea(a, b, c));
    const longest = Math.max(Math.hypot(a.east - b.east, a.north - b.north), Math.hypot(b.east - c.east, b.north - c.north), Math.hypot(c.east - a.east, c.north - a.north));
    if (area < 1e-6 || area / (longest * longest) < 1e-6) { degenerate += 1; continue; }
    tris.push([i, j, k]);
  }
  if (degenerate) result.warnings.push(`Se descartaron ${degenerate} triángulo(s) degenerado(s) (área prácticamente nula).`);
  result.triangles = tris;
  if (tris.length < 2) return fail(`La distribución de los puntos solo permite ${tris.length} triángulo(s): no es una triangulación útil para curvas de nivel. Agregue puntos repartidos en la zona.`);

  if (!isNum(equidistance) || equidistance <= 0) return fail('Indique una equidistancia mayor que cero (m).');
  const zs = pts.map((p) => p.elevation);
  const minZ = Math.min(...zs);
  const maxZ = Math.max(...zs);
  result.minZ = minZ;
  result.maxZ = maxZ;
  const kStart = Math.ceil(minZ / equidistance - 1e-9);
  const kEnd = Math.floor(maxZ / equidistance + 1e-9);
  const count = kEnd - kStart + 1;
  if (count <= 0) return fail(`El desnivel del terreno (${fmt(maxZ - minZ)} m, de ${fmt(minZ)} a ${fmt(maxZ)} m) no alcanza ninguna curva múltiplo de la equidistancia ${fmt(equidistance)} m. Use una equidistancia menor.`);
  if (count > MAX_LEVELS) return fail(`La equidistancia ${fmt(equidistance)} m genera ${count} curvas: es demasiado pequeña para este desnivel.`);

  for (let k = kStart; k <= kEnd; k++) {
    const level = Number((k * equidistance).toFixed(6));
    result.levels.push(level);
    const segments = [];
    for (const [i, j, k2] of tris) {
      const v = [i, j, k2];
      const crossings = [];
      for (let e = 0; e < 3; e++) {
        const ia = v[e]; const ib = v[(e + 1) % 3];
        const A = pts[ia]; const B = pts[ib];
        const aAbove = A.elevation >= level;
        const bAbove = B.elevation >= level;
        if (aAbove === bAbove) continue;
        const t = (level - A.elevation) / (B.elevation - A.elevation);
        let key;
        if (t <= 1e-12) key = `v${ia}`;
        else if (t >= 1 - 1e-12) key = `v${ib}`;
        else key = ia < ib ? `e${ia}_${ib}` : `e${ib}_${ia}`;
        crossings.push({ key, east: A.east + t * (B.east - A.east), north: A.north + t * (B.north - A.north) });
      }
      if (crossings.length === 2 && crossings[0].key !== crossings[1].key) segments.push(crossings);
    }
    for (const line of chainSegments(segments)) result.polylines.push({ level, points: line });
  }
  result.ok = true;
  if (result.polylines.length === 0) result.warnings.push('Ninguna curva cruza los triángulos (todas las curvas coinciden con vértices).');
  return result;
}

/** Une segmentos que comparten un extremo (misma clave de arista o vértice) en polilíneas. */
function chainSegments(segments) {
  const byKey = new Map();
  segments.forEach((s, idx) => {
    for (const end of s) {
      if (!byKey.has(end.key)) byKey.set(end.key, []);
      byKey.get(end.key).push(idx);
    }
  });
  const used = new Array(segments.length).fill(false);
  const lines = [];
  const extend = (line, key) => {
    let current = key;
    for (;;) {
      const next = (byKey.get(current) ?? []).find((idx) => !used[idx]);
      if (next === undefined) break;
      used[next] = true;
      const [a, b] = segments[next];
      const other = a.key === current ? b : a;
      line.push(other);
      current = other.key;
    }
  };
  for (let s = 0; s < segments.length; s++) {
    if (used[s]) continue;
    used[s] = true;
    const [a, b] = segments[s];
    const forward = [a, b];
    extend(forward, b.key);
    const backward = [];
    extend(backward, a.key);
    const line = [...backward.reverse(), ...forward].map(({ east, north }) => ({ east, north }));
    lines.push(line);
  }
  return lines;
}
