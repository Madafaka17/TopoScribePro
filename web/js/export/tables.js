// Tablas de datos para exportar (Excel, CSV): separa claramente
//   · DATOS DE CAMPO (lecturas tal como se registraron, con marca de corrección) y
//   · RESULTADOS (solo cálculos aceptados con «Calcular» y vigentes).

import { isNum, round } from '../core/numbers.js';
import { formatAngle } from '../core/angles.js';
import { INSTRUMENTS, POINT_TYPES, auditValue } from '../core/model.js';
import { rowTypeLabel } from '../core/leveling.js';
import { DIRECTION_LABEL, ORIGIN_LABEL } from '../core/radiation.js';

/** Celda numérica con `d` decimales (vacía si no hay valor). */
export const N = (v, d = 3) => (isNum(v) ? { n: round(v, d) === 0 ? 0 : round(v, d), d } : '');

const STATUS_NOTE = {
  'sin-datos': 'No se registraron datos para este cálculo.',
  'sin-calcular': 'Resultados no calculados: pulse «Calcular» en la aplicación.',
  desactualizado: 'Resultados DESACTUALIZADOS: los datos de campo cambiaron después del último cálculo. Pulse «Calcular».',
};

function edited(project, id) {
  return project.audit.some((a) => a.entityId === id && a.action === 'editar') ? 'sí' : 'no';
}

export function buildTables(project, ev) {
  const tables = [];
  const U = project.theodolite.convention.unit || 'sexagesimal';

  tables.push({
    name: 'Proyecto', kind: 'datos',
    headers: ['Campo', 'Valor'],
    rows: [
      ['Nombre', project.name], ['Ubicación', project.location], ['Fecha', project.date], ['Responsables', project.responsibles],
      ['Objetivo', project.objective], ['Sistema de coordenadas declarado', project.coordinateSystem], ['Referencia de cotas', project.elevationDatum],
      ['Descripción del lugar', project.siteDescription], ['Procedimiento de campo', project.fieldProcedure], ['Observaciones', project.observations],
      ['Nivel de ingeniero', [project.instruments.level.brand, project.instruments.level.model, project.instruments.level.serial].filter(Boolean).join(' · ')],
      ['Teodolito', [project.instruments.theodolite.brand, project.instruments.theodolite.model, project.instruments.theodolite.serial].filter(Boolean).join(' · ')],
      ['Exportado', new Date().toISOString()],
    ],
  });

  tables.push({
    name: 'Puntos', kind: 'datos',
    headers: ['Código', 'Descripción', 'Tipo', 'Instrumento', 'Observaciones', 'Cota justificada (m)', 'Justificación de la cota', 'Fotografía', 'Pie de foto'],
    rows: project.points.map((p) => [p.code, p.description, POINT_TYPES[p.type] ?? p.type, INSTRUMENTS[p.instrument] ?? p.instrument, p.observations,
      N(p.knownElevation), p.elevationJustification ?? '', p.photo ? 'sí' : 'Sin fotografía registrada', p.photo?.caption ?? '']),
  });

  const L = project.leveling;
  tables.push({
    name: 'Nivel - libreta de campo', kind: 'datos',
    note: `Datos de campo del nivel de ingeniero. BM inicial: ${L.bm.code || '—'} (cota ${isNum(L.bm.elevation) ? L.bm.elevation.toFixed(3) : '—'} m). ${L.endBm.enabled ? `BM final: ${L.endBm.code} (cota ${isNum(L.endBm.elevation) ? L.endBm.elevation.toFixed(3) : '—'} m).` : 'Sin BM final de control.'}`,
    headers: ['N.°', 'Punto', 'Vista atrás VA (m)', 'Vista intermedia VI (m)', 'Vista adelante VD (m)', 'Distancia parcial (m)', 'Progresiva (m)', 'En perfil', 'Observaciones', 'Registrado por', 'Fecha de registro', 'Corregida'],
    rows: L.rows.map((r, i) => [i + 1, r.code, N(r.bs), N(r.is), N(r.fs), N(r.partial), N(r.cumulative), r.inProfile === false ? 'no' : 'sí', r.notes ?? '', r.createdBy ?? '', r.createdAt ?? '', edited(project, r.id)]),
  });

  const T = project.theodolite;
  const stationById = new Map(T.stations.map((s) => [s.id, s]));
  tables.push({
    name: 'Teodolito - libreta de campo', kind: 'datos',
    note: `Convenio angular: unidad ${T.convention.unit || '—'}; sentido ${DIRECTION_LABEL[T.convention.direction] ?? '—'}; origen ${ORIGIN_LABEL[T.convention.origin] ?? '—'}.`,
    headers: ['N.°', 'Estación', 'E estación (m)', 'N estación (m)', 'Referencia', 'Az referencia', 'Lectura en referencia', 'Punto', 'Lectura horizontal', 'Tipo de distancia', 'Distancia (m)', 'Tipo de ángulo vertical', 'Ángulo vertical', 'Observaciones', 'Registrado por', 'Fecha de registro', 'Corregida'],
    rows: T.readings.map((r, i) => {
      const s = stationById.get(r.stationId);
      const o = s?.orientation ?? {};
      return [i + 1, s?.code ?? '(no existe)', N(s?.east), N(s?.north), o.refCode ?? '', o.azMode === 'coordenadas' ? `por coordenadas (${o.refEast}, ${o.refNorth})` : (o.refAzimuth ?? ''), o.refReading ?? '',
        r.code, r.hz, r.distType, N(r.distance), r.distType === 'inclinada' ? r.vType : '', r.distType === 'inclinada' ? r.vAngle : '', r.notes ?? '', r.createdBy ?? '', r.createdAt ?? '', edited(project, r.id)];
    }),
  });

  tables.push({
    name: 'Correcciones', kind: 'datos',
    note: 'Registro de cambios: valor original, valor nuevo, quién y cuándo.',
    headers: ['Fecha y hora', 'Usuario', 'Acción', 'Registro', 'Campo', 'Valor anterior', 'Valor nuevo', 'Motivo'],
    rows: project.audit.flatMap((a) => (a.changes?.length ? a.changes : [{ label: '', from: '', to: '' }]).map((c) => [a.at, a.by, a.action, a.label, c.label, c.from === '' ? '' : auditValue(c.from), c.to === '' ? '' : auditValue(c.to), a.reason ?? ''])),
  });

  // ---------------------------- Resultados
  const lev = ev.leveling;
  if (lev.status === 'vigente' && lev.accepted) {
    const a = lev.accepted;
    const rows = a.rows.map((r) => [r.n, r.station, r.code, rowTypeLabel(r.type), N(r.bs), N(r.is), N(r.fs), N(r.hiNew ?? r.hiUsed), N(r.elevation), N(r.partial), N(r.cumulative)]);
    rows.push([]);
    rows.push(['', '', 'ΣVA', '', N(a.sums.sumBs)]);
    rows.push(['', '', 'ΣVD', '', '', '', N(a.sums.sumFs)]);
    rows.push(['', '', 'ΣVA − ΣVD', '', N(a.sums.diff)]);
    if (a.sums.applicable) rows.push(['', '', 'Cota final − cota inicial', '', N(a.sums.elevDiff), '', '', '', a.sums.ok ? 'Coincide' : 'NO coincide']);
    rows.push(['', '', 'Cierre', a.closure.explanation]);
    tables.push({ name: 'Nivel - cálculo', kind: 'resultados', note: `Calculado el ${lev.stored.at} por ${lev.stored.by || '—'}.`, headers: ['N.°', 'Estación', 'Punto', 'Tipo', 'VA (m)', 'VI (m)', 'VD (m)', 'AI (m)', 'Cota (m)', 'Dist. parcial (m)', 'Dist. acumulada (m)'], rows });
  } else {
    tables.push({ name: 'Nivel - cálculo', kind: 'resultados', note: STATUS_NOTE[lev.status], headers: ['Aviso'], rows: [[STATUS_NOTE[lev.status]]] });
  }

  const rad = ev.radiation;
  if (rad.status === 'vigente' && rad.accepted) {
    const a = rad.accepted;
    tables.push({
      name: 'Teodolito - cálculo', kind: 'resultados', note: `Calculado el ${rad.stored.at} por ${rad.stored.by || '—'}.`,
      headers: ['N.°', 'Estación', 'Punto', 'Lectura horizontal', 'Azimut', 'Azimut (grados decimales)', 'Dh (m)', 'ΔE (m)', 'ΔN (m)', 'Este (m)', 'Norte (m)'],
      rows: a.points.map((p) => [p.n, p.stationCode, p.code, formatAngle(p.hzDeg, U), formatAngle(p.az, U), N(p.az, 6), N(p.dh), N(p.dE), N(p.dN), N(p.east), N(p.north)]),
    });
  } else {
    tables.push({ name: 'Teodolito - cálculo', kind: 'resultados', note: STATUS_NOTE[rad.status], headers: ['Aviso'], rows: [[STATUS_NOTE[rad.status]]] });
  }

  tables.push({
    name: 'Coordenadas y cotas', kind: 'resultados',
    note: 'Vinculación por código idéntico de punto: E y N del teodolito, cota del nivel (o cota justificada).',
    headers: ['Código', 'Descripción', 'Este (m)', 'Norte (m)', 'Cota (m)', 'Origen de E/N', 'Origen de la cota', 'Estado'],
    rows: ev.survey.rows.map((r) => [r.code, r.description, N(r.east), N(r.north), N(r.elevation), r.coordSource, r.elevSource, r.status]),
  });

  const ct = ev.contours;
  if (ct.status === 'vigente' && ct.accepted?.ok) {
    const c = ct.accepted;
    tables.push({
      name: 'Curvas de nivel', kind: 'resultados', note: `${c.method} ${c.interpolation}`,
      headers: ['Concepto', 'Valor'],
      rows: [['Equidistancia (m)', N(c.equidistance, 2)], ['Puntos usados', c.usedPoints.length], ['Triángulos', c.triangles.length], ['Cota mínima (m)', N(c.minZ)], ['Cota máxima (m)', N(c.maxZ)],
        ['Curvas (cotas)', c.levels.map((l) => l.toFixed(2)).join(', ')], ['Polilíneas', c.polylines.length]],
    });
  } else {
    const note = ct.status === 'vigente' ? (ct.accepted?.reason || 'No se generaron curvas.') : STATUS_NOTE[ct.status];
    tables.push({ name: 'Curvas de nivel', kind: 'resultados', note, headers: ['Aviso'], rows: [[note]] });
  }
  return tables;
}
