plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.conveyorg"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.conveyorg"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Чистая 64-битная среда: оптимизация под Snapdragon 8 Gen 2 и эмуляторы x86_64
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Отключаем ресурсоемкое сжатие PNG при отладочной сборке
            isCrunchPngs = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = false
        resValues = false
        shaders = false
    }

    packaging {
        resources {
            // Исключение дублирующихся метаданных Ktor CIO и Coroutines
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "META-INF/versions/**"
            )
        }
    }
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
        )
    }
}

dependencies {
    // --- Android Core & Lifecycle ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // --- Jetpack Compose UI ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // --- Асинхронность и потоки (Coroutines) ---
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // --- Высокоскоростной сетевой клиент Ktor (CIO движок) ---
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // --- Сериализация JSON ---
    implementation(libs.kotlinx.serialization.json)

    // --- Аппаратная безопасность Samsung Knox Vault ---
    implementation(libs.androidx.security.crypto)
}

// Ленивое отключение проверки метаданных AAR без обращения к внутренним пакетам AGP
tasks.configureEach {
    if (name.contains("AarMetadata")) {
        enabled = false
    }
}