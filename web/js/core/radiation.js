// Planimetría con teodolito por el método de RADIACIÓN.
//
// Desde una estación de coordenadas conocidas (o locales declaradas) y orientada, cada punto se
// fija con una lectura horizontal y una distancia:
//   Azimut (sentido horario):      Az = Az_ref + (L − L_ref)      normalizado a [0°, 360°)
//   Azimut (sentido antihorario):  Az = Az_ref − (L − L_ref)
//   Círculo orientado al norte:    Az = L  (horario)   ·   Az = 360° − L  (antihorario)
//   Distancia inclinada:           Dh = Di · sen(Z)  (ángulo cenital)   ·   Dh = Di · cos(α)  (ángulo vertical)
//   Proyecciones:                  ΔE = Dh · sen(Az)     ΔN = Dh · cos(Az)
//   Coordenadas:                   E = E_est + ΔE        N = N_est + ΔN

import { fmt, fmtTerm, isNum, normCode } from './numbers.js';
import { formatAngle, normalizeAzimuth, parseAngle, toDeg, toRad, UNITS } from './angles.js';

export const DIRECTION_LABEL = { horario: 'Horario (a la derecha)', antihorario: 'Antihorario (a la izquierda)' };
export const ORIGIN_LABEL = {
  referencia: 'Desde la referencia de orientación (vista atrás)',
  norte: 'Desde el norte (círculo horizontal orientado con 0 al norte)',
};

/** Comprueba que el convenio angular esté completo. Devuelve lista de errores. */
export function conventionErrors(convention) {
  const e = [];
  if (!convention || !UNITS[convention.unit]) e.push('Elija la unidad angular del convenio (sexagesimal o centesimal).');
  if (!convention || !DIRECTION_LABEL[convention.direction]) e.push('Elija el sentido de lectura del círculo horizontal (horario o antihorario).');
  if (!convention || !ORIGIN_LABEL[convention.origin]) e.push('Elija el origen de los ángulos (referencia de orientación o norte).');
  return e;
}

export function computeRadiation({ convention, stations = [], readings = [] }) {
  const errors = [];
  const warnings = [];
  const convErr = conventionErrors(convention);
  convErr.forEach((m) => errors.push({ readingId: null, stationId: null, field: 'convention', message: m }));
  const unit = convention?.unit ?? 'sexagesimal';
  const A = (deg) => formatAngle(deg, unit);
  const full = unit === 'centesimal' ? 400 : 360;
  const halfDeg = 180;
  const sign = convention?.direction === 'antihorario' ? -1 : 1;
  const origin = convention?.origin;

  if (stations.length === 0) errors.push({ readingId: null, stationId: null, field: 'station', message: 'Registre la estación ocupada por el teodolito (código y coordenadas Este/Norte).' });

  // ---- Estaciones y orientación
  const outStations = [];
  const stationById = new Map();
  const stationCodes = new Map();
  const refCoords = new Map(); // código de referencia → {east, north, stationCode}
  for (const st of stations) {
    const code = String(st.code ?? '').trim();
    const s = { id: st.id, code, east: st.east, north: st.north, coordType: st.coordType, ok: true, az0: null, l0: null, steps: [], refCode: null, refEast: null, refNorth: null, refAzMode: null };
    const sErr = (field, message) => { s.ok = false; errors.push({ readingId: null, stationId: st.id, field, message }); };
    outStations.push(s);
    stationById.set(st.id, s);

    if (!code) sErr('code', 'La estación no tiene código.');
    else if (stationCodes.has(normCode(code))) sErr('code', `El código de estación «${code}» está repetido.`);
    else stationCodes.set(normCode(code), s);
    if (!isNum(st.east) || !isNum(st.north)) sErr('coords', `Estación ${code || '(sin código)'}: ingrese las coordenadas Este y Norte (m).`);
    if (st.coordType !== 'conocidas' && st.coordType !== 'locales') sErr('coordType', `Estación ${code}: indique si las coordenadas son conocidas o locales declaradas.`);

    const o = st.orientation ?? {};
    if (origin === 'norte') {
      s.az0 = 0;
      s.l0 = 0;
      if (!String(o.northNote ?? '').trim()) sErr('orientation', `Estación ${code}: describa cómo se orientó el círculo al norte (p. ej., brújula: norte magnético).`);
      s.steps.push(`Orientación: círculo horizontal orientado con lectura 0 hacia el norte (${String(o.northNote ?? '').trim() || 'sin descripción'}).`);
      s.steps.push(sign > 0 ? 'Fórmula: Az = L' : `Fórmula: Az = ${unit === 'centesimal' ? '400g' : '360°'} − L`);
    } else if (origin === 'referencia') {
      const refCode = String(o.refCode ?? '').trim();
      s.refCode = refCode;
      s.refAzMode = o.azMode;
      if (!refCode) sErr('orientation', `Estación ${code}: indique el punto de referencia de orientación (vista atrás).`);
      else if (normCode(refCode) === normCode(code)) sErr('orientation', `Estación ${code}: la referencia de orientación no puede ser la misma estación.`);

      if (o.azMode === 'coordenadas') {
        if (!isNum(o.refEast) || !isNum(o.refNorth)) {
          sErr('orientation', `Estación ${code}: ingrese las coordenadas Este y Norte del punto de referencia «${refCode}».`);
        } else if (isNum(st.east) && isNum(st.north)) {
          const dE = o.refEast - st.east;
          const dN = o.refNorth - st.north;
          if (Math.hypot(dE, dN) < 0.001) {
            sErr('orientation', `Estación ${code}: la referencia «${refCode}» tiene las mismas coordenadas que la estación; no define una dirección.`);
          } else {
            const raw = toDeg(Math.atan2(dE, dN));
            s.az0 = normalizeAzimuth(raw);
            s.refEast = o.refEast;
            s.refNorth = o.refNorth;
            s.steps.push(`ΔE = E(${refCode}) − E(${code}) = ${fmt(o.refEast)} − ${fmtTerm(st.east)} = ${fmt(dE)} m`);
            s.steps.push(`ΔN = N(${refCode}) − N(${code}) = ${fmt(o.refNorth)} − ${fmtTerm(st.north)} = ${fmt(dN)} m`);
            s.steps.push(raw < 0
              ? `Az_ref = atan2(ΔE, ΔN) = ${A(raw)} + ${unit === 'centesimal' ? '400g' : '360°'} = ${A(s.az0)}`
              : `Az_ref = atan2(ΔE, ΔN) = atan2(${fmt(dE)}, ${fmt(dN)}) = ${A(s.az0)}`);
            const key = normCode(refCode);
            const prev = refCoords.get(key);
            if (prev && (Math.abs(prev.east - o.refEast) > 0.0005 || Math.abs(prev.north - o.refNorth) > 0.0005)) {
              sErr('orientation', `La referencia «${refCode}» tiene coordenadas distintas en dos estaciones.`);
            }
            refCoords.set(key, { code: refCode, east: o.refEast, north: o.refNorth, stationCode: code });
          }
        }
      } else if (o.azMode === 'ingresado') {
        const p = parseAngle(o.refAzimuth, unit);
        if (p.empty) sErr('orientation', `Estación ${code}: ingrese el azimut de la línea ${code} → ${refCode || 'referencia'}.`);
        else if (!p.ok) sErr('orientation', `Estación ${code}, azimut de referencia: ${p.error}`);
        else if (p.deg < 0 || p.deg >= 360) sErr('orientation', `Estación ${code}: el azimut de referencia debe estar entre 0 y ${unit === 'centesimal' ? '400g' : '360°'}.`);
        else {
          s.az0 = p.deg;
          s.steps.push(`Az_ref (${code} → ${refCode}) = ${A(p.deg)} (dato ingresado${String(o.azSource ?? '').trim() ? `: ${String(o.azSource).trim()}` : ''})`);
        }
      } else {
        sErr('orientation', `Estación ${code}: indique cómo se conoce el azimut de la referencia (ingresado o por coordenadas).`);
      }

      const pl = parseAngle(o.refReading, unit);
      if (pl.empty) sErr('orientation', `Estación ${code}: ingrese la lectura horizontal en la referencia «${refCode || '?'}» (L_ref).`);
      else if (!pl.ok) sErr('orientation', `Estación ${code}, lectura en la referencia: ${pl.error}`);
      else if (pl.deg < 0 || pl.deg >= 360) sErr('orientation', `Estación ${code}: la lectura en la referencia debe estar entre 0 y ${unit === 'centesimal' ? '400g' : '360°'}.`);
      else {
        s.l0 = pl.deg;
        s.steps.push(`Lectura horizontal en la referencia: L_ref = ${A(pl.deg)}`);
        s.steps.push(sign > 0 ? 'Fórmula: Az = Az_ref + (L − L_ref)' : 'Fórmula: Az = Az_ref − (L − L_ref)');
      }
    }
  }

  // ---- Lecturas radiadas
  const seenCodes = new Map();
  const points = [];
  readings.forEach((rd, i) => {
    const n = i + 1;
    const code = String(rd.code ?? '').trim();
    const st = stationById.get(rd.stationId);
    const p = {
      readingId: rd.id, n, stationId: rd.stationId, stationCode: st?.code ?? '—', code, description: rd.description ?? '',
      hzText: rd.hz ?? '', hzDeg: null, azRaw: null, az: null, azSteps: [],
      distType: rd.distType, distance: rd.distance, vType: rd.vType, vText: rd.vAngle ?? '', vDeg: null,
      dh: null, dhSteps: [], dE: null, dN: null, east: null, north: null, coordSteps: [], ok: true,
    };
    points.push(p);
    const rErr = (field, message) => { p.ok = false; errors.push({ readingId: rd.id, stationId: rd.stationId, field, message: `Lectura n.° ${n}${code ? ` (${code})` : ''}: ${message}` }); };

    if (!code) rErr('code', 'falta el código del punto radiado.');
    else {
      const key = normCode(code);
      if (seenCodes.has(key)) rErr('code', `el código «${code}» ya fue radiado en la lectura n.° ${seenCodes.get(key)}. Cada punto se radia una sola vez.`);
      else seenCodes.set(key, n);
      if (stationCodes.has(key)) rErr('code', `«${code}» es una estación; sus coordenadas se ingresan como dato, no se radian.`);
      if (refCoords.has(key) || outStations.some((s) => s.refCode && normCode(s.refCode) === key)) {
        rErr('code', `«${code}» es la referencia de orientación; no puede registrarse también como punto radiado.`);
      }
    }
    if (!st) rErr('station', 'la estación indicada no existe en la libreta.');

    const hz = parseAngle(rd.hz, unit);
    if (hz.empty) rErr('hz', 'falta la lectura horizontal.');
    else if (!hz.ok) rErr('hz', hz.error);
    else if (hz.deg < 0 || hz.deg >= 360) rErr('hz', `la lectura horizontal debe estar entre 0 y ${unit === 'centesimal' ? '400g' : '360°'}.`);
    else p.hzDeg = hz.deg;

    if (!isNum(rd.distance)) rErr('distance', 'falta la distancia (m).');
    else if (rd.distance <= 0) rErr('distance', 'la distancia debe ser mayor que cero.');

    if (rd.distType === 'inclinada') {
      const v = parseAngle(rd.vAngle, unit);
      if (rd.vType !== 'cenital' && rd.vType !== 'vertical') rErr('vType', 'indique si el ángulo vertical es cenital (Z) o de altura (α).');
      else if (v.empty) rErr('vAngle', `la distancia es inclinada: ingrese el ángulo ${rd.vType === 'cenital' ? 'cenital Z' : 'vertical α'} para reducirla a horizontal.`);
      else if (!v.ok) rErr('vAngle', v.error);
      else if (rd.vType === 'cenital' && (v.deg <= 0 || v.deg >= halfDeg)) rErr('vAngle', `el ángulo cenital debe estar entre 0 y ${unit === 'centesimal' ? '200g' : '180°'} (sin incluirlos).`);
      else if (rd.vType === 'vertical' && (v.deg <= -90 || v.deg >= 90)) rErr('vAngle', `el ángulo vertical debe estar entre −${unit === 'centesimal' ? '100g' : '90°'} y +${unit === 'centesimal' ? '100g' : '90°'}.`);
      else p.vDeg = v.deg;
    } else if (rd.distType !== 'horizontal') {
      rErr('distType', 'indique si la distancia es horizontal o inclinada.');
    }

    // Cálculo (solo si la lectura y su estación son válidas)
    if (!p.ok || !st || !st.ok || st.az0 === null || st.l0 === null || convErr.length) return;
    const L = p.hzDeg;
    let raw;
    if (origin === 'norte') {
      raw = sign > 0 ? L : 360 - L;
      p.azSteps.push(sign > 0 ? `Az = L = ${A(L)}` : `Az = ${unit === 'centesimal' ? '400g' : '360°'} − L = ${unit === 'centesimal' ? '400g' : '360°'} − ${A(L)} = ${A(raw)}`);
    } else {
      raw = st.az0 + sign * (L - st.l0);
      p.azSteps.push(`Az = Az_ref ${sign > 0 ? '+' : '−'} (L − L_ref) = ${A(st.az0)} ${sign > 0 ? '+' : '−'} (${A(L)} − ${A(st.l0)}) = ${A(raw)}`);
    }
    const az = normalizeAzimuth(raw);
    if (Math.abs(az - raw) > 1e-9) {
      const turns = Math.round((raw - az) / 360);
      const turnTxt = unit === 'centesimal' ? `${Math.abs(turns) * 400}g` : `${Math.abs(turns) * 360}°`;
      p.azSteps.push(`Normalización al intervalo [0, ${unit === 'centesimal' ? '400g' : '360°'}): ${A(raw)} ${turns > 0 ? '−' : '+'} ${turnTxt} = ${A(az)}`);
    }
    p.azRaw = raw;
    p.az = az;

    if (rd.distType === 'inclinada') {
      if (rd.vType === 'cenital') {
        p.dh = rd.distance * Math.sin(toRad(p.vDeg));
        p.dhSteps.push(`Dh = Di × sen(Z) = ${fmt(rd.distance)} × sen(${A(p.vDeg)}) = ${fmt(p.dh)} m`);
      } else {
        p.dh = rd.distance * Math.cos(toRad(p.vDeg));
        p.dhSteps.push(`Dh = Di × cos(α) = ${fmt(rd.distance)} × cos(${A(p.vDeg)}) = ${fmt(p.dh)} m`);
      }
    } else {
      p.dh = rd.distance;
      p.dhSteps.push(`Dh = ${fmt(rd.distance)} m (distancia horizontal medida)`);
    }

    p.dE = p.dh * Math.sin(toRad(az));
    p.dN = p.dh * Math.cos(toRad(az));
    p.east = st.east + p.dE;
    p.north = st.north + p.dN;
    p.coordSteps.push(`ΔE = Dh × sen(Az) = ${fmt(p.dh)} × sen(${A(az)}) = ${fmt(p.dE)} m`);
    p.coordSteps.push(`ΔN = Dh × cos(Az) = ${fmt(p.dh)} × cos(${A(az)}) = ${fmt(p.dN)} m`);
    p.coordSteps.push(`E = E(${st.code}) + ΔE = ${fmt(st.east)} + ${fmtTerm(p.dE)} = ${fmt(p.east)} m`);
    p.coordSteps.push(`N = N(${st.code}) + ΔN = ${fmt(st.north)} + ${fmtTerm(p.dN)} = ${fmt(p.north)} m`);
  });

  if (readings.length === 0) warnings.push({ readingId: null, message: 'Todavía no hay lecturas de puntos radiados.' });

  const computable = errors.length === 0;
  if (!computable) {
    for (const p of points) { p.east = null; p.north = null; p.dE = null; p.dN = null; }
  }

  // Coordenadas disponibles por código
  const coordsByCode = new Map();
  if (computable) {
    for (const s of outStations) {
      coordsByCode.set(normCode(s.code), { code: s.code, east: s.east, north: s.north, source: s.coordType === 'conocidas' ? 'Estación (coordenadas conocidas)' : 'Estación (coordenadas locales declaradas)', kind: 'estacion', stationCode: s.code });
    }
    for (const [key, r] of refCoords) {
      if (!coordsByCode.has(key)) coordsByCode.set(key, { code: r.code, east: r.east, north: r.north, source: `Referencia de orientación (coordenadas ingresadas)`, kind: 'referencia', stationCode: r.stationCode });
    }
    for (const p of points) {
      coordsByCode.set(normCode(p.code), { code: p.code, east: p.east, north: p.north, source: `Radiación desde ${p.stationCode}`, kind: 'radiado', stationCode: p.stationCode });
    }
  }

  return { computable, errors, warnings, unit, stations: outStations, points, coordsByCode };
}
