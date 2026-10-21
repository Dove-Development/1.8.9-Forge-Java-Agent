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
    // the (much older) ASM 5 that LaunchWrapper/Forge put on the classpath.
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-tree:$asmVersion")
    implementation("org.ow2.asm:asm-commons:$asmVersion")
    implementation("org.ow2.asm:asm-util:$asmVersion")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
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
    archiveClassifier = ""
    relocate("org.objectweb.asm", "dev.dove.agent.shadow.asm")
    exclude("module-info.class", "META-INF/versions/**/module-info.class")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
