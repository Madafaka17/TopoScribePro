import { html, render } from '../vendor/preact-htm.mjs';
import { App } from './ui/app.js';

const root = document.getElementById('app');
root.textContent = '';
render(html`<${App} />`, root);

// Funcionamiento sin conexión (solo en https o localhost)
if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1')) {
  window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
}
