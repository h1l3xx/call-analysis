plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
    id("io.ktor.plugin") version "2.3.12"
    application
}

group = "com.malikov"
version = "1.0.0"

application {
    mainClass.set("com.malikov.ApplicationKt")
    applicationDefaultJvmArgs = listOf(
        "-Dio.ktor.development=${System.getenv("ENVIRONMENT") != "production"}"
    )
}

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"
val exposedVersion = "0.52.0"
val kotlinVersion = "2.0.0"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-request-validation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-rate-limit:$ktorVersion")

    // Ktor client (для вызова Python pipeline)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // Database — Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")

    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")

    // PostgreSQL driver
    implementation("org.postgresql:postgresql:42.7.3")

    // Connection pool
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Flyway migrations
    implementation("org.flywaydb:flyway-core:10.15.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.15.0")

    // Redis
    implementation("io.lettuce:lettuce-core:6.3.2.RELEASE")

    // Password hashing
    implementation("at.favre.lib:bcrypt:0.10.2")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Config
    implementation("com.typesafe:config:1.4.3")

    // Tests
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.testcontainers:postgresql:1.19.8")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

tasks.test {
    useJUnitPlatform()
}

ktor {
    fatJar {
        archiveFileName.set("malikov-backend.jar")
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
}