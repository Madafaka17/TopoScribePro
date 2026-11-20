// Componentes básicos de la interfaz (Preact + htm, sin compilación).
import { html, useEffect, useRef, useState } from '../../vendor/preact-htm.mjs';

// ------------------------------------------------------------------ Avisos flotantes
const toastListeners = new Set();
export function toast(message, kind = 'info', ms = 3500) {
  const t = { id: Math.random().toString(36).slice(2), message, kind };
  toastListeners.forEach((fn) => fn(t, ms));
}
export function Toasts() {
  const [items, setItems] = useState([]);
  useEffect(() => {
    const fn = (t, ms) => {
      setItems((x) => [...x.slice(-1), t]);
      setTimeout(() => setItems((x) => x.filter((i) => i.id !== t.id)), ms);
    };
    toastListeners.add(fn);
    return () => toastListeners.delete(fn);
  }, []);
  return html`<div class="toasts" role="status" aria-live="polite">${items.map((t) => html`<div class=${`toast ${t.kind}`} key=${t.id}>${t.message}</div>`)}</div>`;
}

// ------------------------------------------------------------------ Botón
/**
 * Botón que SIEMPRE hace algo: si está deshabilitado (disabledReason), al pulsarlo explica por qué,
 * y además muestra el motivo debajo (visible también en el celular, donde no hay "tooltip").
 */
export function Btn({ children, onClick, disabledReason = null, kind = 'primary', small = false, showReason = true, testid, type = 'button', title }) {
  const disabled = !!disabledReason;
  const cls = `btn btn-${kind}${small ? ' btn-sm' : ''}${disabled ? ' is-disabled' : ''}`;
  const click = (e) => {
    if (disabled) { e.preventDefault(); toast(disabledReason, 'warn', 6000); return; }
    onClick?.(e);
  };
  return html`<span class="btn-wrap">
    <button type=${type} class=${cls} aria-disabled=${disabled ? 'true' : 'false'} title=${disabledReason || title || ''} data-testid=${testid} onClick=${click}>${children}</button>
    ${disabled && showReason ? html`<small class="why">${disabledReason}</small>` : null}
  </span>`;
}

// ------------------------------------------------------------------ Campos
export function Field({ label, unit, hint, error, children }) {
  return html`<label class="field">
    <span>${label}</span>
    ${unit ? html`<span class="input-unit">${children}<span class="unit">${unit}</span></span>` : children}
    ${error ? html`<span class="err">${error}</span>` : hint ? html`<span class="hint">${hint}</span>` : null}
  </label>`;
}

export function TextInput({ value, onInput, placeholder, numeric = false, testid, invalid = false, list, autofocus = false, type = 'text' }) {
  const ref = useRef();
  useEffect(() => { if (autofocus) ref.current?.focus(); }, []);
  return html`<input ref=${ref} type=${type} value=${value ?? ''} placeholder=${placeholder ?? ''} list=${list}
    inputmode=${numeric ? 'decimal' : undefined} autocomplete="off" spellcheck=${numeric ? 'false' : undefined}
    class=${invalid ? 'invalid' : ''} data-testid=${testid} onInput=${(e) => onInput(e.currentTarget.value)} />`;
}

export function Select({ value, onChange, options, testid, placeholder }) {
  return html`<select value=${value ?? ''} data-testid=${testid} onChange=${(e) => onChange(e.currentTarget.value)}>
    ${placeholder !== undefined ? html`<option value="">${placeholder}</option>` : null}
    ${options.map(([v, l]) => html`<option value=${v} selected=${v === value}>${l}</option>`)}
  </select>`;
}

export function Check({ checked, onChange, label, testid }) {
  return html`<label class="check"><input type="checkbox" checked=${!!checked} data-testid=${testid} onChange=${(e) => onChange(e.currentTarget.checked)} /> ${label}</label>`;
}

export function Radios({ value, onChange, options, name, testid }) {
  return html`<div class="radio-group" role="radiogroup" data-testid=${testid}>
    ${options.map(([v, l]) => html`<label class=${v === value ? 'on' : ''}>
      <input type="radio" name=${name} value=${v} checked=${v === value} onChange=${() => onChange(v)} /> ${l}
    </label>`)}
  </div>`;
}

// ------------------------------------------------------------------ Ventana modal
export function Modal({ title, onClose, children, footer, wide = false, testid }) {
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose?.(); };
    document.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.removeEventListener('keydown', onKey); document.body.style.overflow = prev; };
  }, []);
  return html`<div class="overlay" onMouseDown=${(e) => { if (e.target === e.currentTarget) onClose?.(); }}>
    <div class=${`modal${wide ? ' wide' : ''}`} role="dialog" aria-modal="true" aria-label=${title} data-testid=${testid}>
      <header><h3>${title}</h3><button class="btn btn-ghost btn-sm" aria-label="Cerrar" onClick=${onClose}>✕</button></header>
      <div class="body">${children}</div>
      ${footer ? html`<footer>${footer}</footer>` : null}
    </div>
  </div>`;
}

// ------------------------------------------------------------------ Confirmación (con motivo opcional)
const confirmListeners = new Set();
/** Pide confirmación. Devuelve Promise<{ok:boolean, reason:string}> */
export function confirmDialog(opts) {
  return new Promise((resolve) => confirmListeners.forEach((fn) => fn({ ...opts, resolve })));
}
export function ConfirmHost() {
  const [req, setReq] = useState(null);
  const [reason, setReason] = useState('');
  const [typed, setTyped] = useState('');
  useEffect(() => {
    const fn = (r) => { setReason(''); setTyped(''); setReq(r); };
    confirmListeners.add(fn);
    return () => confirmListeners.delete(fn);
  }, []);
  if (!req) return null;
  const close = (ok) => { req.resolve({ ok, reason: reason.trim() }); setReq(null); };
  const needType = req.typeToConfirm;
  const blocked = needType && typed.trim() !== needType;
  return html`<${Modal} title=${req.title} onClose=${() => close(false)} testid="confirm"
    footer=${html`<${Btn} kind="secondary" onClick=${() => close(false)}>Cancelar<//>
      <${Btn} kind=${req.danger ? 'danger' : 'primary'} testid="confirm-ok" showReason=${false}
        disabledReason=${blocked ? `Escriba «${needType}» para confirmar.` : null} onClick=${() => close(true)}>${req.confirmText ?? 'Aceptar'}<//>`}>
    <div class="stack">
      ${(Array.isArray(req.message) ? req.message : [req.message]).map((m) => html`<p>${m}</p>`)}
      ${req.askReason ? html`<${Field} label="Motivo (queda en el registro de correcciones)" hint="Opcional"><${TextInput} value=${reason} onInput=${setReason} testid="confirm-reason" /><//>` : null}
      ${needType ? html`<${Field} label=${`Para confirmar escriba: ${needType}`}><${TextInput} value=${typed} onInput=${setTyped} testid="confirm-type" /><//>` : null}
    </div>
  <//>`;
}

// ------------------------------------------------------------------ Aviso en página
export function Notice({ kind = 'info', title, children, testid }) {
  return html`<div class=${`notice ${kind}`} data-testid=${testid}>${title ? html`<strong>${title}</strong>` : null}${children}</div>`;
}

export function StatusBadge({ status }) {
  const map = {
    'sin-datos': ['muted', 'Sin datos'],
    'sin-calcular': ['warn', 'Sin calcular'],
    vigente: ['ok', 'Calculado y vigente'],
    desactualizado: ['err', 'Desactualizado'],
  };
  const [k, l] = map[status] ?? ['muted', status];
  return html`<span class=${`badge ${k}`} data-testid="status-badge">${l}</span>`;
}

export function ErrorList({ title = 'Errores que impiden calcular', errors, testid }) {
  if (!errors?.length) return null;
  return html`<${Notice} kind="err" title=${title} testid=${testid}><ul>${errors.map((e) => html`<li>${e.message ?? e}</li>`)}</ul><//>`;
}

export function WarningList({ title = 'Advertencias', warnings }) {
  if (!warnings?.length) return null;
  return html`<${Notice} kind="warn" title=${title}><ul>${warnings.map((e) => html`<li>${e.message ?? e}</li>`)}</ul><//>`;
}

export const fmtDateTime = (iso) => {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleString('es', { dateStyle: 'short', timeStyle: 'short' }); } catch { return iso; }
};
