// Modelo del proyecto: estructura de datos, vinculación de resultados de ambos instrumentos,
// estado de los cálculos (vigente / desactualizado) y registro de correcciones.

import { computeLeveling } from './leveling.js';
import { computeRadiation } from './radiation.js';
import { computeContours } from './contours.js';
import { fmt, isNum, normCode, stableHash } from './numbers.js';
import { formatAngle } from './angles.js';

export const SCHEMA = 'toposcribe-web';
export const SCHEMA_VERSION = 1;

export const POINT_TYPES = {
  bm: 'BM (banco de nivel)',
  pc: 'Punto de cambio',
  perfil: 'Punto del perfil',
  estacion: 'Estación del teodolito',
  referencia: 'Referencia de orientación',
  detalle: 'Punto radiado / de detalle',
  otro: 'Otro',
};
export const INSTRUMENTS = { nivel: 'Nivel de ingeniero', teodolito: 'Teodolito', ambos: 'Nivel y teodolito' };

export function newId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}-${Math.random().toString(36).slice(2, 10)}`;
}

export const nowIso = () => new Date().toISOString();

/** Proyecto nuevo y VACÍO: sin coordenadas, cotas ni fotografías. */
export function newProject(fields = {}, by = '') {
  const at = nowIso();
  return {
    schema: SCHEMA,
    version: SCHEMA_VERSION,
    id: newId(),
    name: fields.name ?? '',
    location: fields.location ?? '',
    date: fields.date ?? '',
    responsibles: fields.responsibles ?? '',
    objective: fields.objective ?? '',
    observations: fields.observations ?? '',
    coordinateSystem: fields.coordinateSystem ?? '',
    elevationDatum: fields.elevationDatum ?? '',
    siteDescription: fields.siteDescription ?? '',
    fieldProcedure: fields.fieldProcedure ?? '',
    instruments: {
      level: { brand: '', model: '', serial: '', notes: '' },
      theodolite: { brand: '', model: '', serial: '', notes: '' },
    },
    createdAt: at,
    createdBy: by,
    updatedAt: at,
    points: [],
    leveling: {
      bm: { code: '', elevation: null },
      endBm: { enabled: false, code: '', elevation: null },
      rows: [],
      profileScales: { h: null, v: null },
    },
    theodolite: {
      convention: { unit: '', direction: '', origin: '' },
      stations: [],
      readings: [],
    },
    plan: { lines: [], equidistance: null },
    results: { leveling: null, radiation: null, contours: null },
    audit: [],
    legacy: null,
  };
}

// ---------------------------------------------------------------------------------------------
// Entradas de cada cálculo (lo que se "congela" al pulsar Calcular)
// ---------------------------------------------------------------------------------------------

export function levelingInput(project) {
  const L = project.leveling;
  return {
    bm: { code: L.bm.code, elevation: L.bm.elevation },
    endBm: L.endBm.enabled ? { code: L.endBm.code, elevation: L.endBm.elevation } : null,
    rows: L.rows.map((r) => ({ id: r.id, code: r.code, bs: r.bs, is: r.is, fs: r.fs, partial: r.partial, cumulative: r.cumulative, inProfile: r.inProfile !== false })),
  };
}

export function radiationInput(project) {
  const T = project.theodolite;
  return {
    convention: { ...T.convention },
    stations: T.stations.map((s) => ({ id: s.id, code: s.code, east: s.east, north: s.north, coordType: s.coordType, orientation: { ...s.orientation } })),
    readings: T.readings.map((r) => ({ id: r.id, stationId: r.stationId, code: r.code, hz: r.hz, distType: r.distType, distance: r.distance, vType: r.vType, vAngle: r.vAngle, description: pointDescription(project, r.code) || r.description || '' })),
  };
}

export function pointByCode(project, code) {
  const key = normCode(code);
  return project.points.find((p) => normCode(p.code) === key) ?? null;
}

function pointDescription(project, code) {
  return pointByCode(project, code)?.description ?? '';
}

/**
 * Une los resultados aceptados: posición (teodolito) + cota (nivel o cota justificada), SIEMPRE por
 * código idéntico dentro del mismo proyecto. Nunca por proximidad ni por parecido del nombre.
 */
export function linkSurvey(project, lev, rad) {
  const rows = [];
  const warnings = [];
  const codes = new Map();
  const add = (code) => { const k = normCode(code); if (k && !codes.has(k)) codes.set(k, code); };
  project.points.forEach((p) => add(p.code));
  if (rad?.computable) for (const v of rad.coordsByCode.values()) add(v.code);
  if (lev?.computable) for (const v of lev.elevationsByCode.values()) add(v.code);

  for (const [key, code] of codes) {
    const pt = project.points.find((p) => normCode(p.code) === key);
    const c = rad?.computable ? rad.coordsByCode.get(key) : null;
    const lv = lev?.computable ? lev.elevationsByCode.get(key) : null;
    const row = {
      code: pt?.code ?? code, description: pt?.description ?? '', type: pt?.type ?? '',
      east: c?.east ?? null, north: c?.north ?? null, coordSource: c?.source ?? '', coordKind: c?.kind ?? null, stationCode: c?.stationCode ?? null,
      elevation: null, elevSource: '', status: '',
    };
    if (lv) {
      row.elevation = lv.elevation;
      row.elevSource = normCode(project.leveling.bm.code) === key ? 'BM (cota conocida)' : `Nivelación (lectura n.° ${lv.n})`;
    }
    if (pt && isNum(pt.knownElevation)) {
      const just = String(pt.elevationJustification ?? '').trim();
      if (!just) {
        warnings.push(`El punto ${pt.code} tiene una cota registrada sin justificación: no se usa hasta que indique de dónde proviene.`);
      } else if (row.elevation === null) {
        row.elevation = pt.knownElevation;
        row.elevSource = `Cota justificada: ${just}`;
      } else if (Math.abs(row.elevation - pt.knownElevation) >= 0.0005) {
        warnings.push(`El punto ${pt.code} tiene cota de nivelación ${fmt(row.elevation)} m y cota justificada ${fmt(pt.knownElevation)} m; se usa la de nivelación.`);
      }
    }
    const hasXY = isNum(row.east) && isNum(row.north);
    const hasZ = isNum(row.elevation);
    row.status = hasXY && hasZ ? 'Completo: E, N y cota (vinculado por código idéntico)'
      : hasXY ? 'Sin cota: debe nivelarse o registrarse una cota justificada'
        : hasZ ? 'Sin posición planimétrica (solo cota)'
          : 'Sin resultados';
    rows.push(row);
  }
  return { rows, warnings };
}

// ---------------------------------------------------------------------------------------------
// Estado de los cálculos
// ---------------------------------------------------------------------------------------------

export function contoursInput(survey, equidistance) {
  return {
    equidistance,
    points: survey.rows.filter((r) => isNum(r.east) && isNum(r.north) && isNum(r.elevation))
      .map((r) => ({ code: r.code, east: r.east, north: r.north, elevation: r.elevation })),
  };
}

/**
 * Calcula todo con los datos ACTUALES y compara con lo último aceptado con «Calcular».
 * Devuelve para cada módulo: { status: 'sin-datos'|'sin-calcular'|'vigente'|'desactualizado', current, accepted, changes[] }
 */
export function evaluateProject(project) {
  const levIn = levelingInput(project);
  const radIn = radiationInput(project);
  const hasLevData = project.leveling.rows.length > 0 || String(project.leveling.bm.code).trim() !== '';
  const hasRadData = project.theodolite.stations.length > 0 || project.theodolite.readings.length > 0;

  const levCurrent = computeLeveling(levIn);
  const radCurrent = computeRadiation(radIn);
  const R = project.results ?? {};
  const levAccepted = R.leveling ? computeLeveling(R.leveling.input) : null;
  const radAccepted = R.radiation ? computeRadiation(R.radiation.input) : null;

  const leveling = moduleState(hasLevData, R.leveling, stableHash(levIn), levCurrent, levAccepted, diffLeveling);
  const radiation = moduleState(hasRadData, R.radiation, stableHash(radIn), radCurrent, radAccepted, diffRadiation);

  // Vinculación con los resultados ACEPTADOS (lo que se muestra y se exporta)
  const survey = linkSurvey(project, leveling.status === 'vigente' ? levAccepted : null, radiation.status === 'vigente' ? radAccepted : null);
  const surveyCurrent = linkSurvey(project, levCurrent, radCurrent);
  const contIn = contoursInput(surveyCurrent, project.plan.equidistance);
  const contAcceptedIn = R.contours?.input ?? null;
  const contCurrent = computeContours(contIn.points, contIn.equidistance);
  const contAccepted = contAcceptedIn ? computeContours(contAcceptedIn.points, contAcceptedIn.equidistance) : null;
  const hasContData = isNum(project.plan.equidistance);
  const contours = moduleState(hasContData, R.contours, stableHash(contIn), contCurrent, contAccepted, diffContours);

  return { leveling, radiation, contours, survey, levIn, radIn, contIn };
}

function moduleState(hasData, stored, currentHash, current, accepted, differ) {
  if (!stored) return { status: hasData ? 'sin-calcular' : 'sin-datos', current, accepted: null, changes: [], stored: null, hash: currentHash };
  if (stored.hash === currentHash) return { status: 'vigente', current, accepted, changes: [], stored, hash: currentHash };
  return { status: 'desactualizado', current, accepted, changes: differ(accepted, current), stored, hash: currentHash };
}

function diffLeveling(a, b) {
  const changes = [];
  if (!b.computable) changes.push('Los datos actuales de la libreta tienen errores: corrija antes de recalcular.');
  const old = new Map((a?.rows ?? []).map((r) => [r.id, r]));
  for (const r of b.rows) {
    const o = old.get(r.id);
    if (!o) { changes.push(`Lectura n.° ${r.n} (${r.code}) nueva: su cota aún no está calculada.`); continue; }
    if (fmt(o.elevation) !== fmt(r.elevation)) changes.push(`Cota de ${r.code} (lectura n.° ${r.n}): ${fmt(o.elevation)} → ${fmt(r.elevation)} m`);
    if (fmt(o.cumulative) !== fmt(r.cumulative)) changes.push(`Progresiva de ${r.code}: ${fmt(o.cumulative)} → ${fmt(r.cumulative)} m`);
    old.delete(r.id);
  }
  for (const o of old.values()) changes.push(`Lectura de ${o.code} eliminada: su cota deja de existir.`);
  if (a?.closure?.applicable || b.closure.applicable) {
    if (fmt(a?.closure?.error) !== fmt(b.closure.error)) changes.push(`Error de cierre: ${fmt(a?.closure?.error)} → ${fmt(b.closure.error)} m`);
  }
  if (changes.length === 0) changes.push('Cambiaron datos de la libreta (BM, observaciones u orden), aunque las cotas resultantes sean iguales.');
  changes.push('También deben actualizarse: perfil longitudinal, curvas de nivel e informe.');
  return changes;
}

function diffRadiation(a, b) {
  const changes = [];
  if (!b.computable) changes.push('Los datos actuales del teodolito tienen errores: corrija antes de recalcular.');
  const unit = b.unit;
  const old = new Map((a?.points ?? []).map((p) => [p.readingId, p]));
  for (const p of b.points) {
    const o = old.get(p.readingId);
    if (!o) { changes.push(`Punto ${p.code} nuevo: sus coordenadas aún no están calculadas.`); continue; }
    if (formatAngle(o.az, unit) !== formatAngle(p.az, unit)) changes.push(`Azimut de ${p.code}: ${formatAngle(o.az, unit)} → ${formatAngle(p.az, unit)}`);
    if (fmt(o.east) !== fmt(p.east) || fmt(o.north) !== fmt(p.north)) changes.push(`Coordenadas de ${p.code}: (${fmt(o.east)}, ${fmt(o.north)}) → (${fmt(p.east)}, ${fmt(p.north)})`);
    old.delete(p.readingId);
  }
  for (const o of old.values()) changes.push(`Lectura de ${o.code} eliminada: sus coordenadas dejan de existir.`);
  if (changes.length === 0) changes.push('Cambiaron datos de la estación, la orientación o el convenio angular.');
  changes.push('También deben actualizarse: plano, curvas de nivel, DXF e informe.');
  return changes;
}

function diffContours(a, b) {
  const changes = ['Cambiaron los puntos con cota o la equidistancia usados para la triangulación.'];
  if (a && b) changes.push(`Curvas: ${a.levels.length} niveles → ${b.ok ? b.levels.length : 0} niveles; triángulos: ${a.triangles.length} → ${b.triangles.length}.`);
  return changes;
}

/** Acepta (congela) los datos actuales de un módulo: es lo que hace el botón «Calcular». */
export function acceptResults(project, module, evaluation, by) {
  const at = nowIso();
  const src = { leveling: evaluation.levIn, radiation: evaluation.radIn, contours: evaluation.contIn }[module];
  project.results = project.results ?? {};
  project.results[module] = { at, by, hash: stableHash(src), input: structuredCloneSafe(src) };
}

export function structuredCloneSafe(v) {
  return JSON.parse(JSON.stringify(v));
}

// ---------------------------------------------------------------------------------------------
// Registro de correcciones (dato original + quién + cuándo)
// ---------------------------------------------------------------------------------------------

export function audit(project, entry) {
  project.audit.push({ id: newId(), at: nowIso(), ...entry });
}

export function diffFields(before, after, labels) {
  const changes = [];
  for (const [field, label] of Object.entries(labels)) {
    const a = before?.[field] ?? null;
    const b = after?.[field] ?? null;
    if (JSON.stringify(a) !== JSON.stringify(b)) changes.push({ field, label, from: a, to: b });
  }
  return changes;
}

export const LEVEL_ROW_FIELDS = { code: 'Punto', bs: 'Vista atrás (m)', is: 'Vista intermedia (m)', fs: 'Vista adelante (m)', partial: 'Distancia parcial (m)', cumulative: 'Progresiva (m)', inProfile: 'En perfil', notes: 'Observaciones' };
export const RAD_READING_FIELDS = { stationId: 'Estación', code: 'Punto', hz: 'Lectura horizontal', distType: 'Tipo de distancia', distance: 'Distancia (m)', vType: 'Tipo de ángulo vertical', vAngle: 'Ángulo vertical', description: 'Descripción', notes: 'Observaciones' };
export const STATION_FIELDS = { code: 'Estación', east: 'Este (m)', north: 'Norte (m)', coordType: 'Tipo de coordenadas', instrumentHeight: 'Altura del instrumento (m)', orientation: 'Orientación' };
export const POINT_FIELDS = { code: 'Código', description: 'Descripción', type: 'Tipo', instrument: 'Instrumento', observations: 'Observaciones', knownElevation: 'Cota justificada (m)', elevationJustification: 'Justificación de la cota' };

/** Valor legible para el registro de correcciones. */
export function auditValue(v) {
  if (v === null || v === undefined || v === '') return '(vacío)';
  if (typeof v === 'boolean') return v ? 'sí' : 'no';
  if (typeof v === 'number') return fmt(v, 4).replace(/0$/, '');
  if (typeof v === 'object') return Object.entries(v).filter(([, x]) => x !== '' && x !== null && x !== undefined).map(([k, x]) => `${k}: ${x}`).join('; ') || '(vacío)';
  return String(v);
}

/** ¿Qué lecturas/puntos usan un código? (para impedir borrar un punto usado) */
export function codeUsage(project, code) {
  const key = normCode(code);
  const uses = [];
  project.leveling.rows.forEach((r, i) => { if (normCode(r.code) === key) uses.push(`lectura de nivel n.° ${i + 1}`); });
  if (normCode(project.leveling.bm.code) === key) uses.push('BM inicial de la nivelación');
  if (project.leveling.endBm.enabled && normCode(project.leveling.endBm.code) === key) uses.push('BM final de la nivelación');
  project.theodolite.stations.forEach((s) => {
    if (normCode(s.code) === key) uses.push(`estación ${s.code}`);
    if (normCode(s.orientation?.refCode) === key) uses.push(`referencia de orientación de ${s.code}`);
  });
  project.theodolite.readings.forEach((r, i) => { if (normCode(r.code) === key) uses.push(`lectura de teodolito n.° ${i + 1}`); });
  project.plan.lines.forEach((l) => { if (l.codes.some((c) => normCode(c) === key)) uses.push(`línea «${l.name}»`); });
  return uses;
}

/** Validación de las líneas definidas por el usuario contra los puntos con coordenadas. */
export function planLines(project, survey) {
  const byCode = new Map(survey.rows.filter((r) => isNum(r.east) && isNum(r.north)).map((r) => [normCode(r.code), r]));
  return project.plan.lines.map((l) => {
    const missing = l.codes.filter((c) => !byCode.has(normCode(c)));
    const pts = l.codes.map((c) => byCode.get(normCode(c))).filter(Boolean);
    return { ...l, ok: missing.length === 0 && pts.length >= 2, missing, pts: l.closed && pts.length > 2 ? [...pts, pts[0]] : pts };
  });
}
