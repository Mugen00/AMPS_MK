plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "dev.amps.backend"
version = "1.1.0"

dependencies {
    // Ktor 3 (Netty)
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-netty:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("io.ktor:ktor-server-auth:3.0.3")
    implementation("io.ktor:ktor-server-auth-jwt:3.0.3")
    implementation("io.ktor:ktor-server-cors:3.0.3")
    implementation("io.ktor:ktor-server-status-pages:3.0.3")

    // Exposed ORM. Час — epoch-мілісекунди в long-колонках, тому
    // exposed-java-time не потрібен.
    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")

    // База
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")

    // Паролі
    implementation("org.mindrot:jbcrypt:0.4")
    // JWT: com.auth0:java-jwt приходить транзитивно з ktor-server-auth-jwt

    // Email (Resend) і SMS (Twilio) — через REST, без важких SDK
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Логи
    implementation("ch.qos.logback:logback-classic:1.5.16")
}

application {
    mainClass.set("dev.amps.backend.ApplicationKt")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Fat JAR: amps-backend.jar — саме це ім'я чекає Dockerfile.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("amps-backend")
    archiveVersion.set("")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "dev.amps.backend.ApplicationKt")
    }
}
