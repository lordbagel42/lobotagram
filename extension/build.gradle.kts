// The lobotagram extension: plain Java compiled against android.jar and turned
// into a single DEX file that patches merge into Instagram.
//
// No Android Gradle Plugin here on purpose. AGP would pull a large dependency
// tree for what is really two steps: javac against android.jar, then D8.

plugins {
    java
}

val d8 by configurations.registering {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    d8(libs.r8)
}

/**
 * The Android platform jar to compile against, resolved from, in order:
 * the `lobotagram.androidJar` Gradle property, `ANDROID_HOME`, `ANDROID_SDK_ROOT`.
 */
val androidJar: Provider<File> = providers.provider {
    val fromProperty = providers.gradleProperty("lobotagram.androidJar").orNull
    if (fromProperty != null) {
        return@provider File(fromProperty).also {
            require(it.isFile) { "lobotagram.androidJar=$fromProperty does not exist" }
        }
    }

    val sdkRoots = listOfNotNull(
        providers.environmentVariable("ANDROID_HOME").orNull,
        providers.environmentVariable("ANDROID_SDK_ROOT").orNull,
    ).map(::File).filter(File::isDirectory)

    val candidates = sdkRoots
        .flatMap { (File(it, "platforms").listFiles() ?: emptyArray()).toList() }
        .map { File(it, "android.jar") }
        .filter(File::isFile)
        // Highest API level wins, so a newer SDK still works.
        .sortedBy { it.parentFile.name.substringAfterLast('-').toIntOrNull() ?: 0 }

    candidates.lastOrNull() ?: error(
        """
        Could not find android.jar.

        The extension is compiled against the Android platform jar. Install the
        Android SDK and point the build at it with one of:
          * -Plobotagram.androidJar=/path/to/platforms/android-35/android.jar
          * ANDROID_HOME=/path/to/android-sdk
          * ANDROID_SDK_ROOT=/path/to/android-sdk
        Searched: ${sdkRoots.ifEmpty { listOf("(no SDK root set)") }.joinToString()}
        """.trimIndent(),
    )
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.compileJava {
    // Compile against the Android platform, not the JDK: `--release` cannot be
    // used together with a replaced platform classpath, so the platform jar
    // goes on the compile classpath and source/target pin the class format.
    classpath = objects.fileCollection().from(androidJar)
    options.encoding = "UTF-8"
    // -Xlint:-options silences "bootstrap classpath not set in conjunction with -source 17".
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options"))
}

val dexDirectory = layout.buildDirectory.dir("dex")

/** Compiles the extension classes into a single `classes.dex`. */
val dexExtension by tasks.registering(JavaExec::class) {
    description = "Compiles the extension to a single DEX file with D8."
    group = "build"

    dependsOn(tasks.jar)

    classpath = d8.get()
    mainClass = "com.android.tools.r8.D8"

    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.files(androidJar)
    outputs.dir(dexDirectory)

    argumentProviders.add {
        listOf(
            "--release",
            "--min-api", "27",
            "--lib", androidJar.get().absolutePath,
            "--output", dexDirectory.get().asFile.absolutePath,
            tasks.jar.get().archiveFile.get().asFile.absolutePath,
        )
    }

    doFirst { dexDirectory.get().asFile.mkdirs() }
}

/**
 * Publishes the DEX as `extensions/lobotagram.rve`, laid out exactly as the
 * patches jar wants to consume it.
 */
val syncExtension by tasks.registering(Sync::class) {
    description = "Stages the extension DEX as extensions/lobotagram.rve."
    group = "build"

    from(dexExtension.map { it.outputs.files.asFileTree.matching { include("**/*.dex") } })
    into(layout.buildDirectory.dir("revanced/extensions"))
    rename { "lobotagram.rve" }
}

// Consumed by :patches as a resource directory, the same way the official
// ReVanced extension plugin hands its DEX to the patches project.
configurations.consumable("extensionConfiguration") {
    outgoing.artifact(layout.buildDirectory.dir("revanced")) { builtBy(syncExtension) }
}

tasks.assemble { dependsOn(syncExtension) }
