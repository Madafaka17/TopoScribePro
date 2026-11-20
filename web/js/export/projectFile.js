// Archivo de proyecto (.toposcribe.json): respaldo completo y portable entre celular y computadora,
// con lecturas originales, correcciones, resultados aceptados y fotografías (en base64).

import { newProject, SCHEMA, SCHEMA_VERSION } from '../core/model.js';

export const FILE_FORMAT = 'toposcribe-proyecto';

export function serializeProjectFile(project, photos, appVersion) {
  return JSON.stringify({
    format: FILE_FORMAT,
    version: 1,
    app: `TopoScribePro web ${appVersion}`,
    exportedAt: new Date().toISOString(),
    project,
    photos, // [{ id, mime, base64 }]
  });
}

/** Completa con valores por defecto los campos que falten (proyectos de versiones anteriores). */
export function normalizeProject(raw) {
  if (!raw || typeof raw !== 'object') throw new Error('El archivo no contiene un proyecto.');
  if (raw.schema && raw.schema !== SCHEMA) throw new Error('El archivo no es un proyecto de TopoScribePro.');
  if ((raw.version ?? 1) > SCHEMA_VERSION) throw new Error('El proyecto fue creado con una versión más nueva de la aplicación. Actualice la aplicación.');
  const base = newProject();
  const p = { ...base, ...raw };
  p.schema = SCHEMA;
  p.version = SCHEMA_VERSION;
  p.instruments = { level: { ...base.instruments.level, ...(raw.instruments?.level ?? {}) }, theodolite: { ...base.instruments.theodolite, ...(raw.instruments?.theodolite ?? {}) } };
  p.points = Array.isArray(raw.points) ? raw.points : [];
  p.leveling = {
    ...base.leveling,
    ...(raw.leveling ?? {}),
    bm: { ...base.leveling.bm, ...(raw.leveling?.bm ?? {}) },
    endBm: { ...base.leveling.endBm, ...(raw.leveling?.endBm ?? {}) },
    rows: Array.isArray(raw.leveling?.rows) ? raw.leveling.rows : [],
    profileScales: { ...base.leveling.profileScales, ...(raw.leveling?.profileScales ?? {}) },
  };
  p.theodolite = {
    convention: { ...base.theodolite.convention, ...(raw.theodolite?.convention ?? {}) },
    stations: Array.isArray(raw.theodolite?.stations) ? raw.theodolite.stations : [],
    readings: Array.isArray(raw.theodolite?.readings) ? raw.theodolite.readings : [],
  };
  p.plan = { ...base.plan, ...(raw.plan ?? {}), lines: Array.isArray(raw.plan?.lines) ? raw.plan.lines : [] };
  p.results = { ...base.results, ...(raw.results ?? {}) };
  p.audit = Array.isArray(raw.audit) ? raw.audit : [];
  p.legacy = raw.legacy ?? null;
  if (!p.id) throw new Error('El proyecto no tiene identificador.');
  return p;
}

export function parseProjectFile(text) {
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Error('El archivo no es un JSON válido.');
  }
  if (data?.format !== FILE_FORMAT) throw new Error('El archivo no es un proyecto exportado por TopoScribePro (.toposcribe.json).');
  const project = normalizeProject(data.project);
  const photos = Array.isArray(data.photos) ? data.photos.filter((ph) => ph && ph.id && ph.base64) : [];
  const referenced = new Set(project.points.map((pt) => pt.photo?.id).filter(Boolean));
  const missing = [...referenced].filter((id) => !photos.some((ph) => ph.id === id));
  return { project, photos, missing };
}
