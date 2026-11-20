// Contenedor Android de TopoScribePro: empaqueta la app web (carpeta ../web) dentro del APK y la
// muestra en un WebView. No contiene lógica de cálculo propia: es la misma app que en el navegador.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.toposcribe.web"
    compileSdk = 35

    defaultConfig {
        // Identificador distinto de la app Android anterior (com.toposcribe.pro): instalar este APK
        // NO reemplaza ni borra la app vieja ni sus datos.
        applicationId = "com.toposcribe.web"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // La app web se empaqueta tal cual como recursos del APK (assets).
            assets.srcDirs("../../web")
        }
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core:1.13.1")
}
