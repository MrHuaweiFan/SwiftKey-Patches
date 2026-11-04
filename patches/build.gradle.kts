import org.gradle.process.CommandLineArgumentProvider

group = "io.github.mrhuaweifan"

patches {
    about {
        name = "SwiftKey Patches"
        description = "Morphe patches for Microsoft SwiftKey 9.12.13.13."
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

// Coroutines for the ApkRunner (the patcher flow API). Added to patcherProvided so it is
// available at compile time but still excluded from the bundled .mpp, exactly like the
// patcher itself: Morphe provides both at runtime.
val coroutinesClasspath: Configuration by configurations.creating

afterEvaluate {
    dependencies.add("patcherProvided", "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
}

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
    coroutinesClasspath("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // The patch list generator is build tooling; keep it out of the .mpp like
    // the official Morphe patch bundles do.
    named<Jar>("jar") {
        exclude("util/**")
    }

    // 1.2.2 CI hardening: the plain `jar` task produces a bundle WITHOUT
    // classes.dex, which the Android Morphe app cannot load (PatchLoader.Dex
    // reads the dex entries of the bundle zip - the exact reason the released
    // 1.2.1 .mpp failed to import while 1.2.0 installed fine: that release ran
    // `jar`/`build` without the plugin's `buildAndroid` step that compiles the
    // patch classes to dex and merges it in). Wiring buildAndroid into
    // assemble (and therefore into `build` and everything that depends on it,
    // like generatePatchesList) makes every .mpp that lands in build/libs
    // carry classes.dex, no matter which task graph produced it.
    named("assemble") {
        dependsOn("buildAndroid")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
// Applies the built patches bundle to a SwiftKey APK (see util/ApkRunner.kt).
// Usage: ./gradlew :patches:runApk -Pmpp=... -Pin=... -Pout=... -Pmode=defaults|all -Pks=... -Pkspass=... -Palias=...
tasks {
    register<JavaExec>("runApk") {
        group = "patching"
        description = "Applies the built patches bundle to an APK and signs the result."
        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath + coroutinesClasspath
        mainClass.set("util.ApkRunnerKt")

        // Resolved lazily (only when the task actually runs) so that merely configuring the
        // project, e.g. `./gradlew tasks` from semantic-release, does not require -Pmpp etc.
        argumentProviders.add(
            CommandLineArgumentProvider {
                fun prop(name: String) = providers.gradleProperty(name).get()
                listOf(
                    prop("mpp"),
                    prop("in"),
                    prop("out"),
                    providers.gradleProperty("mode").getOrElse("defaults"),
                    prop("ks"),
                    prop("kspass"),
                    prop("alias"),
                )
            },
        )

        // The runner needs the patcher at runtime; it is provided by patcherProvided,
        // which is part of the implementation (and thus runtime) classpath already.
        workingDir = rootProject.projectDir
    }
}

// Writes the full runtime classpath of the patches module to a file, so the ApkRunner
// can be launched with plain java (gradle daemons and the patcher compete for memory).
tasks {
    register("printRuntimeClasspath") {
        doLast {
            val cp = sourceSets["main"].runtimeClasspath +
                patchListGeneratorClasspath +
                configurations.getByName("patcherProvidedClasspath")
            File(rootProject.projectDir, "runner-classpath.txt").writeText(cp.joinToString(":"))
        }
    }
}
