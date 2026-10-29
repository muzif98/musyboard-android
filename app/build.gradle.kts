import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationVariant
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// keyboard-cipher: the permissions this app may request, frozen.
//
// The property the whole project rests on is that the keyboard **has no network
// access**: plaintext cannot leave because there is nothing it could leave
// through. Inspecting the manifest is not enough: the APK contains the **merged**
// manifest, and the merge also brings in permissions declared by dependencies.
// An added or updated library could bring `INTERNET` without anyone writing a
// line, and the build would still succeed.
//
// Hence the list. A new permission in the merged manifest **fails the build**,
// and letting it through requires writing it here: adding a permission to this
// app must be a deliberate act, not a side effect of a dependency update.
//
// `INTERNET` is not in the list and must never be. If it were ever really
// needed, that would be a different project.
val permessiConsentiti = setOf(
    "android.permission.READ_USER_DICTIONARY",
    "android.permission.WRITE_USER_DICTIONARY",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.VIBRATE",
    // The three for the service that keeps the keyboard process alive.
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.POST_NOTIFICATIONS",
    // Nobody wrote this one: AndroidX adds it during the merge to protect
    // receivers registered at runtime (`ContextCompat.registerReceiver`). It is
    // defined by this app at `signature` level, so it grants nothing to anyone
    // else. The first run of this check found it, which is what the check is for.
    //
    // The placeholder is there because the name contains the applicationId,
    // which has the `.debug` suffix in debug builds.
    "\${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
)

/**
 * Reads the MERGED manifest and fails if it finds a permission not in the list.
 *
 * Declared inputs and no reference to the script: the configuration cache
 * accepts it, and the task does not rerun when nothing changed.
 */
abstract class VerificaPermessi : DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    abstract val manifest: RegularFileProperty

    @get:org.gradle.api.tasks.Input
    abstract val consentiti: SetProperty<String>

    /** Replaces `${applicationId}` in the list: it differs between variants. */
    @get:org.gradle.api.tasks.Input
    abstract val applicationId: Property<String>

    @org.gradle.api.tasks.TaskAction
    fun verifica() {
        val testo = manifest.get().asFile.readText()
        // `uses-permission-sdk-23` entries count too: they are permissions in
        // every respect, only conditional on the Android version.
        val trovati = Regex("""<uses-permission(?:-sdk-23)?[^>]*android:name="([^"]+)"""")
            .findAll(testo)
            .map { it.groupValues[1] }
            .toSet()
        val ammessi = consentiti.get()
            .map { it.replace("\${applicationId}", applicationId.get()) }
            .toSet()
        val estranei = (trovati - ammessi).sorted()
        if (estranei.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Permessi non previsti nel manifest unito:")
                    estranei.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("Non sono stati dichiarati qui: li porta una dipendenza.")
                    appendLine("Se il permesso serve davvero, aggiungilo a `permessiConsentiti`")
                    appendLine("in app/build.gradle.kts, con scritto perche'.")
                    appendLine("Se non serve, toglilo con tools:node=\"remove\" nel manifest.")
                }
            )
        }
    }
}

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
}

// keyboard-cipher: release signing.
//
// The key is NOT in the repository and must never be: the properties file is in
// .gitignore and the keystore path points outside the tree. Cloning still
// builds, unsigned (see the `release` buildType).
//
// Values come from `keystore.properties` in the project root, or from the
// environment variables KC_KEYSTORE / KC_KEYSTORE_PASSWORD / KC_KEY_ALIAS /
// KC_KEY_PASSWORD, convenient for CI.
//
// Outside the `android { }` block on purpose: in there `java` is the Gradle
// extension, not the package, so `java.util.Properties` does not resolve.
val cipherKeystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun cipherSecret(name: String, env: String): String? =
    (cipherKeystoreProps.getProperty(name) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val cipherKeystoreFile = cipherSecret("storeFile", "KC_KEYSTORE")?.let { file(it) }

/** `false` if the key is missing: the build continues and produces an unsigned APK. */
val cipherSigningReady: Boolean = cipherKeystoreFile?.exists() == true &&
        cipherSecret("storePassword", "KC_KEYSTORE_PASSWORD") != null &&
        cipherSecret("keyAlias", "KC_KEY_ALIAS") != null

android {
    compileSdk = 36

    defaultConfig {
        // Our own id, distinct from HeliBoard's (`helium314.keyboard`, already on
        // F-Droid): two apps with the same id cannot coexist or be published. To
        // the phone a changed id is a new app: keys move through backup
        // export/import.
        applicationId = "io.github.muzif98.musyboard"
        minSdk = 21
        targetSdk = 36
        versionCode = 4019
        // The MusyBoard fork version, not that of the HeliBoard it derives from.
        // versionCode stays monotonic so that installed APKs can be updated.
        versionName = "0.19.3"
        // Instrumented tests run on a real device or an emulator: the only way to
        // test what goes through the native core (the `.so` is an Android binary
        // and does not load on the JVM) and Android Keystore, absent on the JVM.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    signingConfigs {
        if (cipherSigningReady) {
            create("cipherRelease") {
                storeFile = cipherKeystoreFile
                storePassword = cipherSecret("storePassword", "KC_KEYSTORE_PASSWORD")
                keyAlias = cipherSecret("keyAlias", "KC_KEY_ALIAS")
                // Without a separate key password the keystore's is used: keytool
                // makes them equal when no second one is given, the normal case.
                keyPassword = cipherSecret("keyPassword", "KC_KEY_PASSWORD")
                    ?: cipherSecret("storePassword", "KC_KEYSTORE_PASSWORD")
            }
        }
    }

    // Instrumented tests are built against `debugNoMinify`.
    //
    // The normal `debug` has R8 on (to keep the APK small), and on this project R8
    // cannot compile the test APK. `debugNoMinify` already exists for the same
    // reasons (faster builds, no obfuscation) and is what a test wants: there
    // obfuscation adds nothing and removes readable stack traces.
    testBuildType = "debugNoMinify"

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            // Without a key the APK comes out UNSIGNED instead of failing: anyone
            // cloning the repository must be able to build it. An unsigned APK does
            // not install, so the error still comes, where it makes sense.
            if (cipherSigningReady) signingConfig = signingConfigs.getByName("cipherRelease")
        }
        create("nouserlib") { // same as release, but does not allow the user to provide a library
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            if (cipherSigningReady) signingConfig = signingConfigs.getByName("cipherRelease")
        }
        debug {
            // "normal" debug has minify for smaller APK to fit the GitHub 25 MB limit when zipped
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = true
            isDebuggable = false
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }

        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/dontoptimize.pro"))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    output.outputFileName = "MusyBoard_${defaultConfig.versionName}-${variant.buildType}.apk"
                }
            }

            // The permission check, once per variant.
            //
            // Requesting the MERGED_MANIFEST artifact brings the dependency on the
            // task that produces it, so the check cannot run before the merge, the
            // moment dependency permissions are already in. Checking the source
            // manifest would be pointless: it is exactly what does NOT contain what
            // we are looking for.
            val controllo = tasks.register<VerificaPermessi>(
                "verificaPermessi${variant.name.replaceFirstChar { it.uppercase() }}"
            ) {
                manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                consentiti.set(permessiConsentiti)
                applicationId.set(variant.applicationId)
            }
            // Hooked to the packager rather than `assemble`: it also applies to
            // direct installs, not only to the task building the full APK.
            tasks.matching {
                it.name == "package${variant.name.replaceFirstChar { c -> c.uppercase() }}"
            }.configureEach { dependsOn(controllo) }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    // The crypto core's .so does not go through ndkBuild: it is produced by
    // cargo-ndk from a Rust crate in a separate repository, and lands in jniLibs
    // as a prebuilt library. The ndkBuild chain above remains HeliBoard's native
    // dictionary, untouched.
    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric tests failed with `UnsatisfiedLinkError` (even those
            // predating this fork) on machines where `/tmp` is mounted `noexec`:
            // Robolectric extracts its native libraries there (Conscrypt too) and
            // then cannot load them.
            //
            // They are moved to a folder inside `build/`, which is executable. Two
            // properties rather than one: Conscrypt looks at its own, everything else
            // at `java.io.tmpdir`.
            //
            // Not a patch for an odd environment: `noexec` on `/tmp` is a common
            // hardening choice.
            all {
                // `-PsenzaRete` excludes the internet link checks (XLinkTest). They
                // query third-party sites, so they can turn red on any day without
                // anything changing here. Used by the core's `verify-all.sh`; still
                // useful by hand, without the property.
                if (senzaRete) it.exclude("**/XLinkTest*")
                val nativi = layout.buildDirectory.dir("tmp/robolectric-native").get().asFile
                it.doFirst { nativi.mkdirs() }
                it.systemProperty("org.conscrypt.native.workdir", nativi.absolutePath)
                it.systemProperty("java.io.tmpdir", nativi.absolutePath)
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        target {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.17.0") // 1.18.0 requires minSdk 23
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // keyboard-cipher: `viewpager2` pulls in `fragment:1.1.0`, and with that on the
    // classpath lint blocks the RELEASE build (not debug), because
    // `registerForActivityResult` wants at least 1.3.0. Our activities are not
    // Fragments, but the rule looks at what is compiled, not at who uses it.
    // Raising the version removes the problem instead of silencing the check.
    implementation("androidx.fragment:fragment:1.8.6")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // newer than 2025.11.01 contains androidx.compose.material:material-android:1.10.0, which requires minSdk 23
    // maybe it's possible to use tools:overrideLibrary="androidx.compose.material" as it's not used explicitly, but probably this is just going to crash
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    "debugNoMinifyImplementation"("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors

    // keyboard-cipher: QR generation for in-person exchange.
    //
    // ZXing core only, pure Java: no native code, no permissions, no network. Only
    // the encoder is needed: scanning would require CAMERA, and that permission
    // is not taken for convenience.
    //
    // A dependency rather than writing it: a QR encoder is Reed-Solomon over
    // GF(256) plus mask evaluation, and a subtle bug produces a code some readers
    // accept and others do not.
    implementation("com.google.zxing:core:3.5.3")

    // test
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
}

// ============================================================================
// keyboard-cipher — core crypto in Rust
// ============================================================================

// Path of the keyboard-cipher-core checkout. Default: the `core/` submodule if it
// was initialised (git submodule update --init), otherwise the sibling
// `../musyboard-core` checkout.
// The submodule exists for clean builds, F-Droid included: it pins the core to an
// exact commit, so the same app tag always produces the same cryptographic code.
// Overridable with -PcipherCorePath=/other/path or in gradle.properties.
// Read here, at configuration time, and not inside the test block: the
// configuration cache does not accept project references captured there.
val senzaRete: Boolean = project.hasProperty("senzaRete")

val cipherCorePath: String =
    (project.findProperty("cipherCorePath") as String?)
        ?: if (rootProject.file("core/Cargo.toml").exists()) "../core" else "../../musyboard-core"

// The four ABIs Android uses today. Each adds about 300 KB to the APK.
val cipherAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

// Everything resolved HERE, at configuration time, and not inside the task: the
// configuration cache serialises tasks, and a reference to the Gradle script
// captured in a lambda is not serialisable. From here on only File and String are
// passed.
val cipherCoreDir = file(cipherCorePath).resolve("jni")
val cipherLibsDir = file("src/main/jniLibs")
val cipherCoreAvailable = cipherCoreDir.resolve("Cargo.toml").exists()

// Source path remappings, resolved here at configuration time.
//
// Without them the `.so` embeds in .rodata the absolute paths of the machine that
// built it: the location strings of the dependencies' `panic!`s (jni,
// curve25519-dalek, rand_core, cesu8, cipher), which survive stripping debug info
// because they are not debug info.
//
// Two unwanted consequences: the library reveals the builder's directory layout,
// user name included; and above all it changes depending on WHERE it was built,
// making a reproducible build impossible. A binary nobody can rebuild identically
// must be trusted on faith, the opposite of what an encryption project needs.
val cipherCargoHome: String = System.getenv("CARGO_HOME")
    ?: "${System.getProperty("user.home")}/.cargo"
// `cargo` is looked up first in CARGO_HOME/bin, where rustup puts it, and only then
// in PATH. On the F-Droid build server rustup is installed in a recipe step, and
// the PATH Gradle runs with does not contain it. `cargo ndk` then finds cargo-ndk
// in the same folder.
val cipherCargo: String = file("$cipherCargoHome/bin/cargo").takeIf { it.canExecute() }?.absolutePath
    ?: "cargo"

val cipherRustFlags: String = listOf(
    "--remap-path-prefix=$cipherCargoHome=/cargo",
    "--remap-path-prefix=${cipherCoreDir.absolutePath}=/src",
    "--remap-path-prefix=${cipherCoreDir.parentFile.absolutePath}=/src-core",
).joinToString(" ")

val buildCipherCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Compila keyboard-cipher-jni per le ABI Android con cargo-ndk"

    workingDir = cipherCoreDir
    environment("RUSTFLAGS", cipherRustFlags)
    environment("CARGO_CACHE_AUTO_CLEAN_FREQUENCY", "never")
    commandLine(
        buildList {
            add(cipherCargo)
            add("ndk")
            cipherAbis.forEach { add("-t"); add(it) }
            add("-o"); add(cipherLibsDir.absolutePath)
            add("build")
            add("--release")
        }
    )

    // Do not proceed without the core source. `src/main/jniLibs` may contain
    // artefacts left by a previous build: skipping the task would then produce a
    // seemingly valid APK with old cryptographic code. The failure must be explicit
    // and happen before packaging.
    if (!cipherCoreAvailable) {
        commandLine(
            "sh", "-c",
            "echo 'Core crittografico non trovato: ${cipherCoreDir.absolutePath}' >&2; exit 1",
        )
    }
}

tasks.named("preBuild") {
    dependsOn(buildCipherCore)
}


tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}
