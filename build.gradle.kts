import org.gradle.script.lang.kotlin.*
import org.jetbrains.kotlin.gradle.dsl.Coroutines
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    application
    idea

    val kotlinVersion = "1.1.61"
    kotlin("jvm") version kotlinVersion
    kotlin("kapt") version kotlinVersion
}

version = "1.0-SNAPSHOT"

repositories {
    jcenter()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven("https://dl.bintray.com/kotlin/ktor")
}

dependencies {
    val log4jVersion = "2.8.2"
    val ktorVersion = "0.9.0"

    compile("org.jetbrains.kotlin:kotlin-stdlib-jre8")

    compile("io.ktor:ktor-server-netty:${ktorVersion}")
    compile("io.ktor:ktor-client-cio:${ktorVersion}")
    compile("io.ktor:ktor-jackson:${ktorVersion}")
    compile("io.ktor:ktor-html-builder:${ktorVersion}")
    compile("io.ktor:ktor-locations:${ktorVersion}") {
        exclude("io.ktor", "ktor-auth")
    }

    compile("org.apache.logging.log4j:log4j-slf4j-impl:${log4jVersion}")
    compile("org.apache.logging.log4j:log4j-core:${log4jVersion}")

    testCompile("junit:junit:4.12")
    testCompile("io.ktor:ktor-server-tests:${ktorVersion}") {
        exclude("ch.qos.logback", "logback-classic")
    }
}

application {
    mainClassName = "AppKt"
}

with(tasks["run"] as JavaExec) {
    maxHeapSize = "10m"
    args = listOf("-config=universal_exporter.conf")
}

kotlin {
    experimental.coroutines = Coroutines.ENABLE
}

tasks.withType<KotlinCompile>().all {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8

    // Add kapt directory to sources
    sourceSets["main"].java.srcDir(File("$buildDir/generated/source/kapt/main/"))
}

tasks.withType<Jar>() {
    manifest {
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] = version
        attributes["Main-Class"] = application.mainClassName
        attributes["Class-Path"] = configurations.compile.map { it.name }
                .joinToString(" ")
    }
}

createTask("copyDependencies", Copy::class) {
    into("$buildDir/libs")
    from(configurations.compile)
}

idea {
    module {
        // Tell idea to mark the folder as generated sources
        generatedSourceDirs.add(File("$buildDir/generated/source/kapt/main/"))
    }
}
