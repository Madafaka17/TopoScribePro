// Dibujos en pantalla (SVG) con la MISMA maquetación en milímetros que el PDF (core/scales.js).
import { html, useRef, useState } from '../../vendor/preact-htm.mjs';
import { fmt, fmtChainage, isNum } from '../core/numbers.js';
import { profileLayout } from '../core/scales.js';
import { uniqueEdges, polylineLabelPoint } from '../core/plan.js';
import { Btn } from './components.js';

export function ProfileSvg({ points, scales }) {
  const lay = profileLayout(points, { hScale: scales?.h, vScale: scales?.v });
  const L = 24; const T = 6; const band = 14;
  const W = L + lay.usedWidth + 6;
  const H = T + lay.height + 10 + band * 2 + 4;
  const X = (c) => L + lay.x(c);
  const Y = (z) => T + lay.y(z);
  const gy = T + lay.height + 10;
  const path = points.map((p, i) => `${i ? 'L' : 'M'}${X(p.chainage).toFixed(3)},${Y(p.elevation).toFixed(3)}`).join(' ');
  return html`<div>
    <p class="small"><strong>Escala horizontal 1:${lay.hScale}</strong> · <strong>Escala vertical 1:${lay.vScale}</strong> · Exageración vertical ${fmt(lay.exaggeration, 1)}× · Plano de comparación: cota ${fmt(lay.datum)} m. (Escalas de impresión en el informe, hoja A4 apaisada.)</p>
    ${lay.errors.map((e) => html`<p class="small" style="color:var(--warn)">${e}</p>`)}
    <svg class="drawing" viewBox=${`0 0 ${W} ${H}`} role="img" aria-label="Perfil longitudinal" data-testid="profile-svg" style=${`max-height:70vh`}>
      <g stroke="#e3e6ea" stroke-width="0.12">
        ${lay.zTicks.map((z) => html`<line x1=${L} x2=${L + lay.usedWidth} y1=${Y(z)} y2=${Y(z)} />`)}
        ${lay.chTicks.map((c) => html`<line x1=${X(c)} x2=${X(c)} y1=${T} y2=${T + lay.height} />`)}
      </g>
      <g stroke="#222" stroke-width="0.3"><line x1=${L} x2=${L} y1=${T} y2=${T + lay.height} /><line x1=${L} x2=${L + lay.usedWidth} y1=${T + lay.height} y2=${T + lay.height} /></g>
      <g font-size="2.4" fill="#222">
        ${lay.zTicks.map((z) => html`<text x=${L - 1.2} y=${Y(z) + 0.8} text-anchor="end">${fmt(z, 2)}</text>`)}
        ${lay.chTicks.map((c) => html`<text x=${X(c)} y=${T + lay.height + 3.2} text-anchor="middle">${fmtChainage(c, 0)}</text>`)}
        <text x=${L + lay.usedWidth / 2} y=${T + lay.height + 7} text-anchor="middle" font-weight="bold">Progresiva (m)</text>
        <text transform=${`translate(${5} ${T + lay.height / 2}) rotate(-90)`} text-anchor="middle" font-weight="bold">Cota (m)</text>
      </g>
      <path d=${path} fill="none" stroke="#18528c" stroke-width="0.5" />
      ${points.map((p) => html`<g><circle cx=${X(p.chainage)} cy=${Y(p.elevation)} r="0.7" fill="#18528c" /><text x=${X(p.chainage)} y=${Y(p.elevation) - 1.8} font-size="2.3" text-anchor="middle">${p.code}</text></g>`)}
      <g stroke="#222" stroke-width="0.2" fill="none"><rect x="1" y=${gy} width=${L - 1 + lay.usedWidth} height=${band * 2} /><line x1="1" x2=${L + lay.usedWidth} y1=${gy + band} y2=${gy + band} /><line x1=${L} x2=${L} y1=${gy} y2=${gy + band * 2} /></g>
      <g font-size="2.2" fill="#222">
        <text x="2" y=${gy + band / 2 + 0.8} font-weight="bold">Cota (m)</text>
        <text x="2" y=${gy + band * 1.5 + 0.8} font-weight="bold">Progresiva</text>
        ${points.map((p) => html`<g>
          <text transform=${`translate(${X(p.chainage) + 0.8} ${gy + band - 1}) rotate(-90)`}>${fmt(p.elevation)}</text>
          <text transform=${`translate(${X(p.chainage) + 0.8} ${gy + 2 * band - 1}) rotate(-90)`}>${fmtChainage(p.chainage)}</text>
        </g>`)}
      </g>
    </svg>
  </div>`;
}

export const PLAN_LAYERS = [
  ['estacion', 'Estación'], ['puntos', 'Puntos'], ['codigos', 'Códigos'], ['coordenadas', 'Coordenadas'], ['radiaciones', 'Radiaciones'],
  ['lineas', 'Líneas'], ['triangulacion', 'Triangulación'], ['curvas', 'Curvas de nivel'],
];

export function PlanSvg({ plan, layers }) {
  const Lay = plan.layout;
  const M = 14; // margen para rótulos de la cuadrícula
  const W = Lay.width + 2 * M;
  const H = Lay.height + 2 * M;
  const [view, setView] = useState({ x: 0, y: 0, w: W, h: H });
  const svgRef = useRef();
  const pointers = useRef(new Map());
  const pinch = useRef(null);
  const zoom = W / view.w;
  const s = 1 / Math.max(zoom, 1); // los rótulos no crecen al acercar
  const X = (e) => M + Lay.x(e);
  const Y = (n) => M + Lay.y(n);
  const on = layers;

  const toSvg = (clientX, clientY) => {
    const r = svgRef.current.getBoundingClientRect();
    return { x: view.x + ((clientX - r.left) / r.width) * view.w, y: view.y + ((clientY - r.top) / r.height) * view.h };
  };
  const zoomAt = (factor, cx, cy) => setView((v) => {
    const nw = Math.min(W * 1.5, Math.max(W / 40, v.w / factor));
    const k = nw / v.w;
    return { x: cx - (cx - v.x) * k, y: cy - (cy - v.y) * k, w: nw, h: v.h * k };
  });
  const onWheel = (e) => { e.preventDefault(); const p = toSvg(e.clientX, e.clientY); zoomAt(e.deltaY < 0 ? 1.25 : 0.8, p.x, p.y); };
  const onDown = (e) => { svgRef.current.setPointerCapture?.(e.pointerId); pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY }); };
  const onMove = (e) => {
    const prev = pointers.current.get(e.pointerId);
    if (!prev) return;
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const r = svgRef.current.getBoundingClientRect();
    if (pointers.current.size === 1) {
      const dx = ((e.clientX - prev.x) / r.width) * view.w;
      const dy = ((e.clientY - prev.y) / r.height) * view.h;
      setView((v) => ({ ...v, x: v.x - dx, y: v.y - dy }));
    } else if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()];
      const dist = Math.hypot(a.x - b.x, a.y - b.y);
      if (pinch.current) {
        const mid = toSvg((a.x + b.x) / 2, (a.y + b.y) / 2);
        zoomAt(dist / pinch.current, mid.x, mid.y);
      }
      pinch.current = dist;
    }
  };
  const onUp = (e) => { pointers.current.delete(e.pointerId); if (pointers.current.size < 2) pinch.current = null; };

  const label = (x, y, text, size, extra = {}) => html`<text x=${x} y=${y} font-size=${size * s} ...${extra}>${text}</text>`;
  const sw = (w) => w * s;

  return html`<div class="drawing-box">
    <svg ref=${svgRef} class="drawing" viewBox=${`${view.x} ${view.y} ${view.w} ${view.h}`} role="img" aria-label="Plano planimétrico" data-testid="plan-svg"
      style="aspect-ratio: ${W} / ${H}; max-height: 78vh;" onWheel=${onWheel} onPointerDown=${onDown} onPointerMove=${onMove} onPointerUp=${onUp} onPointerCancel=${onUp}>
      <rect x=${M} y=${M} width=${Lay.width} height=${Lay.height} fill="#fff" stroke="#222" stroke-width=${sw(0.3)} />
      <g stroke="#e6e9ed" stroke-width=${sw(0.12)}>
        ${Lay.gridE.map((e) => html`<line x1=${X(e)} x2=${X(e)} y1=${M} y2=${M + Lay.height} />`)}
        ${Lay.gridN.map((n) => html`<line x1=${M} x2=${M + Lay.width} y1=${Y(n)} y2=${Y(n)} />`)}
      </g>
      <g fill="#667">
        ${Lay.gridE.map((e) => label(X(e), M + Lay.height + 4, `E ${fmt(e, 0)}`, 2.2, { 'text-anchor': 'middle' }))}
        ${Lay.gridN.map((n) => label(M - 1, Y(n) + 0.8, `N ${fmt(n, 0)}`, 2.2, { 'text-anchor': 'end' }))}
      </g>
      ${on.triangulacion && plan.contours ? html`<g stroke="#aaa" stroke-width=${sw(0.15)} data-layer="triangulacion">${uniqueEdges(plan.triangles).map(([a, b]) => html`<line x1=${X(a.east)} y1=${Y(a.north)} x2=${X(b.east)} y2=${Y(b.north)} />`)}</g>` : null}
      ${on.curvas && plan.contours ? html`<g data-layer="curvas">${plan.contours.polylines.map((pl) => {
        const lp = polylineLabelPoint(pl.points);
        return html`<g><polyline fill="none" stroke="#8c5a1e" stroke-width=${sw(0.35)} points=${pl.points.map((p) => `${X(p.east)},${Y(p.north)}`).join(' ')} />
          ${label(X(lp.east), Y(lp.north) - 0.5 * s, fmt(pl.level, 2), 2.1, { 'text-anchor': 'middle', fill: '#8c5a1e', 'font-weight': 'bold' })}</g>`;
      })}</g>` : null}
      ${on.radiaciones ? html`<g stroke="#78a0c8" stroke-width=${sw(0.18)} stroke-dasharray=${`${1 * s} ${0.8 * s}`} data-layer="radiaciones">${plan.rays.map((r) => html`<line x1=${X(r.from.east)} y1=${Y(r.from.north)} x2=${X(r.to.east)} y2=${Y(r.to.north)} />`)}</g>` : null}
      ${on.lineas ? html`<g fill="none" stroke="#222" stroke-width=${sw(0.45)} data-layer="lineas">${plan.lines.map((l) => html`<polyline points=${l.pts.map((p) => `${X(p.east)},${Y(p.north)}`).join(' ')} />`)}</g>` : null}
      ${on.puntos ? html`<g data-layer="puntos">${plan.points.map((p) => html`<g>
        <circle cx=${X(p.east)} cy=${Y(p.north)} r=${0.7 * s} fill="#222" />
        ${on.codigos ? label(X(p.east) + 1.2 * s, Y(p.north) - 1 * s, p.code, 2.4, { 'font-weight': 'bold' }) : null}
        ${on.coordenadas ? label(X(p.east) + 1.2 * s, Y(p.north) + 2 * s, `E ${fmt(p.east)} · N ${fmt(p.north)}${isNum(p.elevation) ? ` · Z ${fmt(p.elevation)}` : ''}`, 1.8, { fill: '#555' }) : null}
      </g>`)}</g>` : null}
      ${on.estacion ? html`<g data-layer="estacion">${plan.stations.map((st) => html`<g>
        <polygon points=${`${X(st.east)},${Y(st.north) - 1.8 * s} ${X(st.east) - 1.6 * s},${Y(st.north) + 1.1 * s} ${X(st.east) + 1.6 * s},${Y(st.north) + 1.1 * s}`} fill="#c81e1e" />
        ${on.codigos ? label(X(st.east) + 2 * s, Y(st.north) + 2.6 * s, st.code, 2.6, { fill: '#c81e1e', 'font-weight': 'bold' }) : null}
        ${on.coordenadas ? label(X(st.east) + 2 * s, Y(st.north) + 5 * s, `E ${fmt(st.east)} · N ${fmt(st.north)}`, 1.8, { fill: '#555' }) : null}
      </g>`)}</g>` : null}
      <g transform=${`translate(${M + Lay.width - 10} ${M + 6})`}>
        <polygon points="0,0 -2.2,7 2.2,7" fill="#222" /><line x1="0" y1="7" x2="0" y2="12" stroke="#222" stroke-width="0.3" />
        <text x="0" y="16" font-size="3.4" text-anchor="middle" font-weight="bold">N</text>
      </g>
      <g transform=${`translate(${M} ${M + Lay.height + 8})`} font-size="2.2">
        <line x1="0" y1="0" x2=${Lay.barMm} y2="0" stroke="#222" stroke-width="0.4" />
        <line x1="0" y1="-1" x2="0" y2="1" stroke="#222" stroke-width="0.3" /><line x1=${Lay.barMm} y1="-1" x2=${Lay.barMm} y2="1" stroke="#222" stroke-width="0.3" />
        <text x="0" y="3.6" text-anchor="middle">0</text><text x=${Lay.barMm} y="3.6" text-anchor="middle">${fmt(Lay.barM, 0)} m</text>
        <text x=${Lay.barMm + 6} y="1" font-weight="bold">Escala 1:${Lay.scale} (impresión A4)</text>
      </g>
    </svg>
    <div class="zoom-ctl">
      <${Btn} kind="secondary" small onClick=${() => zoomAt(1.4, view.x + view.w / 2, view.y + view.h / 2)} title="Acercar">+<//>
      <${Btn} kind="secondary" small onClick=${() => zoomAt(1 / 1.4, view.x + view.w / 2, view.y + view.h / 2)} title="Alejar">−<//>
      <${Btn} kind="secondary" small onClick=${() => setView({ x: 0, y: 0, w: W, h: H })} title="Ajustar">⤢<//>
    </div>
  </div>`;
}
