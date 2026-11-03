group = "io.github.mrhuaweifan"

patches {
    about {
        name = "SwiftKey Patches"
        description = "Morphe patches for Microsoft SwiftKey 9.13.13.5."
        source = "git@github.com:MrHuaweiFan/SwiftKey-Patches.git"
        author = "MrHuaweiFan"
        contact = "na"
        website = "https://github.com/MrHuaweiFan/SwiftKey-Patches"
        license = "GPLv3"
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath: Configuration by configurations.creating

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}