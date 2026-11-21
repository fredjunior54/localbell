// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.versions)
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.detekt)
}

detekt {
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
    source.setFrom(
        "$rootDir/app/src/main/java",
        "$rootDir/app/src/test/java",
    )
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktfmt().kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.kts")
        targetExclude("**/build/**")
        ktfmt().kotlinlangStyle()
    }
}

val detektDesign by tasks.registering {
    group = "verification"
    description = "Run Detekt with the shared Android design rules."
    dependsOn(tasks.named("detekt"))
}

tasks.named("check") {
    dependsOn(detektDesign)
}
