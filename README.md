# TopoScribe Pro 2.1.0

Aplicación Android para registrar **datos reales de un levantamiento topográfico** hecho con dos instrumentos:

| Instrumento | Qué se hace con él |
|---|---|
| **Nivel de ingeniero** | Altimetría: BM de cota conocida, vistas atrás / intermedias / adelante, puntos de cambio, distancias parciales y acumuladas, cotas, comprobación aritmética, error de cierre y **perfil longitudinal**. |
| **Teodolito** | Planimetría **por radiación**: estación ocupada, referencia de orientación, convenio angular, azimut, distancia (horizontal o inclinada reducida), proyecciones ΔE/ΔN y coordenadas. |

Con las coordenadas y las cotas de los **mismos puntos** (misma identidad dentro del proyecto) se genera la triangulación y las
**curvas de nivel**. El producto final es un **informe PDF** (con panel fotográfico y anexos) y un **plano DXF** compatible con AutoCAD.

Un proyecto nuevo comienza **vacío**. La aplicación no carga mediciones, fotografías ni documentos de ejemplo.

## Navegación

`Proyecto · Libreta de campo · Planimetría · Altimetría · Curvas de nivel · Plano CAD · Informe`
(dentro de *Informe*: **Informe**, **Panel fotográfico** y **Anexos**).

Las opciones de estación total, GNSS/RTK, poligonales y Bowditch ya no se ofrecen para capturar. Los datos históricos de proyectos
que las usaron **se conservan** en la base de datos y en el respaldo.

## Compilar y abrir en Android Studio

Requisitos: **Android Studio Ladybug o más reciente**, **JDK 17**, Android SDK con la plataforma **35** (y 36 si usa el emulador por defecto).
Gradle se descarga solo (wrapper 9.1.0, AGP 8.13.2, Kotlin 2.0.21).

1. *File ▸ Open…* y elija la carpeta que contiene `settings.gradle.kts` (la raíz de este proyecto).
2. Android Studio crea `local.properties` con `sdk.dir`. Si compila por consola, créelo a mano:
   `sdk.dir=C\:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk`.
3. En `gradle.properties` la línea `org.gradle.java.home=…` apunta al JDK 17 de esta máquina; **cámbiela o bórrela** si su JDK está en otra ruta.
4. *Build ▸ Make Project*, o por consola:

```
gradlew.bat :app:assembleDebug      # APK en app/build/outputs/apk/debug/app-debug.apk
gradlew.bat :calc:test              # pruebas unitarias (JVM)
gradlew.bat :data:connectedDebugAndroidTest   # migración v1→v2 y respaldo (requiere emulador o dispositivo)
```

## Módulos

| Módulo | Contenido |
|---|---|
| `:calc` | Cálculo puro en Kotlin/JVM: ángulos, nivelación, perfil, radiación con orientación, curvas de nivel (Delaunay), modelo del plano, escritor/verificador **DXF**, reglas de anexos, huellas de desactualización, modelo del informe. |
| `:data` | Room (base v2), migración v1→v2, copia previa a la migración, **respaldo/restauración** de proyecto en `.zip` con fotos y anexos. |
| `:io` | PDF (`android.graphics.pdf`), dibujo del plano y del perfil (el mismo código para pantalla y PDF), CSV, lectura de PDF anexos. |
| `:app` | Interfaz Jetpack Compose, repositorios y ViewModels (Hilt). |

## Datos y compatibilidad

* La base de datos pasa de la versión 1 a la 2 con una **migración que solo añade** columnas y tablas (no borra nada).
* Antes de migrar, la app copia el archivo de la base en `files/backups/pre-migracion-v1-<fecha>/`.
* No existe migración destructiva: si algo fallara, Room no borra los proyectos.
* El respaldo `.zip` incluye `data.json`, `manifest.json` (con la huella SHA-256 de cada archivo) y, dentro de `files/`, **el contenido** de las fotografías y de los anexos.

## Cálculos

* Nivelación: `HI = cota de referencia + VA`, `Cota = HI − VI (o − VD)`, comprobación `Σ VA − Σ VD = cota final − cota inicial`.
* Radiación: `ΔE = d·sen(A)`, `ΔN = d·cos(A)`, `E₂ = E₁ + ΔE`, `N₂ = N₁ + ΔN`; `A` normalizado a [0°, 360°). Con lectura horizontal: `A = Az_ref ± (Hz − Hz_ref)` según el convenio.
* Distancia inclinada: `d = Ds·sen(Z)` (cenital) o `d = Ds·cos(α)` (vertical).

## Versión

`versionName 2.1.0`, `versionCode 2100`, `applicationId com.toposcribe.pro`.
