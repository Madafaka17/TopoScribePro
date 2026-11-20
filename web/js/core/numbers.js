// Números: lectura de lo que escribe el usuario y formato único para pantalla, PDF y exportaciones.
// Usar SIEMPRE estas funciones garantiza que el informe muestre exactamente lo mismo que la app.

/**
 * Interpreta un número escrito por el usuario. Acepta punto o coma decimal ("1.250" o "1,250"),
 * pero no ambos a la vez (sería ambiguo: "1.000,5" o "1,000.5").
 * Devuelve { ok, value, empty, error }.
 */
export function parseNumber(input) {
  if (input === null || input === undefined) return { ok: false, empty: true, value: null, error: null };
  if (typeof input === 'number') {
    return Number.isFinite(input)
      ? { ok: true, empty: false, value: input, error: null }
      : { ok: false, empty: false, value: null, error: 'El valor no es un número válido.' };
  }
  const text = String(input).trim().replace(/\s+/g, '').replace(/[−–]/g, '-');
  if (text === '') return { ok: false, empty: true, value: null, error: null };
  const hasDot = text.includes('.');
  const hasComma = text.includes(',');
  if (hasDot && hasComma) {
    return { ok: false, empty: false, value: null, error: 'Use un solo separador decimal (punto o coma), sin separador de miles.' };
  }
  const normalized = hasComma ? text.replace(',', '.') : text;
  if (!/^[+-]?(\d+(\.\d*)?|\.\d+)$/.test(normalized)) {
    return { ok: false, empty: false, value: null, error: `«${input}» no es un número válido.` };
  }
  const value = Number(normalized);
  if (!Number.isFinite(value)) return { ok: false, empty: false, value: null, error: `«${input}» no es un número válido.` };
  return { ok: true, empty: false, value, error: null };
}

/** Número opcional: vacío → null; inválido → lanza un Error con mensaje en español. */
export function optionalNumber(input, label) {
  const r = parseNumber(input);
  if (r.empty) return null;
  if (!r.ok) throw new Error(`${label}: ${r.error}`);
  return r.value;
}

export function isNum(v) {
  return typeof v === 'number' && Number.isFinite(v);
}

/** Redondeo "comercial" (mitad hacia fuera de cero) a `decimals` decimales, robusto ante 1.0005. */
export function round(value, decimals = 3) {
  if (!isNum(value)) return value;
  const f = 10 ** decimals;
  const scaled = Math.abs(value) * f;
  const r = Math.round(scaled + 1e-9 * Math.max(1, scaled)) / f;
  return value < 0 ? -r : r;
}

/** Formato fijo con punto decimal (convención del docente: 100.000 m). Nunca muestra "-0.000". */
export function fmt(value, decimals = 3) {
  if (!isNum(value)) return '—';
  const r = round(value, decimals);
  const s = Math.abs(r).toFixed(decimals);
  const isZero = Number(s) === 0;
  return (r < 0 && !isZero ? '-' : '') + s;
}

/** Igual que fmt pero con signo explícito (+0.002 / −0.002), útil para errores de cierre. */
export function fmtSigned(value, decimals = 3) {
  if (!isNum(value)) return '—';
  const s = fmt(value, decimals);
  if (Number(s) === 0) return s;
  return s.startsWith('-') ? `−${s.slice(1)}` : `+${s}`;
}

/** Término dentro de una fórmula sustituida: los negativos van entre paréntesis, p. ej. "(−5.000)". */
export function fmtTerm(value, decimals = 3) {
  const s = fmt(value, decimals);
  return s.startsWith('-') ? `(−${s.slice(1)})` : s;
}

/** Valor con el signo menos tipográfico (−) para textos de resultados. */
export function fmtNice(value, decimals = 3) {
  const s = fmt(value, decimals);
  return s.startsWith('-') ? `−${s.slice(1)}` : s;
}

/** Progresiva en formato de kilometraje: 1234.5 → "1+234.500". */
export function fmtChainage(meters, decimals = 3) {
  if (!isNum(meters)) return '—';
  const r = round(meters, decimals);
  const km = Math.floor(Math.abs(r) / 1000);
  const rest = Math.abs(r) - km * 1000;
  const restStr = rest.toFixed(decimals).padStart(decimals > 0 ? 4 + decimals : 3, '0');
  return `${r < 0 ? '-' : ''}${km}+${restStr}`;
}

/** Hash estable (cyrb53) de un valor JSON: detecta si los datos de entrada de un cálculo cambiaron. */
export function stableHash(value) {
  const str = stableStringify(value);
  let h1 = 0xdeadbeef ^ 0;
  let h2 = 0x41c6ce57 ^ 0;
  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36);
}

export function stableStringify(value) {
  if (value === undefined) return 'null';
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  const keys = Object.keys(value).filter((k) => value[k] !== undefined).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${stableStringify(value[k])}`).join(',')}}`;
}

/** Código de punto normalizado para comparar (sin espacios a los lados, sin distinguir mayúsculas). */
export function normCode(code) {
  return String(code ?? '').trim().toUpperCase();
}
