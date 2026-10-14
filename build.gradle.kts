import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.ManagedVirtualDevice
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.ksp) apply false
}

subprojects {
    plugins.withId("io.gitlab.arturbosch.detekt") {
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        }
    }

    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            android.set(true)
            outputToConsole.set(true)
            ignoreFailures.set(false)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val compatibilityApiLevels = listOf(26, 31, 33, 35, 36)

fun CommonExtension<*, *, *, *, *, *>.configureManagedDevices() {
    providers.gradleProperty("lifetracing.compatibility.expectedApi").orNull?.let { expectedApi ->
        require(expectedApi.toInt() in compatibilityApiLevels)
        defaultConfig.testInstrumentationRunnerArguments["compatibilityExpectedApi"] = expectedApi
    }
    testOptions {
        managedDevices {
            allDevices {
                compatibilityApiLevels.forEach { api ->
                    create<ManagedVirtualDevice>("pixel2Api$api") {
                        device = "Pixel 2"
                        apiLevel = api
                        systemImageSource = "aosp"
                    }
                }
            }
        }
    }
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            configureManagedDevices()
        }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            configureManagedDevices()
        }
    }
}

project(":app") {
    compatibilityApiLevels.forEach { api ->
        tasks.matching { it.name == "pixel2Api${api}Setup" }.configureEach {
            // Both modules use the same named AVD; concurrent setup races for its single emulator.
            mustRunAfter(":data:pixel2Api${api}DebugAndroidTest")
        }
    }
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Generates human-readable Kover coverage reports for every module."
    dependsOn(
        ":domain:koverHtmlReport",
        ":domain:koverXmlReport",
        ":data:koverHtmlReport",
        ":data:koverXmlReport",
        ":app:koverHtmlReport",
        ":app:koverXmlReport",
    )
}

tasks.register("coverageVerify") {
    group = "verification"
    description = "Runs Kover's enforced domain coverage verification."
    dependsOn(":domain:koverVerify")
}
