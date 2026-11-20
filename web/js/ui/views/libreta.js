import { html, useEffect, useState } from '../../../vendor/preact-htm.mjs';
import { Btn, Check, ErrorList, Field, Modal, Notice, Radios, Select, TextInput, confirmDialog, fmtDateTime, toast } from '../components.js';
import { INSTRUMENTS, POINT_TYPES, auditValue, levelingInput, newId, nowIso, pointByCode, radiationInput } from '../../core/model.js';
import { fmt, isNum, normCode, parseNumber } from '../../core/numbers.js';
import { computeLeveling, rowTypeLabel } from '../../core/leveling.js';
import { computeRadiation, DIRECTION_LABEL, ORIGIN_LABEL, conventionErrors } from '../../core/radiation.js';
import { formatAngle, parseAngle, UNITS } from '../../core/angles.js';
import * as M from '../mutations.js';
import { deletePhoto, putPhoto } from '../../storage/db.js';
import { forgetPhotoUrl, photoUrl, pickFiles, resizeImage } from '../files.js';

const SUBTABS = [['puntos', 'Puntos'], ['nivelacion', 'Nivelación (nivel)'], ['radiacion', 'Radiación (teodolito)'], ['correcciones', 'Correcciones']];

export function LibretaView({ ctx }) {
  const sub = SUBTABS.some(([s]) => s === ctx.route.sub) ? ctx.route.sub : 'puntos';
  const p = ctx.project;
  const counts = { puntos: p.points.length, nivelacion: p.leveling.rows.length, radiacion: p.theodolite.readings.length, correcciones: p.audit.filter((a) => a.action === 'editar' || a.action === 'eliminar').length };
  return html`<div>
    <div class="page-title"><h2>Libreta de campo</h2><span class="muted small">Datos originales de campo. Los cálculos se hacen en los módulos de cada instrumento.</span></div>
    <div class="subtabs" role="tablist">${SUBTABS.map(([id, label]) => html`<button role="tab" aria-selected=${sub === id} data-testid=${`sub-${id}`} onClick=${() => ctx.nav('libreta', id)}>${label} (${counts[id]})</button>`)}</div>
    ${sub === 'puntos' ? html`<${PointsTab} ctx=${ctx} />` : null}
    ${sub === 'nivelacion' ? html`<${LevelingTab} ctx=${ctx} />` : null}
    ${sub === 'radiacion' ? html`<${RadiationTab} ctx=${ctx} />` : null}
    ${sub === 'correcciones' ? html`<${AuditTab} ctx=${ctx} />` : null}
  </div>`;
}

// ======================================================================================= Utilidades
function num(text, label, { required = false, min = null, positive = false } = {}) {
  const r = parseNumber(text);
  if (r.empty) return required ? { error: `${label}: obligatorio.` } : { value: null };
  if (!r.ok) return { error: `${label}: ${r.error}` };
  if (positive && r.value <= 0) return { error: `${label}: debe ser mayor que cero.` };
  if (min !== null && r.value < min) return { error: `${label}: no puede ser negativo.` };
  return { value: r.value };
}
const str = (v) => (v === null || v === undefined ? '' : String(v));
const isEdited = (p, id) => p.audit.some((a) => a.entityId === id && a.action === 'editar');

function PointCodes({ id, project }) {
  return html`<datalist id=${id}>${project.points.map((pt) => html`<option value=${pt.code}>${pt.description}</option>`)}</datalist>`;
}

export function PhotoThumb({ photo, code }) {
  const [url, setUrl] = useState(null);
  useEffect(() => { let alive = true; photoUrl(photo?.id).then((u) => alive && setUrl(u)); return () => { alive = false; }; }, [photo?.id]);
  if (!photo) return html`<span class="muted small">Sin fotografía registrada</span>`;
  if (!url) return html`<span class="badge warn">Foto no encontrada en este dispositivo</span>`;
  return html`<img class="thumb" src=${url} alt=${`Fotografía de ${code}`} />`;
}

// ======================================================================================= Puntos
function PointsTab({ ctx }) {
  const p = ctx.project;
  const [edit, setEdit] = useState(null); // {point|null}
  const [photoFor, setPhotoFor] = useState(null);
  const del = async (pt) => {
    const r = await confirmDialog({ title: `Eliminar el punto ${pt.code}`, message: 'El punto se elimina de la libreta. Queda una copia en el registro de correcciones.', confirmText: 'Eliminar', danger: true, askReason: true });
    if (!r.ok) return;
    if (await ctx.update((d, by) => M.deletePoint(d, by, pt.id, r.reason))) {
      if (pt.photo?.id) { forgetPhotoUrl(pt.photo.id); await deletePhoto(pt.photo.id); }
      toast(`Punto ${pt.code} eliminado.`, 'ok');
    }
  };
  return html`<div>
    <div class="row between" style="margin-bottom:.6rem">
      <p class="small muted" style="margin:0">Cada punto tiene un código único. El mismo código une la posición del teodolito con la cota del nivel.</p>
      <${Btn} testid="btn-anadir-punto" onClick=${() => setEdit({ point: null })}>Añadir punto<//>
    </div>
    <div class="table-wrap"><table class="data cards" data-testid="points-table">
      <thead><tr><th>Código</th><th>Descripción</th><th>Tipo</th><th>Instrumento</th><th>Observaciones</th><th>Fotografía</th><th>Acciones</th></tr></thead>
      <tbody>
        ${p.points.length === 0 ? html`<tr><td colspan="7" class="empty">No hay puntos todavía. Pulse «Añadir punto», o regístrelos al añadir lecturas.</td></tr>` : null}
        ${p.points.map((pt) => html`<tr class=${isEdited(p, pt.id) ? 'edited' : ''} data-code=${pt.code}>
          <td data-label="Código"><strong>${pt.code}</strong></td>
          <td data-label="Descripción">${pt.description || '—'}</td>
          <td data-label="Tipo">${POINT_TYPES[pt.type] ?? pt.type}</td>
          <td data-label="Instrumento">${INSTRUMENTS[pt.instrument] ?? pt.instrument}</td>
          <td data-label="Observaciones">${pt.observations || '—'}${isNum(pt.knownElevation) ? html`<div class="small">Cota justificada: ${fmt(pt.knownElevation)} m (${pt.elevationJustification})</div>` : null}</td>
          <td data-label="Fotografía" data-testid="photo-cell"><${PhotoThumb} photo=${pt.photo} code=${pt.code} />${pt.photo?.caption ? html`<div class="small muted">${pt.photo.caption}</div>` : null}</td>
          <td class="actions-cell">
            <${Btn} small kind="secondary" onClick=${() => setEdit({ point: pt })}>Editar<//>
            <${Btn} small kind="secondary" testid=${`btn-foto-${pt.code}`} onClick=${() => setPhotoFor(pt)}>${pt.photo ? 'Ver o cambiar fotografía' : 'Agregar fotografía'}<//>
            <${Btn} small kind="danger" onClick=${() => del(pt)}>Eliminar<//>
          </td></tr>`)}
      </tbody></table></div>
    ${edit ? html`<${PointDialog} ctx=${ctx} point=${edit.point} onClose=${() => setEdit(null)} />` : null}
    ${photoFor ? html`<${PhotoDialog} ctx=${ctx} point=${p.points.find((x) => x.id === photoFor.id)} onClose=${() => setPhotoFor(null)} />` : null}
  </div>`;
}

function PointDialog({ ctx, point, onClose }) {
  const [f, setF] = useState({
    code: point?.code ?? '', description: point?.description ?? '', type: point?.type ?? 'detalle', instrument: point?.instrument ?? 'ambos',
    observations: point?.observations ?? '', knownElevation: str(point?.knownElevation), elevationJustification: point?.elevationJustification ?? '', reason: '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const dup = ctx.project.points.find((x) => normCode(x.code) === normCode(f.code) && x.id !== point?.id);
  const elev = num(f.knownElevation, 'Cota');
  const problems = [!f.code.trim() && 'Escriba el código del punto.', dup && `Ya existe el punto «${dup.code}».`, elev.error,
    elev.value !== null && !f.elevationJustification.trim() && 'Indique de dónde proviene la cota (justificación).'].filter(Boolean);
  const save = async () => {
    const ok = await ctx.update((p, by) => M.savePoint(p, by, point?.id ?? null, {
      code: f.code, description: f.description.trim(), type: f.type, instrument: f.instrument, observations: f.observations.trim(),
      knownElevation: elev.value, elevationJustification: elev.value === null ? '' : f.elevationJustification.trim(),
    }, f.reason));
    if (ok) { toast(`Punto ${f.code.trim()} guardado.`, 'ok'); onClose(); }
  };
  return html`<${Modal} title=${point ? `Editar punto ${point.code}` : 'Añadir punto'} onClose=${onClose} testid="point-dialog"
    footer=${html`<${Btn} kind="secondary" onClick=${onClose}>Cancelar<//><${Btn} testid="pt-guardar" disabledReason=${problems[0] ?? null} onClick=${save}>Guardar<//>`}>
    <div class="grid two">
      <${Field} label="Código *" error=${dup ? `Ya existe «${dup.code}»` : null} hint="Único en el proyecto. Ej.: BM-1, EST-1, P1"><${TextInput} value=${f.code} onInput=${set('code')} testid="pt-code" invalid=${!!dup} autofocus /><//>
      <${Field} label="Descripción"><${TextInput} value=${f.description} onInput=${set('description')} testid="pt-desc" /><//>
      <${Field} label="Tipo de punto"><${Select} value=${f.type} onChange=${set('type')} options=${Object.entries(POINT_TYPES)} testid="pt-type" /><//>
      <${Field} label="Instrumento"><${Select} value=${f.instrument} onChange=${set('instrument')} options=${Object.entries(INSTRUMENTS)} testid="pt-instrument" /><//>
    </div>
    <div class="stack" style="margin-top:.6rem">
      <${Field} label="Observaciones"><textarea value=${f.observations} onInput=${(e) => set('observations')(e.currentTarget.value)} data-testid="pt-obs" /><//>
      <fieldset><legend>Cota justificada (opcional)</legend>
        <p class="small muted">Solo si la cota de este punto NO se obtiene con el nivel en esta libreta (p. ej., un BM oficial). Debe indicar su origen.</p>
        <div class="grid two">
          <${Field} label="Cota" unit="m" error=${elev.error}><${TextInput} numeric value=${f.knownElevation} onInput=${set('knownElevation')} testid="pt-elev" /><//>
          <${Field} label="Justificación de la cota"><${TextInput} value=${f.elevationJustification} onInput=${set('elevationJustification')} testid="pt-elev-just" /><//>
        </div>
      </fieldset>
      ${point ? html`<${Field} label="Motivo de la corrección" hint="Opcional; queda en el registro de correcciones."><${TextInput} value=${f.reason} onInput=${set('reason')} /><//>` : null}
    </div>
    ${problems.length ? html`<${Notice} kind="warn">${problems.join(' ')}<//>` : null}
  <//>`;
}

function PhotoDialog({ ctx, point, onClose }) {
  const [caption, setCaption] = useState(point.photo?.caption ?? '');
  const [pending, setPending] = useState(null); // {blob, width, height, url, fileName}
  const [current, setCurrent] = useState(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { photoUrl(point.photo?.id).then(setCurrent); }, [point.photo?.id]);
  const pick = async (capture) => {
    const [file] = await pickFiles({ accept: 'image/*', capture: capture ? 'environment' : null });
    if (!file) return;
    setBusy(true);
    try {
      const r = await resizeImage(file, 2560, 0.9);
      setPending({ ...r, url: URL.createObjectURL(r.blob), fileName: file.name || 'foto.jpg' });
    } catch (e) {
      toast(e.message, 'err');
    } finally { setBusy(false); }
  };
  const save = async () => {
    setBusy(true);
    try {
      let meta = point.photo ? { ...point.photo, caption: caption.trim() } : null;
      let oldId = null;
      if (pending) {
        const id = newId();
        await putPhoto({ id, projectId: ctx.project.id, blob: pending.blob, mime: 'image/jpeg' });
        meta = { id, caption: caption.trim(), fileName: pending.fileName, mime: 'image/jpeg', width: pending.width, height: pending.height, addedAt: nowIso(), addedBy: ctx.user };
        oldId = point.photo?.id ?? null;
      }
      if (!meta) { toast('Primero tome o elija una imagen.', 'warn'); return; }
      const ok = await ctx.update((p, by) => { M.setPointPhoto(p, by, point.id, meta); });
      if (ok && oldId) { forgetPhotoUrl(oldId); await deletePhoto(oldId); }
      if (ok) { toast(`Fotografía de ${point.code} guardada.`, 'ok'); onClose(); }
    } finally { setBusy(false); }
  };
  const remove = async () => {
    const r = await confirmDialog({ title: `Retirar la fotografía de ${point.code}`, message: 'La imagen se elimina de este dispositivo. El cambio queda en el registro de correcciones.', confirmText: 'Retirar', danger: true });
    if (!r.ok) return;
    const oldId = point.photo.id;
    if (await ctx.update((p, by) => { M.setPointPhoto(p, by, point.id, null); })) { forgetPhotoUrl(oldId); await deletePhoto(oldId); toast('Fotografía retirada.', 'ok'); onClose(); }
  };
  const shown = pending?.url ?? current;
  return html`<${Modal} title=${`Fotografía del punto ${point.code}`} onClose=${onClose} testid="photo-dialog"
    footer=${html`${point.photo ? html`<${Btn} kind="danger" onClick=${remove}>Retirar fotografía<//>` : null}<span class="spacer"></span>
      <${Btn} kind="secondary" onClick=${onClose}>Cancelar<//>
      <${Btn} testid="photo-guardar" disabledReason=${busy ? 'Procesando la imagen…' : !pending && !point.photo ? 'Primero tome o elija una imagen.' : null} onClick=${save}>Guardar<//>`}>
    <p class="small muted">${point.description || 'Sin descripción'} · La imagen queda vinculada al código <strong>${point.code}</strong>.</p>
    ${shown ? html`<img class="photo-large" src=${shown} alt=${`Fotografía de ${point.code}`} data-testid="photo-preview" />` : html`<${Notice}>Sin fotografía registrada.<//>`}
    ${pending ? html`<p class="small">Nueva imagen: ${pending.width} × ${pending.height} px (se guarda a 2560 px como máximo, sin deformar).</p>` : null}
    <div class="actions" style="margin:.75rem 0">
      <${Btn} kind="secondary" testid="photo-camera" onClick=${() => pick(true)}>Tomar foto con la cámara<//>
      <${Btn} kind="secondary" testid="photo-file" onClick=${() => pick(false)}>${point.photo || pending ? 'Reemplazar con otra imagen' : 'Elegir imagen'}<//>
    </div>
    <${Field} label="Descripción o pie de foto"><${TextInput} value=${caption} onInput=${setCaption} testid="photo-caption" /><//>
  <//>`;
}

// ======================================================================================= Nivelación
function LevelingTab({ ctx }) {
  const p = ctx.project;
  const L = p.leveling;
  const [dlg, setDlg] = useState(null); // {row|null, insertAt}
  const cur = computeLeveling(levelingInput(p));
  const byId = new Map(cur.rows.map((r) => [r.id, r]));
  const errIds = new Set(cur.errors.map((e) => e.rowId));
  const bmReady = L.bm.code && isNum(L.bm.elevation);
  const del = async (row, i) => {
    const r = await confirmDialog({ title: `Eliminar la lectura n.° ${i + 1} (${row.code})`, message: 'La lectura se elimina de la libreta. Queda una copia con sus valores en el registro de correcciones. Las cotas calculadas deberán actualizarse.', confirmText: 'Eliminar', danger: true, askReason: true });
    if (r.ok && await ctx.update((d, by) => M.deleteLevelRow(d, by, row.id, r.reason))) toast('Lectura eliminada.', 'ok');
  };
  return html`<div>
    <${BmCard} ctx=${ctx} />
    <div class="card">
      <header class="row between"><h3 style="margin:0">Libreta de nivelación geométrica</h3>
        <${Btn} testid="btn-anadir-lectura-nivel" disabledReason=${bmReady ? null : 'Primero registre el BM inicial y su cota conocida (arriba) y pulse Guardar.'} onClick=${() => setDlg({ row: null, insertAt: null })}>Añadir lectura<//></header>
      <p class="small muted">Una fila por punto observado, en el orden de campo. Un punto de cambio lleva vista adelante (estación que termina) y vista atrás (estación nueva). La estación se numera sola. Unidades: metros.</p>
      <div class="table-wrap"><table class="data cards" data-testid="level-table">
        <thead><tr><th>N.°</th><th>Est.</th><th>Punto</th><th>Tipo</th><th class="num">VA <span class="unit-h">(m)</span></th><th class="num">VI <span class="unit-h">(m)</span></th><th class="num">VD <span class="unit-h">(m)</span></th><th class="num">Dist. parcial <span class="unit-h">(m)</span></th><th class="num">Progresiva <span class="unit-h">(m)</span></th><th>Perfil</th><th>Obs.</th><th>Acciones</th></tr></thead>
        <tbody>
          ${L.rows.length === 0 ? html`<tr><td colspan="12" class="empty">Sin lecturas. La primera es la vista atrás sobre el BM inicial.</td></tr>` : null}
          ${L.rows.map((row, i) => {
            const c = byId.get(row.id);
            return html`<tr class=${errIds.has(row.id) ? 'row-error' : isEdited(p, row.id) ? 'edited' : ''}>
              <td data-label="N.°">${i + 1}${isEdited(p, row.id) ? html` <span class="badge warn" title="Corregida: ver Correcciones">corr.</span>` : null}</td>
              <td data-label="Estación">${c?.station ?? '—'}</td>
              <td data-label="Punto"><strong>${row.code}</strong></td>
              <td data-label="Tipo">${c?.type ? rowTypeLabel(c.type) : '—'}</td>
              <td data-label="VA (m)" class="num">${isNum(row.bs) ? fmt(row.bs) : ''}</td>
              <td data-label="VI (m)" class="num">${isNum(row.is) ? fmt(row.is) : ''}</td>
              <td data-label="VD (m)" class="num">${isNum(row.fs) ? fmt(row.fs) : ''}</td>
              <td data-label="Dist. parcial (m)" class="num">${isNum(row.partial) ? fmt(row.partial) : ''}</td>
              <td data-label="Progresiva (m)" class="num">${isNum(row.cumulative) ? fmt(row.cumulative) : ''}</td>
              <td data-label="En perfil">${row.inProfile === false ? 'no' : 'sí'}</td>
              <td data-label="Observaciones">${row.notes || ''}</td>
              <td class="actions-cell">
                <${Btn} small kind="secondary" onClick=${() => setDlg({ row, insertAt: null })}>Editar<//>
                <${Btn} small kind="ghost" disabledReason=${i === 0 ? 'La primera fila debe ser el BM inicial.' : null} showReason=${false} onClick=${() => setDlg({ row: null, insertAt: i })}>Insertar antes<//>
                <${Btn} small kind="danger" onClick=${() => del(row, i)}>Eliminar<//>
              </td></tr>`;
          })}
        </tbody></table></div>
      <${ErrorList} title="Errores en la libreta de nivelación" errors=${L.rows.length ? cur.errors : []} testid="level-errors" />
      ${L.rows.length && cur.computable ? html`<${Notice} kind="ok">La libreta no tiene errores. Calcule las cotas en <a href="#/nivel">Nivel de ingeniero</a>.<//>` : null}
    </div>
    ${dlg ? html`<${LevelReadingDialog} ctx=${ctx} row=${dlg.row} insertAt=${dlg.insertAt} onClose=${() => setDlg(null)} />` : null}
  </div>`;
}

function BmCard({ ctx }) {
  const L = ctx.project.leveling;
  const init = () => ({ code: L.bm.code, elevation: str(L.bm.elevation), endEnabled: !!L.endBm.enabled, endCode: L.endBm.code, endElevation: str(L.endBm.elevation) });
  const [f, setF] = useState(init);
  useEffect(() => setF(init()), [ctx.project.updatedAt]);
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const el = num(f.elevation, 'Cota del BM inicial', { required: true });
  const eel = f.endEnabled ? num(f.endElevation, 'Cota del BM final', { required: true }) : { value: null };
  const problems = [!f.code.trim() && 'Escriba el código del BM inicial.', el.error, f.endEnabled && !f.endCode.trim() && 'Escriba el código del BM final.', eel.error].filter(Boolean);
  const dirty = JSON.stringify(f) !== JSON.stringify(init());
  const save = async () => {
    if (await ctx.update((p, by) => M.saveBm(p, by, { code: f.code.trim(), elevation: el.value }, { enabled: f.endEnabled, code: f.endEnabled ? f.endCode.trim() : '', elevation: f.endEnabled ? eel.value : null }))) toast('BM guardado.', 'ok');
  };
  return html`<div class="card" data-testid="bm-card">
    <header class="row between"><h3 style="margin:0">BM o punto inicial</h3>${dirty ? html`<span class="badge warn">Sin guardar</span>` : null}</header>
    <div class="grid three">
      <${Field} label="Código del BM inicial *"><${TextInput} value=${f.code} onInput=${set('code')} testid="bm-code" list="codes-bm" /><//>
      <${Field} label="Cota conocida *" unit="m" error=${f.elevation ? el.error : null}><${TextInput} numeric value=${f.elevation} onInput=${set('elevation')} testid="bm-elev" /><//>
    </div>
    <div style="margin-top:.6rem"><${Check} checked=${f.endEnabled} onChange=${set('endEnabled')} label="Existe un BM final de cota conocida (para calcular el error de cierre)" testid="bm-end-enabled" /></div>
    ${f.endEnabled ? html`<div class="grid three" style="margin-top:.4rem">
      <${Field} label="Código del BM final"><${TextInput} value=${f.endCode} onInput=${set('endCode')} testid="bm-end-code" list="codes-bm" /><//>
      <${Field} label="Cota conocida del BM final" unit="m" error=${f.endElevation ? eel.error : null}><${TextInput} numeric value=${f.endElevation} onInput=${set('endElevation')} testid="bm-end-elev" /><//>
      <div style="align-self:end"><${Btn} small kind="ghost" onClick=${() => setF((x) => ({ ...x, endCode: x.code, endElevation: x.elevation }))}>La nivelación cierra en el BM inicial<//></div>
    </div>` : html`<p class="small muted">Sin BM final no puede comprobarse el cierre contra un control externo (solo la comprobación aritmética).</p>`}
    <${PointCodes} id="codes-bm" project=${ctx.project} />
    <div class="actions" style="margin-top:.6rem"><${Btn} testid="bm-guardar" disabledReason=${problems[0] ?? (dirty ? null : 'No hay cambios que guardar.')} onClick=${save}>Guardar<//></div>
  </div>`;
}

function LevelReadingDialog({ ctx, row, insertAt, onClose }) {
  const p = ctx.project;
  const L = p.leveling;
  const index = row ? L.rows.findIndex((r) => r.id === row.id) : (insertAt ?? L.rows.length);
  const isFirst = index === 0;
  const initialKind = row ? (isFirst ? 'BM' : isNum(row.is) ? 'VI' : isNum(row.bs) && isNum(row.fs) ? 'PC' : 'VD') : isFirst ? 'BM' : 'VI';
  const [f, setF] = useState({
    code: row?.code ?? (isFirst ? L.bm.code : ''), desc: '', kind: initialKind, bs: str(row?.bs), is: str(row?.is), fs: str(row?.fs),
    partial: str(row?.partial), cumulative: str(row?.cumulative), inProfile: row ? row.inProfile !== false : !isFirst, notes: row?.notes ?? '', reason: '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const kinds = isFirst ? [['BM', 'BM inicial (vista atrás)']] : [['VI', 'Vista intermedia'], ['VD', 'Vista adelante'], ['PC', 'Punto de cambio (VD + VA)']];
  const need = { BM: ['bs'], VI: ['is'], VD: ['fs'], PC: ['fs', 'bs'] }[f.kind];
  const parsed = {
    bs: need.includes('bs') ? num(f.bs, 'Vista atrás', { required: true, min: 0 }) : { value: null },
    is: need.includes('is') ? num(f.is, 'Vista intermedia', { required: true, min: 0 }) : { value: null },
    fs: need.includes('fs') ? num(f.fs, 'Vista adelante', { required: true, min: 0 }) : { value: null },
    partial: num(f.partial, 'Distancia parcial', { min: 0 }),
    cumulative: num(f.cumulative, 'Progresiva', { min: 0 }),
  };
  const fieldErr = Object.values(parsed).map((x) => x.error).filter(Boolean);
  const code = f.code.trim();
  const exists = !!pointByCode(p, code);
  const problems = [!code && 'Escriba el código del punto.', isFirst && normCode(code) !== normCode(L.bm.code) && `La primera lectura es sobre el BM «${L.bm.code}».`, ...fieldErr].filter(Boolean);

  // Vista previa del cálculo de esta fila con los datos del formulario
  let preview = null;
  if (!problems.length) {
    const input = levelingInput(p);
    const draft = { id: row?.id ?? '__nueva__', code, bs: parsed.bs.value, is: parsed.is.value, fs: parsed.fs.value, partial: parsed.partial.value, cumulative: parsed.cumulative.value, inProfile: f.inProfile };
    if (row) input.rows[index] = draft; else input.rows.splice(index, 0, draft);
    const res = computeLeveling(input);
    const mine = res.rows.find((r) => r.id === draft.id);
    const myErrors = res.errors.filter((e) => e.rowId === draft.id);
    preview = { steps: mine?.steps ?? [], errors: myErrors, other: res.errors.length - myErrors.length, mine };
  }
  const save = async () => {
    const ok = await ctx.update((d, by) => M.saveLevelRow(d, by, row?.id ?? null, {
      code, bs: parsed.bs.value, is: parsed.is.value, fs: parsed.fs.value, partial: parsed.partial.value, cumulative: parsed.cumulative.value, inProfile: f.inProfile, notes: f.notes.trim(),
    }, row ? null : index, f.reason, f.desc.trim()));
    if (ok) { toast(row ? 'Lectura corregida. Los resultados que la usan quedaron marcados para actualizar.' : 'Lectura añadida.', 'ok', 5000); onClose(); }
  };
  return html`<${Modal} title=${row ? `Editar lectura de nivel n.° ${index + 1}` : `Añadir lectura de nivel (n.° ${index + 1})`} onClose=${onClose} testid="level-dialog"
    footer=${html`<${Btn} kind="secondary" onClick=${onClose}>Cancelar<//><${Btn} testid="lv-guardar" disabledReason=${problems[0] ?? null} onClick=${save}>Guardar<//>`}>
    ${row ? html`<${Notice} kind="info">El valor original queda guardado en el registro de correcciones con su nombre y la fecha.<//>` : null}
    <div class="grid two">
      <${Field} label="Punto (código) *" hint=${code && !exists ? `El punto «${code}» no existe: se creará al guardar.` : 'Elija uno existente o escriba uno nuevo'}>
        <${TextInput} value=${f.code} onInput=${set('code')} testid="lv-code" list="codes-lv" autofocus=${!isFirst} /><//>
      ${code && !exists ? html`<${Field} label="Descripción del punto nuevo"><${TextInput} value=${f.desc} onInput=${set('desc')} testid="lv-desc" /><//>` : null}
    </div>
    <${PointCodes} id="codes-lv" project=${p} />
    <div style="margin:.7rem 0"><${Radios} name="kind" value=${f.kind} onChange=${set('kind')} options=${kinds} testid="lv-kind" /></div>
    <div class="grid three">
      ${need.includes('bs') ? html`<${Field} label="Vista atrás (VA)" unit="m" error=${f.bs ? parsed.bs.error : null}><${TextInput} numeric value=${f.bs} onInput=${set('bs')} testid="lv-bs" autofocus=${isFirst} /><//>` : null}
      ${need.includes('is') ? html`<${Field} label="Vista intermedia (VI)" unit="m" error=${f.is ? parsed.is.error : null}><${TextInput} numeric value=${f.is} onInput=${set('is')} testid="lv-is" /><//>` : null}
      ${need.includes('fs') ? html`<${Field} label="Vista adelante (VD)" unit="m" error=${f.fs ? parsed.fs.error : null}><${TextInput} numeric value=${f.fs} onInput=${set('fs')} testid="lv-fs" /><//>` : null}
    </div>
    <fieldset style="margin-top:.7rem"><legend>Recorrido del perfil</legend>
      <${Check} checked=${f.inProfile} onChange=${set('inProfile')} label="Este punto forma parte del perfil longitudinal" testid="lv-inprofile" />
      ${f.inProfile ? html`<div class="grid two" style="margin-top:.4rem">
        <${Field} label="Distancia parcial (desde el punto anterior del perfil)" unit="m" error=${parsed.partial.error}><${TextInput} numeric value=${f.partial} onInput=${set('partial')} testid="lv-partial" /><//>
        <${Field} label="Progresiva / distancia acumulada" unit="m" error=${parsed.cumulative.error} hint="Ingrese una de las dos; la otra se calcula."><${TextInput} numeric value=${f.cumulative} onInput=${set('cumulative')} testid="lv-cumulative" /><//>
      </div>` : html`<p class="small muted">No se usará en el perfil (por ejemplo, un BM o un punto de cambio fuera del eje).</p>`}
    </fieldset>
    <div class="stack" style="margin-top:.7rem">
      <${Field} label="Observaciones"><${TextInput} value=${f.notes} onInput=${set('notes')} testid="lv-notes" /><//>
      ${row ? html`<${Field} label="Motivo de la corrección" hint="Opcional"><${TextInput} value=${f.reason} onInput=${set('reason')} testid="lv-reason" /><//>` : null}
    </div>
    ${problems.length ? html`<${Notice} kind="warn">${problems.join(' ')}<//>` : null}
    ${preview ? html`<div style="margin-top:.6rem" data-testid="lv-preview">
      ${preview.errors.length ? html`<${ErrorList} title="Con esta lectura la libreta tendría errores" errors=${preview.errors} />`
        : preview.steps.length ? html`<div class="steps">${preview.steps.map((s) => html`<div>${s}</div>`)}</div>`
          : html`<p class="small muted">La cota se calculará cuando la libreta no tenga errores${preview.other ? ` (hay ${preview.other} error(es) en otras filas)` : ''}.</p>`}
    </div>` : null}
  <//>`;
}

// ======================================================================================= Radiación
function RadiationTab({ ctx }) {
  const p = ctx.project;
  const T = p.theodolite;
  const [stDlg, setStDlg] = useState(null);
  const [rdDlg, setRdDlg] = useState(null);
  const convOk = conventionErrors(T.convention).length === 0;
  const cur = computeRadiation(radiationInput(p));
  const errIds = new Set(cur.errors.map((e) => e.readingId).filter(Boolean));
  const stationById = new Map(T.stations.map((s) => [s.id, s]));
  const U = T.convention.unit || 'sexagesimal';
  const delSt = async (st) => {
    const r = await confirmDialog({ title: `Eliminar la estación ${st.code}`, message: 'Se elimina la estación y su orientación. Queda una copia en el registro de correcciones.', confirmText: 'Eliminar', danger: true, askReason: true });
    if (r.ok && await ctx.update((d, by) => M.deleteStation(d, by, st.id, r.reason))) toast('Estación eliminada.', 'ok');
  };
  const delRd = async (rd, i) => {
    const r = await confirmDialog({ title: `Eliminar la lectura n.° ${i + 1} (${rd.code})`, message: 'Queda una copia en el registro de correcciones. Las coordenadas calculadas deberán actualizarse.', confirmText: 'Eliminar', danger: true, askReason: true });
    if (r.ok && await ctx.update((d, by) => M.deleteRadReading(d, by, rd.id, r.reason))) toast('Lectura eliminada.', 'ok');
  };
  return html`<div>
    <${ConventionCard} ctx=${ctx} />
    <div class="card" data-testid="stations-card">
      <header class="row between"><h3 style="margin:0">Estación ocupada y orientación</h3>
        <${Btn} testid="btn-anadir-estacion" kind=${T.stations.length ? 'secondary' : 'primary'} disabledReason=${convOk ? null : 'Primero defina y guarde el convenio angular (arriba).'} onClick=${() => setStDlg({ station: null })}>${T.stations.length ? 'Añadir otra estación' : 'Registrar estación'}<//></header>
      ${T.stations.length === 0 ? html`<p class="muted">No hay estación registrada. El método de radiación necesita una estación de coordenadas conocidas o locales declaradas, y una orientación.</p>` : null}
      ${T.stations.map((s) => html`<div class="notice info" data-testid="station-item">
        <div class="row between"><strong>Estación ${s.code}</strong>
          <div class="actions"><${Btn} small kind="secondary" onClick=${() => setStDlg({ station: s })}>Editar<//><${Btn} small kind="danger" onClick=${() => delSt(s)}>Eliminar<//></div></div>
        <div class="small">E = ${fmt(s.east)} m · N = ${fmt(s.north)} m (${s.coordType === 'conocidas' ? 'coordenadas conocidas' : 'coordenadas locales declaradas'})${isNum(s.instrumentHeight) ? ` · altura del instrumento ${fmt(s.instrumentHeight)} m` : ''}</div>
        <div class="small">${T.convention.origin === 'norte' ? `Orientación: círculo con 0 al norte — ${s.orientation?.northNote || 'sin descripción'}`
          : `Vista atrás a ${s.orientation?.refCode || '—'} · azimut ${s.orientation?.azMode === 'coordenadas' ? `por coordenadas (E ${fmt(s.orientation.refEast)}, N ${fmt(s.orientation.refNorth)})` : s.orientation?.refAzimuth || '—'} · lectura en la referencia L_ref = ${s.orientation?.refReading || '—'}`}</div>
      </div>`)}
    </div>
    <div class="card">
      <header class="row between"><h3 style="margin:0">Puntos radiados</h3>
        <${Btn} testid="btn-anadir-lectura-teodolito" disabledReason=${!convOk ? 'Primero defina el convenio angular.' : T.stations.length === 0 ? 'Primero registre la estación ocupada y su orientación.' : null} onClick=${() => setRdDlg({ reading: null })}>Añadir lectura<//></header>
      <div class="table-wrap"><table class="data cards" data-testid="rad-table">
        <thead><tr><th>N.°</th><th>Estación</th><th>Punto</th><th>Lectura horizontal</th><th>Distancia</th><th class="num">Dist. <span class="unit-h">(m)</span></th><th>Ángulo vertical</th><th>Descripción</th><th>Acciones</th></tr></thead>
        <tbody>
          ${T.readings.length === 0 ? html`<tr><td colspan="9" class="empty">Sin lecturas de puntos radiados.</td></tr>` : null}
          ${T.readings.map((rd, i) => html`<tr class=${errIds.has(rd.id) ? 'row-error' : isEdited(p, rd.id) ? 'edited' : ''}>
            <td data-label="N.°">${i + 1}${isEdited(p, rd.id) ? html` <span class="badge warn">corr.</span>` : null}</td>
            <td data-label="Estación">${stationById.get(rd.stationId)?.code ?? html`<span class="badge err">no existe</span>`}</td>
            <td data-label="Punto"><strong>${rd.code}</strong></td>
            <td data-label="Lectura horizontal" class="mono">${rd.hz}</td>
            <td data-label="Distancia">${rd.distType}</td>
            <td data-label="Dist. (m)" class="num">${isNum(rd.distance) ? fmt(rd.distance) : ''}</td>
            <td data-label="Ángulo vertical" class="mono">${rd.distType === 'inclinada' ? `${rd.vType === 'cenital' ? 'Z' : 'α'} = ${rd.vAngle}` : '—'}</td>
            <td data-label="Descripción">${pointByCode(p, rd.code)?.description || ''}</td>
            <td class="actions-cell"><${Btn} small kind="secondary" onClick=${() => setRdDlg({ reading: rd })}>Editar<//><${Btn} small kind="danger" onClick=${() => delRd(rd, i)}>Eliminar<//></td>
          </tr>`)}
        </tbody></table></div>
      <p class="small muted">Ángulos en ${UNITS[U]?.label ?? 'la unidad del convenio'}.</p>
      <${ErrorList} title="Errores en la libreta del teodolito" errors=${T.stations.length || T.readings.length ? cur.errors : []} testid="rad-errors" />
      ${T.readings.length && cur.computable ? html`<${Notice} kind="ok">Sin errores. Calcule las coordenadas en <a href="#/teodolito">Teodolito</a>.<//>` : null}
    </div>
    ${stDlg ? html`<${StationDialog} ctx=${ctx} station=${stDlg.station} onClose=${() => setStDlg(null)} />` : null}
    ${rdDlg ? html`<${RadReadingDialog} ctx=${ctx} reading=${rdDlg.reading} onClose=${() => setRdDlg(null)} />` : null}
  </div>`;
}

function ConventionCard({ ctx }) {
  const T = ctx.project.theodolite;
  const [f, setF] = useState({ ...T.convention });
  useEffect(() => setF({ ...T.convention }), [ctx.project.updatedAt]);
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const errs = conventionErrors(f);
  const dirty = JSON.stringify(f) !== JSON.stringify(T.convention);
  const save = async () => {
    if (T.readings.length && T.convention.unit && f.unit !== T.convention.unit) {
      const r = await confirmDialog({ title: 'Cambiar la unidad angular', message: 'Las lecturas ya registradas se interpretarán en la nueva unidad (no se convierten). Úselo solo si la unidad estaba mal declarada.', confirmText: 'Cambiar unidad', danger: true });
      if (!r.ok) return;
    }
    if (await ctx.update((p, by) => M.saveConvention(p, by, f))) toast('Convenio angular guardado.', 'ok');
  };
  return html`<div class="card" data-testid="convention-card">
    <header class="row between"><h3 style="margin:0">Convenio angular</h3>${dirty ? html`<span class="badge warn">Sin guardar</span>` : errs.length ? html`<span class="badge err">Incompleto</span>` : html`<span class="badge ok">Definido</span>`}</header>
    <div class="grid three">
      <${Field} label="Unidad"><${Select} value=${f.unit} onChange=${set('unit')} placeholder="— elegir —" options=${Object.entries(UNITS).map(([k, v]) => [k, v.label])} testid="conv-unit" /><//>
      <${Field} label="Sentido de lectura del círculo horizontal"><${Select} value=${f.direction} onChange=${set('direction')} placeholder="— elegir —" options=${Object.entries(DIRECTION_LABEL)} testid="conv-direction" /><//>
      <${Field} label="Origen de los ángulos"><${Select} value=${f.origin} onChange=${set('origin')} placeholder="— elegir —" options=${Object.entries(ORIGIN_LABEL)} testid="conv-origin" /><//>
    </div>
    <p class="small muted">Azimut medido desde el norte en sentido horario. Con lecturas horarias: Az = Az_ref + (L − L_ref); antihorarias: Az = Az_ref − (L − L_ref); el resultado se normaliza entre 0° y 360°.</p>
    <div class="actions"><${Btn} testid="conv-guardar" disabledReason=${errs[0] ?? (dirty ? null : 'No hay cambios que guardar.')} onClick=${save}>Guardar<//></div>
  </div>`;
}

function AngleField({ label, value, onInput, unit, testid, required = false, hint }) {
  const r = parseAngle(value, unit);
  const ok = r.ok;
  return html`<${Field} label=${label} unit=${unit === 'centesimal' ? 'g' : '° \' "'} error=${!r.empty && !ok ? r.error : required && r.empty ? null : null}
    hint=${ok ? html`<span class="parsed">= ${formatAngle(r.deg, unit)}${unit === 'centesimal' ? '' : ` (${r.deg.toFixed(6)}°)`}</span>` : hint ?? (unit === 'centesimal' ? 'Ej.: 150.2500' : 'Ej.: 45°30\'15" o 45 30 15')}>
    <${TextInput} value=${value} onInput=${onInput} testid=${testid} invalid=${!r.empty && !ok} /><//>`;
}

function StationDialog({ ctx, station, onClose }) {
  const T = ctx.project.theodolite;
  const U = T.convention.unit;
  const o = station?.orientation ?? {};
  const [f, setF] = useState({
    code: station?.code ?? '', east: str(station?.east), north: str(station?.north), coordType: station?.coordType ?? '', instrumentHeight: str(station?.instrumentHeight),
    refCode: o.refCode ?? '', azMode: o.azMode ?? 'ingresado', refAzimuth: o.refAzimuth ?? '', azSource: o.azSource ?? '', refEast: str(o.refEast), refNorth: str(o.refNorth), refReading: o.refReading ?? '', northNote: o.northNote ?? '', reason: '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const E = num(f.east, 'Este', { required: true });
  const N = num(f.north, 'Norte', { required: true });
  const hi = num(f.instrumentHeight, 'Altura del instrumento', { min: 0 });
  const rE = f.azMode === 'coordenadas' ? num(f.refEast, 'Este de la referencia', { required: true }) : { value: null };
  const rN = f.azMode === 'coordenadas' ? num(f.refNorth, 'Norte de la referencia', { required: true }) : { value: null };
  const orientation = T.convention.origin === 'norte' ? { northNote: f.northNote.trim() }
    : { refCode: f.refCode.trim(), azMode: f.azMode, refAzimuth: f.azMode === 'ingresado' ? f.refAzimuth.trim() : '', azSource: f.azMode === 'ingresado' ? f.azSource.trim() : '', refEast: rE.value, refNorth: rN.value, refReading: f.refReading.trim() };
  const problems = [!f.code.trim() && 'Escriba el código de la estación.', E.error, N.error, !f.coordType && 'Indique si las coordenadas son conocidas o locales declaradas.', hi.error, rE.error, rN.error].filter(Boolean);
  // Vista previa de la orientación
  let check = null;
  if (!problems.length) {
    const res = computeRadiation({ convention: T.convention, stations: [{ id: 'x', code: f.code.trim(), east: E.value, north: N.value, coordType: f.coordType, orientation }], readings: [] });
    check = res;
  }
  const save = async () => {
    const ok = await ctx.update((p, by) => M.saveStation(p, by, station?.id ?? null, { code: f.code, east: E.value, north: N.value, coordType: f.coordType, instrumentHeight: hi.value, orientation }, f.reason));
    if (ok) { toast('Estación guardada.', 'ok'); onClose(); }
  };
  return html`<${Modal} title=${station ? `Editar estación ${station.code}` : 'Registrar estación ocupada'} onClose=${onClose} testid="station-dialog"
    footer=${html`<${Btn} kind="secondary" onClick=${onClose}>Cancelar<//><${Btn} testid="st-guardar" disabledReason=${problems[0] ?? null} onClick=${save}>Guardar<//>`}>
    <div class="grid two">
      <${Field} label="Código de la estación *"><${TextInput} value=${f.code} onInput=${set('code')} testid="st-code" autofocus /><//>
      <${Field} label="Coordenadas *"><${Select} value=${f.coordType} onChange=${set('coordType')} placeholder="— elegir —" options=${[['conocidas', 'Conocidas (de un punto de control)'], ['locales', 'Locales declaradas (arbitrarias)']]} testid="st-coordtype" /><//>
      <${Field} label="Este (E) *" unit="m" error=${f.east ? E.error : null}><${TextInput} numeric value=${f.east} onInput=${set('east')} testid="st-east" /><//>
      <${Field} label="Norte (N) *" unit="m" error=${f.north ? N.error : null}><${TextInput} numeric value=${f.north} onInput=${set('north')} testid="st-north" /><//>
      <${Field} label="Altura del instrumento (opcional)" unit="m" hint="No interviene en la planimetría."><${TextInput} numeric value=${f.instrumentHeight} onInput=${set('instrumentHeight')} /><//>
    </div>
    <fieldset style="margin-top:.8rem"><legend>Orientación</legend>
      ${T.convention.origin === 'norte' ? html`
        <p class="small">Convenio: el círculo horizontal se orientó con lectura 0 hacia el norte; la lectura de cada punto es su azimut.</p>
        <${Field} label="¿Cómo se orientó al norte? *" hint="Ej.: brújula del teodolito (norte magnético)"><${TextInput} value=${f.northNote} onInput=${set('northNote')} testid="st-northnote" /><//>`
      : html`<div class="grid two">
          <${Field} label="Punto de referencia (vista atrás) *"><${TextInput} value=${f.refCode} onInput=${set('refCode')} testid="st-refcode" list="codes-st" /><//>
          <${Field} label="Azimut de la referencia"><${Select} value=${f.azMode} onChange=${set('azMode')} options=${[['ingresado', 'Lo ingreso (conocido o medido)'], ['coordenadas', 'Calcularlo con las coordenadas de la referencia']]} testid="st-azmode" /><//>
        </div>
        ${f.azMode === 'ingresado' ? html`<div class="grid two" style="margin-top:.4rem">
          <${AngleField} label=${`Azimut ${f.code || 'EST'} → ${f.refCode || 'referencia'} *`} value=${f.refAzimuth} onInput=${set('refAzimuth')} unit=${U} testid="st-refaz" />
          <${Field} label="Fuente del azimut" hint="Ej.: brújula, dato del docente, plano anterior"><${TextInput} value=${f.azSource} onInput=${set('azSource')} testid="st-azsource" /><//>
        </div>` : html`<div class="grid two" style="margin-top:.4rem">
          <${Field} label="Este de la referencia *" unit="m" error=${f.refEast ? rE.error : null}><${TextInput} numeric value=${f.refEast} onInput=${set('refEast')} testid="st-refeast" /><//>
          <${Field} label="Norte de la referencia *" unit="m" error=${f.refNorth ? rN.error : null}><${TextInput} numeric value=${f.refNorth} onInput=${set('refNorth')} testid="st-refnorth" /><//>
        </div>`}
        <div class="grid two" style="margin-top:.4rem"><${AngleField} label="Lectura horizontal en la referencia (L_ref) *" value=${f.refReading} onInput=${set('refReading')} unit=${U} testid="st-refreading" hint="Lectura del círculo al visar la referencia (suele ser 0°00'00&quot;)" /></div>`}
    </fieldset>
    <${PointCodes} id="codes-st" project=${ctx.project} />
    ${station ? html`<div style="margin-top:.6rem"><${Field} label="Motivo de la corrección" hint="Opcional"><${TextInput} value=${f.reason} onInput=${set('reason')} /><//></div>` : null}
    ${problems.length ? html`<${Notice} kind="warn">${problems.join(' ')}<//>` : null}
    ${check ? (check.errors.length ? html`<${ErrorList} title="La orientación todavía está incompleta" errors=${check.errors} />`
      : html`<div class="steps" data-testid="st-preview">${check.stations[0].steps.map((s) => html`<div>${s}</div>`)}</div>`) : null}
  <//>`;
}

function RadReadingDialog({ ctx, reading, onClose }) {
  const p = ctx.project;
  const T = p.theodolite;
  const U = T.convention.unit;
  const index = reading ? T.readings.findIndex((r) => r.id === reading.id) : T.readings.length;
  const [f, setF] = useState({
    stationId: reading?.stationId ?? T.stations[T.stations.length - 1]?.id ?? '', code: reading?.code ?? '', desc: '', hz: reading?.hz ?? '', distType: reading?.distType ?? 'horizontal',
    distance: str(reading?.distance), vType: reading?.vType ?? 'cenital', vAngle: reading?.vAngle ?? '', notes: reading?.notes ?? '', reason: '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const D = num(f.distance, 'Distancia', { required: true, positive: true });
  const code = f.code.trim();
  const exists = !!pointByCode(p, code);
  const dupReading = T.readings.find((r) => normCode(r.code) === normCode(code) && r.id !== reading?.id);
  const problems = [!f.stationId && 'Elija la estación.', !code && 'Escriba el código del punto radiado.', dupReading && `El punto ${dupReading.code} ya fue radiado.`, !f.hz.trim() && 'Escriba la lectura horizontal.', f.distance ? D.error : 'Escriba la distancia.', f.distType === 'inclinada' && !f.vAngle.trim() && 'La distancia es inclinada: escriba el ángulo vertical para reducirla.'].filter(Boolean);
  let preview = null;
  if (!problems.length) {
    const input = radiationInput(p);
    const draft = { id: reading?.id ?? '__nueva__', stationId: f.stationId, code, hz: f.hz, distType: f.distType, distance: D.value, vType: f.vType, vAngle: f.vAngle };
    if (reading) input.readings[index] = draft; else input.readings.push(draft);
    const res = computeRadiation(input);
    const mine = res.points.find((x) => x.readingId === draft.id);
    const myErr = res.errors.filter((e) => e.readingId === draft.id);
    const stErr = res.errors.filter((e) => e.stationId === f.stationId && !e.readingId);
    // los pasos se calculan aunque otras lecturas tengan errores
    preview = { myErr, stErr, mine };
  }
  const save = async () => {
    const ok = await ctx.update((d, by) => M.saveRadReading(d, by, reading?.id ?? null, { stationId: f.stationId, code, hz: f.hz, distType: f.distType, distance: D.value, vType: f.vType, vAngle: f.vAngle, notes: f.notes.trim() }, f.reason, f.desc.trim()));
    if (ok) { toast(reading ? 'Lectura corregida. Los resultados que la usan quedaron marcados para actualizar.' : 'Lectura añadida.', 'ok', 5000); onClose(); }
  };
  const steps = preview?.mine ? [...preview.mine.azSteps, ...preview.mine.dhSteps, ...preview.mine.coordSteps] : [];
  return html`<${Modal} title=${reading ? `Editar lectura de teodolito n.° ${index + 1}` : `Añadir lectura de teodolito (n.° ${index + 1})`} onClose=${onClose} testid="rad-dialog"
    footer=${html`<${Btn} kind="secondary" onClick=${onClose}>Cancelar<//><${Btn} testid="rd-guardar" disabledReason=${problems[0] ?? null} onClick=${save}>Guardar<//>`}>
    ${reading ? html`<${Notice} kind="info">El valor original queda guardado en el registro de correcciones con su nombre y la fecha.<//>` : null}
    <div class="grid two">
      <${Field} label="Estación"><${Select} value=${f.stationId} onChange=${set('stationId')} options=${T.stations.map((s) => [s.id, s.code])} testid="rd-station" /><//>
      <${Field} label="Punto radiado (código) *" error=${dupReading ? `Ya radiado en otra lectura` : null} hint=${code && !exists ? `El punto «${code}» se creará al guardar.` : null}><${TextInput} value=${f.code} onInput=${set('code')} testid="rd-code" list="codes-rd" autofocus invalid=${!!dupReading} /><//>
      ${code && !exists ? html`<${Field} label="Descripción del punto"><${TextInput} value=${f.desc} onInput=${set('desc')} testid="rd-desc" /><//>` : null}
      <${AngleField} label="Lectura horizontal (L) *" value=${f.hz} onInput=${set('hz')} unit=${U} testid="rd-hz" />
    </div>
    <${PointCodes} id="codes-rd" project=${p} />
    <fieldset style="margin-top:.7rem"><legend>Distancia</legend>
      <${Radios} name="dt" value=${f.distType} onChange=${set('distType')} options=${[['horizontal', 'Horizontal (medida con cinta)'], ['inclinada', 'Inclinada (requiere ángulo vertical)']]} testid="rd-disttype" />
      <div class="grid two" style="margin-top:.5rem">
        <${Field} label=${f.distType === 'inclinada' ? 'Distancia inclinada (Di) *' : 'Distancia horizontal (Dh) *'} unit="m" error=${f.distance ? D.error : null}><${TextInput} numeric value=${f.distance} onInput=${set('distance')} testid="rd-distance" /><//>
        ${f.distType === 'inclinada' ? html`<${Field} label="Tipo de ángulo vertical"><${Select} value=${f.vType} onChange=${set('vType')} options=${[['cenital', 'Cenital Z (desde la vertical)'], ['vertical', 'Vertical α (desde la horizontal)']]} testid="rd-vtype" /><//>` : null}
        ${f.distType === 'inclinada' ? html`<${AngleField} label=${f.vType === 'cenital' ? 'Ángulo cenital Z *' : 'Ángulo vertical α *'} value=${f.vAngle} onInput=${set('vAngle')} unit=${U} testid="rd-vangle" />` : null}
      </div>
    </fieldset>
    <div class="stack" style="margin-top:.7rem">
      <${Field} label="Observaciones"><${TextInput} value=${f.notes} onInput=${set('notes')} /><//>
      ${reading ? html`<${Field} label="Motivo de la corrección" hint="Opcional"><${TextInput} value=${f.reason} onInput=${set('reason')} testid="rd-reason" /><//>` : null}
    </div>
    ${problems.length ? html`<${Notice} kind="warn">${problems.join(' ')}<//>` : null}
    ${preview ? html`<div style="margin-top:.6rem" data-testid="rd-preview">
      ${preview.myErr.length ? html`<${ErrorList} title="Esta lectura tiene errores" errors=${preview.myErr} />` : null}
      ${!preview.myErr.length && preview.stErr.length ? html`<${ErrorList} title="La estación aún no está completa (por eso no se puede calcular la vista previa)" errors=${preview.stErr} />` : null}
      ${steps.length ? html`<div class="steps">${steps.map((s) => html`<div>${s}</div>`)}</div>` : null}
    </div>` : null}
  <//>`;
}

// ======================================================================================= Correcciones
function AuditTab({ ctx }) {
  const list = [...ctx.project.audit].reverse();
  const [all, setAll] = useState(false);
  const shown = all ? list : list.filter((a) => a.action === 'editar' || a.action === 'eliminar');
  return html`<div class="card">
    <header class="row between"><h3 style="margin:0">Registro de correcciones</h3><${Check} checked=${all} onChange=${setAll} label="Mostrar también altas y cálculos" /></header>
    <p class="small muted">Cada corrección conserva el valor original de campo, quién la hizo y cuándo. Las eliminaciones guardan una copia del registro.</p>
    <div class="table-wrap"><table class="data cards" data-testid="audit-table">
      <thead><tr><th>Fecha y hora</th><th>Usuario</th><th>Acción</th><th>Registro</th><th>Cambios (original → nuevo)</th><th>Motivo</th></tr></thead>
      <tbody>
        ${shown.length === 0 ? html`<tr><td colspan="6" class="empty">Sin correcciones registradas.</td></tr>` : null}
        ${shown.map((a) => html`<tr>
          <td data-label="Fecha">${fmtDateTime(a.at)}</td><td data-label="Usuario">${a.by || '—'}</td><td data-label="Acción">${a.action}</td><td data-label="Registro">${a.label}</td>
          <td data-label="Cambios">${(a.changes ?? []).map((c) => html`<div><strong>${c.label}:</strong> ${auditValue(c.from)} → ${auditValue(c.to)}</div>`)}${a.snapshot ? html`<details><summary class="small">Copia del registro eliminado</summary><pre class="small">${JSON.stringify(a.snapshot, null, 1)}</pre></details>` : null}</td>
          <td data-label="Motivo">${a.reason || ''}</td></tr>`)}
      </tbody></table></div>
  </div>`;
}
