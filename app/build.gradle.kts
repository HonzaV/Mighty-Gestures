import io.gitlab.arturbosch.detekt.getSupportedKotlinVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    jacoco
}

android {
    namespace = "cz.mightybities.mightygestures"
    compileSdk = 37

    defaultConfig {
        applicationId = "cz.mightybities.mightygestures"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // en-XA / ar-XB for long-text and RTL checks (docs/engineering/compose-ui.md); never in release.
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Keep the APK free of Google Play dependency metadata blobs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric's SDK 37 sandbox patches FileDescriptor via jdk.internal.access.SharedSecrets.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// detekt 1.23 embeds the Kotlin compiler it was built with; keep the project's newer Kotlin off its classpath.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(getSupportedKotlinVersion())
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    // Compose ui-test pulls Espresso 3.5, which injects input via InputManager.getInstance() (gone in SDK 37).
    testImplementation(libs.androidx.test.espresso.core)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// ---------------------------------------------------------------------------
// Code coverage (JaCoCo) for debug unit tests, gated at 75 % line coverage.
// ---------------------------------------------------------------------------
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        // Required so classes loaded by Robolectric's sandbox are recorded.
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val coverageExclusions =
    listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
    )

val coverageClassDirs =
    files(
        // AGP 9 built-in Kotlin output (no longer tmp/kotlin-classes).
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            exclude(coverageExclusions)
        },
    )
val coverageSourceDirs = files("src/main/java", "src/main/kotlin")
val coverageExecutionData =
    fileTree(layout.buildDirectory) {
        include("jacoco/testDebugUnitTest.exec", "outputs/unit_test_code_coverage/**/*.exec")
    }

// An empty class tree yields 0/0 lines, which JaCoCo does not treat as a violation: fail loudly instead.
fun JacocoReportBase.requireClasses() =
    doFirst {
        check(!classDirectories.asFileTree.isEmpty) { "No classes to measure — did the AGP class output path change?" }
    }

val jacocoTestReport =
    tasks.register<JacocoReport>("jacocoTestReport") {
        group = "verification"
        description = "Generates a JaCoCo coverage report for debug unit tests."
        dependsOn("testDebugUnitTest")
        classDirectories.setFrom(coverageClassDirs)
        sourceDirectories.setFrom(coverageSourceDirs)
        executionData.setFrom(coverageExecutionData)
        requireClasses()
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

val jacocoCoverageVerification =
    tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
        group = "verification"
        description = "Fails the build if debug unit test line coverage is below 75 %."
        dependsOn(jacocoTestReport)
        classDirectories.setFrom(coverageClassDirs)
        sourceDirectories.setFrom(coverageSourceDirs)
        executionData.setFrom(coverageExecutionData)
        requireClasses()
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.75".toBigDecimal()
                }
            }
        }
    }

tasks.named("check") { dependsOn(jacocoCoverageVerification) }
