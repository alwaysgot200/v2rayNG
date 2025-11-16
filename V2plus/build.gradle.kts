// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

buildscript {
    dependencies {
        classpath(libs.gradle.license.plugin)
    }
}

// 明确配置 Gradle Wrapper 版本与发行类型，帮助 IDE 使用正确的 8.13 Wrapper
tasks.named<org.gradle.api.tasks.wrapper.Wrapper>("wrapper") {
    gradleVersion = "8.13"
    distributionType = org.gradle.api.tasks.wrapper.Wrapper.DistributionType.BIN
}

