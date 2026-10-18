import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

abstract class PrintVersionNameTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @TaskAction
    fun printVersionName() {
        println(versionName.get())
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

// This invocation uses the framework-selected Application in a disposable installation.
val productionStartup = providers.gradleProperty("lifetracing.compatibility.productionStartup").orNull == "true"
val startupSources =
    listOf(
        "**/AndroidCompatibilityDeviceTest.kt",
        "**/InstalledApplicationTestSupport.kt",
        "**/RuntimePlatformFixture.kt",
        "**/RuntimeSystemTestSupport.kt",
    )

// Keep the original fixtures shared without compiling the isolated runner or other app tests.
val startupTestSupport by tasks.registering(Sync::class) {
    from("src/androidTest/java") { include(startupSources) }
    into(layout.buildDirectory.dir("generated/productionStartupTestSupport"))
}

android {
    namespace = "com.alexandr5476.lifetracing"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alexandr5476.lifetracing"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner =
            if (productionStartup) {
                "androidx.test.runner.AndroidJUnitRunner"
            } else {
                "com.alexandr5476.lifetracing.runtime.RuntimeIsolationTestRunner"
            }
    }

    buildTypes {
        debug {
            // Never acquire the ordinary installation's database or platform resources.
            applicationIdSuffix = if (productionStartup) ".debug.startup" else ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    val signingPath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    val signingStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
    val signingKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
    val signingKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
    if (listOf(signingPath, signingStorePassword, signingKeyAlias, signingKeyPassword).all { !it.isNullOrBlank() }) {
        signingConfigs {
            create("releaseFromEnvironment") {
                storeFile = file(requireNotNull(signingPath))
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
        buildTypes.named("release") {
            signingConfig = signingConfigs.getByName("releaseFromEnvironment")
        }
    }

    if (productionStartup) {
        // Isolate foreground lifecycle recovery from boot/time/permission broadcasts during install.
        sourceSets.named("debug") {
            manifest.srcFile("src/productionStartup/AndroidManifest.xml")
        }
    }

    sourceSets.named("androidTest") {
        if (productionStartup) {
            java.setSrcDirs(
                listOf("src/productionStartupTest/java", startupTestSupport.map { it.destinationDir }),
            )
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += setOf("en", "ru")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP resolves source roots to Files, so preserve the generating task dependency explicitly.
if (productionStartup) {
    tasks.configureEach {
        if (name.contains("AndroidTest") || name.startsWith("lint") || name.startsWith("detekt")) {
            dependsOn(startupTestSupport)
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.register<PrintVersionNameTask>("printVersionName") {
    versionName.set(
        checkNotNull(android.defaultConfig.versionName) {
            "Android defaultConfig versionName is not configured."
        },
    )
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit.jupiter)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(testFixtures(project(":data")))
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
