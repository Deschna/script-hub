import java.util.Locale

plugins {
    java
    checkstyle
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.deschna"
version = "0.0.1-SNAPSHOT"
description = "ScriptHub API service"

val graalVmVersion = "25.0.3"
val springDocVersion = "2.8.17"
val operatingSystem = System.getProperty("os.name").lowercase(Locale.ROOT)
val architecture = System.getProperty("os.arch").lowercase(Locale.ROOT)
val graalVmPlatform = when {
    operatingSystem.contains("windows") && architecture in setOf("amd64", "x86_64") ->
        "windows-amd64"
    operatingSystem.contains("linux") && architecture in setOf("amd64", "x86_64") ->
        "linux-amd64"
    operatingSystem.contains("linux") && architecture in setOf("aarch64", "arm64") ->
        "linux-aarch64"
    operatingSystem.contains("mac") && architecture in setOf("aarch64", "arm64") ->
        "darwin-aarch64"
    else -> error("Unsupported GraalVM isolate platform: $operatingSystem-$architecture")
}

// Spring Boot 3.5.14 manages commons-lang3 3.17.0, which is affected by CVE-2025-48924.
extra["commons-lang3.version"] = "3.20.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.graalvm.polyglot:polyglot:$graalVmVersion")
    implementation("org.graalvm.polyglot:js-isolate-$graalVmPlatform:$graalVmVersion")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springDocVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

checkstyle {
    toolVersion = "13.5.0"
    configDirectory.set(layout.projectDirectory.dir("config/checkstyle"))
}
