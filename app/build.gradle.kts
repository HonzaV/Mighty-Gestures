plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

android {
    namespace = "io.github.honzav.mightygestures"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.honzav.mightygestures"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
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
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// ---------------------------------------------------------------------------
// Google-free guard: fail the build if Play Services / Firebase sneak in.
// ---------------------------------------------------------------------------
val forbiddenDependencyGroups = listOf(
    "com.google.android.gms",
    "com.google.firebase",
    "com.google.android.play",
)

val verifyNoGoogleServices by tasks.registering {
    group = "verification"
    description = "Fails if any Google Play Services / Firebase artifact is on a release classpath."
    val classpath = configurations.named("releaseRuntimeClasspath")
    doLast {
        val offenders = classpath.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { module -> forbiddenDependencyGroups.any { module.group.startsWith(it) } }
            .map { "${it.group}:${it.name}:${it.version}" }
        if (offenders.isNotEmpty()) {
            throw GradleException("Forbidden Google dependencies found:\n" + offenders.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn(verifyNoGoogleServices) }

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

val coverageExclusions = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/*Test*.*",
)

val coverageClassDirs = files(
    fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) { exclude(coverageExclusions) },
    fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
        exclude(coverageExclusions)
    },
)
val coverageSourceDirs = files("src/main/java", "src/main/kotlin")
val coverageExecutionData = fileTree(layout.buildDirectory) {
    include("jacoco/testDebugUnitTest.exec", "outputs/unit_test_code_coverage/**/*.exec")
}

val jacocoTestReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Generates a JaCoCo coverage report for debug unit tests."
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(coverageClassDirs)
    sourceDirectories.setFrom(coverageSourceDirs)
    executionData.setFrom(coverageExecutionData)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val jacocoCoverageVerification by tasks.registering(JacocoCoverageVerification::class) {
    group = "verification"
    description = "Fails the build if debug unit test line coverage is below 75 %."
    dependsOn(jacocoTestReport)
    classDirectories.setFrom(coverageClassDirs)
    sourceDirectories.setFrom(coverageSourceDirs)
    executionData.setFrom(coverageExecutionData)
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
