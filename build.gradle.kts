import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
}

group = "net.craftavia"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Rewriting the hashed seed packet needs server internals (Mojang-mapped), hence the dev bundle.
    paperweight.paperDevBundle("26.2.build.123-stable")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

// Paper 1.20.5+ runs Mojang-mapped at runtime; the jar is not remapped.
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
    }

    compileTestJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    processResources {
        inputs.property("version", project.version)
        filteringCharset = "UTF-8"
        filesMatching("paper-plugin.yml") {
            expand("version" to project.version)
        }
    }

    test {
        useJUnitPlatform()
    }

    jar {
        archiveBaseName.set("SeedVeil")
        from("LICENSE")
    }
}
