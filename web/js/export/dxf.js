// Exportación DXF R2000 (AC1015) compatible con AutoCAD: unidades en metros ($INSUNITS = 6),
// capas del levantamiento y coordenadas reales (X = Este, Y = Norte, Z = cota cuando se conoce).
// La estructura del archivo (tablas, bloques, objetos) viene de una plantilla generada con ezdxf;
// aquí solo se agregan las entidades.

import { TEMPLATE, MODEL_SPACE_HANDLE, FIRST_FREE_HANDLE } from './dxf-template.js';
import { uniqueEdges, polylineLabelPoint } from '../core/plan.js';
import { fmt, isNum } from '../core/numbers.js';

const num = (v) => {
  const s = (Math.round(v * 1e6) / 1e6).toFixed(6);
  return s === '-0.000000' ? '0.000000' : s;
};

/** Texto DXF R2000: caracteres no ASCII como \U+XXXX (AutoCAD los interpreta en cualquier idioma). */
export function dxfText(text) {
  return String(text ?? '')
    .replace(/[\r\n]+/g, ' ')
    .replace(/[^\x20-\x7e]/gu, (ch) => `\\U+${ch.codePointAt(0).toString(16).toUpperCase().padStart(4, '0')}`);
}

class EntityWriter {
  constructor() {
    this.handle = parseInt(FIRST_FREE_HANDLE, 16);
    this.parts = [];
    this.count = {};
    this.min = [Infinity, Infinity, Infinity];
    this.max = [-Infinity, -Infinity, -Infinity];
  }

  ext(x, y, z = 0) {
    this.min = [Math.min(this.min[0], x), Math.min(this.min[1], y), Math.min(this.min[2], z)];
    this.max = [Math.max(this.max[0], x), Math.max(this.max[1], y), Math.max(this.max[2], z)];
  }

  begin(type, layer, subclass) {
    const h = (this.handle++).toString(16).toUpperCase();
    this.count[type] = (this.count[type] ?? 0) + 1;
    this.parts.push('  0', type, '  5', h, '330', MODEL_SPACE_HANDLE, '100', 'AcDbEntity', '  8', layer, '100', subclass);
  }

  point(layer, x, y, z = 0) {
    this.begin('POINT', layer, 'AcDbPoint');
    this.parts.push(' 10', num(x), ' 20', num(y), ' 30', num(z));
    this.ext(x, y, z);
  }

  line(layer, a, b) {
    this.begin('LINE', layer, 'AcDbLine');
    this.parts.push(' 10', num(a[0]), ' 20', num(a[1]), ' 30', num(a[2] ?? 0), ' 11', num(b[0]), ' 21', num(b[1]), ' 31', num(b[2] ?? 0));
    this.ext(a[0], a[1]); this.ext(b[0], b[1]);
  }

  circle(layer, x, y, r) {
    this.begin('CIRCLE', layer, 'AcDbCircle');
    this.parts.push(' 10', num(x), ' 20', num(y), ' 30', '0.000000', ' 40', num(r));
    this.ext(x - r, y - r); this.ext(x + r, y + r);
  }

  text(layer, x, y, height, value) {
    this.begin('TEXT', layer, 'AcDbText');
    this.parts.push(' 10', num(x), ' 20', num(y), ' 30', '0.000000', ' 40', num(height), '  1', dxfText(value), '100', 'AcDbText');
    this.ext(x, y);
  }

  lwpolyline(layer, pts, closed = false, elevation = 0) {
    this.begin('LWPOLYLINE', layer, 'AcDbPolyline');
    this.parts.push(' 90', String(pts.length), ' 70', closed ? '1' : '0', ' 38', num(elevation));
    for (const [x, y] of pts) { this.parts.push(' 10', num(x), ' 20', num(y)); this.ext(x, y); }
  }
}

/**
 * @param {object} plan modelo de buildPlanModel (solo resultados vigentes)
 * @param {object} project
 * @param {{layers?: Record<string, boolean>}} options capas a incluir
 * @returns {{text: string, summary: object}}
 */
export function buildDxf(plan, project, options = {}) {
  if (!plan?.ok) throw new Error(plan?.reason || 'No hay un plano calculado para exportar.');
  const on = { estacion: true, puntos: true, radiaciones: true, lineas: true, triangulacion: true, curvas: true, ...(options.layers ?? {}) };
  const scale = plan.layout.scale;
  const h = (mm) => (mm * scale) / 1000; // milímetros de papel → metros de dibujo
  const w = new EntityWriter();

  if (on.estacion) {
    for (const s of plan.stations) {
      w.point('ESTACION', s.east, s.north, isNum(s.elevation) ? s.elevation : 0);
      w.circle('ESTACION', s.east, s.north, h(1.6));
      w.text('CODIGOS', s.east + h(2), s.north + h(1), h(2.2), s.code);
    }
  }
  if (on.puntos) {
    for (const p of plan.points) {
      w.point('PUNTOS', p.east, p.north, isNum(p.elevation) ? p.elevation : 0);
      w.text('CODIGOS', p.east + h(1.2), p.north + h(1), h(2), p.code);
      if (isNum(p.elevation)) w.text('COTAS', p.east + h(1.2), p.north - h(2.4), h(1.6), fmt(p.elevation));
    }
  }
  if (on.radiaciones) for (const r of plan.rays) w.line('RADIACIONES', [r.from.east, r.from.north], [r.to.east, r.to.north]);
  if (on.lineas) for (const l of plan.lines) w.lwpolyline('LINEAS', (l.closed ? l.pts.slice(0, -1) : l.pts).map((p) => [p.east, p.north]), l.closed);
  if (plan.contours) {
    if (on.triangulacion) for (const [a, b] of uniqueEdges(plan.triangles)) w.line('TRIANGULACION', [a.east, a.north], [b.east, b.north]);
    if (on.curvas) {
      for (const pl of plan.contours.polylines) {
        w.lwpolyline('CURVAS_NIVEL', pl.points.map((p) => [p.east, p.north]), false, pl.level);
        const lp = polylineLabelPoint(pl.points);
        w.text('CURVAS_TEXTO', lp.east, lp.north, h(1.6), fmt(pl.level, 2));
      }
    }
  }

  // Norte y notas (fuera del área de los puntos, en la capa NOTAS)
  const L = plan.layout;
  const nx = L.eMax - h(12);
  const ny = L.nMax - h(25);
  w.line('NOTAS', [nx, ny], [nx, ny + h(15)]);
  w.line('NOTAS', [nx - h(3), ny + h(10)], [nx, ny + h(15)]);
  w.line('NOTAS', [nx + h(3), ny + h(10)], [nx, ny + h(15)]);
  w.text('NOTAS', nx - h(1.5), ny + h(17), h(4), 'N');
  const notes = [
    `Proyecto: ${project.name || '(sin nombre)'}`,
    `Unidades: metros (1 unidad de dibujo = 1 m). X = Este, Y = Norte, Z = cota (Z = 0 en puntos sin cota).`,
    `Sistema de coordenadas: ${project.coordinateSystem || '(no declarado)'}`,
    `Escala de referencia del plano: 1:${scale}`,
  ];
  if (plan.contours) notes.push(`Curvas de nivel cada ${fmt(plan.contours.equidistance, 2)} m. Triangulacion de Delaunay e interpolacion lineal.`);
  notes.forEach((t, i) => w.text('NOTAS', L.eMin + h(2), L.nMin + h(2 + (notes.length - 1 - i) * 4), h(2.2), t));

  const ent = w.parts.length ? `${w.parts.join('\n')}\n` : '';
  const text = TEMPLATE
    .replace('%%ENTITIES%%', ent)
    .replace('%%HANDSEED%%', w.handle.toString(16).toUpperCase())
    .replace('%%EXTMIN_X%%', num(w.min[0])).replace('%%EXTMIN_Y%%', num(w.min[1])).replace('%%EXTMIN_Z%%', num(w.min[2]))
    .replace('%%EXTMAX_X%%', num(w.max[0])).replace('%%EXTMAX_Y%%', num(w.max[1])).replace('%%EXTMAX_Z%%', num(w.max[2]));
  return { text: text.replace(/\n/g, '\r\n'), summary: { entities: w.count, scale } };
}
