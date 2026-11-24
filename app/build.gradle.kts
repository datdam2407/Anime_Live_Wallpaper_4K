import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Properties

val envProperties = Properties().apply {
  val envFile = rootProject.file(".env")
  if (envFile.exists()) {
    envFile.inputStream().use { load(it) }
  }
}

fun readEnvConfig(key: String): String? {
  return envProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    ?: System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
}

val testAdMobAppId = "ca-app-pub-3940256099942544~3347511713"
val testAdMobBannerId = "ca-app-pub-3940256099942544/9214589741"
val testAdMobInterstitialId = "ca-app-pub-3940256099942544/1033173712"
val testAdMobNativeId = "ca-app-pub-3940256099942544/2247696110"

val useProductionAdMob = readEnvConfig("ADMOB_USE_PRODUCTION_IDS").equals("true", ignoreCase = true)
val releaseAdMobAppId = if (useProductionAdMob) {
  readEnvConfig("ADMOB_PROD_APP_ID") ?: testAdMobAppId
} else {
  testAdMobAppId
}
val releaseAdMobBannerId = if (useProductionAdMob) {
  readEnvConfig("ADMOB_PROD_BANNER_AD_UNIT_ID") ?: testAdMobBannerId
} else {
  testAdMobBannerId
}
val releaseAdMobInterstitialId = if (useProductionAdMob) {
  readEnvConfig("ADMOB_PROD_INTERSTITIAL_AD_UNIT_ID") ?: testAdMobInterstitialId
} else {
  testAdMobInterstitialId
}
val releaseAdMobNativeId = if (useProductionAdMob) {
  readEnvConfig("ADMOB_PROD_NATIVE_AD_UNIT_ID") ?: testAdMobNativeId
} else {
  testAdMobNativeId
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.kuroanimelivewallpapers.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.kuroanimelivewallpapers.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 5
    versionName = "2.0.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      manifestPlaceholders["ADMOB_APP_ID"] = releaseAdMobAppId
      buildConfigField("String", "ADMOB_APP_ID", "\"$releaseAdMobAppId\"")
      buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID", "\"$releaseAdMobBannerId\"")
      buildConfigField("String", "ADMOB_INTERSTITIAL_AD_UNIT_ID", "\"$releaseAdMobInterstitialId\"")
      buildConfigField("String", "ADMOB_NATIVE_AD_UNIT_ID", "\"$releaseAdMobNativeId\"")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      manifestPlaceholders["ADMOB_APP_ID"] = testAdMobAppId
      buildConfigField("String", "ADMOB_APP_ID", "\"$testAdMobAppId\"")
      buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID", "\"$testAdMobBannerId\"")
      buildConfigField("String", "ADMOB_INTERSTITIAL_AD_UNIT_ID", "\"$testAdMobInterstitialId\"")
      buildConfigField("String", "ADMOB_NATIVE_AD_UNIT_ID", "\"$testAdMobNativeId\"")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
    //  signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
  ignoreList.add("ADMOB_.*")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.coil.video)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.effect)
  implementation(libs.androidx.media3.ui)
  implementation(libs.play.services.ads)
  implementation(libs.user.messaging.platform)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
