import { html, useState } from '../../../vendor/preact-htm.mjs';
import { Btn, ErrorList, Field, Notice, StatusBadge, TextInput, WarningList, fmtDateTime, toast } from '../components.js';
import { fmt, fmtSigned, isNum, parseNumber } from '../../core/numbers.js';
import { rowTypeLabel } from '../../core/leveling.js';
import { ProfileSvg } from '../svg.js';

export function ModuleStatus({ state, moduleLabel, editHref, testid }) {
  if (state.status === 'sin-datos') return html`<${Notice} kind="info" testid=${testid}>No hay datos registrados para ${moduleLabel}. Regístrelos en la <a href=${editHref}>libreta de campo</a>.<//>`;
  if (state.status === 'sin-calcular') return html`<${Notice} kind="warn" title="Sin calcular" testid=${testid}>Hay datos registrados. Pulse «Calcular» para obtener los resultados.<//>`;
  if (state.status === 'vigente') return html`<${Notice} kind="ok" testid=${testid}>Calculado el ${fmtDateTime(state.stored.at)} por ${state.stored.by || '—'}. Los resultados corresponden a los datos actuales de la libreta.<//>`;
  return html`<${Notice} kind="err" title="Resultados desactualizados: los datos de campo cambiaron después del último cálculo" testid=${testid}>
    <p class="small">Se muestran los resultados del cálculo del ${fmtDateTime(state.stored.at)}. Estos valores cambiarán al pulsar «Calcular»:</p>
    <ul>${state.changes.map((c) => html`<li>${c}</li>`)}</ul><//>`;
}

export function NivelView({ ctx }) {
  const { project: p, ev } = ctx;
  const st = ev.leveling;
  const cur = st.current;
  const res = st.accepted; // lo último aceptado (vigente o desactualizado)
  const [showProfile, setShowProfile] = useState(false);
  const [scales, setScales] = useState({ h: p.leveling.profileScales?.h ?? '', v: p.leveling.profileScales?.v ?? '' });
  const calcReason = st.status === 'sin-datos' ? 'No hay lecturas de nivelación en la libreta.'
    : !cur.computable ? 'La libreta tiene errores (vea la lista). Corríjalos en la libreta de campo.'
      : st.status === 'vigente' ? 'Los resultados ya están calculados con los datos actuales.' : null;
  const profileReason = !res ? 'Primero pulse «Calcular».' : null;

  const saveScales = async () => {
    const h = parseNumber(scales.h); const v = parseNumber(scales.v);
    if ((!h.empty && (!h.ok || h.value <= 0)) || (!v.empty && (!v.ok || v.value <= 0))) { toast('Las escalas deben ser números positivos (denominador, p. ej. 500).', 'err'); return; }
    if (await ctx.update((d) => { d.leveling.profileScales = { h: h.empty ? null : h.value, v: v.empty ? null : v.value }; })) toast('Escalas del perfil guardadas.', 'ok');
  };

  return html`<div>
    <div class="page-title"><h2>Nivel de ingeniero</h2><${StatusBadge} status=${st.status} /></div>
    <p class="muted small">Altimetría por nivelación geométrica: cálculo de cotas por altura de instrumento y perfil longitudinal. Unidades en metros.</p>
    <div class="card">
      <div class="row between">
        <${ModuleStatus} state=${st} moduleLabel="la nivelación" editHref="#/libreta/nivelacion" testid="level-status" />
      </div>
      <div class="actions">
        <${Btn} testid="btn-calcular-nivel" disabledReason=${calcReason} onClick=${() => ctx.calc('leveling')}>Calcular<//>
        <${Btn} kind="secondary" testid="btn-ver-perfil" disabledReason=${profileReason} onClick=${() => setShowProfile((x) => !x)}>${showProfile ? 'Ocultar perfil' : 'Ver perfil'}<//>
        <${Btn} kind="ghost" onClick=${() => ctx.nav('libreta', 'nivelacion')}>Editar lecturas en la libreta<//>
      </div>
      ${st.status !== 'sin-datos' && !cur.computable ? html`<${ErrorList} errors=${cur.errors} testid="level-calc-errors" />` : null}
    </div>

    ${res ? html`
      ${showProfile ? html`<div class="card" data-testid="profile-card"><h3>Perfil longitudinal</h3>
        ${res.profile.ok ? html`<${ProfileSvg} points=${res.profile.points} scales=${p.leveling.profileScales} />
          <div class="grid three" style="margin-top:.6rem">
            <${Field} label="Escala horizontal 1:" hint="Vacío = automática"><${TextInput} numeric value=${scales.h} onInput=${(v) => setScales((s) => ({ ...s, h: v }))} /><//>
            <${Field} label="Escala vertical 1:" hint="Vacío = automática"><${TextInput} numeric value=${scales.v} onInput=${(v) => setScales((s) => ({ ...s, v: v }))} /><//>
            <div style="align-self:end"><${Btn} kind="secondary" onClick=${saveScales}>Guardar<//></div>
          </div>`
          : html`<${Notice} kind="warn" title="No se dibuja el perfil: faltan datos" testid="profile-missing"><ul>
            ${[res.profile.reason, ...res.profile.errors.map((e) => e.message), ...res.profile.missing.map((m) => m.message)].filter(Boolean).map((t) => html`<li>${t}</li>`)}
          </ul><//>`}
      </div>` : null}

      <div class="kpis" data-testid="level-kpis">
        <div class="kpi"><div class="label">Cota BM inicial</div><div class="value">${fmt(res.sums.startElevation)} m</div></div>
        <div class="kpi"><div class="label">ΣVA</div><div class="value">${fmt(res.sums.sumBs)} m</div></div>
        <div class="kpi"><div class="label">ΣVD</div><div class="value">${fmt(res.sums.sumFs)} m</div></div>
        <div class="kpi"><div class="label">ΣVA − ΣVD</div><div class="value">${fmt(res.sums.diff)} m</div></div>
        <div class="kpi"><div class="label">Error de cierre</div><div class="value" data-testid="closure-value">${res.closure.applicable ? `${fmtSigned(res.closure.error)} m` : 'no comprobable'}</div></div>
      </div>

      <div class="card">
        <h3>Tabla de resultados</h3>
        <div class="table-wrap"><table class="data cards" data-testid="level-results">
          <thead><tr><th>N.°</th><th>Estación</th><th>Punto</th><th>Tipo</th><th class="num">VA</th><th class="num">VI</th><th class="num">VD</th><th class="num">AI <span class="unit-h">(m)</span></th><th class="num">Cota <span class="unit-h">(m)</span></th><th class="num">Dist. parcial <span class="unit-h">(m)</span></th><th class="num">Dist. acumulada <span class="unit-h">(m)</span></th></tr></thead>
          <tbody>${res.rows.map((r) => html`<tr data-code=${r.code}>
            <td data-label="N.°">${r.n}</td><td data-label="Estación">${r.station}</td><td data-label="Punto"><strong>${r.code}</strong></td><td data-label="Tipo">${rowTypeLabel(r.type)}</td>
            <td data-label="VA" class="num">${isNum(r.bs) ? fmt(r.bs) : ''}</td><td data-label="VI" class="num">${isNum(r.is) ? fmt(r.is) : ''}</td><td data-label="VD" class="num">${isNum(r.fs) ? fmt(r.fs) : ''}</td>
            <td data-label="AI (m)" class="num">${fmt(r.hiNew ?? r.hiUsed)}</td><td data-label="Cota (m)" class="num" data-testid="cota"><strong>${fmt(r.elevation)}</strong></td>
            <td data-label="Dist. parcial (m)" class="num">${isNum(r.partial) ? fmt(r.partial) : ''}</td><td data-label="Dist. acumulada (m)" class="num">${isNum(r.cumulative) ? fmt(r.cumulative) : ''}</td>
          </tr>`)}</tbody></table></div>
        <p class="small muted">En un punto de cambio, la AI mostrada es la de la estación nueva (cota del punto de cambio + su vista atrás).</p>
      </div>

      <div class="card">
        <h3>Cálculo paso a paso</h3>
        <div class="steps" data-testid="level-steps">
          <div>AI = cota del punto de referencia + VA  ·  Cota = AI − VI  (o AI − VD)</div>
          ${res.rows.flatMap((r) => r.steps.map((s) => html`<div>${r.n}. ${s}</div>`))}
        </div>
        <h4 style="margin-top:1rem">Comprobación de la libreta</h4>
        <div class="steps" data-testid="level-check">
          <div>ΣVA − ΣVD = ${fmt(res.sums.sumBs)} − ${fmt(res.sums.sumFs)} = ${fmt(res.sums.diff)} m</div>
          ${res.sums.applicable ? html`<div>Cota final (${res.sums.endCode}) − cota inicial = ${fmt(res.sums.endElevation)} − ${fmt(res.sums.startElevation)} = ${fmt(res.sums.elevDiff)} m → ${res.sums.ok ? 'COINCIDE ✓' : 'NO COINCIDE ✗'}</div>` : null}
        </div>
        <${Notice} kind=${res.sums.applicable ? (res.sums.ok ? 'ok' : 'err') : 'warn'}>${res.sums.explanation}<//>
        <h4>Error de cierre</h4>
        <${Notice} kind=${res.closure.applicable ? 'info' : 'warn'} testid="closure-notice">${res.closure.explanation}<//>
        <${WarningList} warnings=${res.warnings} />
      </div>` : null}
  </div>`;
}
