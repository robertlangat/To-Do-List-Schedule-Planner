import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Local, untracked configuration. See README "Configuration" and local.properties.example.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(name: String, default: String = ""): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: default).replace("\"", "\\\"")

// Release signing (Play upload key). Provided by CI secrets or local.properties; never committed.
val releaseStoreFile: String? = System.getenv("ANDROID_KEYSTORE_PATH") ?: localProps.getProperty("RELEASE_STORE_FILE")

android {
    namespace = "com.robertlangat.todoplanner"

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: localProps.getProperty("RELEASE_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: localProps.getProperty("RELEASE_KEY_PASSWORD") ?: storePassword
            }
        }
    }
    compileSdk = 36

    defaultConfig {
        applicationId = "com.robertlangat.todoplanner"
        minSdk = 26
        targetSdk = 36
        // CI passes VERSION_CODE / VERSION_NAME for Play uploads; every upload needs a higher code.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "AI_BACKEND_URL", "\"${cfg("AI_BACKEND_URL")}\"")
        buildConfigField("String", "AI_APP_TOKEN", "\"${cfg("AI_APP_TOKEN")}\"")

        // AdMob. The app ID is not secret (it ships in every APK).
        manifestPlaceholders["admobAppId"] = "ca-app-pub-8306955081407788~5196832723"
        buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-8306955081407788/1308277343\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"ca-app-pub-8306955081407788/1257587713\"")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Same applicationId as release and the Wear module so the Data Layer pairs them.
            // Debug builds show Google's official TEST ads so testing can never count as invalid
            // traffic on the real AdMob account. Build with -PrealAds=true to use the real units.
            if (!(project.findProperty("realAds")?.toString()?.toBoolean() ?: false)) {
                buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-3940256099942544/9214589741\"")
                buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Report everything; fail on errors. Reports land in app/build/reports/lint-results-*.html
        abortOnError = true
        checkDependencies = true
        disable += "GradleDependency" // version bumps are tracked manually in libs.versions.toml
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.play.services.auth)
    implementation(libs.play.services.wearable)
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
