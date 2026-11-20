import { html } from '../../../vendor/preact-htm.mjs';
import { Btn, ErrorList, Notice, StatusBadge } from '../components.js';
import { fmt } from '../../core/numbers.js';
import { formatAngle, UNITS } from '../../core/angles.js';
import { DIRECTION_LABEL, ORIGIN_LABEL } from '../../core/radiation.js';
import { ModuleStatus } from './nivel.js';

export function TeodolitoView({ ctx }) {
  const { project: p, ev } = ctx;
  const st = ev.radiation;
  const cur = st.current;
  const res = st.accepted;
  const conv = p.theodolite.convention;
  const U = conv.unit || 'sexagesimal';
  const A = (d) => formatAngle(d, U);
  const calcReason = st.status === 'sin-datos' ? 'No hay estación ni lecturas de teodolito en la libreta.'
    : !cur.computable ? 'Faltan datos o hay errores (vea la lista). Corríjalos en la libreta de campo.'
      : st.status === 'vigente' ? 'Los resultados ya están calculados con los datos actuales.' : null;
  return html`<div>
    <div class="page-title"><h2>Teodolito — método de radiación</h2><${StatusBadge} status=${st.status} /></div>
    <p class="muted small">Planimetría: azimut de cada visual, distancia horizontal, proyecciones Este/Norte y coordenadas desde la estación.</p>
    <div class="card">
      <${ModuleStatus} state=${st} moduleLabel="el teodolito" editHref="#/libreta/radiacion" testid="rad-status" />
      <div class="actions">
        <${Btn} testid="btn-calcular-teodolito" disabledReason=${calcReason} onClick=${() => ctx.calc('radiation')}>Calcular<//>
        <${Btn} kind="secondary" testid="btn-ver-plano-teo" disabledReason=${res ? null : 'Primero pulse «Calcular».'} onClick=${() => ctx.nav('plano')}>Ver plano<//>
        <${Btn} kind="ghost" onClick=${() => ctx.nav('libreta', 'radiacion')}>Editar lecturas en la libreta<//>
      </div>
      ${st.status !== 'sin-datos' && !cur.computable ? html`<${ErrorList} errors=${cur.errors} testid="rad-calc-errors" />` : null}
    </div>
    <div class="card small">
      <strong>Convenio angular:</strong> ${UNITS[conv.unit]?.label ?? 'sin definir'} · sentido ${DIRECTION_LABEL[conv.direction] ?? 'sin definir'} · origen ${ORIGIN_LABEL[conv.origin] ?? 'sin definir'}.
      <div class="steps" style="margin-top:.4rem">
        <div>${conv.origin === 'norte' ? (conv.direction === 'antihorario' ? 'Az = 360° − L' : 'Az = L') : `Az = Az_ref ${conv.direction === 'antihorario' ? '−' : '+'} (L − L_ref)  →  normalizado a [0°, 360°)`}</div>
        <div>Dh = Di × sen(Z)   ·   Dh = Di × cos(α)   (si la distancia es inclinada)</div>
        <div>ΔE = Dh × sen(Az)   ·   ΔN = Dh × cos(Az)   ·   E = E_est + ΔE   ·   N = N_est + ΔN</div>
      </div>
    </div>
    ${res ? html`
      ${res.stations.map((s) => html`<div class="card"><h3>Estación ${s.code}: orientación</h3>
        <p class="small">E = ${fmt(s.east)} m · N = ${fmt(s.north)} m (${s.coordType === 'conocidas' ? 'coordenadas conocidas' : 'coordenadas locales declaradas'})</p>
        <div class="steps" data-testid="orientation-steps">${s.steps.map((x) => html`<div>${x}</div>`)}</div></div>`)}
      <div class="card">
        <h3>Azimuts, distancias, proyecciones y coordenadas</h3>
        <div class="table-wrap"><table class="data cards" data-testid="rad-results">
          <thead><tr><th>Punto</th><th>Estación</th><th>Lectura horizontal</th><th>Azimut</th><th class="num">Dh <span class="unit-h">(m)</span></th><th class="num">ΔE <span class="unit-h">(m)</span></th><th class="num">ΔN <span class="unit-h">(m)</span></th><th class="num">Este <span class="unit-h">(m)</span></th><th class="num">Norte <span class="unit-h">(m)</span></th></tr></thead>
          <tbody>${res.points.map((pt) => html`<tr data-code=${pt.code}>
            <td data-label="Punto"><strong>${pt.code}</strong></td><td data-label="Estación">${pt.stationCode}</td>
            <td data-label="Lectura" class="mono">${A(pt.hzDeg)}</td><td data-label="Azimut" class="mono" data-testid="az">${A(pt.az)}</td>
            <td data-label="Dh (m)" class="num">${fmt(pt.dh)}</td><td data-label="ΔE (m)" class="num">${fmt(pt.dE)}</td><td data-label="ΔN (m)" class="num">${fmt(pt.dN)}</td>
            <td data-label="Este (m)" class="num" data-testid="east"><strong>${fmt(pt.east)}</strong></td><td data-label="Norte (m)" class="num" data-testid="north"><strong>${fmt(pt.north)}</strong></td>
          </tr>`)}</tbody></table></div>
      </div>
      <div class="card">
        <h3>Cálculo paso a paso</h3>
        ${res.points.map((pt) => html`<details open=${res.points.length <= 3}>
          <summary>Punto ${pt.code} — Az ${A(pt.az)} · E ${fmt(pt.east)} · N ${fmt(pt.north)}</summary>
          <div class="steps" data-testid=${`rad-steps-${pt.code}`}><div>Lectura original: L = ${pt.hzText} → ${A(pt.hzDeg)}; distancia ${pt.distType} = ${fmt(pt.distance)} m${pt.distType === 'inclinada' ? `; ${pt.vType === 'cenital' ? 'Z' : 'α'} = ${pt.vText}` : ''}</div>
            ${[...pt.azSteps, ...pt.dhSteps, ...pt.coordSteps].map((x) => html`<div>${x}</div>`)}</div>
        </details>`)}
        <${Notice} kind="info">La radiación no tiene comprobación propia: cada punto se fija con una sola visual. Revise las lecturas originales.<//>
      </div>` : null}
  </div>`;
}
