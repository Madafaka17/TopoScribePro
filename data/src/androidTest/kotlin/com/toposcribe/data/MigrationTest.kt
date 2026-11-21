package com.toposcribe.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.toposcribe.data.local.ALL_MIGRATIONS
import com.toposcribe.data.local.MIGRATION_1_2
import com.toposcribe.data.local.TopoScribeDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba de migración v1 → v2: se crea una base con el esquema EXACTO de la versión original,
 * se insertan datos históricos (incluidos opciones retiradas de la interfaz) y se comprueba que
 * tras migrar todo sigue ahí y que Room acepta el esquema resultante.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TopoScribeDatabase::class.java)

    private fun seedV1(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO projects (id,name,purpose,instrument,instrument_type,prism_constant_m,coordinate_mode,units_distance,units_angle,display_precision,is_demo,created_at) VALUES (1,'Historico','Lote','Leica TS06','ESTACION_TOTAL',0.0,'LOCAL','m','DMS',3,0,'2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO points (id,project_id,code,description,is_control,role,data_source,fixed_east,fixed_north,fixed_elevation,sketch_photo_path,photo_caption,reviewed,created_at) VALUES (10,1,'EST-A','Estacion',1,'CONTROL','CONTROL_OFICIAL',1000.0,2000.0,100.0,'/data/photos/a.jpg','Foto A',0,'2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO points (id,project_id,code,description,is_control,role,data_source,fixed_east,fixed_north,fixed_elevation,reviewed,created_at,fix_type) VALUES (11,1,'G1','GNSS',0,'RELLENO','MEDIDO_GNSS',1050.0,2050.0,101.5,0,'2025-06-01T10:00:00Z','FIXED')")
        db.execSQL("INSERT INTO points (id,project_id,code,is_control,role,data_source,reviewed,created_at) VALUES (12,1,'P1',0,'RELLENO','OTRO',0,'2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO station_setups (id,project_id,occupied_point_id,instrument_height_ai,backsight_point_label,base_azimuth_deg,base_azimuth_min,base_azimuth_sec,base_azimuth_decimal,created_at) VALUES (1,1,10,1.55,'P1',45,30,0.0,45.5,'2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO traverses (id,project_id,name,type,created_at) VALUES (1,1,'Poligonal 1','CERRADA_POLIGONO','2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO planimetry_observations (id,project_id,occupied_point_id,target_point_id,method,horizontal_distance,azimuth,station_setup_id,source,created_at) VALUES (1,1,10,12,'ACIMUT_DISTANCIA',40.0,135.0,1,'manual','2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO leveling_runs (id,project_id,name,start_bm_point_id,created_at) VALUES (1,1,'Niv',10,'2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO leveling_observations (id,leveling_run_id,sequence_order,point_id,reading_type,reading_value,is_turning_point,source,created_at) VALUES (1,1,1,10,'BS',1.25,0,'manual','2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO leveling_trig_observations (id,project_id,occupied_point_id,target_point_id,slope_distance,zenith_angle,instrument_height,target_height,source,created_at) VALUES (1,1,10,12,50.0,80.0,1.5,1.3,'manual','2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO adjustments (id,traverse_id,method,fE,fN,fL,perimeter,relative_precision,per_point_corrections,applied,created_at) VALUES (1,1,'bowditch',0.01,-0.02,0.022,120.0,'1:5454','[]',1,'2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO computed_points (id,project_id,point_id,east,north,elevation,method,formula_id,source_observation_ids,is_current,computed_at) VALUES (1,1,12,1028.28,1971.72,NULL,'radiacion','RADIACION','[{\"table\":\"planimetry_observations\",\"id\":1}]',1,'2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO observation_edit_log (id,table_name,row_id,field,old_value,new_value,edited_at) VALUES (1,'points',10,'description','Est','Estacion','2025-06-01T10:00:00Z')")
        db.execSQL("INSERT INTO alignments (id,project_id,name,point_sequence,created_at) VALUES (1,1,'Contorno','[10,12]','2025-06-01T10:00:00Z')")
    }

    @Test
    fun migrate1To2_conservaTodosLosDatosHistoricos() {
        helper.createDatabase(dbName, 1).apply { seedV1(this); close() }
        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, count("projects")); assertEquals(3, count("points")); assertEquals(1, count("station_setups"))
        assertEquals(1, count("traverses")); assertEquals(1, count("planimetry_observations")); assertEquals(1, count("leveling_runs"))
        assertEquals(1, count("leveling_observations")); assertEquals(1, count("leveling_trig_observations")); assertEquals(1, count("adjustments"))
        assertEquals(1, count("computed_points")); assertEquals(1, count("observation_edit_log")); assertEquals(1, count("alignments"))
        assertEquals(0, count("annexes")); assertEquals(0, count("calc_snapshots"))

        // valores concretos intactos (incluida la foto y el pie de foto)
        db.query("SELECT code, fixed_east, fixed_elevation, sketch_photo_path, photo_caption, instrument_ref FROM points WHERE id = 10").use {
            assertTrue(it.moveToFirst())
            assertEquals("EST-A", it.getString(0)); assertEquals(1000.0, it.getDouble(1), 0.0); assertEquals(100.0, it.getDouble(2), 0.0)
            assertEquals("/data/photos/a.jpg", it.getString(3)); assertEquals("Foto A", it.getString(4)); assertTrue(it.isNull(5))
        }
        db.query("SELECT instrument, instrument_type, level_instrument, contour_equidistance FROM projects WHERE id = 1").use {
            assertTrue(it.moveToFirst()); assertEquals("Leica TS06", it.getString(0)); assertEquals("ESTACION_TOTAL", it.getString(1)); assertTrue(it.isNull(2)); assertTrue(it.isNull(3))
        }
        // columnas nuevas con el significado que tenian los datos antiguos
        db.query("SELECT backsight_reading_deg, angle_convention, backsight_point_id, base_azimuth_decimal FROM station_setups").use {
            assertTrue(it.moveToFirst()); assertEquals(0.0, it.getDouble(0), 0.0); assertEquals("HORARIO", it.getString(1)); assertTrue(it.isNull(2)); assertEquals(45.5, it.getDouble(3), 0.0)
        }
        db.query("SELECT closed FROM alignments").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        db.query("SELECT partial_distance FROM leveling_observations").use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)) }
    }

    @Test
    fun migrate1To2_RoomAbreLaBaseMigradaYLosDaoLeenLosDatos() = runBlocking {
        helper.createDatabase(dbName, 1).apply { seedV1(this); close() }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(ctx, TopoScribeDatabase::class.java, dbName).addMigrations(*ALL_MIGRATIONS).build()
        try {
            val project = room.projectDao().getById(1)
            assertNotNull(project)
            assertEquals("Historico", project!!.name)
            val points = room.pointDao().listByProject(1)
            assertEquals(listOf("EST-A", "G1", "P1"), points.map { it.code })
            assertEquals("/data/photos/a.jpg", points.first { it.code == "EST-A" }.sketchPhotoPath)
            assertEquals(1, room.planimetryObservationDao().listSnapshot(1).size)
            assertEquals(1, room.levelingObservationDao().listSnapshot(1).size)
            assertEquals(1, room.levelingTrigObservationDao().listSnapshot(1).size)
            assertEquals(1, room.adjustmentDao().listByTraverse(1).size)
            assertEquals(1, room.computedPointDao().listCurrentSnapshot(1).size)
            assertEquals(1, room.observationEditLogDao().listFor("points", 10).size)
            val setup = room.stationSetupDao().getLatest(1)!!
            assertEquals(45.5, setup.baseAzimuthDecimal, 0.0)
            assertEquals(com.toposcribe.calc.AngleConvention.HORARIO, setup.angleConvention)
            assertFalse(room.alignmentDao().listSnapshot(1).single().closed)
            assertNull(room.annexDao().getById(1))
        } finally { room.close() }
    }
}
