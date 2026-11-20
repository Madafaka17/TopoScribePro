// Migración de la base de datos de la versión Android (Room, archivo toposcribe.db) al formato web.
//
// · Nivelación geométrica → libreta de nivelación (una fila por punto; punto de cambio = VD + VA).
// · Observaciones planimétricas de proyectos de TEODOLITO → libreta de radiación (el usuario puede
//   pedir que las de estación total también se incorporen).
// · Todo lo que quedó fuera del alcance (estación total, GNSS/RTK, poligonales, Bowditch, nivelación
//   trigonométrica, resultados calculados por la versión anterior, registro de ediciones) se conserva
//   SIN CAMBIOS en project.legacy: no se borra, pero no entra en los cálculos.
import { audit, newId, newProject, nowIso } from '../core/model.js';
import { formatDms, normalizeAzimuth } from '../core/angles.js';

// ------------------------------------------------------------------ WAL de SQLite
const u32 = (b, o, le) => (le ? (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0 : ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0);

function walChecksum(bytes, start, len, le, s0, s1) {
  for (let i = start; i < start + len; i += 8) {
    s0 = (s0 + u32(bytes, i, le) + s1) >>> 0;
    s1 = (s1 + u32(bytes, i + 4, le) + s0) >>> 0;
  }
  return [s0, s1];
}

/**
 * Aplica al archivo principal las transacciones confirmadas del archivo -wal (Room usa modo WAL:
 * los últimos cambios pueden estar solo en toposcribe.db-wal). Devuelve los bytes de una base
 * autónoma en modo de diario clásico, legible por sql.js.
 */
export function applyWal(dbBytes, walBytes) {
  let db = new Uint8Array(dbBytes);
  const info = { framesApplied: 0, walUsed: false };
  if (walBytes && walBytes.length >= 32) {
    const w = new Uint8Array(walBytes);
    const magic = u32(w, 0, false);
    if (magic === 0x377f0682 || magic === 0x377f0683) {
      const le = magic === 0x377f0682;
      const pageSize = u32(w, 8, false) || 65536;
      const salt1 = u32(w, 16, false);
      const salt2 = u32(w, 20, false);
      let [s0, s1] = walChecksum(w, 0, 24, le, 0, 0);
      if (s0 === u32(w, 24, false) && s1 === u32(w, 28, false)) {
        const frameSize = 24 + pageSize;
        const pending = [];
        let committed = [];
        let dbPages = null;
        for (let off = 32; off + frameSize <= w.length; off += frameSize) {
          if (u32(w, off + 8, false) !== salt1 || u32(w, off + 12, false) !== salt2) break;
          [s0, s1] = walChecksum(w, off, 8, le, s0, s1);
          [s0, s1] = walChecksum(w, off + 24, pageSize, le, s0, s1);
          if (s0 !== u32(w, off + 16, false) || s1 !== u32(w, off + 20, false)) break;
          pending.push({ page: u32(w, off, false), data: w.subarray(off + 24, off + 24 + pageSize) });
          const commitSize = u32(w, off + 4, false);
          if (commitSize > 0) { committed = committed.concat(pending.splice(0)); dbPages = commitSize; }
        }
        if (committed.length) {
          const size = Math.max(dbPages * pageSize, 0);
          const out = new Uint8Array(size);
          out.set(db.subarray(0, Math.min(db.length, size)));
          for (const f of committed) if ((f.page - 1) * pageSize < size) out.set(f.data, (f.page - 1) * pageSize);
          db = out;
          info.framesApplied = committed.length;
          info.walUsed = true;
        }
      }
    }
  }
  if (db.length >= 20 && db[18] === 2 && db[19] === 2) { db[18] = 1; db[19] = 1; } // modo WAL → diario clásico
  return { bytes: db, info };
}

// ------------------------------------------------------------------ Lectura de tablas
const TABLES = ['projects', 'points', 'leveling_runs', 'leveling_observations', 'planimetry_observations', 'station_setups', 'traverses', 'adjustments', 'computed_points', 'leveling_trig_observations', 'observation_edit_log', 'alignments', 'import_batches'];

/** @param SQL módulo de sql.js inicializado */
export function readAndroidDb(SQL, bytes) {
  const database = new SQL.Database(bytes);
  try {
    const exist = new Set(database.exec("SELECT name FROM sqlite_master WHERE type='table'")[0]?.values.map((v) => v[0]) ?? []);
    if (!exist.has('projects') || !exist.has('points')) throw new Error('El archivo no parece ser la base de datos de TopoScribePro para Android (faltan las tablas projects/points).');
    const raw = {};
    for (const t of TABLES) {
      if (!exist.has(t)) { raw[t] = []; continue; }
      const res = database.exec(`SELECT * FROM "${t}"`)[0];
      raw[t] = res ? res.values.map((vals) => Object.fromEntries(res.columns.map((c, i) => [c, vals[i]]))) : [];
    }
    raw.roomVersion = database.exec('PRAGMA user_version')[0]?.values[0][0] ?? null;
    return raw;
  } finally {
    database.close();
  }
}

/** Resumen por proyecto para elegir qué importar. */
export function summarizeAndroid(raw) {
  return raw.projects.map((p) => {
    const pts = raw.points.filter((x) => x.project_id === p.id && !x.deleted_at);
    const runs = raw.leveling_runs.filter((r) => r.project_id === p.id);
    const levObs = raw.leveling_observations.filter((o) => runs.some((r) => r.id === o.leveling_run_id) && !o.deleted_at);
    const plan = raw.planimetry_observations.filter((o) => o.project_id === p.id && !o.deleted_at);
    return {
      id: p.id, name: p.name || `(sin nombre, id ${p.id})`, isDemo: !!p.is_demo, instrumentType: p.instrument_type,
      points: pts.length, levelingObs: levObs.length, planimetryObs: plan.length,
      traverses: raw.traverses.filter((t) => t.project_id === p.id).length,
      trig: raw.leveling_trig_observations.filter((o) => o.project_id === p.id && !o.deleted_at).length,
      gnssPoints: pts.filter((x) => x.data_source === 'MEDIDO_GNSS').length,
      photos: pts.filter((x) => x.sketch_photo_path).length,
    };
  });
}

const INSTRUMENT_LABEL = { ESTACION_TOTAL: 'Estación total', TEODOLITO: 'Teodolito', NIVEL_OPTICO: 'Nivel óptico', GNSS_RTK: 'GNSS/RTK' };
const base = (path) => String(path ?? '').split(/[\\/]/).pop();

function bearingToAzimuth(text) {
  const m = /^([NS])\s*(\d+(?:\.\d+)?)\s*°?\s*([EWO])$/i.exec(String(text ?? '').trim());
  if (!m) return null;
  const a = Number(m[2]);
  const n = m[1].toUpperCase() === 'N';
  const e = m[3].toUpperCase() === 'E';
  return n && e ? a : !n && e ? 180 - a : !n && !e ? 180 + a : 360 - a;
}

/**
 * Convierte un proyecto de la base Android al formato web.
 * @param {object} raw tablas leídas con readAndroidDb
 * @param {number} androidProjectId
 * @param {{totalStationAsTheodolite?: boolean, sourceFile?: string}} options
 * @param {string} by usuario que importa
 * @returns {{project, photoRefs: Array<{pointId, fileName}>, notes: string[]}}
 */
export function convertAndroidProject(raw, androidProjectId, options = {}, by = '') {
  const ap = raw.projects.find((p) => p.id === androidProjectId);
  if (!ap) throw new Error(`No existe el proyecto ${androidProjectId} en la base Android.`);
  const notes = [];
  const legacy = {};
  const keep = (table, rows) => { if (rows.length) legacy[table] = (legacy[table] ?? []).concat(rows); };
  const it = ap.instrument_type;

  const coordinateSystem = ap.coordinate_system || (ap.coordinate_mode === 'UTM'
    ? `UTM ${ap.utm_datum || 'WGS84'} zona ${ap.utm_zone ?? '?'}${ap.utm_hemisphere ?? ''}` : 'Local (declarado en la versión Android)');
  const p = newProject({
    name: `${ap.name || `Proyecto Android ${ap.id}`}${ap.is_demo ? ' (demostración Android — datos sintéticos)' : ''}`,
    location: ap.location ?? '', date: ap.date ?? '', responsibles: ap.responsible ?? '', objective: ap.purpose ?? '',
    observations: [ap.observations, ap.is_demo ? 'Proyecto de DEMOSTRACIÓN de la versión Android: sus valores son sintéticos, no corresponden a un levantamiento real.' : ''].filter(Boolean).join('\n'),
    coordinateSystem, siteDescription: ap.site_characteristics ?? '', fieldProcedure: ap.field_procedure ?? '',
  }, by);
  p.createdAt = ap.created_at || p.createdAt;
  if (ap.instrument) {
    if (it === 'NIVEL_OPTICO') p.instruments.level.notes = ap.instrument;
    else if (it === 'TEODOLITO') p.instruments.theodolite.notes = ap.instrument;
    else notes.push(`Instrumento registrado en Android: «${ap.instrument}» (${INSTRUMENT_LABEL[it] ?? it}).`);
  }
  keep('proyecto (campos de la versión Android)', [ap]);

  // ---------------- Puntos
  const pts = raw.points.filter((x) => x.project_id === ap.id && !x.deleted_at);
  const deletedPts = raw.points.filter((x) => x.project_id === ap.id && x.deleted_at);
  keep('puntos eliminados en Android', deletedPts);
  const codeById = new Map(pts.map((x) => [x.id, x.code]));
  const runs = raw.leveling_runs.filter((r) => r.project_id === ap.id);
  const allLevObs = raw.leveling_observations.filter((o) => runs.some((r) => r.id === o.leveling_run_id));
  const planObs = raw.planimetry_observations.filter((o) => o.project_id === ap.id);

  const importTheo = it === 'TEODOLITO' || (options.totalStationAsTheodolite && planObs.length > 0);
  const roleType = { CONTROL: 'bm', RELLENO: 'detalle', POLIGONAL: 'otro', OTRO: 'otro' };
  const newPoints = new Map();
  const photoRefs = [];
  for (const x of pts) {
    if (x.data_source === 'MEDIDO_GNSS') {
      keep('puntos GNSS/RTK (fuera de alcance)', [x]);
    }
    const pt = {
      id: newId(), code: x.code, description: x.description ?? '', type: roleType[x.role] ?? 'otro', instrument: 'ambos',
      observations: [x.observations, x.tramo_sector ? `Tramo/sector: ${x.tramo_sector}` : ''].filter(Boolean).join(' · '),
      knownElevation: null, elevationJustification: '', photo: null, createdAt: x.created_at || nowIso(), createdBy: 'app Android',
    };
    if (x.fixed_elevation !== null && x.fixed_elevation !== undefined && x.data_source !== 'MEDIDO_GNSS') {
      pt.knownElevation = x.fixed_elevation;
      pt.elevationJustification = `Cota fija registrada como ${x.is_control ? 'punto de control' : 'dato'} en la versión Android (verifíquela)`;
    }
    pt.original = { code: pt.code, description: pt.description, type: pt.type, observations: pt.observations };
    if (x.sketch_photo_path) photoRefs.push({ pointId: pt.id, code: pt.code, fileName: base(x.sketch_photo_path), caption: x.photo_caption ?? '' });
    newPoints.set(x.id, pt);
    p.points.push(pt);
  }

  // ---------------- Nivelación (corrida con más lecturas)
  const usedByLevel = new Set();
  const liveObs = (runId) => allLevObs.filter((o) => o.leveling_run_id === runId && !o.deleted_at).sort((a, b) => a.sequence_order - b.sequence_order || a.id - b.id);
  const chosen = [...runs].sort((a, b) => liveObs(b.id).length - liveObs(a.id).length || b.id - a.id)[0];
  for (const r of runs) if (r !== chosen) { keep('otras nivelaciones (no importadas a la libreta)', [r]); keep('lecturas de otras nivelaciones', liveObs(r.id)); }
  keep('lecturas de nivel eliminadas en Android', allLevObs.filter((o) => o.deleted_at));
  if (chosen) {
    const startPt = pts.find((x) => x.id === chosen.start_bm_point_id);
    const endPt = pts.find((x) => x.id === chosen.end_bm_point_id);
    p.leveling.bm = { code: startPt?.code ?? '', elevation: startPt?.fixed_elevation ?? null };
    if (startPt) { const np = newPoints.get(startPt.id); np.knownElevation = null; np.elevationJustification = ''; np.type = 'bm'; }
    if (p.leveling.bm.elevation === null) notes.push('El BM inicial no tenía cota fija en la versión Android: ingrésela en la libreta de nivelación.');
    const endElev = chosen.end_known_elevation ?? endPt?.fixed_elevation ?? null;
    if (endPt && endElev !== null) p.leveling.endBm = { enabled: true, code: endPt.code, elevation: endElev };
    const rows = [];
    for (const o of liveObs(chosen.id)) {
      const code = codeById.get(o.point_id) ?? `?${o.point_id}`;
      usedByLevel.add(o.point_id);
      const last = rows[rows.length - 1];
      if (o.reading_type === 'BS' && last && last.fs !== null && last.bs === null && last.is === null && last.code === code) {
        last.bs = o.reading_value;
        if (o.raw_notes) last.notes = [last.notes, o.raw_notes].filter(Boolean).join(' · ');
        continue;
      }
      rows.push({
        id: newId(), code, bs: o.reading_type === 'BS' ? o.reading_value : null, is: o.reading_type === 'IS' ? o.reading_value : null, fs: o.reading_type === 'FS' ? o.reading_value : null,
        partial: null, cumulative: null, inProfile: rows.length > 0, notes: o.raw_notes ?? '', createdAt: o.created_at || nowIso(), createdBy: 'app Android',
      });
      if (o.photo_path) {
        const np = newPoints.get(o.point_id);
        if (np && !photoRefs.some((r) => r.pointId === np.id)) photoRefs.push({ pointId: np.id, code: np.code, fileName: base(o.photo_path), caption: '' });
      }
    }
    for (const r of rows) r.original = { code: r.code, bs: r.bs, is: r.is, fs: r.fs, partial: null, cumulative: null, inProfile: r.inProfile, notes: r.notes };
    p.leveling.rows = rows;
    if (rows.length) notes.push(`Nivelación «${chosen.name}»: ${rows.length} filas importadas. La versión Android no registraba distancias: ingrese distancias parciales o progresivas para dibujar el perfil (o desmarque «en perfil» en los puntos fuera del eje).`);
  }

  // ---------------- Teodolito (radiación)
  const usedByTheo = new Set();
  const setups = raw.station_setups.filter((s) => s.project_id === ap.id);
  keep('configuraciones de estación (Android)', setups);
  const live = planObs.filter((o) => !o.deleted_at);
  keep('observaciones planimétricas eliminadas en Android', planObs.filter((o) => o.deleted_at));
  if (!importTheo) {
    if (live.length) {
      keep(`observaciones planimétricas de ${INSTRUMENT_LABEL[it] ?? it} (fuera del flujo)`, live);
      notes.push(`${live.length} observación(es) planimétrica(s) registradas con ${INSTRUMENT_LABEL[it] ?? it} se conservan en el archivo heredado y no entran en la radiación con teodolito.`);
    }
  } else {
    const traverseObs = live.filter((o) => o.traverse_id !== null && o.traverse_id !== undefined);
    keep('observaciones de poligonal (fuera de alcance)', traverseObs);
    const radObs = live.filter((o) => o.traverse_id === null || o.traverse_id === undefined);
    const mode = (o) => (o.horizontal_angle !== null && o.horizontal_angle !== undefined ? 'lectura' : o.azimuth !== null && o.azimuth !== undefined ? 'azimut' : o.bearing ? 'rumbo' : 'sin-angulo');
    const byStation = new Map();
    for (const o of radObs) { if (!byStation.has(o.occupied_point_id)) byStation.set(o.occupied_point_id, []); byStation.get(o.occupied_point_id).push(o); }
    const counts = { lectura: 0, norte: 0 };
    for (const list of byStation.values()) for (const o of list) counts[mode(o) === 'lectura' ? 'lectura' : 'norte'] += 1;
    const origin = counts.lectura >= counts.norte ? 'referencia' : 'norte';
    p.theodolite.convention = { unit: 'sexagesimal', direction: 'horario', origin };
    notes.push(`Convenio angular asumido en la importación: sexagesimal, sentido horario, origen ${origin === 'referencia' ? 'en la referencia de orientación' : 'en el norte (los valores registrados eran azimuts o rumbos)'}. Verifíquelo en la libreta del teodolito.`);
    for (const [occId, list] of byStation) {
      const occ = pts.find((x) => x.id === occId);
      const setup = setups.filter((s) => s.occupied_point_id === occId).sort((a, b) => b.id - a.id)[0];
      const hasXY = occ && occ.fixed_east !== null && occ.fixed_east !== undefined && occ.fixed_north !== null && occ.fixed_north !== undefined;
      const st = {
        id: newId(), code: occ?.code ?? `EST-${occId}`, east: hasXY ? occ.fixed_east : null, north: hasXY ? occ.fixed_north : null,
        coordType: hasXY ? (occ.is_control ? 'conocidas' : 'locales') : '', instrumentHeight: setup?.instrument_height_ai ?? list.find((o) => o.instrument_height !== null)?.instrument_height ?? null,
        orientation: origin === 'norte'
          ? { northNote: 'Importado de Android: los valores registrados eran azimuts (o rumbos convertidos a azimut). Indique cómo se orientó.' }
          : { refCode: setup?.backsight_point_label ?? '', azMode: 'ingresado', refAzimuth: setup ? formatDms(setup.base_azimuth_decimal) : '', azSource: setup ? 'Azimut base registrado en la versión Android' : '', refEast: null, refNorth: null, refReading: '' },
        createdAt: nowIso(), createdBy: 'app Android',
      };
      st.original = JSON.parse(JSON.stringify({ code: st.code, east: st.east, north: st.north, coordType: st.coordType, orientation: st.orientation }));
      usedByTheo.add(occId);
      if (!hasXY) notes.push(`La estación ${st.code} no tenía coordenadas en Android: ingréselas (conocidas o locales declaradas).`);
      if (origin === 'referencia') notes.push(`Estación ${st.code}: la versión Android no registraba la lectura horizontal en la referencia (L_ref); ingrésela antes de calcular.`);
      p.theodolite.stations.push(st);
      const skipped = [];
      for (const o of list) {
        const m = mode(o);
        const fits = origin === 'referencia' ? m === 'lectura' : m === 'azimut' || m === 'rumbo';
        if (!fits) { skipped.push(o); continue; }
        const angle = m === 'lectura' ? o.horizontal_angle : m === 'azimut' ? o.azimuth : bearingToAzimuth(o.bearing);
        const inclined = o.horizontal_distance === null || o.horizontal_distance === undefined;
        const r = {
          id: newId(), stationId: st.id, code: codeById.get(o.target_point_id) ?? `?${o.target_point_id}`, hz: angle === null ? '' : formatDms(normalizeAzimuth(angle)),
          distType: inclined ? 'inclinada' : 'horizontal', distance: inclined ? o.slope_distance : o.horizontal_distance,
          vType: o.zenith_angle !== null && o.zenith_angle !== undefined ? 'cenital' : o.vertical_angle !== null && o.vertical_angle !== undefined ? 'vertical' : 'cenital',
          vAngle: o.zenith_angle !== null && o.zenith_angle !== undefined ? formatDms(o.zenith_angle) : o.vertical_angle !== null && o.vertical_angle !== undefined ? formatDms(o.vertical_angle) : '',
          description: '', notes: [o.raw_notes, o.tramo ? `Tramo: ${o.tramo}` : '', m === 'rumbo' ? `Rumbo original: ${o.bearing}` : ''].filter(Boolean).join(' · '),
          createdAt: o.created_at || nowIso(), createdBy: 'app Android',
        };
        if (!inclined) { r.vType = null; r.vAngle = ''; }
        r.original = JSON.parse(JSON.stringify({ stationId: r.stationId, code: r.code, hz: r.hz, distType: r.distType, distance: r.distance, vType: r.vType, vAngle: r.vAngle, notes: r.notes }));
        usedByTheo.add(o.target_point_id);
        p.theodolite.readings.push(r);
      }
      if (skipped.length) {
        keep('observaciones con otro tipo de ángulo (no importadas)', skipped);
        notes.push(`Estación ${st.code}: ${skipped.length} observación(es) usaban otro tipo de ángulo que el convenio elegido y quedaron en el archivo heredado.`);
      }
      if (it !== 'TEODOLITO') notes.push(`Las observaciones de la estación ${st.code} se registraron como ${INSTRUMENT_LABEL[it] ?? it} y se incorporaron como lecturas de teodolito a pedido del usuario.`);
    }
  }

  // Instrumento de cada punto según su uso
  for (const [aid, pt] of newPoints) {
    const lv = usedByLevel.has(aid);
    const th = usedByTheo.has(aid);
    pt.instrument = lv && th ? 'ambos' : lv ? 'nivel' : th ? 'teodolito' : 'ambos';
  }

  // ---------------- Resto: fuera de alcance, conservado
  const projectTraverses = raw.traverses.filter((t) => t.project_id === ap.id);
  keep('poligonales (fuera de alcance)', projectTraverses);
  keep('compensaciones de Bowditch (fuera de alcance)', raw.adjustments.filter((a) => projectTraverses.some((t) => t.id === a.traverse_id)));
  keep('nivelación trigonométrica (fuera de alcance)', raw.leveling_trig_observations.filter((o) => o.project_id === ap.id));
  keep('resultados calculados por la versión Android', raw.computed_points.filter((c) => c.project_id === ap.id));
  keep('alineamientos (Android)', raw.alignments.filter((a) => a.project_id === ap.id));
  keep('importaciones CSV (Android)', raw.import_batches.filter((b) => b.project_id === ap.id));
  const rowProject = (table, id) => {
    if (table === 'points') return raw.points.find((x) => x.id === id)?.project_id;
    if (table === 'planimetry_observations') return raw.planimetry_observations.find((x) => x.id === id)?.project_id;
    if (table === 'leveling_observations') { const o = raw.leveling_observations.find((x) => x.id === id); return raw.leveling_runs.find((r) => r.id === o?.leveling_run_id)?.project_id; }
    return undefined;
  };
  keep('registro de ediciones de la versión Android', raw.observation_edit_log.filter((e) => rowProject(e.table_name, e.row_id) === ap.id));

  const counts = Object.entries(legacy).filter(([k]) => !k.startsWith('proyecto')).map(([k, v]) => `${v.length} ${k}`);
  p.legacy = {
    source: 'android-room-v1', sourceFile: options.sourceFile ?? 'toposcribe.db', importedAt: nowIso(), importedBy: by, androidProjectId: ap.id,
    instrumentType: it, summary: counts.join('; ') || 'sin registros fuera de alcance', notes, tables: legacy,
  };
  audit(p, { by, action: 'importar', entity: 'Proyecto', entityId: p.id, label: `Importado de la app Android (${options.sourceFile ?? 'toposcribe.db'}, proyecto ${ap.id})`, changes: [] });
  return { project: p, photoRefs, notes };
}
