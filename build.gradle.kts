// Root build for lobotagram: a ReVanced-style patch bundle for Instagram.
//
// The official `app.revanced.patches` Gradle plugin lives on GitHub Packages,
// which needs an authenticated token, so this project reproduces what that
// plugin does by hand. See docs/05-development.md.

allprojects {
    group = "dev.lobotagram"
    version = rootProject.version
}

tasks.register<Delete>("clean") {
    description = "Deletes the root build directory (the rvp output directory)."
    group = "build"

    delete(layout.buildDirectory)
}
