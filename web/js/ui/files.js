// Archivos: descarga, lectura, fotografías (redimensionado sin deformar) y conversiones base64.
import { getPhoto } from '../storage/db.js';

export function downloadBlob(filename, blob) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}

export function safeFileName(text, fallback = 'proyecto') {
  const s = String(text || fallback).normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^_+|_+$/g, '');
  return (s || fallback).slice(0, 80);
}

export const today = () => new Date().toISOString().slice(0, 10);

/** Abre el selector de archivos y devuelve los File elegidos (o [] si se cancela). */
export function pickFiles({ accept = '', multiple = false, capture = null } = {}) {
  return new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.multiple = multiple;
    if (capture) input.setAttribute('capture', capture);
    input.style.display = 'none';
    input.addEventListener('change', () => { resolve([...(input.files ?? [])]); input.remove(); });
    input.addEventListener('cancel', () => { resolve([]); input.remove(); });
    document.body.appendChild(input);
    input.click();
  });
}

export function blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).split(',')[1] ?? '');
    r.onerror = () => reject(r.error);
    r.readAsDataURL(blob);
  });
}

export function base64ToBlob(b64, mime = 'application/octet-stream') {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type: mime });
}

export function arrayBufferToBinaryString(buf) {
  const bytes = new Uint8Array(buf);
  let s = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) s += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  return s;
}

async function decodeImage(blob) {
  if (globalThis.createImageBitmap) {
    try { return await createImageBitmap(blob, { imageOrientation: 'from-image' }); } catch { /* respaldo abajo */ }
  }
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => { resolve(img); URL.revokeObjectURL(url); };
    img.onerror = () => { reject(new Error('No se pudo leer la imagen (¿formato no compatible?).')); URL.revokeObjectURL(url); };
    img.src = url;
  });
}

/**
 * Redimensiona una imagen manteniendo su proporción (nunca la deforma) a lo sumo `maxSide` px de
 * lado mayor y la guarda como JPEG. Respeta la orientación EXIF de la cámara.
 */
export async function resizeImage(blob, maxSide = 2560, quality = 0.9) {
  const img = await decodeImage(blob);
  const w0 = img.width;
  const h0 = img.height;
  if (!w0 || !h0) throw new Error('La imagen está vacía o dañada.');
  const k = Math.min(1, maxSide / Math.max(w0, h0));
  const w = Math.round(w0 * k);
  const h = Math.round(h0 * k);
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, w, h);
  ctx.drawImage(img, 0, 0, w, h);
  img.close?.();
  const out = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality));
  if (!out) throw new Error('No se pudo procesar la imagen.');
  return { blob: out, width: w, height: h, dataUrl: quality < 0.9 ? canvas.toDataURL('image/jpeg', quality) : null };
}

const urlCache = new Map();
/** URL temporal para mostrar la foto guardada (o null si no existe). */
export async function photoUrl(id) {
  if (!id) return null;
  if (urlCache.has(id)) return urlCache.get(id);
  const rec = await getPhoto(id);
  if (!rec?.blob) return null;
  const url = URL.createObjectURL(rec.blob);
  urlCache.set(id, url);
  return url;
}
export function forgetPhotoUrl(id) {
  const u = urlCache.get(id);
  if (u) URL.revokeObjectURL(u);
  urlCache.delete(id);
}

/** Fotos reducidas para el PDF: Map id → {dataUrl, width, height}. */
export async function photosForPdf(project, maxSide = 1600) {
  const out = new Map();
  for (const pt of project.points) {
    if (!pt.photo?.id) continue;
    const rec = await getPhoto(pt.photo.id);
    if (!rec?.blob) continue;
    const r = await resizeImage(rec.blob, maxSide, 0.85);
    out.set(pt.photo.id, { dataUrl: r.dataUrl, width: r.width, height: r.height });
  }
  return out;
}

const scriptCache = new Map();
export function loadScript(src) {
  if (!scriptCache.has(src)) {
    scriptCache.set(src, new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = src;
      s.onload = resolve;
      s.onerror = () => reject(new Error(`No se pudo cargar ${src}. Si está sin conexión, abra la app una vez con conexión para guardarla en caché.`));
      document.head.appendChild(s);
    }));
  }
  return scriptCache.get(src);
}

export async function fetchBinaryString(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`No se pudo cargar ${url}`);
  return arrayBufferToBinaryString(await r.arrayBuffer());
}
