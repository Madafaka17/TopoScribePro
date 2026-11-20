import { html, useState } from '../../../vendor/preact-htm.mjs';
import { Btn, Check, ErrorList, Field, Modal, Notice, StatusBadge, TextInput, WarningList, confirmDialog, toast } from '../components.js';
import { fmt, isNum, parseNumber } from '../../core/numbers.js';
import { buildPlanModel } from '../../core/plan.js';
import { INTERPOLATION_METHOD, TRIANGULATION_METHOD } from '../../core/contours.js';
import { buildDxf } from '../../export/dxf.js';
import { verifyDxf } from '../../export/dxfVerify.js';
import { PLAN_LAYERS, PlanSvg } from '../svg.js';
import { downloadBlob, safeFileName, today } from '../files.js';
import * as M from '../mutations.js';
import { ModuleStatus } from './nivel.js';

export function PlanoView({ ctx }) {
  const { project: p, ev } = ctx;
  const plan = buildPlanModel(p, ev);
  const [showPlan, setShowPlan] = useState(true);
  const [layers, setLayers] = useState(Object.fromEntries(PLAN_LAYERS.map(([k]) => [k, k !== 'coordenadas'])));
  const [lineDlg, setLineDlg] = useState(null);
  const [eq, setEq] = useState(isNum(p.plan.equidistance) ? String(p.plan.equidistance) : '');
  const [verification, setVerification] = useState(null);
  const ct = ev.contours;
  const levOk = ev.leveling.status === 'vigente' || ev.leveling.status === 'sin-datos';
  const radOk = ev.radiation.status === 'vigente';
  const withXY = ev.survey.rows.filter((r) => isNum(r.east) && isNum(r.north));
  const noZ = withXY.filter((r) => !isNum(r.elevation));
  const withXYZ = withXY.filter((r) => isNum(r.elevation));

  const eqParsed = parseNumber(eq);
  const eqDirty = (eqParsed.ok ? eqParsed.value : null) !== (p.plan.equidistance ?? null);
  const saveEq = async () => {
    if (!eqParsed.ok || eqParsed.value <= 0) { toast('La equidistancia debe ser un número mayor que cero (m).', 'err'); return; }
    if (await ctx.update((d) => { d.plan.equidistance = eqParsed.value; })) toast('Equidistancia guardada.', 'ok');
  };
  const contourReason = !radOk ? 'Primero calcule la radiación en Teodolito (las curvas necesitan coordenadas vigentes).'
    : !levOk ? 'Primero calcule la nivelación en Nivel de ingeniero (las cotas deben estar vigentes).'
      : !isNum(p.plan.equidistance) ? 'Escriba y guarde la equidistancia.'
        : eqDirty ? 'Guarde primero la equidistancia escrita.'
          : !ct.current.ok ? ct.current.reason
            : ct.status === 'vigente' ? 'Las curvas ya están calculadas con los datos actuales.' : null;

  const exportDxf = () => {
    try {
      const { text } = buildDxf(plan, p);
      const v = verifyDxf(text, plan); // se verifica exactamente el archivo que se descarga
      downloadBlob(`${safeFileName(p.name)}_${today()}.dxf`, new Blob([text], { type: 'application/dxf' }));
      setVerification(v);
      toast(v.ok ? 'DXF exportado y verificado.' : 'DXF exportado, pero la verificación encontró diferencias.', v.ok ? 'ok' : 'err', 6000);
    } catch (e) {
      toast(e.message, 'err', 8000);
    }
  };

  return html`<div>
    <div class="page-title"><h2>Plano y curvas de nivel</h2></div>
    <${Notice} kind="info"><strong>Qué aporta cada instrumento.</strong> El <strong>teodolito</strong> da la posición planimétrica (Este, Norte) por radiación.
      El <strong>nivel de ingeniero</strong> da las cotas por nivelación. Se vinculan solo por <strong>código de punto idéntico</strong> dentro de este proyecto;
      nunca por proximidad ni por parecido del nombre. Las curvas de nivel solo se generan con puntos que tienen E, N y cota.<//>

    <div class="card">
      <h3>Vinculación de coordenadas y cotas por código</h3>
      ${!radOk ? html`<${ModuleStatus} state=${ev.radiation} moduleLabel="el teodolito" editHref="#/libreta/radiacion" />` : null}
      ${ev.leveling.status !== 'vigente' && ev.leveling.status !== 'sin-datos' ? html`<${Notice} kind="warn">La nivelación no está calculada o está desactualizada: sus cotas no se vinculan hasta pulsar «Calcular» en Nivel de ingeniero.<//>` : null}
      <div class="table-wrap"><table class="data cards" data-testid="link-table">
        <thead><tr><th>Código</th><th class="num">Este <span class="unit-h">(m)</span></th><th class="num">Norte <span class="unit-h">(m)</span></th><th class="num">Cota <span class="unit-h">(m)</span></th><th>Origen de E/N</th><th>Origen de la cota</th><th>Estado</th></tr></thead>
        <tbody>${ev.survey.rows.length === 0 ? html`<tr><td colspan="7" class="empty">Sin puntos.</td></tr>` : null}
          ${ev.survey.rows.map((r) => html`<tr data-code=${r.code}>
            <td data-label="Código"><strong>${r.code}</strong></td><td data-label="Este (m)" class="num">${isNum(r.east) ? fmt(r.east) : '—'}</td><td data-label="Norte (m)" class="num">${isNum(r.north) ? fmt(r.north) : '—'}</td>
            <td data-label="Cota (m)" class="num">${isNum(r.elevation) ? fmt(r.elevation) : '—'}</td><td data-label="Origen E/N">${r.coordSource || '—'}</td><td data-label="Origen cota">${r.elevSource || '—'}</td>
            <td data-label="Estado"><span class=${`badge ${isNum(r.east) && isNum(r.elevation) ? 'ok' : isNum(r.east) ? 'warn' : 'muted'}`}>${r.status}</span></td></tr>`)}
        </tbody></table></div>
      <${WarningList} warnings=${ev.survey.warnings} />
      ${noZ.length ? html`<${Notice} kind="warn" testid="no-elevation">Puntos con posición pero <strong>sin cota</strong>: ${noZ.map((r) => r.code).join(', ')}. No participan en la triangulación ni en las curvas: primero deben nivelarse (mismo código en la libreta de nivelación) o registrarse una cota justificada en «Puntos».<//>` : null}
    </div>

    <div class="card">
      <header class="row between"><h3 style="margin:0">Líneas del plano (definidas por el usuario)</h3><${Btn} kind="secondary" testid="btn-anadir-linea" onClick=${() => setLineDlg({ line: null })}>Añadir línea<//></header>
      <p class="small muted">Los puntos solo se unen según el orden que usted indique (linderos, bordes, ejes). La app no une puntos por su cuenta.</p>
      ${p.plan.lines.length === 0 ? html`<p class="muted">Sin líneas.</p>` : html`<ul data-testid="lines-list">${p.plan.lines.map((l) => html`<li class="row"><strong>${l.name}</strong>: ${l.codes.join(' → ')}${l.closed ? ' → (cerrada)' : ''}
        <${Btn} small kind="ghost" onClick=${() => setLineDlg({ line: l })}>Editar<//>
        <${Btn} small kind="ghost" onClick=${async () => { const r = await confirmDialog({ title: `Eliminar la línea «${l.name}»`, message: 'Solo se elimina la línea del plano; los puntos no cambian.', confirmText: 'Eliminar', danger: true }); if (r.ok) ctx.update((d, by) => M.deleteLine(d, by, l.id)); }}>Eliminar<//></li>`)}</ul>`}
      <${WarningList} title="Líneas que no se pueden dibujar" warnings=${plan.warnings} />
    </div>

    <div class="card" data-testid="contours-card">
      <header class="row between"><h3 style="margin:0">Triangulación y curvas de nivel</h3><${StatusBadge} status=${ct.status} /></header>
      <div class="steps"><div>Triangulación: ${TRIANGULATION_METHOD}</div><div>Interpolación: ${INTERPOLATION_METHOD}</div></div>
      <p class="small">Puntos con E, N y cota disponibles: <strong>${withXYZ.length}</strong>${withXYZ.length ? ` (${withXYZ.map((r) => r.code).join(', ')})` : ''}. Se necesitan al menos 4, no alineados.</p>
      <div class="grid three">
        <${Field} label="Equidistancia de las curvas" unit="m" hint="Ejemplo: 0.25, 0.5 o 1" error=${eq && !eqParsed.ok ? eqParsed.error : null}><${TextInput} numeric value=${eq} onInput=${setEq} testid="eq-input" /><//>
        <div style="align-self:end"><${Btn} kind="secondary" testid="eq-guardar" disabledReason=${eqDirty ? null : 'No hay cambios que guardar.'} showReason=${false} onClick=${saveEq}>Guardar<//></div>
      </div>
      <div class="actions" style="margin-top:.6rem"><${Btn} testid="btn-calcular-curvas" disabledReason=${contourReason} onClick=${() => ctx.calc('contours')}>Calcular<//></div>
      ${ct.status === 'desactualizado' ? html`<${Notice} kind="err" title="Curvas desactualizadas"><ul>${ct.changes.map((c) => html`<li>${c}</li>`)}</ul><//>` : null}
      ${ct.status === 'vigente' && ct.accepted?.ok ? html`<${Notice} kind="ok" testid="contours-ok">${ct.accepted.usedPoints.length} puntos, ${ct.accepted.triangles.length} triángulos, ${ct.accepted.levels.length} curvas (${ct.accepted.levels.map((l) => fmt(l, 2)).join(', ')} m), equidistancia ${fmt(ct.accepted.equidistance, 2)} m.<//>` : null}
      ${ct.current.reason && ct.status !== 'vigente' && radOk && levOk ? html`<${Notice} kind="warn" title="No se pueden generar curvas de nivel con los datos actuales" testid="contours-reason">${ct.current.reason}<//>` : null}
      <${ErrorList} title="Problemas de los puntos" errors=${ct.current.errors} />
      <${WarningList} warnings=${ct.status === 'vigente' ? ct.accepted?.warnings : []} />
    </div>

    <div class="card" data-testid="plan-card">
      <header class="row between"><h3 style="margin:0">Plano</h3>
        <div class="actions">
          <${Btn} kind="secondary" testid="btn-ver-plano" disabledReason=${plan.ok ? null : plan.reason} onClick=${() => setShowPlan((x) => !x)}>${showPlan ? 'Ocultar plano' : 'Ver plano'}<//>
          <${Btn} testid="btn-exportar-dxf" disabledReason=${plan.ok ? null : plan.reason} onClick=${exportDxf}>Exportar DXF<//>
        </div></header>
      ${plan.ok && showPlan ? html`
        <div class="layers" role="group" aria-label="Capas">${PLAN_LAYERS.map(([k, l]) => {
          const unavailable = (k === 'triangulacion' || k === 'curvas') && !plan.contours;
          return html`<${Check} checked=${layers[k] && !unavailable} label=${`${l}${unavailable ? ' (no calculadas)' : ''}`} onChange=${(v) => setLayers((x) => ({ ...x, [k]: v }))} testid=${`layer-${k}`} />`;
        })}</div>
        <${PlanSvg} plan=${plan} layers=${{ ...layers, triangulacion: layers.triangulacion && !!plan.contours, curvas: layers.curvas && !!plan.contours }} />
        <p class="small muted">Arrastre para desplazar; use la rueda, dos dedos o los botones para acercar. La escala indicada es la del plano impreso en el informe (A4).</p>
        <p class="small muted">El DXF incluye todas las capas calculadas; en AutoCAD puede activarlas o desactivarlas.</p>` : null}
      ${!plan.ok ? html`<${Notice} kind="warn">${plan.reason}<//>` : null}
      ${verification ? html`<${Notice} kind=${verification.ok ? 'ok' : 'err'} title="Verificación automática del DXF exportado" testid="dxf-verification"><ul>${verification.lines.map((l) => html`<li>${l}</li>`)}</ul>
        <p class="small">Capas: ESTACION, PUNTOS, CODIGOS, COTAS, RADIACIONES, LINEAS, TRIANGULACION, CURVAS_NIVEL, CURVAS_TEXTO, NOTAS.</p><//>` : null}
    </div>
    ${lineDlg ? html`<${LineDialog} ctx=${ctx} line=${lineDlg.line} onClose=${() => setLineDlg(null)} />` : null}
  </div>`;
}

function LineDialog({ ctx, line, onClose }) {
  const [name, setName] = useState(line?.name ?? '');
  const [codes, setCodes] = useState(line?.codes.join(', ') ?? '');
  const [closed, setClosed] = useState(line?.closed ?? false);
  const list = codes.split(/[,;\s]+/).map((c) => c.trim()).filter(Boolean);
  const save = async () => {
    if (await ctx.update((p, by) => M.saveLine(p, by, line?.id ?? null, { name, codes: list, closed }))) { toast('Línea guardada.', 'ok'); onClose(); }
  };
  return html`<${Modal} title=${line ? 'Editar línea' : 'Añadir línea'} onClose=${onClose} testid="line-dialog"
    footer=${html`<${Btn} kind="secondary" onClick=${onClose}>Cancelar<//><${Btn} testid="ln-guardar" disabledReason=${!name.trim() ? 'Escriba un nombre.' : list.length < 2 ? 'Indique al menos 2 códigos.' : null} onClick=${save}>Guardar<//>`}>
    <div class="stack">
      <${Field} label="Nombre" hint="Ej.: Lindero, Borde de vereda"><${TextInput} value=${name} onInput=${setName} testid="ln-name" autofocus /><//>
      <${Field} label="Códigos de los puntos, en orden" hint=${`Separados por comas. Disponibles: ${ctx.project.points.map((p) => p.code).join(', ') || '—'}`}><${TextInput} value=${codes} onInput=${setCodes} testid="ln-codes" /><//>
      <${Check} checked=${closed} onChange=${setClosed} label="Cerrar la línea (volver al primer punto)" testid="ln-closed" />
    </div>
  <//>`;
}
