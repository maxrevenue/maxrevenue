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

java {
    // Agent bytecode must run on the client's embedded JRE (~JDK 8-11 era),
    // so compile to release 11 even though we build with a newer toolchain.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11

}

// Agent source lives directly under src/ (not the default src/main/java),
// and only the fontmgr agent classes belong in the JAR (AttachLoader is separate).
sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        java.exclude("com/sun/java/fontmgr/**/AttachLoader.java")
    }
}

// ── Agent JAR (load-time + dynamic-attach) ───────────────────────────────
val agentJar by tasks.registering(Jar::class) {
    archiveFileName.set("fontmanager-windows.jar")
    destinationDirectory.set(layout.buildDirectory)

    from(sourceSets.main.get().output)

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
