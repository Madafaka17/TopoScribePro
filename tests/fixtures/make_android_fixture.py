#!/usr/bin/env python3
"""Crea una base de PRUEBA con el esquema Room v1 de la app Android (toposcribe.db + toposcribe.db-wal).

Datos sintéticos, solo para probar la migración. Las tablas y columnas reproducen las entidades de
android-legacy/data/src/main/kotlin/com/toposcribe/data/local/entity/*.kt. Parte de los datos queda
SOLO en el archivo -wal (sin consolidar), como ocurre en el teléfono.

Uso: python3 tests/fixtures/make_android_fixture.py <carpeta_destino>
"""
import os
import shutil
import sqlite3
import sys
import tempfile

DDL = """
CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, purpose TEXT, location TEXT, site_characteristics TEXT, date TEXT, responsible TEXT, field_procedure TEXT, instrument TEXT, instrument_type TEXT NOT NULL, prism_constant_m REAL NOT NULL, angular_precision_arcsec REAL, linear_tolerance_denominator REAL, gnss_solution_status TEXT, pdop REAL, differential_correction_type TEXT, compensator_sensitivity_arcsec REAL, mira_type TEXT, standard_precision_mm_per_km REAL, coordinate_system TEXT, coordinate_mode TEXT NOT NULL, utm_datum TEXT, utm_zone INTEGER, utm_hemisphere TEXT, units_distance TEXT NOT NULL, units_angle TEXT NOT NULL, display_precision INTEGER NOT NULL, observations TEXT, is_demo INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT);
CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, project_id INTEGER NOT NULL, code TEXT NOT NULL, description TEXT, observations TEXT, tramo_sector TEXT, is_control INTEGER NOT NULL, role TEXT NOT NULL, data_source TEXT NOT NULL, fix_type TEXT, fixed_east REAL, fixed_north REAL, fixed_elevation REAL, sketch_photo_path TEXT, photo_caption TEXT, photo_segment TEXT, photo_chainage REAL, reviewed INTEGER NOT NULL, reviewed_at TEXT, reviewed_by TEXT, created_at TEXT NOT NULL, updated_at TEXT, deleted_at TEXT);
CREATE TABLE station_setups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, project_id INTEGER NOT NULL, occupied_point_id INTEGER NOT NULL, instrument_height_ai REAL NOT NULL, backsight_point_label TEXT NOT NULL, base_azimuth_deg INTEGER NOT NULL, base_azimuth_min INTEGER NOT NULL, base_azimuth_sec REAL NOT NULL, base_azimuth_decimal REAL NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE traverses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, project_id INTEGER NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, start_point_id INTEGER, end_point_id INTEGER, start_azimuth REAL, created_at TEXT NOT NULL, updated_at TEXT);
CREATE TABLE planimetry_observations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, project_id INTEGER NOT NULL, occupied_point_id INTEGER NOT NULL, target_point_id INTEGER NOT NULL, method TEXT NOT NULL, horizontal_distance REAL, slope_distance REAL, zenith_angle REAL, vertical_angle REAL, horizontal_angle REAL, azimuth REAL, bearing TEXT, instrument_height REAL, prism_height REAL, tramo TEXT, traverse_id INTEGER, station_setup_id INTEGER, sequence_order INTEGER, observation_date TEXT, raw_notes TEXT, photo_path TEXT, source TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT, deleted_at TEXT);
CREATE TABLE leveling_runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, project_id INTEGER NOT NULL, name TEXT NOT NULL, start_bm_point_id INTEGER NOT NULL, end_bm_point_id INTEGER, end_known_elevation REAL, created_at TEXT NOT NULL, updated_at TEXT);
CREATE TABLE leveling_observations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, leveling_run_id INTEGER NOT NULL, sequence_order INTEGER NOT NULL, point_id INTEGER NOT NULL, reading_type TEXT NOT NULL, reading_value REAL NOT NULL, is_turning_point INTEGER NOT NULL, observation_date TEXT, raw_notes TEXT, photo_path TEXT, source TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT, deleted_at TEXT);
CREATE TABLE leveling_trig_observations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, project_id INTEGER NOT NULL, occupied_point_id INTEGER NOT NULL, target_point_id INTEGER NOT NULL, slope_distance REAL, horizontal_distance REAL, zenith_angle REAL, vertical_angle REAL, instrument_height REAL NOT NULL, target_height REAL NOT NULL, source TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT, deleted_at TEXT);
CREATE TABLE adjustments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, traverse_id INTEGER NOT NULL, method TEXT NOT NULL, angular_misclosure REAL, angular_tolerance REAL, angular_ok INTEGER, fE REAL NOT NULL, fN REAL NOT NULL, fL REAL NOT NULL, perimeter REAL NOT NULL, relative_precision TEXT NOT NULL, per_point_corrections TEXT NOT NULL, applied INTEGER NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE computed_points (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, project_id INTEGER NOT NULL, point_id INTEGER NOT NULL, east REAL, north REAL, elevation REAL, method TEXT NOT NULL, formula_id TEXT NOT NULL, source_observation_ids TEXT NOT NULL, adjustment_id INTEGER, is_current INTEGER NOT NULL, warnings TEXT, computed_at TEXT NOT NULL);
CREATE TABLE observation_edit_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, table_name TEXT NOT NULL, row_id INTEGER NOT NULL, field TEXT NOT NULL, old_value TEXT, new_value TEXT, edited_at TEXT NOT NULL);
CREATE TABLE alignments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, project_id INTEGER NOT NULL, name TEXT NOT NULL, point_sequence TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE import_batches (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, project_id INTEGER NOT NULL, file_name TEXT, target_table TEXT, imported_rows INTEGER, errors TEXT, created_at TEXT NOT NULL);
"""

T = "2026-11-18T10:00:00Z"


def project(c, pid, name, itype, demo=0):
    c.execute("INSERT INTO projects (id,name,purpose,location,site_characteristics,date,responsible,field_procedure,instrument,instrument_type,prism_constant_m,coordinate_system,coordinate_mode,units_distance,units_angle,display_precision,observations,is_demo,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,0,?, 'LOCAL','m','DMS',3,?,?,?)",
              (pid, name, 'Práctica de campo (PRUEBA)', 'Campus (prueba)', 'Terreno ondulado', '2026-11-10', 'Grupo de prueba', 'Procedimiento de prueba', 'Equipo de prueba', itype, 'Local', 'Observación de prueba', demo, T))


def point(c, pid, code, role='OTRO', control=0, e=None, n=None, z=None, source='OTRO', photo=None, caption=None, desc=None):
    c.execute("INSERT INTO points (project_id,code,description,is_control,role,data_source,fixed_east,fixed_north,fixed_elevation,sketch_photo_path,photo_caption,reviewed,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?)",
              (pid, code, desc or f'Punto {code}', control, role, source, e, n, z, photo, caption, T))
    return c.execute("SELECT last_insert_rowid()").fetchone()[0]


def main(dest):
    os.makedirs(dest, exist_ok=True)
    tmp = tempfile.mkdtemp()
    path = os.path.join(tmp, "toposcribe.db")
    c = sqlite3.connect(path, isolation_level=None)
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("PRAGMA wal_autocheckpoint=0")
    c.executescript(DDL)
    c.execute("PRAGMA user_version=1")
    c.execute("PRAGMA wal_checkpoint(TRUNCATE)")  # el esquema queda en el archivo principal

    # ---- Proyecto 1: teodolito + nivelación con punto de cambio (todo esto queda SOLO en el -wal)
    c.execute("BEGIN")
    project(c, 1, 'Práctica teodolito y nivel (PRUEBA)', 'TEODOLITO')
    bm = point(c, 1, 'BM-1', 'CONTROL', 1, None, None, 100.0, 'CONTROL_OFICIAL')
    est = point(c, 1, 'EST-1', 'CONTROL', 1, 500.0, 1000.0, None, 'CONTROL_OFICIAL')
    p1 = point(c, 1, 'P1', 'RELLENO', photo='/data/user/0/com.toposcribe.pro/files/photos/P1_1731900000000.jpg', caption='Esquina del lote')
    p2 = point(c, 1, 'P2', 'RELLENO')
    pc = point(c, 1, 'PC1')
    c.execute("INSERT INTO station_setups (project_id,occupied_point_id,instrument_height_ai,backsight_point_label,base_azimuth_deg,base_azimuth_min,base_azimuth_sec,base_azimuth_decimal,created_at) VALUES (1,?,1.45,'R-1',45,0,0,45.0,?)", (est, T))
    c.execute("INSERT INTO planimetry_observations (project_id,occupied_point_id,target_point_id,method,horizontal_distance,horizontal_angle,source,created_at) VALUES (1,?,?,'ANGULO_DISTANCIA',25.0,30.0,'manual',?)", (est, p1, T))
    c.execute("INSERT INTO planimetry_observations (project_id,occupied_point_id,target_point_id,method,slope_distance,zenith_angle,horizontal_angle,source,created_at) VALUES (1,?,?,'ANGULO_DISTANCIA',30.0,85.0,95.5,'manual',?)", (est, p2, T))
    c.execute("INSERT INTO leveling_runs (id,project_id,name,start_bm_point_id,end_bm_point_id,end_known_elevation,created_at) VALUES (1,1,'Nivelación de prueba',?,?,100.0,?)", (bm, bm, T))
    obs = [(1, bm, 'BS', 1.5, 0), (2, p1, 'IS', 1.2, 0), (3, pc, 'FS', 1.8, 1), (4, pc, 'BS', 1.1, 0), (5, p2, 'IS', 0.9, 0), (6, bm, 'FS', 0.799, 0)]
    for seq, pt, t, v, tp in obs:
        c.execute("INSERT INTO leveling_observations (leveling_run_id,sequence_order,point_id,reading_type,reading_value,is_turning_point,source,created_at) VALUES (1,?,?,?,?,?,'manual',?)", (seq, pt, t, v, tp, T))
    c.execute("INSERT INTO observation_edit_log (table_name,row_id,field,old_value,new_value,edited_at) VALUES ('points',?, 'description','Esquina','Esquina del lote',?)", (p1, T))
    c.execute("COMMIT")

    # ---- Proyecto 2: estación total con poligonal + Bowditch + nivelación trigonométrica (fuera de alcance)
    c.execute("BEGIN")
    project(c, 2, 'Poligonal con estación total (PRUEBA)', 'ESTACION_TOTAL')
    a = point(c, 2, 'A', 'POLIGONAL', 1, 1000.0, 1000.0, 50.0, 'CONTROL_OFICIAL')
    b = point(c, 2, 'B', 'POLIGONAL')
    g = point(c, 2, 'G1', 'RELLENO', 0, 1010.0, 1020.0, 51.0, 'MEDIDO_GNSS')
    c.execute("INSERT INTO traverses (id,project_id,name,type,start_point_id,start_azimuth,created_at) VALUES (1,2,'Poligonal cerrada','CERRADA_POLIGONO',?,30.0,?)", (a, T))
    c.execute("INSERT INTO planimetry_observations (project_id,occupied_point_id,target_point_id,method,horizontal_distance,azimuth,traverse_id,source,created_at) VALUES (2,?,?,'ACIMUT_DISTANCIA',85.32,90.0,1,'manual',?)", (a, b, T))
    c.execute("INSERT INTO planimetry_observations (project_id,occupied_point_id,target_point_id,method,horizontal_distance,azimuth,source,created_at) VALUES (2,?,?,'ACIMUT_DISTANCIA',20.0,45.0,'manual',?)", (a, g, T))
    c.execute("INSERT INTO adjustments (traverse_id,method,fE,fN,fL,perimeter,relative_precision,per_point_corrections,applied,created_at) VALUES (1,'bowditch',0.006,-0.004,0.0072,290.0,'1:40278','[]',1,?)", (T,))
    c.execute("INSERT INTO leveling_trig_observations (project_id,occupied_point_id,target_point_id,slope_distance,zenith_angle,instrument_height,target_height,source,created_at) VALUES (2,?,?,50.0,80.0,1.5,1.2,'manual',?)", (a, b, T))
    c.execute("INSERT INTO computed_points (project_id,point_id,east,north,method,formula_id,source_observation_ids,is_current,computed_at) VALUES (2,?,1085.317,1000.0,'poligonal_bowditch','POLIGONAL-BOWDITCH','[]',1,?)", (b, T))
    c.execute("COMMIT")

    # ---- Proyecto 3: demostración
    c.execute("BEGIN")
    project(c, 3, 'Proyecto de demostración', 'ESTACION_TOTAL', demo=1)
    point(c, 3, 'X-1')
    c.execute("COMMIT")

    for suffix in ("", "-wal"):
        shutil.copy(path + suffix, os.path.join(dest, "toposcribe.db" + suffix))
    c.close()
    shutil.rmtree(tmp)
    # La foto referenciada por P1 (imagen de prueba)
    here = os.path.dirname(os.path.abspath(__file__))
    shutil.copy(os.path.join(here, "foto-prueba-800x600.jpg"), os.path.join(dest, "P1_1731900000000.jpg"))
    print("Base de prueba creada en", dest)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "test-results/android")
