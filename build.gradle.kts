plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.0"
    id("org.jetbrains.intellij") version "1.16.1"
}

group = "com.fina"
version = "1.0.0"

repositories {
    maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
    maven { url = uri("https://maven.aliyun.com/nexus/content/groups/public/") }
    maven { url = uri("https://maven.aliyun.com/nexus/content/repositories/jcenter") }
    maven { url = uri("https://maven.aliyun.com/repository/public/") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    maven { url = uri("https://plugins.jetbrains.com/maven") }
    maven { url = uri("https://www.jetbrains.com/intellij-repository/releases") }
    mavenCentral()
    gradlePluginPortal()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

intellij {
    version.set("2024.3")
    type.set("IC") // Target IDE Platform
    downloadSources.set(true)
    updateSinceUntilBuild.set(false)
    plugins.set(listOf(
        "com.intellij.java",
        "org.intellij.plugins.markdown"
    ))
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.google.code.gson:gson:2.8.9")
    implementation("com.alibaba:fastjson:1.2.83")
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("231")
        untilBuild.set("243.*")
    }
} 