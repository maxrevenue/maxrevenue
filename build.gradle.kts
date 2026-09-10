// Roat PKz Java agent build.
//
// Replaces the handwritten file list in build.bat with convention-based
// compilation. Two artifacts match the paths the launcher/attach scripts expect:
//   - build/fontmanager-windows.jar   (the agent, --release 11)
//   - build/attach/AttachLoader.class (the Dynamic Attach driver)

plugins {
    java
}

group = "com.sun.java.fontmgr"
version = "1.0"

repositories {
    mavenCentral()
}

// ASM is bundled into the agent JAR (see `agentJar` below). It is not present
// anywhere on the client's classpath, so these classes cannot be shadowed.
dependencies {
    implementation("org.ow2.asm:asm:9.4")
}

java {
    // Agent bytecode must run on the client's embedded JRE (~JDK 8-11 era),
    // so compile to release 11 even though we build with a newer toolchain.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11

}

// Agent source lives directly under src/ (not the default src/main/java).
// AttachLoader is compiled separately (it lives in tools/ and is not part of
// this source set), so no exclude is needed here.
sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
    }
}

// ── Agent JAR (load-time + dynamic-attach) ───────────────────────────────
val agentJar by tasks.registering(Jar::class) {
    archiveFileName.set("fontmanager-windows.jar")
    destinationDirectory.set(layout.buildDirectory)

    // Self-contained agent: main classes plus the bundled ASM used by
    // ClassFilePatcher (the client ships no ASM of its own).
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { cfg ->
        cfg.map { dep -> if (dep.isDirectory) dep else zipTree(dep) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF",
            "module-info.class", "META-INF/versions/**")

    manifest {
        attributes(
            "Premain-Class" to "com.sun.java.fontmgr.FontManager",
            "Agent-Class" to "com.sun.java.fontmgr.FontManager",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Can-Set-Native-Method-Prefix" to "true"
        )
    }
    // The manifest is the only input this jar needs beyond compiled classes.
    dependsOn(tasks.classes)
}

// ── AttachLoader (standalone, default-package, needs jdk.attach) ─────────
val attachClasses by tasks.registering(JavaCompile::class) {
    source(fileTree("tools"))
    classpath = sourceSets.main.get().compileClasspath
    // jdk.attach is a JDK module, not on the compile classpath by default.
    options.compilerArgs.add("--add-modules")
    options.compilerArgs.add("jdk.attach")
    options.release.set(11)
    destinationDirectory.set(layout.buildDirectory.dir("attach"))
}

val attach by tasks.registering {
    group = "build"
    description = "Compile the Dynamic Attach driver into build/attach/"
    dependsOn(attachClasses)
}

// ── Convenience: mirror the old build.bat's single entry point ───────────
tasks.register("buildAll") {
    group = "build"
    description = "Build the agent JAR and the attach driver (full build.bat equivalent)"
    dependsOn(agentJar, attach)
}

tasks.build {
    dependsOn(agentJar)
}

tasks.clean {
    delete(layout.buildDirectory)
}

// ── Verification harness (verify/) ──────────────────────────────────────────
// Deliberately NOT under tools/: everything in tools/ is compiled into the
// attach driver, and the harness must stay out of the shipped agent. Own
// source sets, own output dirs, own task: `gradlew verify`.
val verifyStubs by tasks.registering(JavaCompile::class) {
    source(fileTree("verify/stubs"))
    classpath = files()
    options.release.set(11)
    destinationDirectory.set(layout.buildDirectory.dir("verify/stubs"))
}

val verifyHarness by tasks.registering(JavaCompile::class) {
    source(fileTree("verify/harness"))
    classpath = sourceSets.main.get().output + configurations.runtimeClasspath.get()
    options.release.set(11)
    destinationDirectory.set(layout.buildDirectory.dir("verify/harness"))
}

// Patches the four real client classes out of game.jar, defines them in a fresh
// class loader, force-links them with resolveClass (a VerifyError fails the
// link), then re-runs the agent via -javaagent and via Dynamic Attach against
// stubs that carry branches/switch/try-catch.
tasks.register<JavaExec>("verify") {
    group = "verification"
    description = "Patch/link/verify the real client classes and both agent entry points"
    dependsOn(agentJar, attach, verifyStubs, verifyHarness)
    classpath = files(
        layout.buildDirectory.dir("verify/harness"),
        sourceSets.main.get().output,
        configurations.runtimeClasspath.get()
    )
    mainClass.set("com.sun.java.fontmgr.VerifyHarness")
    setArgs(listOf(
        file("game.jar").absolutePath,
        layout.buildDirectory.file("fontmanager-windows.jar").get().asFile.absolutePath,
        layout.buildDirectory.dir("verify/stubs").get().asFile.absolutePath,
        layout.buildDirectory.dir("verify/harness").get().asFile.absolutePath,
        layout.buildDirectory.dir("attach").get().asFile.absolutePath
    ))
}