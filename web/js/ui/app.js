import { html, useEffect, useMemo, useRef, useState } from '../../vendor/preact-htm.mjs';
import * as db from '../storage/db.js';
import { acceptResults, audit, evaluateProject, newProject, nowIso } from '../core/model.js';
import { normalizeProject, parseProjectFile, serializeProjectFile } from '../export/projectFile.js';
import { Btn, ConfirmHost, Field, Modal, TextInput, Toasts, confirmDialog, toast } from './components.js';
import { base64ToBlob, blobToBase64, downloadBlob, pickFiles, safeFileName, today } from './files.js';
import { ProyectoView } from './views/proyecto.js';
import { LibretaView } from './views/libreta.js';
import { NivelView } from './views/nivel.js';
import { TeodolitoView } from './views/teodolito.js';
import { PlanoView } from './views/plano.js';
import { InformeView } from './views/informe.js';

export const APP_VERSION = '2.0.0';

const TABS = [
  ['proyecto', 'Proyecto', 'M3 7h6l2 2h10v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'],
  ['libreta', 'Libreta de campo', 'M5 3h12a2 2 0 0 1 2 2v16H7a2 2 0 0 1-2-2zM9 7h7M9 11h7M9 15h5'],
  ['nivel', 'Nivel de ingeniero', 'M3 14h18M7 14V9h10v5M12 9V5M9 19l3-5 3 5'],
  ['teodolito', 'Teodolito', 'M12 3v4M12 12m-4 0a4 4 0 1 0 8 0a4 4 0 1 0-8 0M8 21l4-5 4 5M3 12h5M16 12h5'],
  ['plano', 'Plano y curvas de nivel', 'M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2zM9 4v14M15 6v14'],
  ['informe', 'Informe', 'M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h7'],
];
const SHORT = { proyecto: 'Proyecto', libreta: 'Libreta', nivel: 'Nivel', teodolito: 'Teodolito', plano: 'Plano', informe: 'Informe' };

const ls = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch { /* sin almacenamiento local */ } },
};

function readHash() {
  const [tab = 'proyecto', sub = ''] = location.hash.replace(/^#\/?/, '').split('/');
  return { tab: TABS.some(([t]) => t === tab) ? tab : 'proyecto', sub };
}

export function App() {
  const [route, setRoute] = useState(readHash());
  const [project, setProject] = useState(null);
  const [projects, setProjects] = useState([]);
  const [user, setUserState] = useState(ls.get('toposcribe.user') ?? '');
  const [askUser, setAskUser] = useState(false);
  const [savedAt, setSavedAt] = useState(null);
  const [loading, setLoading] = useState(true);
  const projectRef = useRef(null);
  const saveChain = useRef(Promise.resolve());
  const pendingAfterUser = useRef(null);

  const ev = useMemo(() => (project ? evaluateProject(project) : null), [project]);

  const refreshList = async () => setProjects(await db.listProjects());

  useEffect(() => {
    const onHash = () => setRoute(readHash());
    window.addEventListener('hashchange', onHash);
    (async () => {
      try {
        if (!user) { const u = await db.getMeta('user'); if (u) { setUserState(u); ls.set('toposcribe.user', u); } }
        await refreshList();
        const last = ls.get('toposcribe.currentProject') ?? (await db.getMeta('currentProject'));
        if (last) {
          const p = await db.getProject(last);
          if (p) { const n = normalizeProject(p); projectRef.current = n; setProject(n); }
        }
      } catch (e) {
        toast(`No se pudo abrir el almacenamiento del navegador: ${e.message}`, 'err', 10000);
      } finally {
        setLoading(false);
      }
    })();
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  const nav = (tab, sub = '') => { location.hash = `/${tab}${sub ? `/${sub}` : ''}`; window.scrollTo(0, 0); };

  const setUser = async (name) => {
    const n = name.trim();
    setUserState(n);
    ls.set('toposcribe.user', n || null);
    await db.setMeta('user', n);
  };

  /** Pide el nombre de quien registra antes de cualquier cambio (queda en el registro de correcciones). */
  const ensureUser = (retry) => {
    if (user) return true;
    pendingAfterUser.current = retry ?? true;
    setAskUser(true);
    toast('Indique primero quién registra los datos en este dispositivo.', 'warn');
    return false;
  };

  const persist = (next) => {
    saveChain.current = saveChain.current.then(async () => {
      await db.saveProject(next);
      setSavedAt(new Date());
    }).catch((e) => toast(`No se pudo guardar: ${e.message}`, 'err', 10000));
    return saveChain.current;
  };

  /**
   * Aplica un cambio al proyecto y lo guarda. mutator(p, by) puede lanzar Error('mensaje') para
   * rechazar el cambio. Devuelve true si se guardó.
   */
  const update = async (mutator, { requireUser = true } = {}) => {
    if (!projectRef.current) { toast('No hay un proyecto abierto.', 'warn'); return false; }
    if (requireUser && !ensureUser(() => update(mutator))) return false;
    const next = structuredClone(projectRef.current);
    try {
      mutator(next, user);
    } catch (e) {
      toast(e.message, 'err', 7000);
      return false;
    }
    next.updatedAt = nowIso();
    projectRef.current = next;
    setProject(next);
    await persist(next);
    return true;
  };

  const openProject = async (id) => {
    const p = await db.getProject(id);
    if (!p) { toast('El proyecto ya no existe en este dispositivo.', 'err'); await refreshList(); return; }
    const n = normalizeProject(p);
    projectRef.current = n;
    setProject(n);
    ls.set('toposcribe.currentProject', id);
    await db.setMeta('currentProject', id);
    toast(`Proyecto «${n.name}» abierto.`, 'ok');
  };

  const createProject = async (fields) => {
    if (!ensureUser()) return false;
    const p = newProject(fields, user);
    audit(p, { by: user, action: 'crear', entity: 'Proyecto', entityId: p.id, label: `Proyecto «${p.name}»`, changes: [] });
    await db.saveProject(p);
    projectRef.current = p;
    setProject(p);
    ls.set('toposcribe.currentProject', p.id);
    await db.setMeta('currentProject', p.id);
    await refreshList();
    setSavedAt(new Date());
    toast(`Proyecto «${p.name}» creado y guardado. Está vacío: sin coordenadas, cotas ni fotografías.`, 'ok', 6000);
    return true;
  };

  const closeProject = () => {
    projectRef.current = null;
    setProject(null);
    ls.set('toposcribe.currentProject', null);
    db.setMeta('currentProject', null);
    refreshList();
  };

  const deleteProject = async (id, name) => {
    const r = await confirmDialog({
      title: 'Eliminar proyecto',
      message: [`Se eliminará «${name}» con todas sus lecturas, resultados y fotografías de ESTE dispositivo.`, 'Esta acción no se puede deshacer. Si lo necesita después, exporte antes el proyecto (.json).'],
      confirmText: 'Eliminar definitivamente', danger: true, typeToConfirm: name || 'ELIMINAR',
    });
    if (!r.ok) return;
    await saveChain.current;
    await db.deleteProject(id);
    if (projectRef.current?.id === id) closeProject();
    await refreshList();
    toast('Proyecto eliminado.', 'ok');
  };

  const saveNow = async () => {
    if (!projectRef.current) return;
    await persist(projectRef.current);
    const stored = await db.getProject(projectRef.current.id);
    const persisted = await db.requestPersistence();
    if (stored && stored.updatedAt === projectRef.current.updatedAt) {
      toast(`Proyecto guardado en este dispositivo y verificado (${new Date().toLocaleTimeString('es')}).${persisted ? '' : ' Recuerde exportar el archivo .json como respaldo.'}`, 'ok', 5000);
    } else {
      toast('No se pudo verificar el guardado. Exporte el proyecto (.json) como respaldo.', 'err', 8000);
    }
    refreshList();
  };

  const exportProjectFile = async () => {
    const p = projectRef.current;
    await saveChain.current;
    const photos = [];
    for (const rec of await db.getProjectPhotos(p.id)) {
      if (p.points.some((pt) => pt.photo?.id === rec.id)) photos.push({ id: rec.id, mime: rec.mime || 'image/jpeg', base64: await blobToBase64(rec.blob) });
    }
    const text = serializeProjectFile(p, photos, APP_VERSION);
    downloadBlob(`${safeFileName(p.name)}_${today()}.toposcribe.json`, new Blob([text], { type: 'application/json' }));
    toast(`Proyecto exportado con ${photos.length} fotografía(s).`, 'ok');
  };

  const importProjectFile = async () => {
    const [file] = await pickFiles({ accept: '.json,application/json' });
    if (!file) return;
    try {
      const { project: p, photos, missing } = parseProjectFile(await file.text());
      const existing = await db.getProject(p.id);
      if (existing) {
        const r = await confirmDialog({
          title: 'El proyecto ya existe en este dispositivo',
          message: [`«${existing.name}» (modificado ${new Date(existing.updatedAt).toLocaleString('es')}) tiene el mismo identificador.`, 'Aceptar: se importa como COPIA nueva, sin tocar el existente.'],
          confirmText: 'Importar como copia',
        });
        if (!r.ok) return;
        p.id = newProject().id;
        p.name = `${p.name} (copia importada)`;
      }
      for (const ph of photos) await db.putPhoto({ id: ph.id, projectId: p.id, blob: base64ToBlob(ph.base64, ph.mime), mime: ph.mime });
      audit(p, { by: user || 'desconocido', action: 'importar', entity: 'Proyecto', entityId: p.id, label: `Importado desde ${file.name}`, changes: [] });
      await db.saveProject(p);
      await refreshList();
      await openProject(p.id);
      if (missing.length) toast(`Atención: ${missing.length} fotografía(s) referenciadas no venían en el archivo.`, 'warn', 8000);
    } catch (e) {
      toast(`No se pudo importar: ${e.message}`, 'err', 9000);
    }
  };

  /** «Calcular»: acepta los datos actuales de un módulo como resultado vigente. */
  const calc = async (module) => {
    const evNow = evaluateProject(projectRef.current);
    const st = evNow[module];
    const cur = st.current;
    if (module !== 'contours' && !cur.computable) { toast('Corrija los errores indicados antes de calcular.', 'err'); return false; }
    if (module === 'contours' && !cur.ok) { toast(cur.reason || 'No se pueden generar curvas con los datos actuales.', 'err', 8000); return false; }
    const label = { leveling: 'Nivelación', radiation: 'Radiación', contours: 'Curvas de nivel' }[module];
    const ok = await update((p, by) => {
      acceptResults(p, module, evNow, by);
      audit(p, { by, action: 'calcular', entity: label, entityId: module, label: `${label} calculada`, changes: [] });
    });
    if (ok) toast(`${label}: cálculo realizado y guardado.`, 'ok');
    return ok;
  };

  const ctx = { project, ev, user, projects, route, nav, update, ensureUser, openProject, createProject, closeProject, deleteProject, exportProjectFile, importProjectFile, refreshList, calc, setUser, askUser: () => setAskUser(true), saveNow, appVersion: APP_VERSION, saveChain };

  const View = { proyecto: ProyectoView, libreta: LibretaView, nivel: NivelView, teodolito: TeodolitoView, plano: PlanoView, informe: InformeView }[route.tab];
  const needsProject = route.tab !== 'proyecto';

  return html`<div class="app">
    <header class="topbar">
      <span class="brand">TopoScribePro</span>
      <span class="project-name" data-testid="current-project">${project ? project.name : 'Sin proyecto abierto'}</span>
      <span class="save-state" data-testid="save-state">${savedAt ? `Guardado ${savedAt.toLocaleTimeString('es', { hour: '2-digit', minute: '2-digit' })}` : ''}</span>
      <${Btn} small kind="secondary" testid="btn-guardar" showReason=${false} disabledReason=${project ? null : 'No hay un proyecto abierto para guardar. Cree o abra uno en «Proyecto».'} onClick=${saveNow}>Guardar<//>
    </header>
    <nav class="nav" aria-label="Secciones">
      <ol>${TABS.map(([id, label, icon], i) => html`
        ${i > 0 ? html`<li class="arrow" aria-hidden="true">›</li>` : null}
        <li><button aria-current=${route.tab === id ? 'page' : undefined} data-testid=${`nav-${id}`} onClick=${() => nav(id)}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d=${icon} /></svg>
          <span class="label-long">${label}</span><span class="label-short">${SHORT[id]}</span>
        </button></li>`)}</ol>
    </nav>
    <main>
      ${loading ? html`<p class="muted">Cargando…</p>`
        : needsProject && !project ? html`<div class="card welcome"><h2>${TABS.find(([t]) => t === route.tab)[1]}</h2>
            <p>Primero cree un proyecto nuevo o abra uno guardado.</p><${Btn} onClick=${() => nav('proyecto')}>Ir a Proyecto<//></div>`
          : html`<${View} ctx=${ctx} />`}
    </main>
    ${askUser ? html`<${UserDialog} initial=${user} suggestions=${project?.responsibles ?? ''} onClose=${() => setAskUser(false)}
      onSave=${async (n) => { await setUser(n); setAskUser(false); toast(`Los registros quedarán a nombre de «${n}».${pendingAfterUser.current ? ' Repita la acción que estaba haciendo.' : ''}`, 'ok'); pendingAfterUser.current = null; }} />` : null}
    <${ConfirmHost} />
    <${Toasts} />
  </div>`;
}

function UserDialog({ initial, onSave, onClose, suggestions }) {
  const [name, setName] = useState(initial ?? '');
  const valid = name.trim().length >= 2;
  return html`<${Modal} title="¿Quién registra los datos?" onClose=${onClose} testid="user-dialog"
    footer=${html`<${Btn} kind="secondary" onClick=${onClose}>Cancelar<//><${Btn} testid="user-save" disabledReason=${valid ? null : 'Escriba un nombre (al menos 2 letras).'} onClick=${() => onSave(name.trim())}>Guardar<//>`}>
    <p>Cada lectura, corrección y cálculo queda registrado con este nombre y la fecha. Se guarda solo en este dispositivo.</p>
    <${Field} label="Nombre y apellido" hint=${suggestions ? `Responsables del proyecto: ${suggestions}` : 'Ejemplo: Ana Pérez'}><${TextInput} value=${name} onInput=${setName} testid="user-name" autofocus /><//>
  <//>`;
}

// Etiquetas cortas en la barra inferior del celular
export function shortLabel(id) { return SHORT[id]; }
