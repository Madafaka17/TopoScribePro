# TopoScribePro — versión Android anterior (archivada)

Este es el proyecto Android (Kotlin, Jetpack Compose, Room) tal como estaba antes de la
simplificación. **No se modificó**: se conserva como respaldo del código y para poder extraer los
datos guardados en el teléfono.

Incluye funciones que quedaron **fuera del alcance** indicado por el docente (estación total, GNSS/RTK,
poligonales y compensación de Bowditch, nivelación trigonométrica). La aplicación vigente es la
versión web de la carpeta [`../web`](../web), que funciona en computadora y celular.

* Para pasar los proyectos de esta app a la versión web, siga la sección «Migrar los datos de la app
  Android» del [README principal](../README.md).
* Para compilarla: abra esta carpeta (`android-legacy/`) en Android Studio. Revise `gradle.properties`,
  que fija una ruta de JDK de Windows (`org.gradle.java.home`).
