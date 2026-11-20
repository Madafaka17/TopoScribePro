// CASO DIDÁCTICO DE PRUEBA (no es un levantamiento real). Solo lo usan las pruebas automáticas;
// la aplicación nunca lo carga en los proyectos del usuario.
import { newProject } from '../../web/js/core/model.js';

export function makeSampleProject() {
  const p = newProject({
    name: 'PRUEBA — Levantamiento didáctico',
    location: 'Caso de prueba (datos sintéticos)',
    date: '2026-11-20',
    responsibles: 'Equipo de pruebas automáticas',
    objective: 'Verificar cálculos y exportaciones con datos de prueba conocidos.',
    coordinateSystem: 'Local arbitrario (E = 500.000, N = 1000.000 en EST-1)',
    elevationDatum: 'Cota arbitraria BM-1 = 100.000 m',
    siteDescription: 'Terreno de prueba.',
    fieldProcedure: 'Nivelación cerrada en BM-1 y radiación desde EST-1.',
  }, 'pruebas');
  const pt = (code, type, instrument, description = '') => ({ id: `pt-${code}`, code, description, type, instrument, observations: '', knownElevation: null, elevationJustification: '', photo: null, createdAt: p.createdAt, createdBy: 'pruebas' });
  p.points = [
    pt('BM-1', 'bm', 'nivel', 'Banco de nivel de partida'),
    pt('EST-1', 'estacion', 'ambos', 'Estación del teodolito'),
    pt('R-1', 'referencia', 'teodolito', 'Referencia de orientación'),
    pt('P1', 'detalle', 'ambos', 'Esquina noreste'),
    pt('P2', 'detalle', 'ambos', 'Poste'),
    pt('PC1', 'pc', 'nivel', 'Punto de cambio'),
    pt('P3', 'detalle', 'ambos', 'Esquina sureste'),
    pt('P4', 'detalle', 'ambos', 'Esquina suroeste'),
    pt('P5', 'detalle', 'ambos', 'Esquina noroeste'),
  ];
  const r = (code, bs, is, fs, partial, cumulative, inProfile = true) => ({ id: `lv-${code}-${bs ?? ''}${fs ?? ''}`, code, bs, is, fs, partial, cumulative, inProfile, notes: '' });
  p.leveling.bm = { code: 'BM-1', elevation: 100 };
  p.leveling.endBm = { enabled: true, code: 'BM-1', elevation: 100 };
  p.leveling.rows = [
    r('BM-1', 1.432, null, null, null, null, false),
    r('EST-1', null, 1.105, null, null, 0),
    r('P1', null, 0.870, null, 15, null),
    r('P2', null, 1.540, null, 12.5, null),
    r('PC1', 1.215, null, 1.968, 10, null),
    r('P3', null, 0.954, null, 14, null),
    r('P4', null, 1.402, null, null, 63),
    r('P5', null, 1.780, null, 9.5, null),
    r('BM-1', null, null, 0.676, null, null, false),
  ];
  p.theodolite.convention = { unit: 'sexagesimal', direction: 'horario', origin: 'referencia' };
  p.theodolite.stations = [{
    id: 'st-1', code: 'EST-1', east: 500, north: 1000, coordType: 'locales', instrumentHeight: 1.45,
    orientation: { refCode: 'R-1', azMode: 'coordenadas', refEast: 500, refNorth: 1050, refAzimuth: '', refReading: '0°00\'00"', northNote: '' },
  }];
  const rd = (code, hz, distType, distance, vType = null, vAngle = '') => ({ id: `rd-${code}`, stationId: 'st-1', code, hz, distType, distance, vType, vAngle, description: '', notes: '' });
  p.theodolite.readings = [
    rd('P1', '30°00\'00"', 'horizontal', 25),
    rd('P2', '95°30\'00"', 'inclinada', 30, 'cenital', '85°00\'00"'),
    rd('P3', '150°15\'30"', 'horizontal', 28),
    rd('P4', '210°00\'00"', 'horizontal', 22.5),
    rd('P5', '300°45\'00"', 'horizontal', 26),
  ];
  p.plan.lines = [{ id: 'ln-1', name: 'Lindero', codes: ['P1', 'P2', 'P3', 'P4', 'P5'], closed: true }];
  p.plan.equidistance = 0.5;
  return p;
}
