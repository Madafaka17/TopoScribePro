# Entrega — TopoScribe Pro 2.1.0 (versionCode 2100)

APK: `TopoScribePro-2.1.0-debug.apk` · `applicationId com.toposcribe.pro` · firmado con la clave **debug** de esta máquina.
Instrucciones de compilación en `README.md`.

## 0. Lo que debe saber primero

* **No recibí el APK `TopoScribePro-2.0.0-debug.apk`** (no existe en el equipo; lo busqué en todo el disco). Por eso **no pude comparar el código con esa versión**.
  El código fuente recibido era la **v0.1.0 (base de datos Room v1)**, sin módulo de anexos, con menús de estación total/GNSS/poligonal y con un proyecto de demostración.
  Tomé esa fuente como base y la compilé y comprobé que arrancaba antes de modificarla. Si su APK 2.0.0 usa un esquema de base de datos distinto al v1, la migración v1→v2 **no está probada contra él**.
* **Esta entrega NO está verificada de punta a punta en pantalla.** Pedí detener las pruebas y cerrar la entrega; lo que sí y lo que no se probó está en la sección 4. Revise esa sección antes de usarla en campo.

## 1. Qué hace la versión 2.1.0

Navegación: **Proyecto · Libreta de campo · Planimetría · Altimetría · Curvas de nivel · Plano CAD · Informe** (Informe incluye *Panel fotográfico* y *Anexos*).

| Requisito | Dónde está |
|---|---|
| Proyecto nuevo vacío; crear/editar/guardar/reabrir con objetivo, ubicación, fecha, responsables, descripción del terreno, unidades, sistema de coordenadas y observaciones; eliminar con confirmación | `ui/screens/proyecto/*`, `ProjectRepository` (se eliminó el proyecto de demostración) |
| Solo **Nivel de ingeniero** y **Teodolito**; estación total, GNSS/RTK, poligonales y Bowditch fuera de la captura (datos históricos conservados) | pantallas nuevas; datos en `:data` intactos |
| Botones **«Añadir punto»** y **«Añadir lectura»**; editar/eliminar con confirmación; código único (sin distinguir mayúsculas), campos obligatorios, formatos angulares (G M S o decimal), referencias a puntos inexistentes | `ui/screens/libreta/*`, `PointRepository`, `LevelingRepository`, `RadiationRepository` |
| Lectura original + **historial de correcciones**; resultado **desactualizado** al editar una lectura | `EditHistoryRepository`, huella de entradas (`calc/Staleness.kt`) + tabla `calc_snapshots` |
| Altimetría: BM, VA/VI/VD, puntos de cambio, distancias parcial y acumulada, HI y cotas con operaciones, comprobación ΣVA−ΣVD, error de cierre solo con BM final conocido, **perfil longitudinal** (no se dibuja si faltan datos y lista los puntos incompletos) | `calc/LevelingDifferential.kt`, `ProfileRenderer`, `AltimetriaScreen` |
| Planimetría por **radiación** con estación, orientación, convenio angular, azimut normalizado, distancia inclinada reducida con cenital/vertical, ficha con fórmulas sustituidas, unir puntos en el orden elegido, distancias entre puntos | `calc/Polar.kt`, `RadiationRepository`, `PlanimetriaScreen` |
| **Curvas de nivel** solo con puntos con E, N y cota válidos (nivel de ingeniero o control conocido); un punto sin cota no recibe cota; explica qué falta; equidistancia y método visibles | `calc/Contours.kt`, `CurvasScreen` |
| **Plano CAD** con capas, norte, escala gráfica, leyenda, sistema de coordenadas; **DXF** R12/AutoCAD por capas con verificación automática del archivo escrito | `calc/Plan.kt`, `calc/Dxf.kt`, `io/PlanRenderer.kt` |
| Fotografía por punto: agregar (cámara/galería), pie de foto, ver, reemplazar, retirar; **panel fotográfico** | `ui/components/PointPhoto.kt`, `PhotoStorage.kt` |
| **Anexos**: agregar, ver, editar descripción, cambiar orden, reemplazar, descargar, eliminar; validación de tipo/tamaño (máx. 25 MB) y de relación con puntos | `AnnexRepository`, `calc/AnnexRules.kt`, `InformeScreen` |
| **Respaldo completo** `.zip` (datos + fotos + anexos con SHA-256) e importación | `data/backup/ProjectBackup.kt` |
| **Informe PDF** (10 secciones, vista previa, páginas numeradas «N de T», índice de anexos, imágenes y PDF anexos incorporados; lo que no se puede insertar se marca «No incorporado» y queda en el respaldo) | `io/PdfReportBuilder.kt` |
| CSV por separado: libreta original (nivel / radiación, solo datos ingresados) y resultados (solo calculados) | `ExportRepository` |

Migración de datos: base **v1 → v2 solo añadiendo** columnas/tablas, sin migración destructiva, con **copia previa** del archivo en `files/backups/pre-migracion-v1-<fecha>/`.

## 2. Archivos

Resumen (comparado con la fuente recibida): **40 archivos añadidos, 56 modificados, 35 eliminados**.

* **Añadidos** — `:calc`: `AnnexRules`, `Dxf`, `Plan`, `Staleness`, `TraverseType` y 4 clases de prueba · `:data`: `AnnexEntity`, `CalcSnapshotEntity`, `AnnexDao`, `CalcSnapshotDao`, `Migrations`, `PreMigrationBackup`, `backup/ProjectBackup`, esquemas Room `1.json` y `2.json`, 2 pruebas instrumentadas · `:io`: `ImageUtils`, `PdfFiles`, `PlanRenderer`, `ProfileRenderer`, 1 prueba instrumentada · `:app`: repositorios (`CoreRepositories`, `RadiationRepository`, `ResultsRepositories`, `AnnexRepository`, `ExportRepository`), componentes (`Common`, `PlanCanvas`, `PointPhoto`), `nav/TopoRoot`, pantallas Proyecto, Curvas e Informe.
* **Modificados** — entidades/DAO de Room, `TopoScribeDatabase` (v2), `Polar`, `LevelingDifferential`, `Contours`, `Angles`, `FormulaRegistry`, `ReportTypes`, `PdfReportBuilder`, `PdfLayoutHelpers`, `CsvWriter`, pantallas Libreta/Planimetría/Altimetría/Plano CAD, `DatabaseModule`, `MethodPriority`, `build.gradle.kts`, `libs.versions.toml`.
* **Eliminados** — motores y pantallas de poligonal/Bowditch/nivelación trigonométrica/estación total/GNSS, `DemoSeeder`, y las pantallas e informe anteriores (sustituidos). Los **datos** históricos de esas opciones siguen en la base de datos y en el respaldo.

## 3. Pruebas que SÍ se ejecutaron y su resultado

| Prueba | Resultado |
|---|---|
| `:calc:test` (JVM) — 68 pruebas: caso nivel (BM 100.000, VA 1.250, VD 0.850 → HI 101.250, cota 100.400), caso radiación (E 500, N 1000, d 10, Az 90° → 510 / 1000), cambio de estación, distancia acumulada, orientación horaria/antihoraria, distancia inclinada, azimut > 360°, perfil válido/incompleto, elegibilidad de curvas (punto sin cota no produce curvas), DXF y su verificación, reglas de anexos | **68 / 68 correctas** |
| `:data:connectedDebugAndroidTest` en emulador (Android 16, x86_64) — migración v1→v2 con datos históricos y respaldo/restauración con fotos y anexos reales (contenido byte a byte, ids remapeados, respaldo alterado rechazado, archivo ajeno rechazado) | **7 / 7 correctas** |
| **Actualización real**: APK original (v0.1.0) instalado en un AVD limpio, base v1 poblada con datos históricos (estación total, GNSS, poligonal, nivelación, historial, foto), APK 2.1.0 instalado encima | Migró sin errores; `user_version = 2`; todas las tablas conservadas; `integrity_check = ok`; se creó la copia previa; la app abre y muestra los datos históricos y la advertencia «resultado de una versión anterior» |
| DXF generado abierto con **ezdxf** (lector independiente) | AC1009, 0 errores de auditoría, capas/entidades/coordenadas correctas |
| Recorrido visual en el emulador de las pestañas Proyecto, Libreta y Planimetría (proyecto nuevo vacío y proyecto histórico) | Se muestran correctamente |

## 4. Lo que NO se verificó (límites y riesgos reales)

1. **Informe PDF completo en el dispositivo:** la prueba `io/.../ReportPdfTest.kt` (PDF con fotos, plano, curvas, perfil, anexos imagen/PDF/otros) está escrita y compila, pero **no se ejecutó**; no he visto un PDF generado por la app. Puede haber defectos de maquetación o de paginación.
2. **Recorrido con datos en pantalla** de Altimetría, Curvas de nivel, Plano CAD (gestos, capas), exportación de DXF desde la app, y la pestaña Informe (vista previa, exportar/compartir PDF): no se recorrieron. Los cálculos subyacentes sí tienen pruebas; la interfaz que los usa no.
3. **Selectores del sistema** (cámara, galería, abrir/guardar archivo) para fotografías, anexos, respaldo e importación **no se probaron en el emulador**. La lógica de respaldo/restauración sí (prueba instrumentada), pero no su invocación desde la interfaz, ni la restauración en un segundo dispositivo físico.
4. **Entrada de datos por la interfaz:** el emulador lento y el teclado de pantalla impidieron automatizar el ingreso de datos; no verifiqué los formularios (puntos, lecturas, estación, anexos) con uso real.
5. **APK 2.0.0 de referencia no comparado** (no se proporcionó).
6. El APK es **debug** (no firmado para publicación) y la compilación se hizo con `--offline` desde la caché de Gradle.

## 5. Funciones pendientes / limitaciones conocidas

* **Excel (.xlsx):** solo CSV (con BOM UTF-8; Excel lo abre).
* **DXF:** formato R12 (AC1009) en Windows-1252; los puntos sin cota se escriben con Z = 0 (la capa de cotas solo rotula los que sí tienen). No se exportan bloques ni estilos de texto personalizados.
* **Curvas:** triangulación de Delaunay con interpolación lineal; los triángulos con ángulo < 1° se descartan; sin suavizado. Mínimo 4 puntos con E, N y cota.
* **Anexos en el PDF:** imágenes y PDF se incorporan (PDF: hasta 30 páginas por anexo, el resto queda en el respaldo); DOCX, XLSX, TXT, DXF y DWG **no** se insertan y el índice lo indica.
* **Perfil longitudinal:** solo con la nivelación (distancia acumulada vs. cota); se exige distancia parcial en cada VI/VD.
* **Una sola fotografía por punto** (más fotos generales van como anexos).
* **Teodolito:** sin ajuste de errores angulares ni de cierre planimétrico (solo radiación, como se pidió).
* **Datos históricos** de poligonales/GNSS/estación total: se conservan y se listan, pero no se recalculan ni se editan.
* Las cotas calculadas por la antigua nivelación trigonométrica no se usan para curvas (se muestran como «Otro cálculo (no usado)»).
* `gradle.properties` apunta al JDK 17 de esta máquina; ajústelo en otra computadora.
