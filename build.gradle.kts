plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Keep build intermediates out of the OneDrive-synced folder. OneDrive locks files
// mid-build, which produces random "Access is denied" failures.
val buildRoot: File = File(System.getenv("LOCALAPPDATA") ?: rootDir.absolutePath, "CarLauncherBuild")

subprojects {
    layout.buildDirectory.set(File(buildRoot, project.name))
}
