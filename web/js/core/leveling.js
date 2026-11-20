// Nivelación geométrica con nivel de ingeniero (libreta de altura de instrumento).
//
//   Altura de instrumento (AI) = cota del punto de referencia + vista atrás (VA)
//   Cota del punto observado     = AI − vista intermedia (VI)   o   AI − vista adelante (VD)
//
// Formato de la libreta: una fila por punto observado, en el orden de campo.
//   · Fila 1: el BM inicial, solo con VA (abre la estación E1).
//   · Vista intermedia: fila con VI.
//   · Punto de cambio (PC): fila con VD (leída desde la estación que termina) y VA (leída desde la
//     estación nueva). La VA se suma a la cota recién calculada del PC: así se encadenan las estaciones.
//   · Última fila: normalmente una VD, que cierra la última estación.

import { fmt, fmtSigned, isNum, normCode } from './numbers.js';

const TOL_CHECK = 0.0005; // medio milímetro: las lecturas se registran al milímetro
const MAX_READING_WARN = 5.0;

export const ROW_TYPE_LABEL = {
  BM: 'BM inicial (VA)',
  PC: 'Punto de cambio (VD + VA)',
  VI: 'Vista intermedia',
  VD: 'Vista adelante',
};

/**
 * @param {object} input
 * @param {{code:string, elevation:number}} input.bm   BM o punto inicial de cota conocida
 * @param {{code:string, elevation:number}|null} input.endBm  BM final de cota conocida (opcional)
 * @param {Array<{id, code, bs, is, fs, partial, cumulative, inProfile}>} input.rows
 */
export function computeLeveling({ bm, endBm = null, rows = [] }) {
  const errors = [];
  const warnings = [];
  const out = [];
  const err = (row, n, message, field) => errors.push({ rowId: row?.id ?? null, n, field: field ?? null, message });

  const bmCode = String(bm?.code ?? '').trim();
  if (!bmCode) errors.push({ rowId: null, n: null, field: 'bm.code', message: 'Indique el código del BM o punto inicial.' });
  if (!isNum(bm?.elevation)) errors.push({ rowId: null, n: null, field: 'bm.elevation', message: 'Indique la cota conocida del BM inicial (m).' });
  if (rows.length === 0) errors.push({ rowId: null, n: null, field: null, message: 'La libreta de nivelación no tiene lecturas todavía.' });

  let hi = null; // AI de la estación activa
  let station = 0;
  let stationOpen = false;
  let lastClosedCode = null;
  let sumBs = 0;
  let sumFs = 0;

  rows.forEach((row, i) => {
    const n = i + 1;
    const code = String(row.code ?? '').trim();
    const hasBs = isNum(row.bs);
    const hasIs = isNum(row.is);
    const hasFs = isNum(row.fs);
    const r = {
      id: row.id, n, code, bs: hasBs ? row.bs : null, is: hasIs ? row.is : null, fs: hasFs ? row.fs : null,
      type: null, station: null, hiUsed: null, hiNew: null, elevation: null, steps: [],
      inProfile: row.inProfile !== false, partial: null, cumulative: null, partialSource: null, cumulativeSource: null,
    };
    out.push(r);

    if (!code) err(row, n, `Lectura n.° ${n}: falta el código del punto.`, 'code');
    for (const [field, label] of [['bs', 'vista atrás'], ['is', 'vista intermedia'], ['fs', 'vista adelante']]) {
      const v = row[field];
      if (v !== null && v !== undefined && !isNum(v)) err(row, n, `Lectura n.° ${n} (${code}): la ${label} no es un número.`, field);
      if (isNum(v) && v < 0) err(row, n, `Lectura n.° ${n} (${code}): la ${label} no puede ser negativa.`, field);
      if (isNum(v) && v > MAX_READING_WARN) warnings.push({ rowId: row.id, n, message: `Lectura n.° ${n} (${code}): ${label} de ${fmt(v)} m mayor que ${fmt(MAX_READING_WARN, 1)} m; verifique la longitud de la mira.` });
    }

    if (i === 0) {
      r.type = 'BM';
      if (bmCode && normCode(code) !== normCode(bmCode)) {
        err(row, n, `La primera lectura debe ser la vista atrás sobre el BM «${bmCode}» (se registró «${code}»).`, 'code');
      }
      if (!hasBs) err(row, n, `Lectura n.° 1 (${code}): el BM inicial necesita una vista atrás (VA).`, 'bs');
      if (hasIs || hasFs) err(row, n, `Lectura n.° 1 (${code}): sobre el BM inicial solo se registra la vista atrás (VA).`, 'fs');
      station = 1;
      r.station = 'E1';
      if (hasBs && isNum(bm?.elevation)) {
        r.elevation = bm.elevation;
        hi = bm.elevation + row.bs;
        r.hiNew = hi;
        sumBs += row.bs;
        stationOpen = true;
        r.steps.push(`Cota ${code} = ${fmt(bm.elevation)} m (cota conocida del BM)`);
        r.steps.push(`AI (E1) = Cota ${code} + VA = ${fmt(bm.elevation)} + ${fmt(row.bs)} = ${fmt(hi)} m`);
      }
      return;
    }

    // Filas siguientes
    if (hasIs && hasFs) { err(row, n, `Lectura n.° ${n} (${code}): una fila no puede tener vista intermedia y vista adelante a la vez.`, 'is'); return; }
    if (hasIs && hasBs) { err(row, n, `Lectura n.° ${n} (${code}): un punto con vista intermedia no es punto de cambio. Para cambiar de estación registre vista adelante y vista atrás.`, 'bs'); return; }
    if (!hasIs && !hasFs) {
      err(row, n, hasBs
        ? `Lectura n.° ${n} (${code}): falta la vista adelante. Un punto de cambio lleva vista adelante (estación anterior) y vista atrás (estación nueva).`
        : `Lectura n.° ${n} (${code}): registre una vista intermedia o una vista adelante.`, 'fs');
      return;
    }
    if (!stationOpen) {
      err(row, n, lastClosedCode
        ? `Lectura n.° ${n} (${code}): no hay estación activa. Después de la vista adelante en «${lastClosedCode}» falta la vista atrás (márquelo como punto de cambio).`
        : `Lectura n.° ${n} (${code}): no hay estación activa (revise la primera lectura).`, 'bs');
      return;
    }

    r.station = `E${station}`;
    r.hiUsed = hi;
    if (hasIs) {
      r.type = 'VI';
      r.elevation = hi - row.is;
      r.steps.push(`Cota ${code} = AI (E${station}) − VI = ${fmt(hi)} − ${fmt(row.is)} = ${fmt(r.elevation)} m`);
      return;
    }
    // Vista adelante
    r.elevation = hi - row.fs;
    sumFs += row.fs;
    r.steps.push(`Cota ${code} = AI (E${station}) − VD = ${fmt(hi)} − ${fmt(row.fs)} = ${fmt(r.elevation)} m`);
    if (hasBs) {
      r.type = 'PC';
      station += 1;
      hi = r.elevation + row.bs;
      r.hiNew = hi;
      sumBs += row.bs;
      r.station = `E${station - 1} → E${station}`;
      r.steps.push(`Cambio de estación: AI (E${station}) = Cota ${code} + VA = ${fmt(r.elevation)} + ${fmt(row.bs)} = ${fmt(hi)} m`);
    } else {
      r.type = 'VD';
      stationOpen = false;
      lastClosedCode = code;
    }
  });

  const computable = errors.length === 0;
  if (!computable) for (const r of out) { r.elevation = null; r.hiUsed = null; r.hiNew = null; r.steps = []; }

  // ---- Comprobación aritmética ΣVA − ΣVD = cota final − cota inicial
  const last = out[out.length - 1];
  const sums = { sumBs, sumFs, diff: sumBs - sumFs, applicable: false, ok: null, startElevation: bm?.elevation ?? null, endElevation: null, endCode: null, explanation: '' };
  if (computable && last) {
    if (last.type === 'VD') {
      sums.applicable = true;
      sums.endElevation = last.elevation;
      sums.endCode = last.code;
      sums.elevDiff = last.elevation - bm.elevation;
      sums.ok = Math.abs(sums.diff - sums.elevDiff) < TOL_CHECK;
      sums.explanation = sums.ok
        ? 'ΣVA − ΣVD coincide con la diferencia entre la cota final y la inicial: las operaciones de la libreta son correctas. Esta comprobación no detecta errores de lectura en campo.'
        : 'ΣVA − ΣVD NO coincide con la diferencia de cotas: revise las operaciones.';
    } else {
      sums.explanation = last.type === 'PC'
        ? 'La última fila es un punto de cambio con vista atrás sin lecturas posteriores: complete la estación con una vista adelante para poder comprobar.'
        : 'La libreta no termina en una vista adelante: la comprobación ΣVA − ΣVD requiere cerrar la última estación con una vista adelante.';
    }
  } else if (!computable) {
    sums.explanation = 'No se puede comprobar mientras la libreta tenga errores.';
  }

  // ---- Error de cierre contra un BM final de cota conocida
  const closure = { applicable: false, code: null, known: null, computed: null, error: null, n: null, explanation: '' };
  const endCode = String(endBm?.code ?? '').trim();
  if (!endBm || !endCode || !isNum(endBm.elevation)) {
    closure.explanation = 'No se registró un BM final de cota conocida: no puede comprobarse el cierre contra un control externo. La comprobación ΣVA − ΣVD solo verifica las operaciones, no los errores de campo.';
  } else if (!computable) {
    closure.explanation = 'No se puede calcular el cierre mientras la libreta tenga errores.';
  } else {
    const target = [...out].reverse().find((r) => normCode(r.code) === normCode(endCode) && r.fs !== null);
    if (!target) {
      closure.explanation = `El BM final «${endCode}» no aparece en la libreta con una vista adelante: no se puede calcular el error de cierre.`;
    } else {
      closure.applicable = true;
      closure.code = target.code;
      closure.n = target.n;
      closure.known = endBm.elevation;
      closure.computed = target.elevation;
      closure.error = target.elevation - endBm.elevation;
      closure.explanation = `Error de cierre = cota calculada − cota conocida = ${fmt(target.elevation)} − ${fmt(endBm.elevation)} = ${fmtSigned(closure.error)} m`;
    }
  }

  // ---- Distancias parciales y progresivas, y perfil longitudinal
  const profile = computeProfileDistances(out, rows, computable);

  // ---- Cotas por código (para vincular con el teodolito)
  const byCode = new Map();
  if (computable) {
    for (const r of out) {
      if (!isNum(r.elevation)) continue;
      const key = normCode(r.code);
      if (!byCode.has(key)) byCode.set(key, { code: r.code, elevation: r.elevation, n: r.n, all: [r.elevation] });
      else byCode.get(key).all.push(r.elevation);
    }
    for (const v of byCode.values()) {
      const spread = Math.max(...v.all) - Math.min(...v.all);
      if (v.all.length > 1 && spread >= TOL_CHECK) {
        warnings.push({ rowId: null, n: null, message: `El punto ${v.code} aparece ${v.all.length} veces con cotas distintas (${v.all.map((e) => fmt(e)).join(', ')} m). Para vincular con el teodolito se usa la primera (lectura n.° ${v.n}).` });
      }
    }
  }

  return { computable, errors, warnings, rows: out, sums, closure, profile, elevationsByCode: byCode };
}

function computeProfileDistances(out, rawRows, computable) {
  const errors = [];
  const missing = [];
  const points = [];
  let prevCum = null;
  let first = true;

  out.forEach((r, i) => {
    const raw = rawRows[i];
    const partialIn = isNum(raw.partial) ? raw.partial : null;
    const cumIn = isNum(raw.cumulative) ? raw.cumulative : null;
    if (!r.inProfile) return;
    if (partialIn !== null && partialIn < 0) errors.push({ n: r.n, message: `Lectura n.° ${r.n} (${r.code}): la distancia parcial no puede ser negativa.` });
    if (cumIn !== null && cumIn < 0) errors.push({ n: r.n, message: `Lectura n.° ${r.n} (${r.code}): la progresiva no puede ser negativa.` });

    if (first) {
      first = false;
      if (cumIn !== null) { r.cumulative = cumIn; r.cumulativeSource = 'ingresada'; }
      else if (partialIn !== null) { r.cumulative = partialIn; r.cumulativeSource = 'calculada'; r.partial = partialIn; r.partialSource = 'ingresada'; }
      else { r.cumulative = 0; r.cumulativeSource = 'origen del recorrido'; }
      prevCum = r.cumulative;
      return;
    }

    if (cumIn !== null) {
      r.cumulative = cumIn;
      r.cumulativeSource = 'ingresada';
      if (prevCum !== null) {
        const p = cumIn - prevCum;
        if (partialIn !== null && Math.abs(partialIn - p) > TOL_CHECK) {
          errors.push({ n: r.n, message: `Lectura n.° ${r.n} (${r.code}): la distancia parcial ingresada (${fmt(partialIn)} m) no coincide con la diferencia de progresivas (${fmt(p)} m).` });
        }
        r.partial = p;
        r.partialSource = partialIn !== null ? 'ingresada' : 'calculada';
        if (p < -1e-9) errors.push({ n: r.n, message: `Lectura n.° ${r.n} (${r.code}): la progresiva ${fmt(cumIn)} m es menor que la anterior (${fmt(prevCum)} m).` });
      } else if (partialIn !== null) {
        r.partial = partialIn;
        r.partialSource = 'ingresada';
      }
    } else if (partialIn !== null && prevCum !== null) {
      r.partial = partialIn;
      r.partialSource = 'ingresada';
      r.cumulative = prevCum + partialIn;
      r.cumulativeSource = 'calculada';
    } else {
      if (partialIn !== null) { r.partial = partialIn; r.partialSource = 'ingresada'; }
      missing.push({ n: r.n, code: r.code, message: partialIn !== null
        ? `Lectura n.° ${r.n} (${r.code}): no se puede acumular la distancia porque falta la progresiva del punto anterior del perfil.`
        : `Lectura n.° ${r.n} (${r.code}): falta la distancia parcial o la progresiva.` });
    }
    prevCum = r.cumulative;
  });

  const profileRows = out.filter((r) => r.inProfile);
  for (const r of profileRows) {
    if (isNum(r.cumulative) && isNum(r.elevation)) points.push({ code: r.code, n: r.n, chainage: r.cumulative, elevation: r.elevation });
  }
  if (!computable) {
    missing.push({ n: null, code: null, message: 'Las cotas no se pudieron calcular: corrija primero los errores de la libreta de nivelación.' });
  }
  let reason = '';
  if (profileRows.length < 2) reason = 'Se necesitan al menos 2 puntos marcados «en perfil» con cota y progresiva.';
  else if (errors.length) reason = 'Hay errores en las distancias del perfil.';
  else if (missing.length) reason = 'Faltan datos para dibujar el perfil.';
  const ok = computable && profileRows.length >= 2 && errors.length === 0 && missing.length === 0 && points.length >= 2;
  return { ok, reason: ok ? '' : reason, errors, missing, points };
}

/** Resumen textual del tipo de fila, para tablas. */
export function rowTypeLabel(type) {
  return ROW_TYPE_LABEL[type] ?? '—';
}
