// Cambios sobre el proyecto (se ejecutan dentro de ctx.update). Cada alta, corrección o baja de un
// dato de campo queda en el registro de correcciones con quién, cuándo, valor anterior y nuevo.
import { LEVEL_ROW_FIELDS, POINT_FIELDS, RAD_READING_FIELDS, STATION_FIELDS, audit, codeUsage, diffFields, newId, nowIso, pointByCode } from '../core/model.js';
import { normCode } from '../core/numbers.js';

const clone = (x) => JSON.parse(JSON.stringify(x));

export function ensurePoint(p, by, code, defaults = {}) {
  const c = String(code ?? '').trim();
  if (!c) throw new Error('Falta el código del punto.');
  let pt = pointByCode(p, c);
  if (!pt) {
    pt = {
      id: newId(), code: c, description: defaults.description ?? '', type: defaults.type ?? 'otro', instrument: defaults.instrument ?? 'ambos',
      observations: '', knownElevation: null, elevationJustification: '', photo: null, createdAt: nowIso(), createdBy: by,
    };
    pt.original = clone({ code: pt.code, description: pt.description, type: pt.type, instrument: pt.instrument });
    p.points.push(pt);
    audit(p, { by, action: 'crear', entity: 'Punto', entityId: pt.id, label: `Punto ${pt.code}`, changes: [], note: 'creado al registrar una lectura' });
  } else if (defaults.instrument && pt.instrument !== 'ambos' && pt.instrument !== defaults.instrument) {
    const before = pt.instrument;
    pt.instrument = 'ambos';
    audit(p, { by, action: 'actualizar', entity: 'Punto', entityId: pt.id, label: `Punto ${pt.code}`, changes: [{ field: 'instrument', label: 'Instrumento', from: before, to: 'ambos' }], reason: 'Actualización automática: usado por los dos instrumentos' });
  }
  return pt;
}

// ------------------------------------------------------------------ Puntos
export function savePoint(p, by, id, data, reason = '') {
  const code = String(data.code ?? '').trim();
  if (!code) throw new Error('El código del punto es obligatorio.');
  const dup = p.points.find((x) => normCode(x.code) === normCode(code) && x.id !== id);
  if (dup) throw new Error(`Ya existe un punto con el código «${dup.code}». Los códigos deben ser únicos.`);
  if (data.knownElevation !== null && !String(data.elevationJustification ?? '').trim()) {
    throw new Error('Si registra una cota para el punto, indique su justificación (de dónde proviene).');
  }
  if (!id) {
    const pt = { id: newId(), photo: null, createdAt: nowIso(), createdBy: by, ...data, code };
    pt.original = clone({ code, description: data.description, type: data.type, instrument: data.instrument, observations: data.observations, knownElevation: data.knownElevation, elevationJustification: data.elevationJustification });
    p.points.push(pt);
    audit(p, { by, action: 'crear', entity: 'Punto', entityId: pt.id, label: `Punto ${code}`, changes: [] });
    return pt;
  }
  const pt = p.points.find((x) => x.id === id);
  const before = clone(pt);
  Object.assign(pt, data, { code });
  const changes = diffFields(before, pt, POINT_FIELDS);
  if (!changes.length) return pt;
  pt.updatedAt = nowIso();
  pt.updatedBy = by;
  audit(p, { by, action: 'editar', entity: 'Punto', entityId: pt.id, label: `Punto ${before.code}`, changes, reason });
  if (normCode(before.code) !== normCode(code) || before.code !== code) renameCode(p, by, before.code, code);
  return pt;
}

/** Cambia el código en todas las lecturas que lo usan (y lo deja registrado). */
function renameCode(p, by, from, to) {
  const k = normCode(from);
  const touched = [];
  p.leveling.rows.forEach((r, i) => { if (normCode(r.code) === k) { r.code = to; touched.push(`lectura de nivel n.° ${i + 1}`); } });
  if (normCode(p.leveling.bm.code) === k) { p.leveling.bm.code = to; touched.push('BM inicial'); }
  if (normCode(p.leveling.endBm.code) === k) { p.leveling.endBm.code = to; touched.push('BM final'); }
  for (const s of p.theodolite.stations) {
    if (normCode(s.code) === k) { s.code = to; touched.push(`estación ${to}`); }
    if (normCode(s.orientation?.refCode) === k) { s.orientation.refCode = to; touched.push(`referencia de ${s.code}`); }
  }
  p.theodolite.readings.forEach((r, i) => { if (normCode(r.code) === k) { r.code = to; touched.push(`lectura de teodolito n.° ${i + 1}`); } });
  for (const l of p.plan.lines) l.codes = l.codes.map((c) => (normCode(c) === k ? to : c));
  if (touched.length) audit(p, { by, action: 'editar', entity: 'Código de punto', entityId: null, label: `${from} → ${to}`, changes: [{ field: 'code', label: 'Código', from, to }], reason: `Cambio de código aplicado en: ${touched.join(', ')}` });
}

export function deletePoint(p, by, id, reason = '') {
  const pt = p.points.find((x) => x.id === id);
  if (!pt) return;
  const uses = codeUsage(p, pt.code);
  if (uses.length) throw new Error(`No se puede eliminar ${pt.code}: se usa en ${uses.join(', ')}. Elimine o corrija primero esas lecturas.`);
  p.points = p.points.filter((x) => x.id !== id);
  audit(p, { by, action: 'eliminar', entity: 'Punto', entityId: id, label: `Punto ${pt.code}`, changes: [], reason, snapshot: clone(pt) });
}

export function setPointPhoto(p, by, pointId, photoMeta) {
  const pt = p.points.find((x) => x.id === pointId);
  if (!pt) throw new Error('El punto ya no existe.');
  const before = pt.photo;
  pt.photo = photoMeta;
  const action = !before ? 'crear' : !photoMeta ? 'eliminar' : 'editar';
  audit(p, {
    by, action, entity: 'Fotografía', entityId: pt.id, label: `Fotografía de ${pt.code}`,
    changes: [{ field: 'photo', label: 'Fotografía', from: before ? `${before.fileName ?? 'foto'} — ${before.caption ?? ''}` : '', to: photoMeta ? `${photoMeta.fileName ?? 'foto'} — ${photoMeta.caption ?? ''}` : '' }],
  });
  return before?.id ?? null;
}

// ------------------------------------------------------------------ Nivelación
export function saveBm(p, by, bm, endBm) {
  const before = clone({ bm: p.leveling.bm, endBm: p.leveling.endBm });
  p.leveling.bm = bm;
  p.leveling.endBm = endBm;
  if (bm.code) ensurePoint(p, by, bm.code, { type: 'bm', instrument: 'nivel', description: 'BM inicial' });
  if (endBm.enabled && endBm.code) ensurePoint(p, by, endBm.code, { type: 'bm', instrument: 'nivel', description: 'BM final' });
  const changes = diffFields(before, { bm, endBm }, { bm: 'BM inicial', endBm: 'BM final' });
  if (changes.length) audit(p, { by, action: before.bm.code || before.bm.elevation !== null ? 'editar' : 'crear', entity: 'BM de nivelación', entityId: 'bm', label: 'BM de la nivelación', changes });
}

export function saveLevelRow(p, by, id, data, insertAt = null, reason = '', newPointDescription = '') {
  ensurePoint(p, by, data.code, { instrument: 'nivel', type: data.bs !== null && data.fs !== null ? 'pc' : 'perfil', description: newPointDescription });
  const fields = { code: data.code.trim(), bs: data.bs, is: data.is, fs: data.fs, partial: data.partial, cumulative: data.cumulative, inProfile: data.inProfile, notes: data.notes ?? '' };
  if (!id) {
    const row = { id: newId(), ...fields, createdAt: nowIso(), createdBy: by };
    row.original = clone(fields);
    const at = insertAt === null ? p.leveling.rows.length : insertAt;
    p.leveling.rows.splice(at, 0, row);
    audit(p, { by, action: 'crear', entity: 'Lectura de nivel', entityId: row.id, label: `Lectura de nivel n.° ${at + 1} (${row.code})`, changes: [] });
    return row;
  }
  const idx = p.leveling.rows.findIndex((r) => r.id === id);
  const row = p.leveling.rows[idx];
  const before = clone(row);
  Object.assign(row, fields);
  const changes = diffFields(before, row, LEVEL_ROW_FIELDS);
  if (changes.length) {
    row.updatedAt = nowIso();
    row.updatedBy = by;
    audit(p, { by, action: 'editar', entity: 'Lectura de nivel', entityId: row.id, label: `Lectura de nivel n.° ${idx + 1} (${before.code})`, changes, reason });
  }
  return row;
}

export function deleteLevelRow(p, by, id, reason = '') {
  const idx = p.leveling.rows.findIndex((r) => r.id === id);
  if (idx < 0) return;
  const [row] = p.leveling.rows.splice(idx, 1);
  audit(p, { by, action: 'eliminar', entity: 'Lectura de nivel', entityId: id, label: `Lectura de nivel n.° ${idx + 1} (${row.code})`, changes: [], reason, snapshot: clone(row) });
}

// ------------------------------------------------------------------ Teodolito
export function saveConvention(p, by, conv) {
  const before = clone(p.theodolite.convention);
  p.theodolite.convention = conv;
  const changes = diffFields(before, conv, { unit: 'Unidad angular', direction: 'Sentido de lectura', origin: 'Origen de los ángulos' });
  if (changes.length) audit(p, { by, action: before.unit ? 'editar' : 'crear', entity: 'Convenio angular', entityId: 'convention', label: 'Convenio angular', changes });
}

export function saveStation(p, by, id, data, reason = '') {
  const code = String(data.code ?? '').trim();
  if (!code) throw new Error('El código de la estación es obligatorio.');
  const dup = p.theodolite.stations.find((s) => normCode(s.code) === normCode(code) && s.id !== id);
  if (dup) throw new Error(`Ya existe la estación ${dup.code}.`);
  ensurePoint(p, by, code, { type: 'estacion', instrument: 'teodolito', description: 'Estación del teodolito' });
  if (data.orientation?.refCode) ensurePoint(p, by, data.orientation.refCode, { type: 'referencia', instrument: 'teodolito', description: 'Referencia de orientación' });
  const fields = { code, east: data.east, north: data.north, coordType: data.coordType, instrumentHeight: data.instrumentHeight, orientation: data.orientation };
  if (!id) {
    const st = { id: newId(), ...fields, createdAt: nowIso(), createdBy: by };
    st.original = clone(fields);
    p.theodolite.stations.push(st);
    audit(p, { by, action: 'crear', entity: 'Estación del teodolito', entityId: st.id, label: `Estación ${code}`, changes: [] });
    return st;
  }
  const st = p.theodolite.stations.find((s) => s.id === id);
  const before = clone(st);
  Object.assign(st, fields);
  const changes = diffFields(before, st, STATION_FIELDS);
  if (changes.length) {
    st.updatedAt = nowIso();
    audit(p, { by, action: 'editar', entity: 'Estación del teodolito', entityId: st.id, label: `Estación ${before.code}`, changes, reason });
  }
  return st;
}

export function deleteStation(p, by, id, reason = '') {
  const st = p.theodolite.stations.find((s) => s.id === id);
  if (!st) return;
  const n = p.theodolite.readings.filter((r) => r.stationId === id).length;
  if (n) throw new Error(`La estación ${st.code} tiene ${n} lectura(s). Elimínelas o asígnelas a otra estación primero.`);
  p.theodolite.stations = p.theodolite.stations.filter((s) => s.id !== id);
  audit(p, { by, action: 'eliminar', entity: 'Estación del teodolito', entityId: id, label: `Estación ${st.code}`, changes: [], reason, snapshot: clone(st) });
}

export function saveRadReading(p, by, id, data, reason = '', newPointDescription = '') {
  ensurePoint(p, by, data.code, { instrument: 'teodolito', type: 'detalle', description: newPointDescription });
  const fields = { stationId: data.stationId, code: data.code.trim(), hz: data.hz.trim(), distType: data.distType, distance: data.distance, vType: data.distType === 'inclinada' ? data.vType : null, vAngle: data.distType === 'inclinada' ? data.vAngle.trim() : '', notes: data.notes ?? '' };
  if (!id) {
    const r = { id: newId(), ...fields, createdAt: nowIso(), createdBy: by };
    r.original = clone(fields);
    p.theodolite.readings.push(r);
    audit(p, { by, action: 'crear', entity: 'Lectura de teodolito', entityId: r.id, label: `Lectura de teodolito n.° ${p.theodolite.readings.length} (${r.code})`, changes: [] });
    return r;
  }
  const idx = p.theodolite.readings.findIndex((r) => r.id === id);
  const r = p.theodolite.readings[idx];
  const before = clone(r);
  Object.assign(r, fields);
  const changes = diffFields(before, r, RAD_READING_FIELDS);
  if (changes.length) {
    r.updatedAt = nowIso();
    r.updatedBy = by;
    audit(p, { by, action: 'editar', entity: 'Lectura de teodolito', entityId: r.id, label: `Lectura de teodolito n.° ${idx + 1} (${before.code})`, changes, reason });
  }
  return r;
}

export function deleteRadReading(p, by, id, reason = '') {
  const idx = p.theodolite.readings.findIndex((r) => r.id === id);
  if (idx < 0) return;
  const [r] = p.theodolite.readings.splice(idx, 1);
  audit(p, { by, action: 'eliminar', entity: 'Lectura de teodolito', entityId: id, label: `Lectura de teodolito n.° ${idx + 1} (${r.code})`, changes: [], reason, snapshot: clone(r) });
}

// ------------------------------------------------------------------ Plano
export function saveLine(p, by, id, data) {
  const name = String(data.name ?? '').trim();
  if (!name) throw new Error('Escriba un nombre para la línea.');
  if (data.codes.length < 2) throw new Error('Una línea necesita al menos 2 puntos.');
  const missing = data.codes.filter((c) => !pointByCode(p, c));
  if (missing.length) throw new Error(`Estos códigos no existen en la libreta: ${missing.join(', ')}.`);
  if (!id) {
    const l = { id: newId(), name, codes: data.codes, closed: !!data.closed };
    p.plan.lines.push(l);
    audit(p, { by, action: 'crear', entity: 'Línea del plano', entityId: l.id, label: `Línea «${name}»`, changes: [] });
    return;
  }
  const l = p.plan.lines.find((x) => x.id === id);
  const before = clone(l);
  Object.assign(l, { name, codes: data.codes, closed: !!data.closed });
  const changes = diffFields(before, l, { name: 'Nombre', codes: 'Puntos', closed: 'Cerrada' });
  if (changes.length) audit(p, { by, action: 'editar', entity: 'Línea del plano', entityId: l.id, label: `Línea «${before.name}»`, changes });
}

export function deleteLine(p, by, id) {
  const l = p.plan.lines.find((x) => x.id === id);
  p.plan.lines = p.plan.lines.filter((x) => x.id !== id);
  if (l) audit(p, { by, action: 'eliminar', entity: 'Línea del plano', entityId: id, label: `Línea «${l.name}»`, changes: [], snapshot: clone(l) });
}
