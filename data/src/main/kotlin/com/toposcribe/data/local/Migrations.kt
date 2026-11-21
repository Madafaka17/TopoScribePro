package com.toposcribe.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2. Solo AÑADE columnas y tablas: no borra, renombra ni reescribe ningún dato existente
 * (proyectos, puntos, lecturas, resultados, fotografías, historial). Los valores por defecto de las
 * columnas nuevas conservan el significado que tenían los datos antiguos (convenio horario, lectura
 * 0° en la referencia).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `projects` ADD COLUMN `contour_equidistance` REAL")
        db.execSQL("ALTER TABLE `projects` ADD COLUMN `level_instrument` TEXT")
        db.execSQL("ALTER TABLE `projects` ADD COLUMN `theodolite_instrument` TEXT")

        db.execSQL("ALTER TABLE `points` ADD COLUMN `instrument_ref` TEXT")

        db.execSQL("ALTER TABLE `leveling_observations` ADD COLUMN `partial_distance` REAL")

        db.execSQL("ALTER TABLE `station_setups` ADD COLUMN `backsight_reading_deg` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `station_setups` ADD COLUMN `angle_convention` TEXT NOT NULL DEFAULT 'HORARIO'")
        db.execSQL("ALTER TABLE `station_setups` ADD COLUMN `backsight_point_id` INTEGER")

        db.execSQL("ALTER TABLE `alignments` ADD COLUMN `closed` INTEGER NOT NULL DEFAULT 0")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `annexes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`project_id` INTEGER NOT NULL, `original_name` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, " +
                "`mime_type` TEXT NOT NULL, `kind` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `stored_file` TEXT NOT NULL, " +
                "`added_at` TEXT NOT NULL, `updated_at` TEXT, `point_id` INTEGER, `report_section` TEXT, `sort_order` INTEGER NOT NULL, " +
                "FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`point_id`) REFERENCES `points`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_annexes_project_id` ON `annexes` (`project_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_annexes_point_id` ON `annexes` (`point_id`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calc_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`project_id` INTEGER NOT NULL, `kind` TEXT NOT NULL, `subject_id` INTEGER NOT NULL, `input_hash` TEXT NOT NULL, " +
                "`computed_at` TEXT NOT NULL, " +
                "FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_calc_snapshots_project_id_kind_subject_id` ON `calc_snapshots` (`project_id`, `kind`, `subject_id`)")
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
