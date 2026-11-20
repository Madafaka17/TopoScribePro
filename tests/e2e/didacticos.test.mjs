// CASOS DIDÁCTICOS DEL DOCENTE, introducidos por la interfaz como lo haría un estudiante,
// en vista de celular y de computadora. Cada caso usa un navegador con almacenamiento vacío.
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { OUT, addLevel, addRad, addStation, chromium, click, devices, newPage, newProject, setBm, setConvention, setUser, startServer, tableValues, tid } from './helpers.mjs';

let server;
let browser;
before(async () => { server = await startServer(); browser = await chromium.launch(); await mkdir(OUT, { recursive: true }); });
after(async () => { await browser?.close(); server?.stop(); });

for (const [label, options] of [['celular (Pixel 7)', { ...devices['Pixel 7'] }], ['computadora (1366×900)', { viewport: { width: 1366, height: 900 } }]]) {
  test(`PRUEBA nivelación del docente en ${label}: BM 100.000, VA 1.250, VD 0.850 → AI 101.250, cota 100.400`, async () => {
    const { page, errors, context } = await newPage(browser, server.url, options);
    await setUser(page, 'Prueba automática');
    await newProject(page, { name: 'PRUEBA — nivelación del docente' });
    await setBm(page, 'BM', '100.000');
    await addLevel(page, { code: 'BM', kind: 'BM', bs: '1.250', inProfile: false });
    await addLevel(page, { code: 'P1', kind: 'VD', fs: '0,850', inProfile: false }); // con coma decimal
    await click(page, 'nav-nivel');
    await click(page, 'btn-calcular-nivel');
    await page.locator(tid('level-status')).getByText('Calculado el').waitFor();
    const steps = await page.locator(tid('level-steps')).textContent();
    assert.match(steps, /AI \(E1\) = Cota BM \+ VA = 100\.000 \+ 1\.250 = 101\.250 m/);
    assert.match(steps, /Cota P1 = AI \(E1\) − VD = 101\.250 − 0\.850 = 100\.400 m/);
    const v = await tableValues(page, 'level-results', ['cota']);
    assert.equal(v.P1.cota, '100.400');
    assert.match(await page.locator(tid('level-check')).textContent(), /0\.400.*COINCIDE/s);
    assert.match(await page.locator(tid('closure-notice')).textContent(), /no puede comprobarse el cierre/);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert.ok(overflow <= 1, `sin desplazamiento horizontal de la página (sobran ${overflow}px)`);
    await page.screenshot({ path: `${OUT}didactico-nivel-${label.split(' ')[0]}.png`, fullPage: true });
    assert.deepEqual(errors, []);
    await context.close();
  });

  test(`PRUEBA radiación del docente en ${label}: E 500, N 1000, Dh 10, Az 90° → E 510, N 1000`, async () => {
    const { page, errors, context } = await newPage(browser, server.url, options);
    await setUser(page, 'Prueba automática');
    await newProject(page, { name: 'PRUEBA — radiación del docente' });
    await setConvention(page, 'sexagesimal', 'horario', 'norte');
    await addStation(page, { code: 'EST', coordType: 'locales', east: '500.000', north: '1000.000', northNote: 'Caso de prueba: círculo orientado al norte' });
    await addRad(page, { code: 'P', hz: '90°00\'00"', distance: '10.000' });
    await click(page, 'nav-teodolito');
    await click(page, 'btn-calcular-teodolito');
    const v = await tableValues(page, 'rad-results', ['az', 'east', 'north']);
    assert.deepEqual(v.P, { az: '90°00\'00"', east: '510.000', north: '1000.000' });
    const steps = await page.locator(tid('rad-steps-P')).textContent();
    assert.match(steps, /ΔE = Dh × sen\(Az\) = 10\.000 × sen\(90°00'00"\) = 10\.000 m/);
    assert.match(steps, /N = N\(EST\) \+ ΔN = 1000\.000 \+ 0\.000 = 1000\.000 m/);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert.ok(overflow <= 1, `sin desplazamiento horizontal (sobran ${overflow}px)`);
    await page.screenshot({ path: `${OUT}didactico-radiacion-${label.split(' ')[0]}.png`, fullPage: true });
    assert.deepEqual(errors, []);
    await context.close();
  });
}
