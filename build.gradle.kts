plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "net.curxxed.dev"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
    compileOnly("org.spongepowered:mixin:0.8.7")
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set(providers.gradleProperty("agentJarName").orElse("Mod-Agent.jar"))

    // Relocate so we don't collide with other ASM versions
    relocate("org.objectweb.asm", "net.curxxed.dev.agent.asm")

    manifest {
        attributes(
            "Premain-Class"           to "net.curxxed.dev.agent.AgentBootstrap",
            "Agent-Class"             to "net.curxxed.dev.agent.AgentBootstrap",
            "Main-Class"              to "net.curxxed.dev.agent.AgentLauncher",
            "Implementation-Version"  to project.version,
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes"    to "true"
        )
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
