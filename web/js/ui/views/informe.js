import { html, useEffect, useRef, useState } from '../../../vendor/preact-htm.mjs';
import { Btn, Notice, Select, StatusBadge, toast } from '../components.js';
import { evaluateProject } from '../../core/model.js';
import { buildPlanModel } from '../../core/plan.js';
import { buildReportPdf } from '../../export/pdf.js';
import { buildTables } from '../../export/tables.js';
import { buildCsvZip, buildXlsx } from '../../export/xlsx.js';
import { downloadBlob, fetchBinaryString, loadScript, photosForPdf, safeFileName, today } from '../files.js';

const REQUIRED_FIELDS = [['name', 'nombre'], ['location', 'ubicación'], ['date', 'fecha'], ['responsibles', 'responsables'], ['objective', 'objetivo'], ['coordinateSystem', 'sistema de coordenadas'], ['siteDescription', 'descripción del lugar'], ['fieldProcedure', 'procedimiento de campo']];

/** Motivos que impiden generar el informe (resultados sin calcular o desactualizados). */
export function reportBlockers(ev) {
  const out = [];
  const lv = ev.leveling;
  if (lv.status === 'sin-calcular' || lv.status === 'desactualizado') out.push(lv.current.computable ? `Nivelación ${lv.status === 'sin-calcular' ? 'sin calcular' : 'desactualizada'}: pulse «Calcular» en Nivel de ingeniero.` : 'La libreta de nivelación tiene errores: corríjalos antes de generar el informe.');
  const rd = ev.radiation;
  if (rd.status === 'sin-calcular' || rd.status === 'desactualizado') out.push(rd.current.computable ? `Radiación ${rd.status === 'sin-calcular' ? 'sin calcular' : 'desactualizada'}: pulse «Calcular» en Teodolito.` : 'La libreta del teodolito tiene errores o datos incompletos: corríjalos antes de generar el informe.');
  const ct = ev.contours;
  if ((ct.status === 'sin-calcular' || ct.status === 'desactualizado') && ct.current.ok && out.length === 0) out.push(`Curvas de nivel ${ct.status === 'sin-calcular' ? 'sin calcular' : 'desactualizadas'}: pulse «Calcular» en Plano y curvas de nivel.`);
  return out;
}

async function generatePdf(ctx) {
  await ctx.saveChain.current;
  await loadScript('vendor/jspdf.umd.min.js');
  const fonts = { regular: await fetchBinaryString('vendor/fonts/DejaVuSans-subset.ttf'), bold: await fetchBinaryString('vendor/fonts/DejaVuSans-Bold-subset.ttf') };
  const project = ctx.project;
  const ev = evaluateProject(project);
  const plan = buildPlanModel(project, ev);
  const photos = await photosForPdf(project);
  return buildReportPdf(window.jspdf.jsPDF, fonts, project, ev, plan, photos, { generatedBy: ctx.user, appVersion: ctx.appVersion });
}

export function InformeView({ ctx }) {
  const { project: p, ev } = ctx;
  const [busy, setBusy] = useState('');
  const [preview, setPreview] = useState(null); // {bytes, pages, at}
  const [decimal, setDecimal] = useState(() => ((1.5).toLocaleString().includes(',') ? ',' : '.'));
  const plan = buildPlanModel(p, ev);
  const blockers = reportBlockers(ev);
  const missing = REQUIRED_FIELDS.filter(([k]) => !String(p[k] ?? '').trim()).map(([, l]) => l);
  const withPhoto = p.points.filter((x) => x.photo).length;
  const fresh = preview && preview.at === p.updatedAt;
  const reason = busy ? 'Generando…' : !p.name.trim() ? 'El proyecto no tiene nombre.' : blockers[0] ?? null;

  const doPreview = async () => {
    setBusy('preview');
    try {
      const out = await generatePdf(ctx);
      setPreview({ ...out, at: p.updatedAt });
    } catch (e) { toast(`No se pudo generar la vista previa: ${e.message}`, 'err', 9000); } finally { setBusy(''); }
  };
  const doDownload = async () => {
    setBusy('pdf');
    try {
      const out = fresh ? preview : { ...(await generatePdf(ctx)), at: p.updatedAt };
      if (!fresh) setPreview(out);
      downloadBlob(`Informe_${safeFileName(p.name)}_${today()}.pdf`, new Blob([out.bytes], { type: 'application/pdf' }));
      toast(`Informe PDF generado (${out.pages} páginas).`, 'ok');
    } catch (e) { toast(`No se pudo generar el informe: ${e.message}`, 'err', 9000); } finally { setBusy(''); }
  };
  const doXlsx = () => {
    const bytes = buildXlsx(buildTables(p, ev));
    downloadBlob(`Libreta_y_calculos_${safeFileName(p.name)}_${today()}.xlsx`, new Blob([bytes], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
    toast('Excel exportado: hojas de DATOS DE CAMPO y de RESULTADOS por separado.', 'ok');
  };
  const doCsv = () => {
    const bytes = buildCsvZip(buildTables(p, ev), decimal);
    downloadBlob(`CSV_${safeFileName(p.name)}_${today()}.zip`, new Blob([bytes], { type: 'application/zip' }));
    toast('CSV exportados en un .zip (archivos DATOS-CAMPO y RESULTADOS).', 'ok');
  };
  const calcAll = async () => {
    setBusy('calc');
    try {
      let e = evaluateProject(ctx.project);
      if (e.leveling.status !== 'vigente' && e.leveling.status !== 'sin-datos' && e.leveling.current.computable) await ctx.calc('leveling');
      e = evaluateProject(ctx.project);
      if (e.radiation.status !== 'vigente' && e.radiation.status !== 'sin-datos' && e.radiation.current.computable) await ctx.calc('radiation');
    } finally { setBusy(''); }
  };
  const calcContoursIfPossible = async () => { await ctx.calc('contours'); };

  const item = (ok, title, detail, kind) => html`<li><span class=${`badge ${kind ?? (ok ? 'ok' : 'warn')}`}>${ok ? '✓' : '!'}</span><div><strong>${title}</strong><div class="small muted">${detail}</div></div></li>`;
  const lev = ev.leveling.accepted;
  return html`<div>
    <div class="page-title"><h2>Informe de levantamiento topográfico</h2></div>
    <div class="card">
      <h3>Estado del informe</h3>
      <ul class="checklist" data-testid="report-checklist">
        ${item(missing.length === 0, 'Datos del proyecto', missing.length ? `Pendientes (se marcan en el PDF): ${missing.join(', ')}.` : 'Completos.')}
        <li><${StatusBadge} status=${ev.leveling.status} /><div><strong>Nivelación</strong><div class="small muted">${lev && ev.leveling.status === 'vigente' ? `${lev.rows.length} lecturas; ${lev.closure.applicable ? `error de cierre ${lev.closure.error >= 0 ? '+' : ''}${lev.closure.error.toFixed(3)} m` : 'sin BM final (cierre no comprobable)'}.` : 'Se calcula en Nivel de ingeniero.'}</div></div></li>
        <li><${StatusBadge} status=${ev.radiation.status} /><div><strong>Radiación con teodolito</strong><div class="small muted">${ev.radiation.status === 'vigente' ? `${ev.radiation.accepted.points.length} puntos radiados.` : 'Se calcula en Teodolito.'}</div></div></li>
        ${item(!!lev?.profile.ok && ev.leveling.status === 'vigente', 'Perfil longitudinal', lev?.profile.ok ? 'Se incluye.' : `No se incluye: ${lev?.profile.reason || 'nivelación no calculada'}`, lev?.profile.ok ? 'ok' : 'muted')}
        ${item(plan.ok, 'Plano planimétrico', plan.ok ? `Escala 1:${plan.layout.scale}.` : `No se incluye: ${plan.reason}`, plan.ok ? 'ok' : 'muted')}
        <li><${StatusBadge} status=${ev.contours.status} /><div><strong>Triangulación y curvas de nivel</strong><div class="small muted">${ev.contours.status === 'vigente' && ev.contours.accepted.ok ? `${ev.contours.accepted.levels.length} curvas cada ${ev.contours.accepted.equidistance} m.` : ev.contours.current.reason || 'Se calculan en Plano y curvas de nivel.'}</div></div></li>
        ${item(withPhoto > 0, 'Panel fotográfico', `${withPhoto} punto(s) con fotografía; ${p.points.length - withPhoto} sin fotografía (se indica «Sin fotografía registrada»).`, withPhoto ? 'ok' : 'muted')}
      </ul>
      ${blockers.length ? html`<${Notice} kind="err" title="Antes de generar el informe" testid="report-blockers"><ul>${blockers.map((b) => html`<li>${b}</li>`)}</ul>
        <div class="actions" style="margin-top:.5rem"><${Btn} kind="secondary" testid="btn-calcular-todo" onClick=${calcAll}>Calcular nivelación y radiación<//>
        ${ev.contours.current.ok && ev.contours.status !== 'vigente' && ev.leveling.status !== 'desactualizado' && ev.radiation.status === 'vigente' ? html`<${Btn} kind="secondary" onClick=${calcContoursIfPossible}>Calcular curvas<//>` : null}</div><//>` : null}
      <div class="actions" style="margin-top:.8rem">
        <${Btn} kind="secondary" testid="btn-vista-previa" disabledReason=${reason} onClick=${doPreview}>${busy === 'preview' ? 'Generando vista previa…' : 'Vista previa'}<//>
        <${Btn} testid="btn-generar-informe" disabledReason=${reason} onClick=${doDownload}>${busy === 'pdf' ? 'Generando…' : 'Generar informe (PDF)'}<//>
      </div>
      <p class="small muted">El PDF usa exactamente los resultados calculados y vigentes que se muestran en la aplicación.</p>
    </div>

    ${preview ? html`<div class="card"><header class="row between"><h3 style="margin:0">Vista previa (${preview.pages} páginas)</h3>${fresh ? html`<span class="badge ok">Actual</span>` : html`<span class="badge err">El proyecto cambió: vuelva a generar la vista previa</span>`}</header>
      <${PdfPreview} bytes=${preview.bytes} /></div>` : null}

    <div class="card">
      <h3>Tablas para Excel o CSV</h3>
      <p class="small">Se exportan por separado los <strong>datos de campo</strong> (lecturas registradas, con marca de corrección y registro de correcciones) y los <strong>resultados</strong> (solo cálculos vigentes).</p>
      <div class="row" style="align-items:flex-end">
        <${Btn} testid="btn-exportar-excel" onClick=${doXlsx}>Exportar Excel (.xlsx)<//>
        <label class="field" style="max-width:240px"><span>Separador decimal del CSV</span><${Select} value=${decimal} onChange=${setDecimal} options=${[[',', 'Coma (1,250) y campos con ;'], ['.', 'Punto (1.250) y campos con ,']]} /></label>
        <${Btn} kind="secondary" testid="btn-exportar-csv" onClick=${doCsv}>Exportar CSV (.zip)<//>
      </div>
    </div>
    <div class="card"><h3>Respaldo</h3><${Btn} kind="secondary" onClick=${ctx.exportProjectFile}>Exportar proyecto (.json)<//></div>
  </div>`;
}

function PdfPreview({ bytes }) {
  const ref = useRef();
  const [err, setErr] = useState(null);
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const pdfjs = await import('../../../vendor/pdfjs/pdf.min.mjs');
        pdfjs.GlobalWorkerOptions.workerSrc = new URL('../../../vendor/pdfjs/pdf.worker.min.mjs', import.meta.url).href;
        const doc = await pdfjs.getDocument({ data: bytes.slice() }).promise;
        const box = ref.current;
        box.innerHTML = '';
        const width = Math.min(box.clientWidth - 24, 900);
        for (let i = 1; i <= doc.numPages && !cancelled; i++) {
          const page = await doc.getPage(i);
          const vp1 = page.getViewport({ scale: 1 });
          const scale = (width / vp1.width) * (window.devicePixelRatio || 1);
          const vp = page.getViewport({ scale });
          const canvas = document.createElement('canvas');
          canvas.width = vp.width;
          canvas.height = vp.height;
          canvas.style.width = `${vp.width / (window.devicePixelRatio || 1)}px`;
          canvas.setAttribute('aria-label', `Página ${i}`);
          box.appendChild(canvas);
          await page.render({ canvasContext: canvas.getContext('2d'), viewport: vp, canvas }).promise;
        }
        box.dataset.rendered = String(doc.numPages);
      } catch (e) { setErr(e.message); }
    })();
    return () => { cancelled = true; };
  }, [bytes]);
  return html`<div>${err ? html`<${Notice} kind="err">No se pudo mostrar la vista previa: ${err}<//>` : null}<div class="pdf-preview" ref=${ref} data-testid="pdf-preview"></div></div>`;
}
