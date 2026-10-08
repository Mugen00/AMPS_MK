import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
/**
 * Модель аниме-тегера качается при сборке, а не хранится в git.
 *
 * Файл весит 166,8 МБ. GitHub отклоняет в репозитории всё больше 100 МБ,
 * а квота Git LFS у аккаунта исчерпана, поэтому оба способа держать модель
 * в репозитории закрыты. Оригинал лежит на Hugging Face
 * (`SmilingWolf/wd-swinv2-tagger-v3`, Apache-2.0) — там он и должен быть.
 *
 * Сверка обязательна: молча собранный APK с битой моделью уехал бы
 * пользователю, и виноват был бы R8, а не сеть.
 */
val animeTagger = layout.projectDirectory.file("src/main/assets/models/anime_tagger_int8.onnx")
val animeTaggerSha256 = "d91e54a7a0097014aeed5c41410747531e262d25ee5c2f6e946b7647f0b5878c"
val animeTaggerSource = "https://huggingface.co/SmilingWolf/wd-swinv2-tagger-v3/resolve/main/model.onnx"

val fetchAnimeTagger by tasks.registering {
    description = "Скачивает модель аниме-тегера и сверяет SHA256."
    group = "setup"

    val target = animeTagger
    val expected = animeTaggerSha256
    val source = animeTaggerSource

    // Модель меняется редко, поэтому при верном файле сеть не трогается:
    // иначе каждая сборка ждала бы 167 МБ.
    onlyIf { !target.asFile.exists() || sha256(target.asFile) != expected }

    doLast {
        target.asFile.parentFile.mkdirs()
        val partial = target.asFile.resolveSibling("${target.asFile.name}.part")
        partial.delete()
        logger.lifecycle("Скачиваю модель аниме-тегера (166,8 МБ) с Hugging Face…")

        val connection = URI(source).toURL().openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000
        connection.inputStream.use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }

        val actual = sha256(partial)
        if (actual != expected) {
            partial.delete()
            throw GradleException(
                "Модель скачалась, но не сошлась по SHA256.\n" +
                    "  ожидалось: $expected\n  получено:  $actual\n" +
                    "Файл не используется и удалён: это либо обрыв загрузки, " +
                    "либо подмена на стороне сервера.",
            )
        }
        if (!partial.renameTo(target.asFile)) {
            throw GradleException("не удалось сохранить модель в ${target.asFile}")
        }
        logger.lifecycle("Модель готова, SHA256 сошёлся.")
    }
}

android {
    namespace = "dev.amps.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.amps.app"
        minSdk = 24
        targetSdk = 35
        // 1.0.6b: тегер вынесен в отдельный процесс. Падение внутри ONNX Runtime —
// нативный SIGSEGV, мимо Java-обработчиков; в общем процессе оно уносило
// всё приложение. Теперь Android убьёт только процесс `:tagger`.
// Также largeHeap, проверка свободной памяти до загрузки модели и
// Throwable вместо Exception в разборе кадра.
        versionCode = 16
        versionName = "1.1.2"
        resourceConfigurations += listOf("ru", "en")

        ndk {
            // Только настоящие телефоны. Без фильтра в APK попадают ещё и x86 с
            // x86_64, а они нужны исключительно эмуляторам. Приложение на
            // эмуляторе не запускают, а APK раздаётся напрямую одним файлом,
            // поэтому эти две лишние архитектуры — чистый вес.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val storePath = rootProject.file("keystore/amps.jks")
            if (storePath.exists()) {
                storeFile = storePath
                storePassword = (rootProject.file("keystore/keystore.properties").takeIf { it.exists() }
                    ?.readLines()
                    ?.firstOrNull { it.startsWith("storePassword=") }
                    ?.substringAfter("=")) ?: ""
                keyAlias = "amps"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val storePath = rootProject.file("keystore/amps.jks")
            signingConfig = if (storePath.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // lint-gradle is a separate download; the release APK must build offline.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // 1.0.6: аниме-тегер вместо ML Kit. ML Kit отдавал общие словари
    // («человек», «волосы») и занимал 20 МБ APK — 81% размера. Тегер
    // знает имена персонажей и весит больше, но работает.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}

tasks.named("preBuild").configure { dependsOn(fetchAnimeTagger) }

/** SHA256 файла в нижнем регистре; для отсутствующего файла — пустая строка. */
fun sha256(file: File): String {
    if (!file.exists()) return ""
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
