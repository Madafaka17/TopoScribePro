// Informe de levantamiento topográfico en PDF (jsPDF). Usa EXACTAMENTE los mismos resultados
// aceptados y las mismas funciones de formato que la pantalla, y la misma maquetación de perfil y
// plano (core/scales.js), para que el documento coincida con la aplicación.

import { fmt, fmtChainage, fmtSigned, isNum, normCode } from '../core/numbers.js';
import { formatAngle } from '../core/angles.js';
import { INSTRUMENTS, POINT_TYPES, auditValue } from '../core/model.js';
import { rowTypeLabel } from '../core/leveling.js';
import { DIRECTION_LABEL, ORIGIN_LABEL } from '../core/radiation.js';
import { profileLayout } from '../core/scales.js';
import { uniqueEdges, polylineLabelPoint } from '../core/plan.js';

const PAGE = { w: 210, h: 297 };
const M = { l: 20, r: 15, t: 22, b: 20 };
const CW = PAGE.w - M.l - M.r;
const C = {
  text: [33, 37, 41], muted: [100, 106, 115], line: [200, 205, 212], head: [232, 238, 244], accent: [24, 82, 140],
  warn: [178, 60, 23], ok: [26, 120, 60], ray: [120, 160, 200], tri: [175, 175, 175], contour: [140, 90, 30], userLine: [40, 40, 40],
};
const PENDING = (what) => `[PENDIENTE: ${what}]`;

class Writer {
  constructor(jsPDF, fonts) {
    this.doc = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'portrait', compress: true });
    const d = this.doc;
    d.addFileToVFS('DejaVuSans.ttf', fonts.regular);
    d.addFont('DejaVuSans.ttf', 'DejaVu', 'normal');
    d.addFileToVFS('DejaVuSans-Bold.ttf', fonts.bold);
    d.addFont('DejaVuSans-Bold.ttf', 'DejaVu', 'bold');
    d.setFont('DejaVu', 'normal');
    d.setLineHeightFactor(1.25);
    this.y = M.t;
    this.toc = [];
    this.pageW = PAGE.w;
    this.pageH = PAGE.h;
  }

  get bottom() { return this.pageH - M.b; }
  get right() { return this.pageW - M.r; }
  get cw() { return this.pageW - M.l - M.r; }

  font(size, style = 'normal', color = C.text) {
    this.doc.setFont('DejaVu', style);
    this.doc.setFontSize(size);
    this.doc.setTextColor(...color);
  }

  newPage(orientation = 'portrait') {
    this.doc.addPage('a4', orientation);
    this.pageW = orientation === 'landscape' ? PAGE.h : PAGE.w;
    this.pageH = orientation === 'landscape' ? PAGE.w : PAGE.h;
    this.y = M.t;
  }

  ensure(h) { if (this.y + h > this.bottom) this.newPage(); }

  lineH(size) { return (size * 0.3528) * 1.3; }

  h1(text) {
    this.ensure(30);
    this.toc.push({ level: 1, text, page: this.doc.getNumberOfPages() });
    this.font(14, 'bold', C.accent);
    this.doc.text(text, M.l, this.y + 5);
    this.y += 8;
    this.doc.setDrawColor(...C.accent);
    this.doc.setLineWidth(0.4);
    this.doc.line(M.l, this.y, this.right, this.y);
    this.y += 5;
  }

  h2(text) {
    this.ensure(18);
    this.toc.push({ level: 2, text, page: this.doc.getNumberOfPages() });
    this.font(11, 'bold', C.text);
    this.doc.text(text, M.l, this.y + 4);
    this.y += 8;
  }

  para(text, { size = 9.5, style = 'normal', color = C.text, indent = 0, gap = 2 } = {}) {
    this.font(size, style, color);
    const lines = this.doc.splitTextToSize(String(text), this.cw - indent);
    const lh = this.lineH(size);
    for (const ln of lines) {
      this.ensure(lh);
      this.doc.text(ln, M.l + indent, this.y + lh * 0.75);
      this.y += lh;
    }
    this.y += gap;
  }

  /** Párrafo de un campo del proyecto: si está vacío, lo marca como pendiente. */
  field(value, what) {
    const v = String(value ?? '').trim();
    if (v) this.para(v);
    else this.para(PENDING(what), { color: C.warn, style: 'bold' });
  }

  bullets(items, opts = {}) {
    for (const it of items) {
      const color = it.color ?? opts.color ?? C.text;
      this.font(opts.size ?? 9.5, 'normal', color);
      const lines = this.doc.splitTextToSize(String(it.text ?? it), this.cw - 6);
      const lh = this.lineH(opts.size ?? 9.5);
      lines.forEach((ln, i) => {
        this.ensure(lh);
        if (i === 0) this.doc.text('•', M.l + 1, this.y + lh * 0.75);
        this.doc.text(ln, M.l + 5, this.y + lh * 0.75);
        this.y += lh;
      });
      this.y += 0.8;
    }
    this.y += 1.5;
  }

  formulaBlock(lines) {
    this.font(9, 'normal', C.text);
    const lh = this.lineH(9);
    const wrapped = lines.flatMap((l) => this.doc.splitTextToSize(l, this.cw - 8));
    let i = 0;
    while (i < wrapped.length) {
      this.ensure(lh + 3);
      const room = Math.max(1, Math.floor((this.bottom - this.y - 3) / lh));
      const chunk = wrapped.slice(i, i + room);
      const h = chunk.length * lh + 3;
      this.doc.setFillColor(246, 248, 250);
      this.doc.setDrawColor(...C.line);
      this.doc.rect(M.l, this.y, this.cw, h, 'FD');
      chunk.forEach((ln, k) => this.doc.text(ln, M.l + 4, this.y + 1.5 + lh * (k + 0.75)));
      this.y += h;
      i += chunk.length;
    }
    this.y += 3;
  }

  /**
   * Tabla con encabezado repetido en cada página.
   * cols: [{ h: 'Título', w: peso, align: 'left'|'right'|'center' }]
   */
  table(cols, rows, { size = 7.8, headSize = 7.6, zebra = true, marks = [] } = {}) {
    const d = this.doc;
    const total = cols.reduce((s, c) => s + c.w, 0);
    const widths = cols.map((c) => (c.w / total) * this.cw);
    const pad = 1.2;
    const lh = this.lineH(size);
    const drawHead = () => {
      this.font(headSize, 'bold', C.text);
      const headLines = cols.map((c, i) => d.splitTextToSize(c.h, widths[i] - 2 * pad));
      const hh = Math.max(...headLines.map((l) => l.length)) * this.lineH(headSize) + 2 * pad;
      this.ensure(hh + lh + 2 * pad);
      d.setFillColor(...C.head);
      d.setDrawColor(...C.line);
      d.rect(M.l, this.y, this.cw, hh, 'FD');
      let x = M.l;
      headLines.forEach((lines, i) => {
        lines.forEach((ln, k) => {
          const tx = cols[i].align === 'right' ? x + widths[i] - pad : cols[i].align === 'center' ? x + widths[i] / 2 : x + pad;
          d.text(ln, tx, this.y + pad + this.lineH(headSize) * (k + 0.75), { align: cols[i].align === 'right' ? 'right' : cols[i].align === 'center' ? 'center' : 'left' });
        });
        x += widths[i];
      });
      this.y += hh;
    };
    drawHead();
    rows.forEach((row, ri) => {
      this.font(size, 'normal', C.text);
      const cellLines = row.map((c, i) => d.splitTextToSize(String(c ?? ''), widths[i] - 2 * pad));
      const rh = Math.max(1, ...cellLines.map((l) => l.length)) * lh + 2 * pad;
      if (this.y + rh > this.bottom) { this.newPage(this.pageW > this.pageH ? 'landscape' : 'portrait'); drawHead(); this.font(size, 'normal', C.text); }
      if (zebra && ri % 2 === 1) { d.setFillColor(250, 251, 252); d.rect(M.l, this.y, this.cw, rh, 'F'); }
      if (marks[ri]) { d.setFillColor(255, 244, 229); d.rect(M.l, this.y, this.cw, rh, 'F'); }
      d.setDrawColor(...C.line);
      d.setLineWidth(0.1);
      d.line(M.l, this.y + rh, M.l + this.cw, this.y + rh);
      let x = M.l;
      cellLines.forEach((lines, i) => {
        lines.forEach((ln, k) => {
          const align = cols[i].align ?? 'left';
          const tx = align === 'right' ? x + widths[i] - pad : align === 'center' ? x + widths[i] / 2 : x + pad;
          d.text(ln, tx, this.y + pad + lh * (k + 0.75), { align });
        });
        x += widths[i];
      });
      this.y += rh;
    });
    this.y += 4;
  }

  kv(pairs) {
    this.table([{ h: 'Dato', w: 32 }, { h: 'Valor', w: 68 }], pairs.map(([k, v]) => [k, v]), { size: 8.5, headSize: 8 });
  }

  box(text, color = C.warn) {
    this.font(9, 'normal', color);
    const lines = this.doc.splitTextToSize(text, this.cw - 8);
    const h = lines.length * this.lineH(9) + 4;
    this.ensure(h + 2);
    this.doc.setDrawColor(...color);
    this.doc.setLineWidth(0.3);
    this.doc.rect(M.l, this.y, this.cw, h);
    lines.forEach((ln, k) => this.doc.text(ln, M.l + 4, this.y + 2 + this.lineH(9) * (k + 0.75)));
    this.y += h + 4;
  }
}

// ---------------------------------------------------------------------------------------------

/**
 * @param {Function} jsPDF constructor de jsPDF
 * @param {{regular: string, bold: string}} fonts TTF como cadenas binarias
 * @param {object} project
 * @param {object} ev evaluateProject(project)
 * @param {object} plan buildPlanModel(project, ev)
 * @param {Map<string,{dataUrl:string,width:number,height:number}>} photos fotos por id (ya reducidas)
 * @param {{generatedBy?: string, appVersion?: string}} meta
 */
export function buildReportPdf(jsPDF, fonts, project, ev, plan, photos, meta = {}) {
  const w = new Writer(jsPDF, fonts);
  const U = project.theodolite.convention.unit || 'sexagesimal';
  const A = (deg) => formatAngle(deg, U);
  const lev = ev.leveling.status === 'vigente' ? ev.leveling.accepted : null;
  const rad = ev.radiation.status === 'vigente' ? ev.radiation.accepted : null;
  const cont = ev.contours.status === 'vigente' ? ev.contours.accepted : null;
  const limitations = [];

  // ===================================================================== 1. Portada
  w.y = 40;
  w.font(10, 'bold', C.muted);
  w.doc.text('INFORME DE LEVANTAMIENTO TOPOGRÁFICO', PAGE.w / 2, w.y, { align: 'center' });
  w.y += 14;
  w.font(20, 'bold', C.accent);
  const titleLines = w.doc.splitTextToSize(project.name || PENDING('nombre del proyecto'), CW);
  titleLines.forEach((ln) => { w.doc.text(ln, PAGE.w / 2, w.y, { align: 'center' }); w.y += 9; });
  w.y += 8;
  w.toc.push({ level: 1, text: '1. Portada y datos del proyecto', page: 1 });
  const inst = (i) => [i.brand, i.model, i.serial ? `serie ${i.serial}` : ''].filter(Boolean).join(' · ');
  const val = (v, what) => (String(v ?? '').trim() ? String(v) : PENDING(what));
  w.kv([
    ['Ubicación', val(project.location, 'ubicación')],
    ['Fecha del levantamiento', val(project.date, 'fecha')],
    ['Responsables', val(project.responsibles, 'responsables')],
    ['Objetivo', val(project.objective, 'objetivo del levantamiento')],
    ['Sistema de coordenadas', val(project.coordinateSystem, 'sistema de coordenadas declarado')],
    ['Referencia de cotas', val(project.elevationDatum, 'referencia de cotas (p. ej., cota arbitraria del BM)')],
    ['Nivel de ingeniero', inst(project.instruments.level) || (project.leveling.rows.length ? PENDING('marca y modelo del nivel') : 'No se usó')],
    ['Teodolito', inst(project.instruments.theodolite) || (project.theodolite.readings.length ? PENDING('marca y modelo del teodolito') : 'No se usó')],
    ['Informe generado', `${new Date().toLocaleString('es')} — ${meta.generatedBy || 'usuario no indicado'}`],
  ]);
  const tocY = w.y + 4;

  // ===================================================================== 2. Memoria descriptiva
  w.newPage();
  w.h1('2. Memoria descriptiva');
  w.h2('2.1 Descripción del lugar');
  w.field(project.siteDescription, 'descripción del lugar');
  w.h2('2.2 Finalidad del levantamiento');
  w.field(project.objective, 'objetivo o finalidad');
  w.h2('2.3 Instrumentos utilizados');
  const levRows = project.leveling.rows.length;
  const radRows = project.theodolite.readings.length;
  w.bullets([
    `Nivel de ingeniero (altimetría y perfil longitudinal): ${inst(project.instruments.level) || (levRows ? PENDING('marca y modelo') : 'no se registraron lecturas')}. ${levRows ? `${levRows} lecturas registradas${lev ? ` en ${new Set(lev.rows.map((r) => r.station).join(' ').match(/E\d+/g)).size} estación(es) del instrumento` : ''}.` : ''}`,
    `Teodolito (planimetría por radiación): ${inst(project.instruments.theodolite) || (radRows ? PENDING('marca y modelo') : 'no se registraron lecturas')}. ${radRows ? `${radRows} puntos radiados desde ${project.theodolite.stations.map((s) => s.code).join(', ') || '—'}.` : ''}`,
  ]);
  w.h2('2.4 Procedimiento de campo');
  w.field(project.fieldProcedure, 'procedimiento de campo');
  const auto = [];
  if (lev) auto.push(`Nivelación geométrica desde el BM ${project.leveling.bm.code} (cota ${fmt(project.leveling.bm.elevation)} m) por el método de altura de instrumento${lev.closure.applicable ? `, cerrando en ${lev.closure.code}` : ''}.`);
  if (rad) auto.push(`Radiación desde ${rad.stations.map((s) => s.code).join(', ')}; orientación ${project.theodolite.convention.origin === 'norte' ? 'con el círculo horizontal orientado al norte' : `con vista atrás a ${rad.stations.map((s) => s.refCode).join(', ')}`}; lecturas en sentido ${project.theodolite.convention.direction}.`);
  if (auto.length) w.para(`Según los registros de la libreta: ${auto.join(' ')}`, { color: C.muted, size: 9 });
  w.h2('2.5 Sistema de referencia');
  w.para(`Coordenadas: ${val(project.coordinateSystem, 'sistema de coordenadas')}. Cotas: ${val(project.elevationDatum, 'referencia de cotas')}. Unidades: metros; ángulos en ${U === 'centesimal' ? 'grados centesimales (g)' : 'grados sexagesimales (° \' ")'}.`);

  // ===================================================================== 3. Libreta de campo
  w.newPage();
  w.h1('3. Libreta de campo (registros originales)');
  const editedIds = new Set(project.audit.filter((a) => a.action === 'editar').map((a) => a.entityId));
  w.h2('3.1 Nivel de ingeniero — libreta de nivelación');
  if (levRows === 0) w.para('No se registraron lecturas de nivelación.', { color: C.muted });
  else {
    const L = project.leveling;
    w.para(`BM inicial: ${L.bm.code || '—'}, cota conocida ${isNum(L.bm.elevation) ? `${fmt(L.bm.elevation)} m` : PENDING('cota')}. ${L.endBm.enabled ? `BM final de control: ${L.endBm.code}, cota conocida ${fmt(L.endBm.elevation)} m.` : 'Sin BM final de control.'}`, { size: 9 });
    w.table(
      [{ h: 'N.°', w: 5, align: 'right' }, { h: 'Punto', w: 11 }, { h: 'VA (m)', w: 9, align: 'right' }, { h: 'VI (m)', w: 9, align: 'right' }, { h: 'VD (m)', w: 9, align: 'right' },
        { h: 'Dist. parcial (m)', w: 10, align: 'right' }, { h: 'Progresiva (m)', w: 10, align: 'right' }, { h: 'En perfil', w: 7, align: 'center' }, { h: 'Observaciones', w: 22 }],
      L.rows.map((r, i) => [`${i + 1}${editedIds.has(r.id) ? '*' : ''}`, r.code, fmtOpt(r.bs), fmtOpt(r.is), fmtOpt(r.fs), fmtOpt(r.partial), fmtOpt(r.cumulative), r.inProfile === false ? 'no' : 'sí', r.notes ?? '']),
      { marks: L.rows.map((r) => editedIds.has(r.id)) },
    );
  }
  w.h2('3.2 Teodolito — libreta de radiación');
  const T = project.theodolite;
  if (T.stations.length === 0 && radRows === 0) w.para('No se registraron lecturas con teodolito.', { color: C.muted });
  else {
    w.para(`Convenio angular: unidad ${U}; sentido de lectura ${DIRECTION_LABEL[T.convention.direction] ?? PENDING('sentido')}; origen de los ángulos: ${ORIGIN_LABEL[T.convention.origin] ?? PENDING('origen')}.`, { size: 9 });
    w.table(
      [{ h: 'Estación', w: 10 }, { h: 'E (m)', w: 12, align: 'right' }, { h: 'N (m)', w: 12, align: 'right' }, { h: 'Coordenadas', w: 11 }, { h: 'Orientación', w: 40 }],
      T.stations.map((s) => [s.code, fmtOpt(s.east), fmtOpt(s.north), s.coordType === 'conocidas' ? 'conocidas' : 'locales declaradas', orientationText(s, T.convention)]),
    );
    const stCode = new Map(T.stations.map((s) => [s.id, s.code]));
    w.table(
      [{ h: 'N.°', w: 5, align: 'right' }, { h: 'Estación', w: 9 }, { h: 'Punto', w: 9 }, { h: 'Lectura horizontal', w: 13 }, { h: 'Distancia', w: 12 }, { h: 'Dist. (m)', w: 9, align: 'right' }, { h: 'Áng. vertical', w: 13 }, { h: 'Descripción', w: 22 }],
      T.readings.map((r, i) => [`${i + 1}${editedIds.has(r.id) ? '*' : ''}`, stCode.get(r.stationId) ?? '(no existe)', r.code, r.hz, r.distType ?? '', fmtOpt(r.distance), r.distType === 'inclinada' ? `${r.vType === 'cenital' ? 'Z' : 'α'} = ${r.vAngle}` : '—', pointDesc(project, r.code)]),
      { marks: T.readings.map((r) => editedIds.has(r.id)) },
    );
  }
  w.h2('3.3 Registro de correcciones');
  const corrections = project.audit.filter((a) => a.action === 'editar' || a.action === 'eliminar');
  if (corrections.length === 0) w.para('No se registraron correcciones de datos de campo.', { color: C.muted });
  else {
    w.para('Las filas marcadas con * fueron corregidas. Se conserva el valor original registrado en campo:', { size: 9 });
    w.table(
      [{ h: 'Fecha y hora', w: 15 }, { h: 'Usuario', w: 11 }, { h: 'Acción', w: 8 }, { h: 'Registro', w: 18 }, { h: 'Campo', w: 13 }, { h: 'Valor original', w: 12 }, { h: 'Valor nuevo', w: 12 }, { h: 'Motivo', w: 15 }],
      corrections.flatMap((a) => (a.changes?.length ? a.changes : [{ label: '—', from: '', to: '' }]).map((c) => [fmtDate(a.at), a.by || '—', a.action, a.label, c.label, c.from === '' ? '' : auditValue(c.from), c.to === '' ? '' : auditValue(c.to), a.reason || ''])),
      { size: 7.2, headSize: 7.2 },
    );
  }

  // ===================================================================== 4. Memoria de cálculo
  w.newPage();
  w.h1('4. Memoria de cálculo');
  w.h2('4.1 Nivelación geométrica (nivel de ingeniero)');
  if (!lev) {
    w.para(levRows ? 'Resultados no calculados.' : 'No se registró nivelación.', { color: C.muted });
  } else {
    w.formulaBlock([
      'Altura de instrumento:  AI = cota del punto de referencia + vista atrás (VA)',
      'Cota del punto observado:  Cota = AI − vista intermedia (VI)   o   Cota = AI − vista adelante (VD)',
      'Cambio de estación: la cota del punto de cambio (calculada con su VD) más la nueva VA da la AI de la estación siguiente.',
      'Comprobación aritmética:  ΣVA − ΣVD = cota final − cota inicial',
    ]);
    w.table(
      [{ h: 'N.°', w: 4, align: 'right' }, { h: 'Est.', w: 8 }, { h: 'Punto', w: 10 }, { h: 'VA', w: 8, align: 'right' }, { h: 'VI', w: 8, align: 'right' }, { h: 'VD', w: 8, align: 'right' },
        { h: 'AI (m)', w: 10, align: 'right' }, { h: 'Cota (m)', w: 10, align: 'right' }, { h: 'Dist. parcial (m)', w: 10, align: 'right' }, { h: 'Dist. acumulada (m)', w: 11, align: 'right' }],
      lev.rows.map((r) => [r.n, r.station, r.code, fmtOpt(r.bs), fmtOpt(r.is), fmtOpt(r.fs), fmtOpt(r.hiNew ?? r.hiUsed), fmtOpt(r.elevation), fmtOpt(r.partial), fmtOpt(r.cumulative)]),
    );
    w.para('Cálculo paso a paso:', { style: 'bold', size: 9.5 });
    w.formulaBlock(lev.rows.flatMap((r) => r.steps.map((s) => `${r.n}. ${s}`)));
    w.para('Comprobación de la libreta:', { style: 'bold', size: 9.5 });
    const s = lev.sums;
    const checkLines = [`ΣVA = ${fmt(s.sumBs)} m     ΣVD = ${fmt(s.sumFs)} m     ΣVA − ΣVD = ${fmt(s.sumBs)} − ${fmt(s.sumFs)} = ${fmt(s.diff)} m`];
    if (s.applicable) checkLines.push(`Cota final (${s.endCode}) − cota inicial = ${fmt(s.endElevation)} − ${fmt(s.startElevation)} = ${fmt(s.elevDiff)} m → ${s.ok ? 'COINCIDE' : 'NO COINCIDE'}`);
    checkLines.push(s.explanation);
    w.formulaBlock(checkLines);
    w.para('Error de cierre:', { style: 'bold', size: 9.5 });
    w.formulaBlock([lev.closure.explanation]);
    if (!lev.closure.applicable) limitations.push('Nivelación sin BM final de cota conocida: el cierre no se pudo comprobar contra un control externo.');
    else limitations.push(`Error de cierre de la nivelación: ${fmtSigned(lev.closure.error)} m (cota calculada ${fmt(lev.closure.computed)} m frente a la conocida ${fmt(lev.closure.known)} m en ${lev.closure.code}). El informe no aplica compensación.`);
    lev.warnings.forEach((x) => limitations.push(x.message));
  }

  w.h2('4.2 Radiación con teodolito');
  if (!rad) {
    w.para(radRows ? 'Resultados no calculados.' : 'No se registraron lecturas con teodolito.', { color: C.muted });
  } else {
    w.formulaBlock([
      `Convenio angular: ${U}; sentido ${T.convention.direction}; origen ${ORIGIN_LABEL[T.convention.origin]}.`,
      T.convention.origin === 'norte' ? (T.convention.direction === 'horario' ? 'Azimut: Az = L (lectura horizontal con 0 hacia el norte)' : 'Azimut: Az = 360° − L') : `Azimut: Az = Az_ref ${T.convention.direction === 'horario' ? '+' : '−'} (L − L_ref), normalizado al intervalo [0°, 360°)`,
      'Distancia horizontal: medida, o reducida de la inclinada: Dh = Di · sen(Z)  ·  Dh = Di · cos(α)',
      'Proyecciones: ΔE = Dh · sen(Az)     ΔN = Dh · cos(Az)',
      'Coordenadas: E = E_estación + ΔE     N = N_estación + ΔN',
    ]);
    for (const st of rad.stations) {
      w.para(`Estación ${st.code}: E = ${fmt(st.east)} m, N = ${fmt(st.north)} m (${st.coordType === 'conocidas' ? 'coordenadas conocidas' : 'coordenadas locales declaradas'}).`, { size: 9, style: 'bold' });
      w.formulaBlock(st.steps);
    }
    w.table(
      [{ h: 'Punto', w: 9 }, { h: 'Est.', w: 8 }, { h: 'Lectura Hz', w: 13 }, { h: 'Azimut', w: 13 }, { h: 'Dh (m)', w: 10, align: 'right' }, { h: 'ΔE (m)', w: 10, align: 'right' }, { h: 'ΔN (m)', w: 10, align: 'right' }, { h: 'Este (m)', w: 12, align: 'right' }, { h: 'Norte (m)', w: 12, align: 'right' }],
      rad.points.map((p) => [p.code, p.stationCode, A(p.hzDeg), A(p.az), fmt(p.dh), fmt(p.dE), fmt(p.dN), fmt(p.east), fmt(p.north)]),
    );
    w.para('Cálculo paso a paso:', { style: 'bold', size: 9.5 });
    for (const p of rad.points) {
      w.formulaBlock([`Punto ${p.code} (lectura horizontal L = ${A(p.hzDeg)}):`, ...p.azSteps, ...p.dhSteps, ...p.coordSteps]);
    }
    limitations.push('La radiación no tiene comprobación propia: cada punto se fija con una sola visual, por lo que un error de lectura o de distancia no se detecta en el cálculo.');
  }

  w.h2('4.3 Coordenadas y cotas de los puntos (vinculación por código)');
  w.para('La posición planimétrica proviene del teodolito y la cota del nivel de ingeniero. Ambos se vinculan solo cuando el código del punto es idéntico en el mismo proyecto; nunca por proximidad ni por parecido del nombre.', { size: 9 });
  w.table(
    [{ h: 'Código', w: 10 }, { h: 'Este (m)', w: 12, align: 'right' }, { h: 'Norte (m)', w: 12, align: 'right' }, { h: 'Cota (m)', w: 10, align: 'right' }, { h: 'Origen de la cota', w: 20 }, { h: 'Estado', w: 26 }],
    ev.survey.rows.map((r) => [r.code, fmtOpt(r.east), fmtOpt(r.north), fmtOpt(r.elevation), r.elevSource || '—', r.status]),
  );
  ev.survey.warnings.forEach((x) => limitations.push(x));
  const noZ = ev.survey.rows.filter((r) => isNum(r.east) && !isNum(r.elevation)).map((r) => r.code);
  if (noZ.length) limitations.push(`Puntos con posición pero sin cota (no participan en la triangulación ni en las curvas de nivel): ${noZ.join(', ')}.`);

  w.h2('4.4 Triangulación y curvas de nivel');
  if (cont?.ok) {
    w.formulaBlock([`Método de triangulación: ${cont.method}`, `Método de interpolación: ${cont.interpolation}`,
      `Equidistancia: ${fmt(cont.equidistance, 2)} m. Puntos usados: ${cont.usedPoints.length}. Triángulos: ${cont.triangles.length}. Cotas de ${fmt(cont.minZ)} a ${fmt(cont.maxZ)} m.`,
      `Curvas generadas: ${cont.levels.map((l) => fmt(l, 2)).join(', ')} m.`]);
    cont.warnings.forEach((x) => limitations.push(x));
    limitations.push('Las curvas de nivel son una interpolación lineal entre puntos levantados: su exactitud depende de la densidad y ubicación de los puntos (cambios de pendiente entre puntos no se representan).');
  } else {
    const reason = ev.contours.status === 'sin-datos' ? 'No se definió la equidistancia: no se calcularon curvas de nivel.' : (cont?.reason || 'No se calcularon curvas de nivel.');
    w.para(reason, { color: C.muted });
    limitations.push(`Curvas de nivel no generadas: ${reason}`);
  }

  // ===================================================================== 5. Resultados gráficos
  if (lev?.profile.ok) {
    w.newPage('landscape');
    w.h1('5. Resultados gráficos');
    drawProfile(w, lev.profile.points, project);
  } else {
    w.newPage();
    w.h1('5. Resultados gráficos');
    w.h2('5.1 Perfil longitudinal');
    const why = lev ? [lev.profile.reason, ...lev.profile.errors.map((e) => e.message), ...lev.profile.missing.map((m) => m.message)].filter(Boolean) : ['No hay nivelación calculada.'];
    w.para('No se dibujó el perfil longitudinal porque faltan datos:', { color: C.warn });
    w.bullets(why, { color: C.warn, size: 9 });
    limitations.push(`Perfil longitudinal no generado: ${why[0]}`);
  }
  if (plan?.ok) {
    drawPlanPage(w, plan, project, false);
    if (plan.contours) drawPlanPage(w, plan, project, true);
    plan.warnings.forEach((x) => limitations.push(x));
  } else {
    w.h2('5.2 Plano planimétrico');
    w.para(`No se dibujó el plano: ${plan?.reason || 'no hay radiación calculada.'}`, { color: C.warn });
  }

  // ===================================================================== 6. Panel fotográfico
  w.newPage();
  w.h1('6. Panel fotográfico');
  const withPhoto = project.points.filter((p) => p.photo && photos.has(p.photo.id));
  const brokenPhoto = project.points.filter((p) => p.photo && !photos.has(p.photo.id));
  const noPhoto = project.points.filter((p) => !p.photo || !photos.has(p.photo.id));
  if (withPhoto.length === 0) w.para('No hay fotografías registradas en el proyecto.', { color: C.muted });
  const cellH = 112;
  withPhoto.forEach((pt) => {
    if (w.y + cellH > w.bottom) w.newPage();
    const ph = photos.get(pt.photo.id);
    const boxW = CW;
    const boxH = 92;
    const ratio = Math.min(boxW / ph.width, boxH / ph.height);
    const iw = ph.width * ratio;
    const ih = ph.height * ratio;
    w.doc.setDrawColor(...C.line);
    w.doc.rect(M.l, w.y, boxW, boxH);
    w.doc.addImage(ph.dataUrl, 'JPEG', M.l + (boxW - iw) / 2, w.y + (boxH - ih) / 2, iw, ih, undefined, 'FAST');
    w.y += boxH + 2;
    w.para(`${pt.code} — ${pt.description || 'sin descripción'}`, { style: 'bold', size: 9.5, gap: 0.5 });
    w.para(pt.photo.caption ? `Pie de foto: ${pt.photo.caption}` : 'Pie de foto: (sin pie de foto)', { size: 9, color: C.muted, gap: 5 });
  });
  if (noPhoto.length) {
    w.para('Puntos sin fotografía:', { style: 'bold', size: 9.5 });
    w.table([{ h: 'Código', w: 12 }, { h: 'Descripción', w: 40 }, { h: 'Fotografía', w: 25 }], noPhoto.map((p) => [p.code, p.description || '—', 'Sin fotografía registrada']));
  }
  if (brokenPhoto.length) limitations.push(`Fotografías referenciadas que no se encontraron en el dispositivo: ${brokenPhoto.map((p) => p.code).join(', ')}.`);

  // ===================================================================== 7. Observaciones y limitaciones
  w.newPage();
  w.h1('7. Observaciones y limitaciones');
  w.h2('7.1 Observaciones del equipo');
  w.field(project.observations, 'observaciones del levantamiento');
  w.h2('7.2 Controles, cálculos pendientes y datos no verificados');
  const pend = [];
  for (const [label, m] of [['Nivelación', ev.leveling], ['Radiación', ev.radiation], ['Curvas de nivel', ev.contours]]) {
    if (m.status === 'sin-calcular') pend.push(`${label}: hay datos registrados pero no se calcularon.`);
    if (m.status === 'desactualizado') pend.push(`${label}: resultados desactualizados.`);
  }
  if (lev?.sums.applicable) limitations.unshift(`Comprobación aritmética de la libreta de nivelación: ${lev.sums.ok ? 'correcta' : 'NO coincide'} (ΣVA − ΣVD = ${fmt(lev.sums.diff)} m).`);
  const missingFields = [['siteDescription', 'descripción del lugar'], ['objective', 'objetivo'], ['fieldProcedure', 'procedimiento de campo'], ['responsibles', 'responsables'], ['coordinateSystem', 'sistema de coordenadas'], ['location', 'ubicación'], ['date', 'fecha']]
    .filter(([k]) => !String(project[k] ?? '').trim()).map(([, l]) => l);
  if (missingFields.length) pend.push(`Datos del proyecto por completar: ${missingFields.join(', ')}.`);
  if (project.legacy) pend.push(`El proyecto contiene datos importados de la versión Android que quedaron fuera del flujo simplificado (${project.legacy.summary ?? 'ver archivo heredado'}). No se usan en los cálculos de este informe.`);
  w.bullets([...pend.map((t) => ({ text: t, color: C.warn })), ...limitations]);
  if (pend.length === 0 && limitations.length === 0) w.para('Sin observaciones adicionales.');

  // ===================================================================== Índice, encabezados y numeración
  const total = w.doc.getNumberOfPages();
  w.doc.setPage(1);
  w.y = tocY;
  w.font(11, 'bold', C.text);
  w.doc.text('Contenido', M.l, w.y);
  w.y += 6;
  for (const e of w.toc.filter((t) => t.level === 1)) {
    w.font(9.5, 'normal', C.text);
    w.doc.text(e.text, M.l + 2, w.y);
    w.doc.text(String(e.page), M.l + CW, w.y, { align: 'right' });
    w.y += 5.5;
  }
  for (let i = 1; i <= total; i++) {
    w.doc.setPage(i);
    const size = w.doc.internal.pageSize;
    const pw = size.getWidth();
    const phh = size.getHeight();
    w.font(7.5, 'normal', C.muted);
    if (i > 1) {
      w.doc.text(project.name || '', M.l, 12);
      w.doc.text('Informe de levantamiento topográfico', pw - M.r, 12, { align: 'right' });
      w.doc.setDrawColor(...C.line);
      w.doc.setLineWidth(0.2);
      w.doc.line(M.l, 14, pw - M.r, 14);
    }
    w.doc.text(`Página ${i} de ${total}`, pw - M.r, phh - 8, { align: 'right' });
    w.doc.text(`TopoScribePro ${meta.appVersion ?? ''}`, M.l, phh - 8);
  }
  return { bytes: new Uint8Array(w.doc.output('arraybuffer')), pages: total, toc: w.toc };
}

function fmtOpt(v) { return isNum(v) ? fmt(v) : ''; }
function fmtDate(iso) { try { return new Date(iso).toLocaleString('es'); } catch { return iso; } }
function pointDesc(project, code) { return project.points.find((p) => normCode(p.code) === normCode(code))?.description ?? ''; }

function orientationText(s, conv) {
  const o = s.orientation ?? {};
  if (conv.origin === 'norte') return `Círculo orientado al norte: ${o.northNote || '—'}`;
  const az = o.azMode === 'coordenadas' ? `azimut calculado con las coordenadas de ${o.refCode} (E ${fmtOpt(o.refEast)}, N ${fmtOpt(o.refNorth)})` : `azimut ingresado ${o.refAzimuth || '—'}${o.azSource ? ` (${o.azSource})` : ''}`;
  return `Vista atrás a ${o.refCode || '—'}; ${az}; lectura en la referencia L_ref = ${o.refReading || '—'}`;
}

// ---------------------------------------------------------------------------------------------
// Perfil longitudinal (hoja A4 apaisada)
// ---------------------------------------------------------------------------------------------
function drawProfile(w, points, project) {
  const d = w.doc;
  const lay = profileLayout(points, { hScale: project.leveling.profileScales?.h, vScale: project.leveling.profileScales?.v });
  w.h2('5.1 Perfil longitudinal');
  w.font(9, 'normal', C.text);
  d.text(`Escala horizontal 1:${lay.hScale}   ·   Escala vertical 1:${lay.vScale}   ·   Exageración vertical ${fmt(lay.exaggeration, 1)}×   ·   Plano de comparación: cota ${fmt(lay.datum)} m`, M.l, w.y);
  const x0 = 40;
  const y0 = w.y + 4;
  const X = (c) => x0 + lay.x(c);
  const Y = (z) => y0 + lay.y(z);
  // cuadrícula y ejes
  d.setLineWidth(0.1);
  d.setDrawColor(225, 228, 232);
  for (const z of lay.zTicks) d.line(x0, Y(z), x0 + lay.usedWidth, Y(z));
  for (const c of lay.chTicks) d.line(X(c), y0, X(c), y0 + lay.height);
  d.setDrawColor(...C.text);
  d.setLineWidth(0.3);
  d.line(x0, y0, x0, y0 + lay.height);
  d.line(x0, y0 + lay.height, x0 + lay.usedWidth, y0 + lay.height);
  w.font(7, 'normal', C.text);
  for (const z of lay.zTicks) d.text(fmt(z, 2), x0 - 1.5, Y(z) + 1, { align: 'right' });
  for (const c of lay.chTicks) d.text(fmtChainage(c, 0), X(c), y0 + lay.height + 3.5, { align: 'center' });
  w.font(7.5, 'bold', C.text);
  d.text('Cota (m)', x0 - 16, y0 + lay.height / 2, { angle: 90 });
  d.text('Progresiva (m)', x0 + lay.usedWidth / 2, y0 + lay.height + 7.5, { align: 'center' });
  // perfil del terreno
  d.setDrawColor(...C.accent);
  d.setLineWidth(0.5);
  for (let i = 1; i < points.length; i++) d.line(X(points[i - 1].chainage), Y(points[i - 1].elevation), X(points[i].chainage), Y(points[i].elevation));
  d.setFillColor(...C.accent);
  w.font(6.8, 'normal', C.text);
  for (const p of points) {
    d.circle(X(p.chainage), Y(p.elevation), 0.7, 'F');
    d.text(p.code, X(p.chainage), Y(p.elevation) - 2, { align: 'center' });
  }
  // guitarra (datos bajo el perfil)
  const gy = y0 + lay.height + 11;
  const rowH = 16;
  d.setDrawColor(...C.text);
  d.setLineWidth(0.2);
  d.rect(M.l, gy, x0 - M.l + lay.usedWidth, rowH * 2);
  d.line(M.l, gy + rowH, x0 + lay.usedWidth, gy + rowH);
  d.line(x0, gy, x0, gy + rowH * 2);
  w.font(7, 'bold', C.text);
  d.text('Cota (m)', M.l + 1.5, gy + rowH / 2 + 1);
  d.text('Progresiva', M.l + 1.5, gy + rowH + rowH / 2 + 1);
  w.font(6.5, 'normal', C.text);
  for (const p of points) {
    d.text(fmt(p.elevation), X(p.chainage) + 1, gy + rowH - 1.5, { angle: 90 });
    d.text(fmtChainage(p.chainage), X(p.chainage) + 1, gy + 2 * rowH - 1.5, { angle: 90 });
  }
  if (lay.errors.length) { w.font(7.5, 'normal', C.warn); d.text(lay.errors.join(' '), M.l, gy + 2 * rowH + 6); }
  w.y = gy + 2 * rowH + 10;
}

// ---------------------------------------------------------------------------------------------
// Plano planimétrico y plano de triangulación + curvas (hoja A4 vertical, misma escala)
// ---------------------------------------------------------------------------------------------
function drawPlanPage(w, plan, project, contoursMode) {
  w.newPage('portrait');
  const d = w.doc;
  const L = plan.layout;
  w.h2(contoursMode ? '5.3 Triangulación y curvas de nivel' : '5.2 Plano planimétrico');
  const x0 = M.l + (CW - L.width) / 2;
  const y0 = w.y;
  const X = (e) => x0 + L.x(e);
  const Y = (n) => y0 + L.y(n);
  d.setDrawColor(...C.text);
  d.setLineWidth(0.3);
  d.rect(x0, y0, L.width, L.height);
  // cuadrícula con coordenadas
  d.setDrawColor(230, 232, 236);
  d.setLineWidth(0.1);
  w.font(6, 'normal', C.muted);
  for (const e of L.gridE) { d.line(X(e), y0, X(e), y0 + L.height); d.text(`E ${fmt(e, 0)}`, X(e), y0 + L.height + 3, { align: 'center' }); }
  for (const n of L.gridN) { d.line(x0, Y(n), x0 + L.width, Y(n)); d.text(`N ${fmt(n, 0)}`, x0 - 1, Y(n) + 1, { align: 'right', angle: 0 }); }

  if (contoursMode) {
    d.setDrawColor(...C.tri);
    d.setLineWidth(0.12);
    for (const [a, b] of uniqueEdges(plan.triangles)) d.line(X(a.east), Y(a.north), X(b.east), Y(b.north));
    d.setDrawColor(...C.contour);
    for (const pl of plan.contours.polylines) {
      const major = Math.abs(pl.level / (plan.contours.equidistance * 5) - Math.round(pl.level / (plan.contours.equidistance * 5))) < 1e-6;
      d.setLineWidth(major ? 0.45 : 0.25);
      for (let i = 1; i < pl.points.length; i++) d.line(X(pl.points[i - 1].east), Y(pl.points[i - 1].north), X(pl.points[i].east), Y(pl.points[i].north));
      const lp = polylineLabelPoint(pl.points);
      w.font(6, 'bold', C.contour);
      d.text(fmt(pl.level, 2), X(lp.east), Y(lp.north) - 0.6, { align: 'center' });
    }
  } else {
    d.setDrawColor(...C.ray);
    d.setLineWidth(0.15);
    d.setLineDashPattern([1, 0.8], 0);
    for (const r of plan.rays) d.line(X(r.from.east), Y(r.from.north), X(r.to.east), Y(r.to.north));
    d.setLineDashPattern([], 0);
    d.setDrawColor(...C.userLine);
    d.setLineWidth(0.4);
    for (const l of plan.lines) for (let i = 1; i < l.pts.length; i++) d.line(X(l.pts[i - 1].east), Y(l.pts[i - 1].north), X(l.pts[i].east), Y(l.pts[i].north));
  }
  // puntos y estaciones
  d.setFillColor(...C.text);
  for (const p of plan.points) {
    d.circle(X(p.east), Y(p.north), 0.55, 'F');
    w.font(6.3, 'bold', C.text);
    d.text(p.code, X(p.east) + 1.2, Y(p.north) - 1);
    if (contoursMode && isNum(p.elevation)) { w.font(5.6, 'normal', C.muted); d.text(fmt(p.elevation), X(p.east) + 1.2, Y(p.north) + 2.2); }
  }
  d.setFillColor(200, 30, 30);
  for (const s of plan.stations) {
    const sx = X(s.east); const sy = Y(s.north);
    d.triangle(sx, sy - 1.6, sx - 1.4, sy + 1, sx + 1.4, sy + 1, 'F');
    w.font(6.8, 'bold', [200, 30, 30]);
    d.text(s.code, sx + 1.8, sy + 2.6);
  }
  // norte
  const nx = x0 + L.width - 10;
  const ny = y0 + 6;
  d.setDrawColor(...C.text);
  d.setFillColor(...C.text);
  d.setLineWidth(0.3);
  d.triangle(nx, ny, nx - 2.2, ny + 7, nx + 2.2, ny + 7, 'F');
  d.line(nx, ny + 7, nx, ny + 12);
  w.font(9, 'bold', C.text);
  d.text('N', nx, ny + 16, { align: 'center' });
  // barra de escala y leyenda
  const by = y0 + L.height + 9;
  w.font(8, 'bold', C.text);
  d.text(`Escala 1:${L.scale}`, x0, by);
  d.setLineWidth(0.4);
  d.line(x0, by + 3, x0 + L.barMm, by + 3);
  d.line(x0, by + 2, x0, by + 4);
  d.line(x0 + L.barMm / 2, by + 2.3, x0 + L.barMm / 2, by + 3.7);
  d.line(x0 + L.barMm, by + 2, x0 + L.barMm, by + 4);
  w.font(7, 'normal', C.text);
  d.text('0', x0, by + 7, { align: 'center' });
  d.text(`${fmt(L.barM, 0)} m`, x0 + L.barMm, by + 7, { align: 'center' });
  w.font(7.2, 'normal', C.text);
  const legend = contoursMode
    ? [`Triangulación de Delaunay (gris) y curvas de nivel cada ${fmt(plan.contours.equidistance, 2)} m por interpolación lineal (marrón; trazo grueso cada 5 curvas).`, `Sistema de coordenadas: ${project.coordinateSystem || '(no declarado)'}. Unidades: metros.`]
    : ['▲ Estación   ● Punto radiado   - - - Visual (radiación)   ── Línea definida por el usuario', `Sistema de coordenadas: ${project.coordinateSystem || '(no declarado)'}. Unidades: metros.`];
  legend.forEach((t, i) => {
    const lines = d.splitTextToSize(t, L.width - L.barMm - 15);
    lines.forEach((ln, k) => d.text(ln, x0 + L.barMm + 12, by + i * 7 + k * 3.2));
  });
  w.y = by + 14;
  if (!contoursMode) {
    const rows = [...plan.stations.map((s) => [`${s.code} (estación)`, fmt(s.east), fmt(s.north), fmtOpt(s.elevation)]), ...plan.points.map((p) => [p.code, fmt(p.east), fmt(p.north), fmtOpt(p.elevation)])];
    if (w.y + 7 + rows.length * 4.6 <= w.bottom) {
      w.table([{ h: 'Código', w: 10 }, { h: 'Este (m)', w: 12, align: 'right' }, { h: 'Norte (m)', w: 12, align: 'right' }, { h: 'Cota (m)', w: 10, align: 'right' }], rows, { size: 7, headSize: 7 });
    } else {
      w.para('Coordenadas de cada punto: ver la tabla de la sección 4.3.', { size: 8.5, color: C.muted });
    }
  }
}
