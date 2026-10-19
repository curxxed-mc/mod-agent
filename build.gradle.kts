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

val diagnostic by sourceSets.creating

val diagnosticJar by tasks.registering(Jar::class) {
    dependsOn(tasks.named(diagnostic.classesTaskName))
    from(diagnostic.output)
    archiveFileName.set("mod-agent-diagnostic.jar")
    manifest {
        attributes("Premain-Class" to "net.curxxed.dev.agent.diagnostic.DiagnosticAgent")
    }
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
}

dependencies {
    // ASM — needed at JVM startup so it HAS to be shaded in, not a provided dep
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
    compileOnly("org.spongepowered:mixin:0.8.7")
}

tasks.shadowJar {
    archiveClassifier.set("")
    //archiveFileName.set("lunar-agent-${project.version}.jar")
    archiveFileName.set(providers.gradleProperty("agentJarName").orElse("lunar-agent.jar"))

    // relocate so we don't collide with whatever ASM version lunar/ichor ships with
    relocate("org.objectweb.asm", "net.curxxed.dev.agent.asm")

    manifest {
        attributes(
            "Premain-Class"           to "net.curxxed.dev.agent.AgentBootstrap",
            "Agent-Class"             to "net.curxxed.dev.agent.AgentBootstrap",
            "Main-Class"              to "net.curxxed.dev.agent.AgentLauncher",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes"    to "true"
        )
    }
}

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
