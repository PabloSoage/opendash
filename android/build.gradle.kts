import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.varuna.opendash"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.varuna.opendash"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Spanish and English from the first commit: adding a language later
        // means auditing every string, and it is never done.
        androidResources.localeFilters += setOf("en", "es")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.AMAZON)
    }
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.biometric)
}




// --- RUST CORE INTEGRATION ---

// Define the target architectures to compile (64-bit physical devices and modern emulators)
val targets = listOf("arm64-v8a", "x86_64")

// Detect the host operating system to invoke the correct executable file for Cargo
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val cargoExecutableName = if (isWindows) "cargo.exe" else "cargo"
val cargoHome = System.getenv("CARGO_HOME") ?: (System.getProperty("user.home") + "/.cargo")
val cargoPath = file("$cargoHome/bin/$cargoExecutableName")
val cargoCommand: String = if (cargoPath.exists()) cargoPath.absolutePath else cargoExecutableName

// Get the number of CPU cores for parallel compilation
val cpuCount = Runtime.getRuntime().availableProcessors()

// Dynamically register an individual Gradle 'Exec' task for each specified architecture
targets.forEach { target ->
    tasks.register<Exec>("buildRustCore_$target") {
        group = "rust"
        description = "Compiles the Rust core for $target architecture via cargo-ndk"
        workingDir = file("../core")

        // Retrieve the NDK path from Android components
        val androidComponents = project.extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()
        val ndkDir = androidComponents.sdkComponents.ndkDirectory.get().asFile
        val sdkDir = androidComponents.sdkComponents.sdkDirectory.get().asFile

        // Bindgen and cargo-ndk on Windows often fail if the NDK path contains spaces (e.g., in the user profile).
        // To fix this universally, we create a Junction (symlink) in the build directory (which usually has no spaces).
        val noSpaceNdkDir = file("${project.layout.buildDirectory.get().asFile.absolutePath}/ndk_link")
        val needsJunction = isWindows && ndkDir.absolutePath.contains(" ")
        val effectiveNdkDir = if (needsJunction) noSpaceNdkDir else ndkDir

        // Set the environment variables required by cargo-ndk
        environment("ANDROID_NDK_HOME", effectiveNdkDir.absolutePath)
        environment("ANDROID_HOME", sdkDir.absolutePath)

        // Find libclang statically to avoid I/O during Gradle's configuration phase (supports Configuration Cache)
        val hostTag = if (isWindows) "windows-x86_64" else if (System.getProperty("os.name").lowercase().contains("mac")) "darwin-x86_64" else "linux-x86_64"
        val clangDir = if (isWindows) {
            file("${effectiveNdkDir.absolutePath}/toolchains/llvm/prebuilt/$hostTag/bin")
        } else {
            file("${effectiveNdkDir.absolutePath}/toolchains/llvm/prebuilt/$hostTag/lib64")
        }

        environment("LIBCLANG_PATH", clangDir.absolutePath)
        if (isWindows) {
            // On Windows, libclang.dll depends on other DLLs in the same folder. We append it to PATH.
            val currentPath = System.getenv("PATH") ?: ""
            environment("PATH", "${clangDir.absolutePath};$currentPath")
        }

        doFirst {
            // Create the symlink right before the task executes (fully supports Configuration Cache)
            if (needsJunction && !noSpaceNdkDir.exists()) {
                noSpaceNdkDir.parentFile.mkdirs()
                Runtime.getRuntime().exec(arrayOf("cmd", "/c", "mklink", "/J", noSpaceNdkDir.absolutePath, ndkDir.absolutePath)).waitFor()
            }
        }

        inputs.dir("../core/src")
        outputs.dir("src/main/jniLibs/$target")

        // Execute the cargo-ndk command with explicit job parallelism and specific architecture target
        commandLine(
            cargoCommand, "ndk",
            "-t", target,
            "-o", "../android/src/main/jniLibs",
            "build", "--release",
            "-j", cpuCount.toString() // Use all available CPU cores for compilation
        )
    }
}

// Orchestrator task that triggers the compilation of all defined Rust architectures in parallel
tasks.register("buildRustCoreAll") {
    group = "rust"
    description = "Compiles the Rust core for all supported Android ABIs (in parallel)"

    // Each architecture task will run in parallel with others
    dependsOn(targets.map { "buildRustCore_$it" })
}

// Hook into the Android build lifecycle to automate Rust compilation
// This intercepts the package generation phase right before Android merges the native JNI libraries
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn("buildRustCoreAll")
    }
}

