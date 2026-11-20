import { html, useState } from '../../vendor/preact-htm.mjs';
import { Btn, Check, Modal, Notice, toast } from './components.js';
import { applyWal, convertAndroidProject, readAndroidDb, summarizeAndroid } from '../import/androidRoom.js';
import { loadScript, pickFiles, resizeImage } from './files.js';
import { putPhoto, saveProject } from '../storage/db.js';
import { newId, nowIso } from '../core/model.js';

const INSTR = { ESTACION_TOTAL: 'Estación total', TEODOLITO: 'Teodolito', NIVEL_OPTICO: 'Nivel óptico', GNSS_RTK: 'GNSS/RTK' };

async function loadSql() {
  await loadScript('vendor/sqljs/sql-wasm-browser.js');
  return window.initSqlJs({ locateFile: (f) => `vendor/sqljs/${f.replace('sql-wasm.wasm', 'sql-wasm-browser.wasm')}` });
}

export function AndroidImportDialog({ ctx, onClose }) {
  const [step, setStep] = useState('files');
  const [db, setDb] = useState(null);
  const [wal, setWal] = useState(null);
  const [photos, setPhotos] = useState([]);
  const [raw, setRaw] = useState(null);
  const [summary, setSummary] = useState([]);
  const [selected, setSelected] = useState({});
  const [tsAsTheo, setTsAsTheo] = useState({});
  const [busy, setBusy] = useState(false);
  const [walInfo, setWalInfo] = useState(null);
  const [result, setResult] = useState(null);

  const pickDb = async () => {
    const files = await pickFiles({ multiple: true });
    for (const f of files) {
      if (/-wal$/i.test(f.name)) setWal(f);
      else if (/-shm$/i.test(f.name)) { /* no se necesita */ } else setDb(f);
    }
  };
  const pickPhotos = async () => setPhotos(await pickFiles({ accept: 'image/*', multiple: true }));

  const analyze = async () => {
    setBusy(true);
    try {
      const SQL = await loadSql();
      const { bytes, info } = applyWal(new Uint8Array(await db.arrayBuffer()), wal ? new Uint8Array(await wal.arrayBuffer()) : null);
      const r = readAndroidDb(SQL, bytes);
      const s = summarizeAndroid(r);
      setRaw(r); setSummary(s); setWalInfo(info);
      setSelected(Object.fromEntries(s.map((x) => [x.id, !x.isDemo])));
      setStep('choose');
    } catch (e) {
      toast(`No se pudo leer la base de datos: ${e.message}`, 'err', 10000);
    } finally { setBusy(false); }
  };

  const doImport = async () => {
    setBusy(true);
    const report = [];
    try {
      const byName = new Map(photos.map((f) => [f.name, f]));
      let lastId = null;
      for (const s of summary.filter((x) => selected[x.id])) {
        const { project, photoRefs, notes } = convertAndroidProject(raw, s.id, { totalStationAsTheodolite: !!tsAsTheo[s.id], sourceFile: db.name }, ctx.user);
        const missingPhotos = [];
        for (const ref of photoRefs) {
          const f = byName.get(ref.fileName);
          if (!f) { missingPhotos.push(`${ref.code} (${ref.fileName})`); continue; }
          const r = await resizeImage(f, 2560, 0.9);
          const id = newId();
          await putPhoto({ id, projectId: project.id, blob: r.blob, mime: 'image/jpeg' });
          const pt = project.points.find((x) => x.id === ref.pointId);
          pt.photo = { id, caption: ref.caption, fileName: ref.fileName, mime: 'image/jpeg', width: r.width, height: r.height, addedAt: nowIso(), addedBy: 'app Android (importada)' };
        }
        if (missingPhotos.length) project.legacy.notes.push(`Fotografías referenciadas que no se seleccionaron al importar: ${missingPhotos.join(', ')}.`);
        await saveProject(project);
        lastId = project.id;
        report.push({ name: project.name, notes: [...notes, ...(missingPhotos.length ? [`${missingPhotos.length} fotografía(s) no encontradas entre los archivos elegidos.`] : [])], summary: project.legacy.summary });
      }
      await ctx.refreshList();
      if (lastId) await ctx.openProject(lastId);
      setResult(report);
      setStep('done');
    } catch (e) {
      toast(`Error al importar: ${e.message}`, 'err', 10000);
    } finally { setBusy(false); }
  };

  const nSel = Object.values(selected).filter(Boolean).length;
  return html`<${Modal} title="Importar datos de la app Android" onClose=${onClose} wide testid="android-dialog"
    footer=${step === 'files' ? html`<${Btn} kind="secondary" onClick=${onClose}>Cancelar<//><${Btn} testid="and-analizar" disabledReason=${!db ? 'Elija el archivo toposcribe.db.' : busy ? 'Leyendo…' : null} onClick=${analyze}>Leer base de datos<//>`
      : step === 'choose' ? html`<${Btn} kind="secondary" onClick=${() => setStep('files')}>Atrás<//><${Btn} testid="and-importar" disabledReason=${nSel === 0 ? 'Marque al menos un proyecto.' : busy ? 'Importando…' : null} onClick=${doImport}>Importar ${nSel} proyecto(s)<//>`
        : html`<${Btn} onClick=${onClose}>Cerrar<//>`}>
    ${step === 'files' ? html`<div class="stack">
      <p>Los proyectos de la versión Android están en el archivo <code>toposcribe.db</code> dentro del teléfono. Para copiarlo a la computadora (la app Android debe haberse instalado desde Android Studio):</p>
      <ol class="small">
        <li>Conecte el teléfono por USB con la depuración USB activada y abra Android Studio.</li>
        <li>Menú <em>View › Tool Windows › Device Explorer</em>. Abra <code>data/data/com.toposcribe.pro/databases/</code>.</li>
        <li>Guarde <code>toposcribe.db</code> y también <code>toposcribe.db-wal</code> si existe (contiene los cambios más recientes). Clic derecho › <em>Save As</em>.</li>
        <li>Para las fotografías, guarde la carpeta <code>data/data/com.toposcribe.pro/files/photos/</code>.</li>
        <li>Alternativa por consola: <code>adb exec-out run-as com.toposcribe.pro cat databases/toposcribe.db &gt; toposcribe.db</code> (y lo mismo con <code>toposcribe.db-wal</code>).</li>
      </ol>
      <${Notice} kind="warn">No desinstale la app Android hasta comprobar que la importación está completa: al desinstalarla se borran sus datos.<//>
      <div class="actions"><${Btn} kind="secondary" testid="and-elegir-db" onClick=${pickDb}>Elegir toposcribe.db (y -wal)<//><${Btn} kind="secondary" onClick=${pickPhotos}>Elegir fotografías (opcional)<//></div>
      <ul class="small" data-testid="and-files">
        <li>Base de datos: ${db ? html`<strong>${db.name}</strong>` : 'sin elegir'}</li>
        <li>Archivo WAL: ${wal ? html`<strong>${wal.name}</strong>` : 'no elegido (si existía, los últimos cambios podrían faltar)'}</li>
        <li>Fotografías: ${photos.length} archivo(s)</li>
      </ul>
    </div>` : null}
    ${step === 'choose' ? html`<div class="stack">
      ${walInfo?.walUsed ? html`<${Notice} kind="ok">Se aplicaron ${walInfo.framesApplied} página(s) del archivo WAL (cambios recientes).<//>` : null}
      <p>Proyectos encontrados (${summary.length}). Lo que pertenece a funciones o instrumentos fuera del alcance (estación total, GNSS/RTK, poligonales, Bowditch, nivelación trigonométrica) <strong>no se borra</strong>: queda en el «archivo heredado» del proyecto, visible en Proyecto, sin entrar en los cálculos.</p>
      <div class="table-wrap"><table class="data cards" data-testid="and-projects">
        <thead><tr><th>Importar</th><th>Proyecto</th><th>Instrumento</th><th>Contenido</th><th>Opciones</th></tr></thead>
        <tbody>${summary.map((s) => html`<tr>
          <td data-label="Importar"><input type="checkbox" checked=${!!selected[s.id]} onChange=${(e) => setSelected((x) => ({ ...x, [s.id]: e.currentTarget.checked }))} /></td>
          <td data-label="Proyecto"><strong>${s.name}</strong>${s.isDemo ? html` <span class="badge warn">demostración (sintético)</span>` : null}</td>
          <td data-label="Instrumento">${INSTR[s.instrumentType] ?? s.instrumentType}</td>
          <td data-label="Contenido" class="small">${s.points} puntos · ${s.levelingObs} lecturas de nivel · ${s.planimetryObs} obs. planimétricas${s.traverses ? ` · ${s.traverses} poligonales` : ''}${s.trig ? ` · ${s.trig} niv. trigonométricas` : ''}${s.gnssPoints ? ` · ${s.gnssPoints} puntos GNSS` : ''} · ${s.photos} fotos</td>
          <td data-label="Opciones">${s.planimetryObs && s.instrumentType !== 'TEODOLITO' ? html`<${Check} checked=${!!tsAsTheo[s.id]} onChange=${(v) => setTsAsTheo((x) => ({ ...x, [s.id]: v }))} label=${`Las observaciones de ${INSTR[s.instrumentType] ?? s.instrumentType} se midieron en realidad con teodolito: incorporarlas a la radiación`} />` : html`<span class="muted small">—</span>`}</td>
        </tr>`)}</tbody></table></div>
    </div>` : null}
    ${step === 'done' ? html`<div class="stack" data-testid="and-result">
      <${Notice} kind="ok" title="Importación terminada">Se importaron ${result.length} proyecto(s). Revise cada uno: los datos importados pueden necesitar completar distancias, la lectura en la referencia u otros datos que la versión Android no registraba.<//>
      ${result.map((r) => html`<div class="card"><strong>${r.name}</strong><ul class="small">${r.notes.map((n) => html`<li>${n}</li>`)}<li>Archivo heredado: ${r.summary}</li></ul></div>`)}
    </div>` : null}
  <//>`;
}
