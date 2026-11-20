import { html, useEffect, useState } from '../../../vendor/preact-htm.mjs';
import { Btn, Field, Modal, Notice, TextInput, fmtDateTime, toast } from '../components.js';
import { storageEstimate } from '../../storage/db.js';
import { AndroidImportDialog } from '../androidImport.js';

const PROJECT_FIELDS = [
  ['name', 'Nombre del proyecto', 'Obligatorio'],
  ['location', 'Lugar o ubicación', 'Distrito, provincia, referencia del terreno'],
  ['date', 'Fecha del levantamiento', ''],
  ['responsibles', 'Responsables', 'Nombres del equipo de campo, separados por comas'],
  ['objective', 'Objetivo del levantamiento', 'Para qué se levanta el terreno'],
  ['coordinateSystem', 'Sistema de coordenadas declarado', 'Ejemplo: Local arbitrario, EST-1 = (500.000; 1000.000)  ·  o UTM WGS84 zona 18 S'],
  ['elevationDatum', 'Referencia de cotas', 'Ejemplo: cota arbitraria del BM-1 = 100.000 m  ·  o cota IGN del BM'],
  ['siteDescription', 'Descripción del lugar (memoria descriptiva)', 'Relieve, vegetación, accesos, linderos'],
  ['fieldProcedure', 'Procedimiento de campo', 'Cómo se hizo el trabajo con cada instrumento'],
  ['observations', 'Observaciones', ''],
];
const LONG = new Set(['objective', 'siteDescription', 'fieldProcedure', 'observations', 'responsibles']);

export function ProyectoView({ ctx }) {
  const { project, user } = ctx;
  const [showNew, setShowNew] = useState(false);
  const [showAndroid, setShowAndroid] = useState(false);
  const [storage, setStorage] = useState(null);
  useEffect(() => { storageEstimate().then(setStorage); }, [project?.updatedAt]);

  return html`<div>
    <div class="page-title"><h2>Proyecto</h2>
      <div class="actions">
        <${Btn} testid="btn-nuevo-proyecto" onClick=${() => (ctx.ensureUser() ? setShowNew(true) : null)}>Nuevo proyecto<//>
        <${Btn} kind="secondary" testid="btn-abrir-archivo" onClick=${ctx.importProjectFile}>Abrir archivo de proyecto (.json)<//>
      </div>
    </div>

    <div class="card">
      <div class="row between">
        <div><strong>Usuario de este dispositivo:</strong> <span data-testid="user-label">${user || 'sin indicar'}</span>
          <div class="small muted">Queda registrado en cada lectura, corrección y cálculo.</div></div>
        <${Btn} small kind="secondary" testid="btn-usuario" onClick=${ctx.askUser}>${user ? 'Cambiar' : 'Indicar nombre'}<//>
      </div>
    </div>

    ${project ? html`<${ProjectForm} key=${project.id} ctx=${ctx} />` : html`<${Welcome} />`}

    <div class="card">
      <h3>Proyectos guardados en este dispositivo</h3>
      ${ctx.projects.length === 0 ? html`<p class="muted">Todavía no hay proyectos guardados.</p>` : html`
        <div class="table-wrap"><table class="data cards" data-testid="project-list">
          <thead><tr><th>Nombre</th><th>Lugar</th><th>Fecha</th><th>Datos</th><th>Modificado</th><th></th></tr></thead>
          <tbody>${ctx.projects.map((p) => html`<tr>
            <td data-label="Nombre"><strong>${p.name}</strong>${p.id === project?.id ? html` <span class="badge info">abierto</span>` : null}${p.legacy ? html` <span class="badge muted">importado de Android</span>` : null}</td>
            <td data-label="Lugar">${p.location || '—'}</td>
            <td data-label="Fecha">${p.date || '—'}</td>
            <td data-label="Datos">${p.points} puntos · ${p.levelRows} lect. nivel · ${p.readings} lect. teodolito</td>
            <td data-label="Modificado">${fmtDateTime(p.updatedAt)}</td>
            <td class="actions-cell">
              ${p.id !== project?.id ? html`<${Btn} small onClick=${() => ctx.openProject(p.id)}>Abrir<//>` : null}
              <${Btn} small kind="danger" onClick=${() => ctx.deleteProject(p.id, p.name)}>Eliminar<//>
            </td></tr>`)}</tbody></table></div>`}
      <div class="actions" style="margin-top:.8rem">
        <${Btn} kind="secondary" testid="btn-importar-android" onClick=${() => (ctx.ensureUser() ? setShowAndroid(true) : null)}>Importar datos de la app Android (toposcribe.db)<//>
      </div>
    </div>

    <div class="card small">
      <strong>Dónde se guardan los datos.</strong> En el almacenamiento de este navegador y dispositivo, al pulsar «Guardar» en cada formulario
      (también puede pulsar «Guardar» arriba para verificarlo). Para pasar un proyecto del celular a la computadora, o como respaldo,
      use «Exportar proyecto (.json)»: incluye lecturas, correcciones, resultados y fotografías.
      ${storage ? html`<div class="muted">Espacio usado: ${(storage.usage / 1048576).toFixed(1)} MB${storage.persisted ? ' · almacenamiento persistente concedido' : ' · el navegador podría liberar el espacio si falta memoria: exporte respaldos.'}</div>` : null}
    </div>

    ${showNew ? html`<${NewProjectDialog} onClose=${() => setShowNew(false)} onCreate=${async (f) => { if (await ctx.createProject(f)) setShowNew(false); }} />` : null}
    ${showAndroid ? html`<${AndroidImportDialog} ctx=${ctx} onClose=${() => setShowAndroid(false)} />` : null}
  </div>`;
}

function Welcome() {
  return html`<div class="card welcome">
    <h3>Cómo se trabaja</h3>
    <ol>
      <li><strong>Proyecto:</strong> cree un proyecto vacío con sus datos generales.</li>
      <li><strong>Libreta de campo:</strong> registre puntos, lecturas del nivel y lecturas del teodolito (con fotografías).</li>
      <li><strong>Nivel de ingeniero:</strong> calcule cotas, comprobación, cierre y perfil longitudinal.</li>
      <li><strong>Teodolito:</strong> calcule azimuts, distancias y coordenadas por radiación.</li>
      <li><strong>Plano y curvas de nivel:</strong> vincule cotas por código, triangule, genere curvas y exporte el DXF.</li>
      <li><strong>Informe:</strong> revise la vista previa y genere el PDF, el Excel o los CSV.</li>
    </ol>
    <p class="muted small">Un proyecto nuevo empieza vacío: la aplicación no inventa coordenadas, cotas ni fotografías.</p>
  </div>`;
}

function NewProjectDialog({ onClose, onCreate }) {
  const [f, setF] = useState({ name: '', location: '', date: new Date().toISOString().slice(0, 10), responsibles: '', objective: '', observations: '', coordinateSystem: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const reason = f.name.trim() ? null : 'Escriba el nombre del proyecto.';
  return html`<${Modal} title="Nuevo proyecto (vacío)" onClose=${onClose} testid="new-project-dialog"
    footer=${html`<${Btn} kind="secondary" onClick=${onClose}>Cancelar<//><${Btn} testid="np-guardar" disabledReason=${reason} onClick=${() => onCreate({ ...f, name: f.name.trim() })}>Guardar<//>`}>
    <div class="grid two">
      <${Field} label="Nombre del proyecto *"><${TextInput} value=${f.name} onInput=${set('name')} testid="np-name" autofocus /><//>
      <${Field} label="Lugar"><${TextInput} value=${f.location} onInput=${set('location')} testid="np-location" /><//>
      <${Field} label="Fecha"><${TextInput} type="date" value=${f.date} onInput=${set('date')} testid="np-date" /><//>
      <${Field} label="Responsables"><${TextInput} value=${f.responsibles} onInput=${set('responsibles')} testid="np-responsibles" /><//>
      <${Field} label="Sistema de coordenadas declarado" hint="Ejemplo: Local arbitrario"><${TextInput} value=${f.coordinateSystem} onInput=${set('coordinateSystem')} testid="np-crs" /><//>
    </div>
    <div class="stack" style="margin-top:.75rem">
      <${Field} label="Objetivo del levantamiento"><textarea data-testid="np-objective" value=${f.objective} onInput=${(e) => set('objective')(e.currentTarget.value)} /><//>
      <${Field} label="Observaciones"><textarea value=${f.observations} onInput=${(e) => set('observations')(e.currentTarget.value)} /><//>
    </div>
  <//>`;
}

function ProjectForm({ ctx }) {
  const { project } = ctx;
  const initial = () => ({ ...Object.fromEntries(PROJECT_FIELDS.map(([k]) => [k, project[k] ?? ''])), instruments: structuredClone(project.instruments) });
  const [f, setF] = useState(initial);
  const dirty = JSON.stringify(f) !== JSON.stringify(initial());
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const setInst = (which, k) => (v) => setF((x) => ({ ...x, instruments: { ...x.instruments, [which]: { ...x.instruments[which], [k]: v } } }));
  const save = async () => {
    const ok = await ctx.update((p) => {
      if (!f.name.trim()) throw new Error('El nombre del proyecto no puede quedar vacío.');
      for (const [k] of PROJECT_FIELDS) p[k] = typeof f[k] === 'string' ? f[k].trim() : f[k];
      p.instruments = f.instruments;
    });
    if (ok) { await ctx.refreshList(); toast('Datos del proyecto guardados.', 'ok'); }
  };
  const missing = PROJECT_FIELDS.filter(([k]) => !String(project[k] ?? '').trim()).map(([, l]) => l);
  return html`<div>
    <div class="card" data-testid="project-form">
      <header class="row between"><h3 style="margin:0">Datos del proyecto</h3>
        ${dirty ? html`<span class="badge warn">Cambios sin guardar</span>` : html`<span class="badge ok">Guardado</span>`}</header>
      ${missing.length ? html`<${Notice} kind="warn" title="Datos pendientes para el informe">Quedan señalados como pendientes en el PDF: ${missing.join(', ')}.<//>` : null}
      <div class="grid two">
        ${PROJECT_FIELDS.filter(([k]) => !LONG.has(k)).map(([k, l, h]) => html`<${Field} label=${l} hint=${h}>
          <${TextInput} type=${k === 'date' ? 'date' : 'text'} value=${f[k]} onInput=${set(k)} testid=${`pf-${k}`} /><//>`)}
      </div>
      <div class="stack" style="margin-top:.75rem">
        ${PROJECT_FIELDS.filter(([k]) => LONG.has(k)).map(([k, l, h]) => html`<${Field} label=${l} hint=${h}>
          <textarea data-testid=${`pf-${k}`} value=${f[k]} onInput=${(e) => set(k)(e.currentTarget.value)} /><//>`)}
      </div>
      <h4 style="margin-top:1rem">Instrumentos utilizados</h4>
      <div class="grid two">
        <fieldset><legend>Nivel de ingeniero</legend><div class="grid two">
          <${Field} label="Marca"><${TextInput} value=${f.instruments.level.brand} onInput=${setInst('level', 'brand')} testid="pf-level-brand" /><//>
          <${Field} label="Modelo"><${TextInput} value=${f.instruments.level.model} onInput=${setInst('level', 'model')} testid="pf-level-model" /><//>
          <${Field} label="N.° de serie"><${TextInput} value=${f.instruments.level.serial} onInput=${setInst('level', 'serial')} /><//>
          <${Field} label="Mira / observaciones"><${TextInput} value=${f.instruments.level.notes} onInput=${setInst('level', 'notes')} /><//>
        </div></fieldset>
        <fieldset><legend>Teodolito</legend><div class="grid two">
          <${Field} label="Marca"><${TextInput} value=${f.instruments.theodolite.brand} onInput=${setInst('theodolite', 'brand')} testid="pf-theo-brand" /><//>
          <${Field} label="Modelo"><${TextInput} value=${f.instruments.theodolite.model} onInput=${setInst('theodolite', 'model')} testid="pf-theo-model" /><//>
          <${Field} label="N.° de serie"><${TextInput} value=${f.instruments.theodolite.serial} onInput=${setInst('theodolite', 'serial')} /><//>
          <${Field} label="Precisión / observaciones" hint="Ejemplo: lectura al 20&quot;"><${TextInput} value=${f.instruments.theodolite.notes} onInput=${setInst('theodolite', 'notes')} /><//>
        </div></fieldset>
      </div>
      <div class="actions" style="margin-top:1rem">
        <${Btn} testid="pf-guardar" disabledReason=${dirty ? null : 'No hay cambios que guardar.'} showReason=${false} onClick=${save}>Guardar<//>
        ${dirty ? html`<${Btn} kind="secondary" onClick=${() => setF(initial())}>Descartar cambios<//>` : null}
      </div>
    </div>

    <div class="card">
      <h3>Archivo del proyecto</h3>
      <div class="actions">
        <${Btn} kind="secondary" testid="btn-exportar-json" onClick=${ctx.exportProjectFile}>Exportar proyecto (.json)<//>
        <${Btn} kind="secondary" onClick=${ctx.closeProject}>Cerrar proyecto<//>
        <${Btn} kind="danger" onClick=${() => ctx.deleteProject(project.id, project.name)}>Eliminar proyecto<//>
      </div>
      <p class="small muted">Creado el ${fmtDateTime(project.createdAt)} por ${project.createdBy || '—'} · última modificación ${fmtDateTime(project.updatedAt)}.</p>
    </div>

    ${project.legacy ? html`<${LegacyCard} legacy=${project.legacy} />` : null}
  </div>`;
}

function LegacyCard({ legacy }) {
  return html`<div class="card legacy" data-testid="legacy-card">
    <h3>Datos heredados de la versión Android (fuera del flujo simplificado)</h3>
    <${Notice} kind="info">
      Estos registros se importaron desde <code>${legacy.sourceFile ?? 'toposcribe.db'}</code> el ${fmtDateTime(legacy.importedAt)}.
      Corresponden a funciones o instrumentos que ya no forman parte del trabajo (estación total, GNSS/RTK, poligonales y
      compensación de Bowditch, nivelación trigonométrica, resultados calculados por la versión anterior).
      <strong>No se borraron:</strong> se conservan aquí, sin cambios, y se incluyen al exportar el proyecto (.json), pero
      no se usan en los cálculos, en el plano ni en el informe.
    <//>
    ${legacy.notes?.length ? html`<ul class="small">${legacy.notes.map((n) => html`<li>${n}</li>`)}</ul>` : null}
    ${Object.entries(legacy.tables ?? {}).filter(([, rows]) => rows.length).map(([name, rows]) => html`<details>
      <summary>${name} (${rows.length} registro${rows.length === 1 ? '' : 's'})</summary>
      <pre>${JSON.stringify(rows, null, 1)}</pre>
    </details>`)}
  </div>`;
}
