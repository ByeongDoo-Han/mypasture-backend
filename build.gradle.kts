import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.0.21"
    kotlin("kapt") version "2.4.20"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "mypasture"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "2.0.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("com.google.firebase:firebase-admin:9.4.3")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("org.postgresql:postgresql")
    developmentOnly("com.h2database:h2")
    testRuntimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencies {
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<Test>("test") {
    // 개발자별 .env.local이 Firebase, OpenAI 등 통합 테스트 설정을 오염시키지 않게 격리합니다.
    systemProperty("spring.profiles.active", "test")
    systemProperty("app.auth.email-verification.required", "false")
    // 정책 외 기존 테스트 fixture는 동의 UI 계약을 다루지 않으므로 정책 통합 테스트에서만 다시 활성화합니다.
    systemProperty("app.policy.enforcement-enabled", "false")
    useJUnitPlatform {
        excludeTags("openai-live")
    }
}

/** 실제 과금 API는 명시적으로 실행할 때만 호출해 일반 테스트와 CI의 비용 발생을 막습니다. */
tasks.register<Test>("openAiSmokeTest") {
    group = "verification"
    description = "Runs the opt-in, minimal-token OpenAI API smoke test."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("openai-live")
    }
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
