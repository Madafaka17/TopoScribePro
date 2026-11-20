// Almacenamiento local en el navegador (IndexedDB): proyectos y fotografías.
// Los datos quedan en ESTE dispositivo y navegador. Para pasar un proyecto a otro equipo use
// «Exportar proyecto (.json)», que incluye las fotografías.

const DB_NAME = 'toposcribe';
const DB_VERSION = 1;
let dbPromise = null;

function open() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains('projects')) db.createObjectStore('projects', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('photos')) {
        const s = db.createObjectStore('photos', { keyPath: 'id' });
        s.createIndex('projectId', 'projectId');
      }
      if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta', { keyPath: 'key' });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error('No se pudo abrir el almacenamiento local.'));
  });
  return dbPromise;
}

function tx(store, mode, fn) {
  return open().then((db) => new Promise((resolve, reject) => {
    const t = db.transaction(store, mode);
    let result;
    t.oncomplete = () => resolve(result);
    t.onerror = () => reject(t.error ?? new Error('Error de almacenamiento.'));
    t.onabort = () => reject(t.error ?? new Error('La operación de almacenamiento se canceló (¿espacio insuficiente?).'));
    const r = fn(t.objectStore(store), t);
    if (r && typeof r === 'object' && 'onsuccess' in r) r.onsuccess = () => { result = r.result; };
  }));
}

export async function listProjects() {
  const all = await tx('projects', 'readonly', (s) => s.getAll());
  return (all ?? []).map((p) => ({
    id: p.id, name: p.name, location: p.location, date: p.date, updatedAt: p.updatedAt,
    points: p.points.length, levelRows: p.leveling.rows.length, readings: p.theodolite.readings.length,
    legacy: !!p.legacy,
  })).sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
}

export const getProject = (id) => tx('projects', 'readonly', (s) => s.get(id));
export const saveProject = (project) => tx('projects', 'readwrite', (s) => s.put(project));

export async function deleteProject(id) {
  const photos = await tx('photos', 'readonly', (s) => s.index('projectId').getAllKeys(id));
  await tx('photos', 'readwrite', (s) => { for (const k of photos ?? []) s.delete(k); });
  await tx('projects', 'readwrite', (s) => s.delete(id));
}

export const putPhoto = (photo) => tx('photos', 'readwrite', (s) => s.put(photo)); // {id, projectId, blob, mime}
export const getPhoto = (id) => tx('photos', 'readonly', (s) => s.get(id));
export const deletePhoto = (id) => tx('photos', 'readwrite', (s) => s.delete(id));
export const getProjectPhotos = (projectId) => tx('photos', 'readonly', (s) => s.index('projectId').getAll(projectId));

export const getMeta = async (key) => (await tx('meta', 'readonly', (s) => s.get(key)))?.value ?? null;
export const setMeta = (key, value) => tx('meta', 'readwrite', (s) => s.put({ key, value }));

/** Pide al navegador que no borre los datos ante falta de espacio. Devuelve true si queda persistente. */
export async function requestPersistence() {
  try {
    if (!navigator.storage?.persist) return false;
    if (await navigator.storage.persisted()) return true;
    return await navigator.storage.persist();
  } catch {
    return false;
  }
}

export async function storageEstimate() {
  try {
    const e = await navigator.storage?.estimate?.();
    const persisted = await navigator.storage?.persisted?.();
    return e ? { usage: e.usage, quota: e.quota, persisted: !!persisted } : null;
  } catch {
    return null;
  }
}
