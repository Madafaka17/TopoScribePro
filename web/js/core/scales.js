// Escalas normalizadas y maquetación (en milímetros de papel) del perfil longitudinal y del plano.
// La pantalla (SVG) y el PDF usan EXACTAMENTE esta misma maquetación, así el dibujo coincide.

import { isNum } from './numbers.js';

/** Denominadores de escala normalizados: 1, 2, 2.5, 5 × 10^k (1:1 … 1:5 000 000). */
export const STANDARD_SCALES = (() => {
  const list = [];
  for (let k = 0; k <= 6; k++) for (const m of [1, 2, 2.5, 5]) list.push(m * 10 ** k);
  return list;
})();

/** Menor escala normalizada con la que `lengthM` metros caben en `paperMm` milímetros. */
export function chooseScale(lengthM, paperMm) {
  const needed = (Math.max(lengthM, 1e-6) * 1000) / paperMm;
  return STANDARD_SCALES.find((s) => s >= needed - 1e-9) ?? STANDARD_SCALES[STANDARD_SCALES.length - 1];
}

/** Intervalo "redondo" (1, 2, 5 × 10^k) cercano a `approx`. */
export function niceStep(approx) {
  if (!(approx > 0)) return 1;
  const exp = Math.floor(Math.log10(approx));
  const base = 10 ** exp;
  const f = approx / base;
  const m = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
  return m * base;
}

export const PROFILE_AREA = { width: 237, height: 90 }; // A4 apaisado, zona del gráfico (mm)
export const PLAN_AREA = { width: 170, height: 185 }; // A4 vertical, zona del plano (mm)

/**
 * Maquetación del perfil longitudinal.
 * @param {Array<{chainage, elevation, code}>} points
 * @param {{hScale?:number, vScale?:number}} overrides escalas elegidas por el usuario (denominadores)
 */
export function profileLayout(points, overrides = {}, area = PROFILE_AREA) {
  const ch = points.map((p) => p.chainage);
  const zs = points.map((p) => p.elevation);
  const chMin = Math.min(...ch);
  const chMax = Math.max(...ch);
  const zMinRaw = Math.min(...zs);
  const zMaxRaw = Math.max(...zs);
  const length = Math.max(chMax - chMin, 0.001); // longitud del recorrido (m)

  const errors = [];
  let hScale = chooseScale(length, area.width - 8);
  if (isNum(overrides.hScale) && overrides.hScale > 0) {
    if ((length * 1000) / overrides.hScale > area.width - 8 + 1e-6) {
      errors.push(`Con la escala horizontal 1:${overrides.hScale} el perfil (${length.toFixed(1)} m) no cabe en ${area.width} mm; se usa 1:${hScale}.`);
    } else hScale = overrides.hScale;
  }
  const relief = Math.max(zMaxRaw - zMinRaw, 0.01);
  // Margen de 15 % arriba y abajo para rótulos
  const neededRange = relief * 1.3;
  let vScale = STANDARD_SCALES.find((s) => s >= Math.max((neededRange * 1000) / area.height, hScale / 10) - 1e-9) ?? hScale;
  if (isNum(overrides.vScale) && overrides.vScale > 0) {
    if ((neededRange * 1000) / overrides.vScale > area.height + 1e-6) {
      errors.push(`Con la escala vertical 1:${overrides.vScale} el desnivel no cabe en ${area.height} mm; se usa 1:${vScale}.`);
    } else vScale = overrides.vScale;
  }

  // Plano de comparación: cota redonda por debajo del punto más bajo
  const zSpanAvailable = (area.height * vScale) / 1000;
  const zStep = niceStep((10 * vScale) / 1000); // marcas cada ~10 mm
  let datum = Math.floor((zMinRaw - (zSpanAvailable - relief) / 2) / zStep) * zStep;
  if (datum > zMinRaw) datum -= zStep;
  const zTop = datum + zSpanAvailable;
  const chStep = niceStep((20 * hScale) / 1000); // marcas cada ~20 mm
  const chStart = Math.floor(chMin / chStep) * chStep;

  const PAD = 4; // mm libres a cada lado del eje horizontal
  const x = (chainage) => PAD + ((chainage - chStart) * 1000) / hScale;
  const y = (z) => area.height - ((z - datum) * 1000) / vScale; // y hacia abajo (como SVG/PDF)

  const chTicks = [];
  for (let c = chStart; c <= chMax + 1e-9; c += chStep) chTicks.push(Number(c.toFixed(6)));
  const zTicks = [];
  for (let z = datum; z <= zTop + 1e-9; z += zStep) zTicks.push(Number(z.toFixed(6)));

  return {
    hScale, vScale, exaggeration: hScale / vScale, datum, zTop, chStart, chMax, chStep, zStep,
    width: area.width, height: area.height, usedWidth: x(chMax) + PAD, x, y, chTicks, zTicks, errors,
  };
}

/**
 * Maquetación del plano: una sola escala para Este y Norte (sin deformar).
 * @param {Array<{east, north}>} points
 */
export function planLayout(points, area = PLAN_AREA) {
  const es = points.map((p) => p.east);
  const ns = points.map((p) => p.north);
  let eMin = Math.min(...es); let eMax = Math.max(...es);
  let nMin = Math.min(...ns); let nMax = Math.max(...ns);
  const w = Math.max(eMax - eMin, 1);
  const h = Math.max(nMax - nMin, 1);
  // margen del 12 % para rótulos
  const scale = Math.max(chooseScale(w * 1.24, area.width), chooseScale(h * 1.24, area.height));
  const spanE = (area.width * scale) / 1000;
  const spanN = (area.height * scale) / 1000;
  const cE = (eMin + eMax) / 2;
  const cN = (nMin + nMax) / 2;
  eMin = cE - spanE / 2; eMax = cE + spanE / 2;
  nMin = cN - spanN / 2; nMax = cN + spanN / 2;
  const x = (e) => ((e - eMin) * 1000) / scale;
  const y = (n) => area.height - ((n - nMin) * 1000) / scale;
  const gridStep = niceStep((40 * scale) / 1000); // cuadrícula cada ~40 mm
  const gridE = [];
  for (let e = Math.ceil(eMin / gridStep) * gridStep; e <= eMax; e += gridStep) gridE.push(Number(e.toFixed(6)));
  const gridN = [];
  for (let n = Math.ceil(nMin / gridStep) * gridStep; n <= nMax; n += gridStep) gridN.push(Number(n.toFixed(6)));
  const barM = niceStep((30 * scale) / 1000); // barra de escala ~30 mm
  return { scale, width: area.width, height: area.height, eMin, eMax, nMin, nMax, x, y, gridStep, gridE, gridN, barM, barMm: (barM * 1000) / scale };
}
