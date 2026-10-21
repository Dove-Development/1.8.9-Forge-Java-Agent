plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

val asmVersion = "9.8"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // ASM is shaded and relocated into the agent jar so it never collides with
    // the ASM that LaunchWrapper/Forge/mods put on the classpath.
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-tree:$asmVersion")

    // Pack200 was removed in Java 14; Forge needs it to unpack its binary patches.
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The hooks write static final fields through the JDK's internal Unsafe. At runtime the
// agent opens these packages itself (ModuleOpener); compile and test need them spelled out.
val jdkInternals = listOf(
    "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // No options.release: it forbids --add-exports. The JDK 25 toolchain already targets 25.
    options.compilerArgs.addAll(jdkInternals)
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Premain-Class" to "dev.dove.agent.DoveAgent",
            "Agent-Class" to "dev.dove.agent.DoveAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Implementation-Title" to "Dove Agent",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.jar {
    archiveClassifier = "slim"
}

tasks.shadowJar {
    // The launcher looks for exactly this name.
    archiveFileName = "dove-agent.jar"
    // Relocate everything shaded: Minecraft ships its own (older) commons-compress, -io, -lang3, -codec.
    relocate("org.objectweb.asm", "dev.dove.agent.shadow.asm")
    relocate("org.apache.commons", "dev.dove.agent.shadow.commons")
    minimize()
    exclude("module-info.class", "META-INF/versions/**/module-info.class", "META-INF/maven/**")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    )
}
