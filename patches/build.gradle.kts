import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

// The lobotagram patches, packaged as an .rvp bundle.
//
// An .rvp is a jar with:
//   * the patch classes,
//   * the extension DEX as the resource extensions/lobotagram.rve,
//   * a classes.dex of the patch classes themselves, so ReVanced Manager can
//     run the same file on a phone,
//   * manifest attributes describing the bundle.
// The official Gradle plugin does all of that; it is not fetchable here, so the
// tasks below reproduce it.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

val cliVersion = "6.0.0"
val cliJarName = "revanced-cli-$cliVersion-all.jar"
val cliUrl = "https://github.com/ReVanced/revanced-cli/releases/download/v$cliVersion/$cliJarName"
val cliSha256 = "c25549bc17d59d2eb94fa5f86e60e9b77a02772ca88f7050f8f1276f923a9958"

/**
 * ReVanced CLI 6.0.0 is the compile classpath: it is a fat jar that bundles
 * ReVanced Patcher v22, smali/dexlib2, multidexlib2 and the Kotlin standard
 * library. The patcher artifacts themselves live on GitHub Packages, which
 * needs an authenticated token, so the CLI release jar stands in for them.
 *
 * Resolved from, in order: the `lobotagram.cliJar` Gradle property, the
 * `LOBOTAGRAM_CLI_JAR` environment variable, `tools/<cliJarName>`.
 */
val cliJar: File = providers.gradleProperty("lobotagram.cliJar").orNull?.let(::File)
    ?: providers.environmentVariable("LOBOTAGRAM_CLI_JAR").orNull?.let(::File)
    ?: rootProject.layout.projectDirectory.file("tools/$cliJarName").asFile

/** Downloads the CLI jar into tools/ when it is not present, so a fresh clone builds. */
val fetchCli by tasks.registering {
    description = "Downloads ReVanced CLI $cliVersion into tools/ if it is missing."
    group = "build setup"

    outputs.file(cliJar)
    onlyIf { !cliJar.isFile }

    doLast {
        cliJar.parentFile.mkdirs()
        logger.lifecycle("Downloading $cliUrl")

        var url = URI(cliUrl).toURL()
        var redirects = 0
        while (true) {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000

            val location = if (connection.responseCode in 300..399) connection.getHeaderField("Location") else null
            if (location == null) {
                check(connection.responseCode == 200) {
                    "Failed to download $url: HTTP ${connection.responseCode}"
                }
                connection.inputStream.use { input -> cliJar.outputStream().use(input::copyTo) }
                break
            }

            connection.disconnect()
            check(redirects++ < 5) { "Too many redirects downloading $cliUrl" }
            url = URI(location).toURL()
        }

        val actual = MessageDigest.getInstance("SHA-256")
            .digest(cliJar.readBytes())
            .joinToString("") { "%02x".format(it) }

        if (actual != cliSha256) {
            cliJar.delete()
            error("Checksum mismatch for $cliJarName: expected $cliSha256, got $actual")
        }
    }
}

val d8 by configurations.registering {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// The extension DEX, staged by :extension as extensions/lobotagram.rve.
val extensionArtifact by configurations.registering {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly(files(cliJar))
    d8(libs.r8)
    extensionArtifact(project(mapOf("path" to ":extension", "configuration" to "extensionConfiguration")))
}

sourceSets.main {
    // Puts extensions/lobotagram.rve into the rvp, where extendWith() finds it.
    resources.srcDir(extensionArtifact)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.addAll(
            // The patcher's matching DSL is built on context parameters.
            "-Xcontext-parameters",
            // ReVanced Patcher v22 was published from a pre-release Kotlin 2.3
            // build. Its metadata version matches this compiler, only the
            // pre-release marker differs.
            "-Xskip-prerelease-check",
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.compileKotlin { dependsOn(fetchCli) }

val about = mapOf(
    "Name" to "Lobotagram",
    "Description" to "Removes Reels as a surface from Instagram while keeping DMs, Stories and posting intact.",
    "Version" to project.version.toString(),
    "Timestamp" to System.currentTimeMillis().toString(),
    "Source" to "https://github.com/lordbagel42/lobotagram",
    "Author" to "lordbagel42",
    "Contact" to "https://github.com/lordbagel42",
    "Website" to "https://github.com/lordbagel42/lobotagram",
    "License" to "GPL-3.0",
)

tasks.jar {
    description = "Builds the rvp patch bundle (without the Android DEX yet)."

    archiveBaseName = "lobotagram"
    archiveExtension = "rvp"
    destinationDirectory = rootProject.layout.buildDirectory

    manifest.attributes(about)
}

val patchesDexDirectory = layout.buildDirectory.dir("dex")

/**
 * The patch classes as a plain `.jar`, which is what D8 accepts as input. D8
 * rejects files by extension, so it cannot read the `.rvp` directly.
 */
val patchesClassesJar by tasks.registering(Jar::class) {
    description = "Packages the patch classes for D8."

    archiveBaseName = "lobotagram-patches"
    archiveClassifier = "classes"
    destinationDirectory = layout.buildDirectory.dir("dex-input")

    from(sourceSets.main.get().output.classesDirs)
}

/** D8-compiles the patch classes so ReVanced Manager can load them on Android. */
val dexPatches by tasks.registering(JavaExec::class) {
    description = "Compiles the patch classes to DEX with D8."
    group = "build"

    dependsOn(patchesClassesJar, fetchCli)

    classpath = d8.get()
    mainClass = "com.android.tools.r8.D8"

    inputs.file(patchesClassesJar.flatMap { it.archiveFile })
    outputs.dir(patchesDexDirectory)

    argumentProviders.add {
        listOf(
            "--release",
            "--min-api", "27",
            // The patcher and the Kotlin standard library are provided by the host.
            "--classpath", cliJar.absolutePath,
            "--output", patchesDexDirectory.get().asFile.absolutePath,
            patchesClassesJar.get().archiveFile.get().asFile.absolutePath,
        )
    }

    doFirst { patchesDexDirectory.get().asFile.mkdirs() }
}

/**
 * Merges the patch DEX into the rvp, the way the official plugin's
 * `buildAndroid` task does, so one file works in both ReVanced CLI and
 * ReVanced Manager.
 */
val buildAndroid by tasks.registering {
    description = "Merges the patch DEX into the rvp so it also runs on Android."
    group = "build"

    dependsOn(dexPatches, tasks.jar)

    val rvp = tasks.jar.flatMap { it.archiveFile }
    val dexFiles = patchesDexDirectory.map { it.asFile.listFiles()?.filter { f -> f.extension == "dex" }.orEmpty() }

    inputs.files(rvp)
    inputs.dir(patchesDexDirectory)
    outputs.file(rvp)

    doLast {
        val rvpFile = rvp.get().asFile
        val dexes = dexFiles.get()
        check(dexes.isNotEmpty()) { "D8 produced no DEX file in ${patchesDexDirectory.get()}" }

        val merged = File(rvpFile.parentFile, "${rvpFile.name}.merging")
        ZipFile(rvpFile).use { source ->
            JarOutputStream(merged.outputStream().buffered()).use { out ->
                source.entries().asSequence()
                    .filterNot { it.isDirectory || it.name.endsWith(".dex") }
                    .forEach { entry ->
                        out.putNextEntry(JarEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }

                dexes.forEach { dex ->
                    out.putNextEntry(JarEntry(dex.name))
                    dex.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }

        check(merged.renameTo(rvpFile) || (rvpFile.delete() && merged.renameTo(rvpFile))) {
            "Failed to replace $rvpFile with the merged rvp"
        }

        logger.lifecycle("Built $rvpFile with ${dexes.joinToString { it.name }}")
    }
}

/** A stable file name for scripts, CI and ReVanced Manager URLs. */
val copyRvp by tasks.registering(Copy::class) {
    description = "Copies the versioned rvp to build/lobotagram.rvp."
    group = "build"

    dependsOn(buildAndroid)

    from(tasks.jar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory)
    rename { "lobotagram.rvp" }
}

tasks.assemble { dependsOn(copyRvp) }
