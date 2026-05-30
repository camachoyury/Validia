plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // TODO Paso 5: Agregar el plugin de serialización cuando implementes InventoryItem
    // alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.camachoyury.validia"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.camachoyury.validia"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // TODO Paso 5: Descomentar los abiFilters cuando agregues la dependencia LiteRT-LM.
        // LiteRT requiere ABIs de 64 bits (arm64-v8a para dispositivos reales, x86_64 para emulador).
        // ndk {
        //     abiFilters += listOf("arm64-v8a", "x86_64")
        // }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // TODO Paso 5: Descomentar cuando agregues el modelo .litertlm a assets/.
    // Evita doble compresión del modelo y habilita acceso por memory-mapping.
    // androidResources {
    //     noCompress += "litertlm"
    // }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // TODO Paso 5: Agregar estas dos dependencias cuando implementes el motor de inferencia:
    // implementation(libs.kotlinx.serialization.json)
    // implementation(libs.litert.lm.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
