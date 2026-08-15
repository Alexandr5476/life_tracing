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

tasks.register("coverageReport") {
    group = "verification"
    description = "Generates human-readable Kover coverage reports for every module."
    dependsOn(":domain:koverHtmlReport", ":domain:koverXmlReport", ":data:koverHtmlReport", ":app:koverHtmlReport")
}

tasks.register("coverageVerify") {
    group = "verification"
    description = "Runs the coverage-report pipeline; module thresholds can be added to Kover later."
    dependsOn("coverageReport")
}
