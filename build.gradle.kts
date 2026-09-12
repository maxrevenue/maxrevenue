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
    create("launcher") {
        java.setSrcDirs(listOf("launcher/src"))
        compileClasspath += sourceSets["main"].output
        compileClasspath += configurations.compileClasspath.get()
        runtimeClasspath += output
        runtimeClasspath += compileClasspath
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
    jvmArgs("-Dfontmgr.license.bypass=true")
}

tasks.named<JavaCompile>("compileLauncherJava") {
    options.release.set(17)
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.attach"))
}

val launcherJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Branded launcher JAR (not the game agent)"
    archiveFileName.set("roatz-launcher.jar")
    destinationDirectory.set(layout.buildDirectory.dir("launcher"))
    from(sourceSets.named("launcher").get().output)
    from(sourceSets.main.get().output) {
        include("com/sun/java/fontmgr/Product.class")
        include("com/sun/java/fontmgr/Hwid.class")
        include("com/sun/java/fontmgr/License*.class")
        // Shared palette: roatz.launcher.Theme delegates to it, so the launcher
        // must not depend on agent.jar being on the classpath after it.
        include("com/sun/java/fontmgr/Theme.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "roatz.launcher.LauncherApp")
    }
    dependsOn("launcherClasses", tasks.classes)
}

tasks.register<JavaExec>("runLauncher") {
    group = "application"
    description = "Run the branded launcher from this repo (dev). Default license API is local wrangler."
    dependsOn(agentJar, "launcherClasses")
    classpath = sourceSets.named("launcher").get().runtimeClasspath
    mainClass.set("roatz.launcher.LauncherApp")
    jvmArgs("--add-modules", "jdk.attach")
    workingDir = layout.projectDirectory.asFile
    systemProperty(
        "roatz.agent.jar",
        layout.buildDirectory.file("fontmanager-windows.jar").get().asFile.absolutePath
    )
    val api = System.getenv("ROATZ_LICENSE_API") ?: "http://127.0.0.1:8787"
    systemProperty("roatz.license.api", api)
}

val jpackageInput by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Stage launcher + agent jars for jpackage"
    dependsOn(agentJar, launcherJar)
    into(layout.buildDirectory.dir("jpackage-input"))
    from(launcherJar) {
        rename { "roatz-launcher.jar" }
    }
    from(agentJar) {
        rename { "agent.jar" }
    }
}

fun jpackageBinary(): String {
    val home = System.getProperty("java.home")
    val win = file("$home/bin/jpackage.exe")
    val nix = file("$home/bin/jpackage")
    return when {
        win.exists() -> win.absolutePath
        nix.exists() -> nix.absolutePath
        else -> "jpackage"
    }
}

fun innoCompiler(): File? {
    val locals = mutableListOf(
        file("C:/Program Files (x86)/Inno Setup 6/ISCC.exe"),
        file("C:/Program Files/Inno Setup 6/ISCC.exe")
    )
    System.getenv("LOCALAPPDATA")?.let { local ->
        locals.add(0, file("$local/Programs/Inno Setup 6/ISCC.exe"))
    }
    return locals.firstOrNull { it.isFile }
}

val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Windows app-image with a bundled JDK (jdk.attach)"
    dependsOn(jpackageInput)
    val destDir = layout.buildDirectory.dir("jpackage")
    val inputDir = layout.buildDirectory.dir("jpackage-input")
    inputs.dir(inputDir)
    // jpackage wipes/rebuilds the image tree; Gradle MD5 of a half-deleted tree fails.
    doNotTrackState("jpackage recreates the app-image directory each run")
    doFirst {
        val image = destDir.get().asFile.resolve("Roatz")
        if (image.exists() && !image.deleteRecursively()) {
            throw GradleException(
                "Could not delete ${image.absolutePath}. Close Roatz.exe and retry."
            )
        }
        destDir.get().asFile.mkdirs()
    }
    executable(jpackageBinary())
    val licenseApi = (findProperty("roatzLicenseApi") as String?)
        ?: System.getenv("ROATZ_LICENSE_API")
    args(
        "--type", "app-image",
        "--name", "Roatz",
        "--app-version", "1.0.7",
        "--vendor", "Roatz",
        "--description", "Roatz launcher",
        "--input", inputDir.get().asFile.absolutePath,
        "--main-jar", "roatz-launcher.jar",
        "--main-class", "roatz.launcher.LauncherApp",
        "--dest", destDir.get().asFile.absolutePath,
        "--add-modules",
        "java.desktop,java.logging,java.management,java.net.http,java.xml,java.naming,jdk.attach,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs",
        "--java-options", "--add-modules jdk.attach"
    )
    if (!licenseApi.isNullOrBlank()) {
        args("--java-options", "-Droatz.license.api=$licenseApi")
    }
    // App icon (taskbar / Explorer / shortcut). jpackage wants a multi-size .ico
    // on Windows; regenerate it with installer/make-icon.py.
    val icon = file("installer/roatz.ico")
    if (icon.isFile) {
        args("--icon", icon.absolutePath)
    } else {
        logger.warn("installer/roatz.ico missing — run python installer/make-icon.py; building without an icon")
    }
}

tasks.register("dist") {
    group = "distribution"
    description = "Build Roatz-Setup.exe (app-image + Inno Setup when ISCC is installed)"
    dependsOn(jpackageImage)
    doLast {
        val image = layout.buildDirectory.dir("jpackage/Roatz").get().asFile
        if (!image.isDirectory) {
            throw GradleException("jpackage image missing at ${image.absolutePath}")
        }
        val iscc = innoCompiler()
        if (iscc == null) {
            logger.warn("Inno Setup 6 not found (ISCC.exe). App image is at ${image.absolutePath}")
            logger.warn("Install Inno Setup and re-run: gradlew dist")
            return@doLast
        }
        val distDir = layout.buildDirectory.dir("dist").get().asFile
        distDir.mkdirs()
        exec {
            commandLine(iscc.absolutePath, file("installer/roatz.iss").absolutePath)
        }
        logger.lifecycle("Installer: ${file("build/dist/Roatz-Setup.exe").absolutePath}")
    }
}