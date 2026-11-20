# TopoScribePro

Aplicación en español para registrar la **libreta de campo** de un levantamiento topográfico con
**nivel de ingeniero** (altimetría y perfil longitudinal) y **teodolito** (planimetría por el método de
radiación), y producir el **informe de levantamiento topográfico** en PDF, el plano en **DXF** y las
tablas en **Excel/CSV**.

Es una aplicación web instalable (PWA): funciona en el navegador de la **computadora** y del
**celular**, también sin conexión una vez abierta. No necesita servidor de datos: todo se guarda en el
dispositivo.

> La versión anterior (app Android en Kotlin) se conserva sin cambios en [`android-legacy/`](android-legacy/README.md).
> Sus proyectos se pueden importar (ver «Migrar los datos de la app Android»).

## Flujo de trabajo

**Proyecto → Libreta de campo → Nivel de ingeniero → Teodolito → Plano y curvas de nivel → Informe**

| Sección | Qué se hace |
|---|---|
| Proyecto | Crear un proyecto **vacío** (nombre, lugar, fecha, responsables, objetivo, observaciones, sistema de coordenadas declarado). Abrir, exportar o importar proyectos. Indicar quién registra los datos. |
| Libreta de campo | **Puntos** (código único, descripción, tipo, instrumento, observaciones, fotografía). **Nivelación**: BM inicial (y BM final opcional), lecturas VA/VI/VD, puntos de cambio, distancias parciales o progresivas. **Radiación**: convenio angular, estación y orientación, lecturas horizontales y distancias. **Correcciones**: registro con valor original, valor nuevo, quién y cuándo. |
| Nivel de ingeniero | «Calcular»: altura de instrumento y cotas paso a paso, ΣVA − ΣVD frente a la diferencia de cotas, error de cierre (si hay BM final). «Ver perfil». |
| Teodolito | «Calcular»: azimut de cada visual (normalizado a 0°–360°), reducción de la distancia inclinada, ΔE, ΔN y coordenadas, con fórmulas sustituidas. |
| Plano y curvas de nivel | Vinculación de coordenadas (teodolito) y cotas (nivel) **solo por código idéntico**. Líneas definidas por el usuario. Triangulación de Delaunay y curvas por interpolación lineal con la equidistancia elegida. «Ver plano» con capas. «Exportar DXF» (verificado automáticamente). |
| Informe | Lista de comprobación, «Vista previa» del PDF real, «Generar informe», exportar Excel y CSV. |

### Fórmulas implementadas

* Altura de instrumento: `AI = cota del punto de referencia + VA`
* Cota: `Cota = AI − VI` o `Cota = AI − VD`; en un punto de cambio, `AI nueva = cota del PC + VA`
* Comprobación: `ΣVA − ΣVD = cota final − cota inicial`; error de cierre: `cota calculada − cota conocida`
* Azimut (horario): `Az = Az_ref + (L − L_ref)`; (antihorario): `Az = Az_ref − (L − L_ref)`; círculo orientado al norte: `Az = L`
* Distancia inclinada: `Dh = Di · sen(Z)` (cenital) o `Dh = Di · cos(α)` (vertical)
* `ΔE = Dh · sen(Az)`, `ΔN = Dh · cos(Az)`, `E = E_est + ΔE`, `N = N_est + ΔN`
* Curvas: triangulación de Delaunay (Bowyer–Watson) e interpolación lineal `t = (Zc − Za)/(Zb − Za)` sobre los lados

El código de los cálculos está en [`web/js/core/`](web/js/core) (sin dependencias, comentado en español).

## Cómo ejecutarla

### En la computadora

Necesita [Node.js](https://nodejs.org) 18 o superior (o Python 3).

```bash
npm start
```

Abra <http://localhost:8080>. Sin Node, con Python:

```bash
python3 -m http.server 8080 --directory web
```

### En el celular

**Opción recomendada — GitHub Pages (https, funciona sin conexión e instalable):**

1. En GitHub: *Settings › Pages › Build and deployment › Source: GitHub Actions*.
   (En repositorios privados, GitHub Pages requiere un plan de pago.)
2. Haga un *merge* de esta rama a `main`, o ejecute manualmente el flujo **Publicar en GitHub Pages**
   (pestaña *Actions*). El flujo ejecuta las pruebas y publica la carpeta `web/`.
3. Abra la dirección que indica GitHub en Chrome del celular y use *Menú › Instalar aplicación* (o
   *Agregar a pantalla principal*).

**Opción rápida — misma red Wi-Fi:** ejecute `npm start` en la computadora y abra en el celular la
dirección `http://192.168.x.x:8080` que muestra la consola. Sin https el modo sin conexión no se activa,
pero la app funciona.

### APK de Android

La carpeta [`android/`](android) es un contenedor Android mínimo: empaqueta la carpeta `web/` dentro
del APK y la muestra en un WebView (la misma app, sin conexión), con selector de archivos, cámara para
las fotografías y guardado de PDF, DXF, Excel, CSV y respaldos en *Descargas/TopoScribePro*.

* Paquete `com.toposcribe.web`, distinto de la app anterior (`com.toposcribe.pro`): instalarlo **no**
  reemplaza la app vieja ni borra sus datos, así que puede migrarlos después.
* Compilar (requiere el SDK de Android, por ejemplo el de Android Studio):

```bash
cd android && ./gradlew assembleDebug
```

  El APK queda en `android/app/build/outputs/apk/debug/app-debug.apk`. Cada push también lo compila
  en GitHub Actions (flujo **Compilar APK de Android**, artefacto `TopoScribePro-apk`).
* Es un APK de **depuración** firmado con la clave de pruebas de Android: sirve para instalarlo en
  el teléfono (activando «Instalar apps desconocidas»), no para publicarlo en Google Play.
* Si cambia algo en `web/`, vuelva a compilar el APK para incluirlo.
* Los datos del APK son independientes de los del navegador: use «Exportar proyecto (.json)» para
  pasarlos de uno a otro. Al desinstalar el APK se borran sus datos.

### Dónde quedan los datos

En el almacenamiento del navegador de **cada** dispositivo. Para pasar un proyecto del celular a la
computadora (o como respaldo) use **Exportar proyecto (.json)** y luego **Abrir archivo de proyecto**:
el archivo incluye lecturas originales, correcciones, resultados y fotografías.

## Migrar los datos de la app Android

Los proyectos de la versión Android están dentro del teléfono, en `toposcribe.db`.

1. Con el teléfono conectado y Android Studio abierto: *View › Tool Windows › Device Explorer* →
   `data/data/com.toposcribe.pro/databases/`. Guarde `toposcribe.db` **y** `toposcribe.db-wal` (si
   existe; contiene los cambios más recientes). Las fotos están en `data/data/com.toposcribe.pro/files/photos/`.
   Por consola: `adb exec-out run-as com.toposcribe.pro cat databases/toposcribe.db > toposcribe.db`
   (y lo mismo con `toposcribe.db-wal`).
2. En la app web: **Proyecto › Importar datos de la app Android**, elija los archivos y las fotos, y
   marque los proyectos a importar (el proyecto de demostración aparece desmarcado).

Qué pasa con cada tipo de dato:

* **Nivelación geométrica** → libreta de nivelación (las lecturas VD y VA de un punto de cambio se
  unen en una fila). La versión Android no registraba distancias: hay que completarlas para el perfil.
* **Observaciones de proyectos de teodolito** → libreta de radiación. La versión Android no guardaba la
  lectura horizontal en la referencia (`L_ref`): la app pide completarla antes de calcular; no la inventa.
* **Estación total, GNSS/RTK, poligonales, compensación de Bowditch, nivelación trigonométrica,
  resultados calculados por la versión anterior y su registro de ediciones** → **no se borran**: quedan
  en el «archivo heredado» del proyecto (visible en *Proyecto*, incluido al exportar el .json), fuera de
  los cálculos, el plano y el informe. Si unas observaciones registradas como «estación total» se
  midieron en realidad con teodolito, el importador permite incorporarlas a la radiación.
* No desinstale la app Android hasta comprobar la importación: al desinstalarla se borran sus datos.

## Pruebas

```bash
npm install          # herramientas de prueba (Playwright, pdf.js, dxf-parser, jsPDF, sql.js)
npm test             # pruebas unitarias: cálculos, exportaciones y migración (Node)
npm run test:e2e     # pruebas en el navegador (escritorio y celular emulado)
```

Las pruebas usan **casos didácticos identificados como PRUEBA**, en un almacenamiento de navegador
vacío y temporal: nunca se mezclan con proyectos reales. Incluyen:

* Nivelación del docente: BM 100.000, VA 1.250, VD 0.850 → AI 101.250, cota 100.400 (celular y computadora).
* Radiación del docente: E 500.000, N 1000.000, Dh 10.000, Az 90° → E 510.000, N 1000.000 (celular y computadora).
* Cambio de estación del nivel, distancias parciales y acumuladas, comprobación y error de cierre.
* Orientación por coordenadas de la referencia, sentido horario/antihorario, normalización de azimuts,
  reducción de distancia inclinada (cenital y vertical).
* Datos incompletos, códigos duplicados, formato angular incorrecto y puntos radiados sin cota: no
  producen coordenadas ni curvas, y se explica por qué.
* Triangulación e interpolación de curvas sobre superficies conocidas.
* Flujo completo: guardar, recargar y reabrir con fotografía; exportar e importar el proyecto en otro
  navegador; PDF y DXF descargados, reabiertos y comparados con los valores en pantalla; Excel.
* Migración de una base Room v1 (creada con `tests/fixtures/make_android_fixture.py`) con cambios solo en el archivo WAL.

Después de modificar archivos de `web/`, regenere la lista de archivos para el modo sin conexión:

```bash
npm run build:sw
```

## Estructura

```
web/                 aplicación (se publica tal cual, sin compilación)
  js/core/           cálculos: números, ángulos, nivelación, radiación, curvas, escalas, modelo, plano
  js/export/         PDF (jsPDF), DXF R2000 (+ verificación), Excel/CSV, archivo de proyecto
  js/import/         migración desde la base de la app Android
  js/ui/             interfaz (Preact + htm) y vistas de cada sección
  vendor/            bibliotecas y fuentes con sus licencias
tests/               pruebas unitarias, de navegador y datos de prueba
tools/               servidor local, generador del service worker y de la plantilla DXF
android/             contenedor Android (APK) que empaqueta web/
android-legacy/      versión Android anterior (sin cambios)
```

## Limitaciones y pendientes

* El DXF se verificó con **ezdxf** (lectura y auditoría sin errores, unidades en metros, AC1015) y con
  **dxf-parser**, pero **no se abrió en AutoCAD** (no disponible en el entorno de desarrollo).
* La interfaz se probó en Chromium de escritorio y con emulación de Pixel 7; **no en un teléfono físico
  ni en Safari/iPhone**. El APK se compiló y se verificó su contenido y firma, y la app se probó en las
  condiciones del WebView (mismo origen y tipos MIME, puente simulado), pero **no se instaló en un
  teléfono ni en un emulador** (el entorno de desarrollo no tiene aceleración para emularlo): la
  cámara, el selector de archivos y el guardado en Descargas no están probados en un dispositivo.
* La migración Android se probó con una base **sintética** que reproduce el esquema Room v1, no con la
  base real de un teléfono. La app Android no se pudo compilar en este entorno.
* No se calcula la **tolerancia** del error de cierre de la nivelación ni se compensa: el docente no
  la indicó (se muestra el error y se deja constancia en el informe).
* Una sola libreta de nivelación por proyecto. No incluye taquimetría (distancia por estadía).
* Los datos viven en cada navegador: no hay sincronización automática entre dispositivos (use la
  exportación .json). El navegador podría liberar el espacio si falta memoria: exporte respaldos.
* El PDF usa un subconjunto de la fuente DejaVu Sans (latín, griego y símbolos técnicos); caracteres
  fuera de ese conjunto (p. ej., emojis) no aparecerán en el PDF.
