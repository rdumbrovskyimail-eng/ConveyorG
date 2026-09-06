# ====================================================================
# ClientG Stack — Правила оптимизатора R8 Full Mode
# Целевая платформа: Android 16 (API 36) | Snapdragon 8 Gen 2 for Galaxy
# ====================================================================

# --------------------------------------------------------------------
# 1. Метаданные JVM, деобфускация и читаемость стек-трейсов
# --------------------------------------------------------------------
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses, EnclosingMethod
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# --------------------------------------------------------------------
# 2. Аппаратная оптимизация R8 Full Mode (Snapdragon 8 Gen 2)
# --------------------------------------------------------------------
-allowaccessmodification
-repackageclasses ''

# --------------------------------------------------------------------
# 3. Ktor 3.x Client (Движок CIO, сетевые сокеты и буферы)
# --------------------------------------------------------------------
-dontwarn io.ktor.**

# Защита атомарных апдейтеров (AtomicReferenceFieldUpdater), используемых сокетным движком Ktor CIO
-keepclassmembers class io.ktor.utils.io.** {
    volatile <fields>;
}
-keepclassmembers class io.ktor.network.** {
    volatile <fields>;
}
-keepclassmembers class io.ktor.client.engine.cio.** {
    volatile <fields>;
}

# Сохранение фабрик, плагинов и контейнеров движка CIO
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }
-keep class io.ktor.client.plugins.** { *; }

# --------------------------------------------------------------------
# 4. Kotlinx Coroutines (Диспетчеры, фабрики ServiceLoader)
# --------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**

# Фабрики диспетчеров для 120 Гц рендеринга (загружаются через ServiceLoader)
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory {
    public <init>();
}
-keep class kotlinx.coroutines.internal.MainDispatcherFactory {
    public <init>();
}
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler {
    public <init>();
}

-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --------------------------------------------------------------------
# 5. Kotlinx Serialization: DTO Сети, Агентов и Рабочей Области
# --------------------------------------------------------------------
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**

# Защита полей с аннотацией @SerialName от переименования
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Сохранение Companion объектов и сгенерированных сериализаторов $serializer
-keepclassmembers class * {
    public static final *** Companion;
}
-keepclasseswithmembers class * {
    public static final *** Companion;
}
-keepnames class * implements kotlinx.serialization.KSerializer { *; }
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    public static final *** INSTANCE;
}

# Прямая защита сетевых моделей, DTO агентов и файлового хранилища
-keep class com.conveyorg.network.** { *; }
-keepclassmembers class com.conveyorg.network.** {
    *** Companion;
    *** $serializer;
    <fields>;
}

-keep class com.conveyorg.agent.** { *; }
-keepclassmembers class com.conveyorg.agent.** {
    *** Companion;
    *** $serializer;
    <fields>;
}

-keep class com.conveyorg.data.** { *; }
-keepclassmembers class com.conveyorg.data.** {
    *** Companion;
    *** $serializer;
    <fields>;
}

# --------------------------------------------------------------------
# 6. AndroidX Security Crypto & Google Tink (Samsung Knox Vault)
# --------------------------------------------------------------------
-dontwarn androidx.security.crypto.**
-dontwarn com.google.crypto.tink.**

-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keep class * extends com.google.crypto.tink.KeyManager { *; }
-keep class * extends com.google.crypto.tink.KeyTypeManager { *; }
-keepclassmembers class * extends java.security.Provider { *; }

# --------------------------------------------------------------------
# 7. Jetpack Compose (Классовые правила стабильности и состояние)
# --------------------------------------------------------------------
-dontwarn androidx.compose.**

-keepclassmembers class * extends androidx.compose.runtime.State { *; }
-keepclassmembers class * extends androidx.compose.runtime.MutableState { *; }

-keep @androidx.compose.runtime.Stable class * { *; }
-keep @androidx.compose.runtime.Immutable class * { *; }
-keepclassmembers @androidx.compose.runtime.Stable class * { *; }
-keepclassmembers @androidx.compose.runtime.Immutable class * { *; }

# --------------------------------------------------------------------
# 8. Android Architecture Components (ViewModel & Activity)
# --------------------------------------------------------------------
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    public <init>(android.app.Application);
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>();
}

-keep class * implements android.window.OnBackInvokedCallback { *; }