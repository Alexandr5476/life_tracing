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

fun CommonExtension<*, *, *, *, *, *>.configureManagedDevice() {
    testOptions {
        managedDevices {
            allDevices {
                create<ManagedVirtualDevice>("pixel2Api35") {
                    device = "Pixel 2"
                    apiLevel = 35
                    systemImageSource = "aosp"
                }
            }
        }
    }
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            configureManagedDevice()
        }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            configureManagedDevice()
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
