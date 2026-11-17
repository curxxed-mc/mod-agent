import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.3.21"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.curxxed"
version = "1.0.0"

// Gradle itself needs JDK 17+, but the jar runs on Java 8. Compile (and test) with a real JDK 8
// so newer JDK methods can't slip in. --release 8 isn't an option: it hides sun.misc.Unsafe.
// Gradle uses an installed JDK 8 or downloads one (see settings.gradle.kts).
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

// Kotlin is only used for the setup window and the jar's entry point (src/main/kotlin).
// Everything that runs inside the game stays Java and never touches the Kotlin stdlib.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
    compileOnly("org.spongepowered:mixin:0.8.7")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set(providers.gradleProperty("agentJarName").orElse("Mod-Agent-${project.version}.jar"))

    // Relocate so we don't collide with other ASM versions
    relocate("org.objectweb.asm", "dev.curxxed.agent.asm")
    relocate("kotlin", "dev.curxxed.agent.kotlin")
    relocate("org.jetbrains.annotations", "dev.curxxed.agent.annotations")
    relocate("org.intellij.lang.annotations", "dev.curxxed.agent.annotations.intellij")
    exclude("META-INF/*.kotlin_module", "META-INF/versions/**/module-info.class", "module-info.class", "META-INF/maven/org.jetbrains/**")

    // Drops the parts of the Kotlin stdlib the setup window doesn't use. ASM stays complete as before.
    minimize {
        exclude(dependency("org.ow2.asm:.*:.*"))
    }

    manifest {
        attributes(
            "Premain-Class"           to "dev.curxxed.agent.AgentBootstrap",
            "Agent-Class"             to "dev.curxxed.agent.AgentBootstrap",
            "Main-Class"              to "dev.curxxed.agent.AgentLauncher",
            "Implementation-Version"  to project.version,
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes"    to "true"
        )
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
