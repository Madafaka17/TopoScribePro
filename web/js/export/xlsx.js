// Libro Excel (.xlsx, Office Open XML) y paquete de CSV a partir de las tablas de tables.js.

import { buildZip } from './zip.js';

const xmlEsc = (s) => String(s)
  // eslint-disable-next-line no-control-regex
  .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

function colName(i) {
  let s = '';
  let n = i + 1;
  while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); }
  return s;
}

function sheetName(name, used) {
  let base = name.replace(/[[\]:*?/\\]/g, '-').slice(0, 31);
  let candidate = base;
  let k = 2;
  while (used.has(candidate.toLowerCase())) { candidate = `${base.slice(0, 28)} ${k++}`; }
  used.add(candidate.toLowerCase());
  return candidate;
}

// Estilos: 0 normal · 1 encabezado en negrita · 2 número 0.000 · 3 número 0.00 · 4 número 0.000000 · 5 nota en cursiva · 6 entero
const STYLE_FOR_DECIMALS = { 3: 2, 2: 3, 6: 4, 4: 2, 0: 6 };

function sheetXml(table) {
  const rows = [];
  let r = 0;
  const widths = table.headers.map((h) => Math.min(Math.max(String(h).length + 2, 10), 50));
  const cell = (c, ci, rowIndex) => {
    const ref = `${colName(ci)}${rowIndex}`;
    if (c && typeof c === 'object' && 'n' in c) return `<c r="${ref}" s="${STYLE_FOR_DECIMALS[c.d] ?? 2}"><v>${c.n}</v></c>`;
    if (typeof c === 'number' && Number.isFinite(c)) return `<c r="${ref}" s="6"><v>${c}</v></c>`;
    const text = c === null || c === undefined ? '' : String(c);
    if (text === '') return '';
    widths[ci] = Math.min(Math.max(widths[ci] ?? 10, text.length + 2), 60);
    return `<c r="${ref}" t="inlineStr"><is><t xml:space="preserve">${xmlEsc(text)}</t></is></c>`;
  };
  if (table.note) {
    r += 1;
    rows.push(`<row r="${r}"><c r="A${r}" t="inlineStr" s="5"><is><t xml:space="preserve">${xmlEsc(table.note)}</t></is></c></row>`);
    r += 1;
  }
  r += 1;
  const headerRow = r;
  rows.push(`<row r="${r}">${table.headers.map((h, i) => `<c r="${colName(i)}${r}" t="inlineStr" s="1"><is><t xml:space="preserve">${xmlEsc(h)}</t></is></c>`).join('')}</row>`);
  for (const row of table.rows) {
    r += 1;
    rows.push(`<row r="${r}">${row.map((c, i) => cell(c, i, r)).join('')}</row>`);
  }
  const cols = widths.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`).join('');
  const pane = `<sheetViews><sheetView workbookViewId="0"><pane ySplit="${headerRow}" topLeftCell="A${headerRow + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>`;
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">${pane}<cols>${cols}</cols><sheetData>${rows.join('')}</sheetData></worksheet>`;
}

const STYLES = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="3"><numFmt numFmtId="164" formatCode="0.000"/><numFmt numFmtId="165" formatCode="0.00"/><numFmt numFmtId="166" formatCode="0.000000"/></numFmts>
<fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><i/><sz val="10"/><color rgb="FF555555"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE8EEF4"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="7">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="1" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>`;

/** @returns {Uint8Array} contenido del archivo .xlsx */
export function buildXlsx(tables) {
  const used = new Set();
  const names = tables.map((t) => sheetName(t.name, used));
  const files = [
    { name: '[Content_Types].xml', data: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${tables.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')}</Types>` },
    { name: '_rels/.rels', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>' },
    { name: 'xl/workbook.xml', data: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${names.map((n, i) => `<sheet name="${xmlEsc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')}</sheets></workbook>` },
    { name: 'xl/_rels/workbook.xml.rels', data: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${names.map((_, i) => `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')}<Relationship Id="rId${names.length + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>` },
    { name: 'xl/styles.xml', data: STYLES },
    ...tables.map((t, i) => ({ name: `xl/worksheets/sheet${i + 1}.xml`, data: sheetXml(t) })),
  ];
  return buildZip(files);
}

/**
 * CSV de una tabla. decimal: '.' (separador de campos ',') o ',' (separador ';', Excel en español).
 * Incluye BOM UTF-8 para que Excel muestre bien las tildes.
 */
export function buildCsv(table, decimal = '.') {
  const sep = decimal === ',' ? ';' : ',';
  const cellText = (c) => {
    if (c && typeof c === 'object' && 'n' in c) {
      const s = c.n.toFixed(c.d);
      return decimal === ',' ? s.replace('.', ',') : s;
    }
    if (typeof c === 'number') return decimal === ',' ? String(c).replace('.', ',') : String(c);
    return c === null || c === undefined ? '' : String(c);
  };
  const esc = (s) => (/["\n\r]/.test(s) || s.includes(sep) ? `"${s.replace(/"/g, '""')}"` : s);
  const lines = [];
  if (table.note) lines.push(esc(`# ${table.note}`));
  lines.push(table.headers.map((h) => esc(String(h))).join(sep));
  for (const row of table.rows) lines.push(row.map((c) => esc(cellText(c))).join(sep));
  return `﻿${lines.join('\r\n')}\r\n`;
}

export function buildCsvZip(tables, decimal = '.') {
  const used = new Set();
  return buildZip(tables.map((t, i) => {
    const base = `${String(i + 1).padStart(2, '0')}-${t.kind === 'resultados' ? 'RESULTADOS' : 'DATOS-CAMPO'}-${t.name}`.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^A-Za-z0-9-]+/g, '_');
    let name = `${base}.csv`;
    let k = 2;
    while (used.has(name)) name = `${base}_${k++}.csv`;
    used.add(name);
    return { name, data: buildCsv(t, decimal) };
  }));
}
