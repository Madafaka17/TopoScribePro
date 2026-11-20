// Ángulos: lectura de formatos de campo, conversión y formato. Internamente todo se calcula en
// grados sexagesimales decimales; el usuario escribe y ve los ángulos en la unidad declarada en el
// convenio angular del proyecto (sexagesimal ° ' " o centesimal g).

import { round } from './numbers.js';

export const UNITS = {
  sexagesimal: { label: 'Sexagesimal (° \' ")', full: 360 },
  centesimal: { label: 'Centesimal (g, 400 g = vuelta completa)', full: 400 },
};

export const toRad = (deg) => (deg * Math.PI) / 180;
export const toDeg = (rad) => (rad * 180) / Math.PI;

/** Normaliza un azimut al intervalo [0°, 360°). */
export function normalizeAzimuth(deg) {
  let a = deg % 360;
  if (a < 0) a += 360;
  // Evita 359.99999999999994 por redondeo binario cuando el valor es 360 exacto.
  if (Math.abs(a - 360) < 1e-10) a = 0;
  return a;
}

/**
 * Interpreta un ángulo escrito por el usuario en la unidad indicada.
 * Sexagesimal: 45°30'15"  ·  45 30 15  ·  45-30-15  ·  45:30:15  ·  45°30'  ·  45  ·  45.5 (grados decimales)
 * Centesimal:  150.2500  ·  150,25g
 * Devuelve { ok, empty, deg, error }. deg siempre en grados sexagesimales decimales.
 */
export function parseAngle(input, unit = 'sexagesimal') {
  if (input === null || input === undefined) return { ok: false, empty: true, deg: null, error: null };
  if (typeof input === 'number') {
    if (!Number.isFinite(input)) return { ok: false, empty: false, deg: null, error: 'Ángulo no válido.' };
    return { ok: true, empty: false, deg: unit === 'centesimal' ? input * 0.9 : input, error: null };
  }
  let text = String(input).trim().replace(/[−–]/g, '-');
  if (text === '') return { ok: false, empty: true, deg: null, error: null };
  if (!UNITS[unit]) return { ok: false, empty: false, deg: null, error: 'Unidad angular no definida en el convenio.' };

  let sign = 1;
  if (text.startsWith('-')) {
    sign = -1;
    text = text.slice(1).trim();
  } else if (text.startsWith('+')) {
    text = text.slice(1).trim();
  }

  if (unit === 'centesimal') {
    const t = text.replace(/(gon|g|ᵍ)$/i, '').trim().replace(',', '.');
    if (!/^(\d+(\.\d*)?|\.\d+)$/.test(t)) {
      return { ok: false, empty: false, deg: null, error: `«${input}» no es un ángulo centesimal válido (ejemplo: 150.2500).` };
    }
    return { ok: true, empty: false, deg: sign * Number(t) * 0.9, error: null };
  }

  // Sexagesimal
  const separators = /[\s°º˚′'’″"dDmMsS:-]+/;
  const cleaned = text.replace(/''/g, '"');
  const parts = cleaned.split(separators).filter((p) => p !== '');
  const formatHelp = 'Use grados, minutos y segundos (ejemplo: 45°30\'15" o 45 30 15) o grados decimales (45.5).';
  if (parts.length === 0 || parts.length > 3) {
    return { ok: false, empty: false, deg: null, error: `«${input}» no es un ángulo válido. ${formatHelp}` };
  }
  if (parts.length === 1) {
    const t = parts[0].replace(',', '.');
    if (!/^(\d+(\.\d*)?|\.\d+)$/.test(t)) {
      return { ok: false, empty: false, deg: null, error: `«${input}» no es un ángulo válido. ${formatHelp}` };
    }
    return { ok: true, empty: false, deg: sign * Number(t), error: null };
  }
  const [dStr, mStr, sStr = '0'] = parts;
  if (!/^\d+$/.test(dStr) || !/^\d+$/.test(mStr)) {
    return { ok: false, empty: false, deg: null, error: `«${input}»: los grados y los minutos deben ser enteros. ${formatHelp}` };
  }
  const sNorm = sStr.replace(',', '.');
  if (!/^(\d+(\.\d*)?|\.\d+)$/.test(sNorm)) {
    return { ok: false, empty: false, deg: null, error: `«${input}»: segundos no válidos. ${formatHelp}` };
  }
  const d = Number(dStr);
  const m = Number(mStr);
  const s = Number(sNorm);
  if (m >= 60) return { ok: false, empty: false, deg: null, error: `«${input}»: los minutos deben ser menores que 60.` };
  if (s >= 60) return { ok: false, empty: false, deg: null, error: `«${input}»: los segundos deben ser menores que 60.` };
  return { ok: true, empty: false, deg: sign * (d + m / 60 + s / 3600), error: null };
}

/** Grados decimales → texto G°MM'SS" (segundos con 0 o 1 decimal según haga falta). */
export function formatDms(deg, secDecimals = 'auto') {
  if (deg === null || deg === undefined || !Number.isFinite(deg)) return '—';
  const sign = deg < 0 ? '-' : '';
  const abs = Math.abs(deg);
  let dec = secDecimals;
  if (dec === 'auto') {
    const totalSec = abs * 3600;
    dec = Math.abs(totalSec - Math.round(totalSec)) < 0.05 ? 0 : 1;
  }
  const total = round(abs * 3600, dec);
  let d = Math.floor(total / 3600 + 1e-12);
  let rem = total - d * 3600;
  let m = Math.floor(rem / 60 + 1e-12);
  let s = round(rem - m * 60, dec);
  if (s >= 60) { s -= 60; m += 1; }
  if (m >= 60) { m -= 60; d += 1; }
  const sStr = dec > 0 ? s.toFixed(dec).padStart(3 + dec, '0') : String(Math.round(s)).padStart(2, '0');
  const isZero = d === 0 && m === 0 && Number(s) === 0;
  return `${isZero ? '' : sign}${d}°${String(m).padStart(2, '0')}'${sStr}"`;
}

/** Ángulo en la unidad del convenio, para mostrar. */
export function formatAngle(deg, unit = 'sexagesimal') {
  if (deg === null || deg === undefined || !Number.isFinite(deg)) return '—';
  if (unit === 'centesimal') {
    const g = round(deg / 0.9, 4);
    return `${g.toFixed(4)}g`;
  }
  return formatDms(deg);
}

/** Valor completo de una vuelta en la unidad (para mostrar "± 360°" o "± 400g" en la normalización). */
export function fullTurnLabel(unit = 'sexagesimal') {
  return unit === 'centesimal' ? '400g' : '360°';
}
