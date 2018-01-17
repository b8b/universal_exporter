import org.gradle.kotlin.dsl.version
import org.gradle.script.lang.kotlin.*
import org.jetbrains.kotlin.gradle.dsl.Coroutines
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.script.tryConstructClassFromStringArgs

plugins {
    java
    application
    idea

    val kotlinVersion = "1.2.20"
    kotlin("jvm") version kotlinVersion
    kotlin("kapt") version kotlinVersion
}

version = project.properties["VERSION"]!!

repositories {
    jcenter()
    maven("https://dl.bintray.com/kotlin/kotlinx")
}

dependencies {
    val logbackVersion = "1.2.3"
    val vertxVersion = "3.5.0"
    val jacksonVersion = "2.9.2"

    compile("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    compile("org.jetbrains.kotlin:kotlin-reflect")

    compile("org.jetbrains.kotlinx:kotlinx-coroutines-core:0.21")
    compile("org.jetbrains.kotlinx:kotlinx-coroutines-io:0.21")
    compile("org.jetbrains.kotlinx:kotlinx-html-jvm:0.6.8")

    compile("com.typesafe:config:1.2.1")

    compile("com.fasterxml:aalto-xml:1.0.0")
    compile("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

    compile("io.prometheus:simpleclient_hotspot:0.1.0")

    compile("io.vertx:vertx-lang-kotlin:$vertxVersion") {
        exclude("org.jetbrains.kotlin", "kotlin-stdlib-jre8")
        exclude("org.jetbrains.kotlin", "kotlin-stdlib-jre7")
    }
    compile("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion") {
        exclude("org.jetbrains.kotlin", "kotlin-stdlib-jre8")
        exclude("org.jetbrains.kotlin", "kotlin-stdlib-jre7")
    }
    compile("io.vertx:vertx-web:$vertxVersion")

    compile("ch.qos.logback:logback-classic:$logbackVersion")

    testCompile("junit:junit:4.12")
    testCompile("io.vertx:vertx-unit:$vertxVersion")
}

application {
    mainClassName = "exporter.VerticleKt"
}

with(tasks["run"] as JavaExec) {
    maxHeapSize = "20m"
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

idea {
    module {
        // Tell idea to mark the folder as generated sources
        generatedSourceDirs.add(File("$buildDir/generated/source/kapt/main/"))
    }
}

tasks.withType<Jar> {
    manifest {
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] = version
        attributes["Main-Class"] = application.mainClassName
        attributes["Class-Path"] = configurations.compile.joinToString(" ") { it.name }
    }
}

createTask("copyDependencies", Copy::class) {
    into("$buildDir/libs")
    from(configurations.compile)
}

distributions["main"].contents {
    from("README.md", "gradle.properties")
    from("src/main/dist")
}
