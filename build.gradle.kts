import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    `maven-publish`
    idea
    id("net.neoforged.moddev") version "2.0.96"
    kotlin("jvm")
}

version = project.property("mod_version") as String
group = project.property("mod_group_id") as String

repositories {
    mavenLocal()

    flatDir {
        dirs("../lib")
    }

    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
        content {
            includeGroup("thedarkcolour")
        }
    }

    maven {
        name = "ithundxr's Maven Snapshots"
        url = uri("https://maven.ithundxr.dev/snapshots")
    }
}

base {
    archivesName.set(project.property("mod_id") as String)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

neoForge {
    // 指定使用的 NeoForge 版本
    version = project.property("neo_version") as String

    parchment {
        mappingsVersion = project.property("parchment_mappings_version") as String
        minecraftVersion = project.property("parchment_minecraft_version") as String
    }

    // This line is optional. Access Transformers are automatically detected
    // accessTransformers.add('src/main/resources/META-INF/accesstransformer.cfg')

    runs {
        create("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", project.property("mod_id") as String)
        }

        create("server") {
            server()
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", project.property("mod_id") as String)
        }

        create("gameTestServer") {
            type.set("gameTestServer")
            systemProperty("neoforge.enabledGameTestNamespaces", project.property("mod_id") as String)
        }

        create("data") {
            data()
            programArguments.addAll(
                "--mod", project.property("mod_id") as String,
                "--all",
                "--output", file("src/generated/resources").absolutePath,
                "--existing", file("src/main/resources").absolutePath
            )
        }

        // 应用于所有运行配置
        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel.set(org.slf4j.event.Level.DEBUG)
        }
    }

    // 模组源集绑定
    mods {
        register(project.property("mod_id") as String) {
            sourceSet(sourceSets["main"])
        }
    }
}

// 包含数据生成器生成的资源
sourceSets["main"].resources {
    srcDir("src/generated/resources")
}

dependencies {
    implementation("thedarkcolour:kotlinforforge-neoforge:${project.property("kotlin4forge_version")}")

    compileOnly("net.luckperms:api:5.4")

    with(project.property("registrate_version") as String) {
        implementation(group = "com.tterrag.registrate", name = "Registrate", version = this)
        jarJar(group = "com.tterrag.registrate", name = "Registrate", version = this)
    }

    api(group = "com.github.wintersteve25.tau", name = "tau", version = "2.0.4")
    jarJar(group = "com.github.wintersteve25.tau", name = "tau", version = "[2.0.4, 3)")
}

// 模组元数据生成任务
val generateModMetadata by tasks.register<Copy>("generateModMetadata") {
    val replaceProperties = mapOf(
        "minecraft_version" to project.property("minecraft_version"),
        "minecraft_version_range" to project.property("minecraft_version_range"),
        "neo_version" to project.property("neo_version"),
        "neo_version_range" to project.property("neo_version_range"),
        "loader_version_range" to project.property("loader_version_range"),
        "mod_id" to project.property("mod_id"),
        "mod_name" to project.property("mod_name"),
        "mod_license" to project.property("mod_license"),
        "mod_version" to project.property("mod_version"),
        "mod_authors" to project.property("mod_authors"),
        "mod_description" to project.property("mod_description")
    )

    inputs.properties(replaceProperties)
    expand(replaceProperties)

    from("src/main/templates")
    into("build/generated/sources/modMetadata")
}

// 将生成的元数据添加到资源目录
sourceSets["main"].resources.srcDir(generateModMetadata)

neoForge.ideSyncTask(generateModMetadata)

// 发布配置
publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            url = uri("${project.projectDir}/repo")
        }
    }
}

// IDEA 配置
idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}