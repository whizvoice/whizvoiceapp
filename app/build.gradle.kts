plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)

    // Apply kotlin-kapt plugin explicitly
    id("kotlin-kapt")

    // Apply Hilt and KSP with explicit versions for better resolution
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.whiz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.whiz"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.example.whiz.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // Add Room schema location and KSP args
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
            arg("room.generateKotlin", "true")
        }
    }

    buildToolsVersion = "35.0.0"

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    // Configure test options for Android framework mocking
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    
    // Lint configuration for faster CI builds
    lint {
        // Disable slow/expensive checks for CI
        disable += setOf(
            "UnusedResources",           // Very slow on large projects
            "IconMissingDensityFolder",  // Slow and often not critical
            "GoogleAppIndexingWarning",  // Not needed for most apps
            "TypographyFractions",       // Slow typography check
            "TypographyDashes",          // Slow typography check
            "Typos",                     // Very slow spell checking
            "ContentDescription",        // Can be slow, handle separately
            "LintBaseline",              // Baseline processing can be slow
            "UnusedIds"                  // Can be slow on large layouts
        )
        
        // Performance optimizations
        checkReleaseBuilds = false       // Only check debug builds in CI
        abortOnError = false            // Don't fail build on lint errors
        checkDependencies = false       // Don't lint dependencies
        ignoreTestSources = true        // Skip test sources for speed
        
        // Limit scope for faster execution
        checkGeneratedSources = false   // Skip generated code
        
        // Output options
        htmlReport = false              // Disable HTML report generation
        xmlReport = true                // Keep XML for CI tools
        textReport = false              // Disable text report
    }
    
    // Add test options to handle Android framework mocking
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Compose dependencies
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Room database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt dependency injection
    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // UI & System
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.36.0")
    implementation("com.google.accompanist:accompanist-swiperefresh:0.36.0")

    // Data & Storage
    implementation("androidx.datastore:datastore-preferences:1.1.5")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Authentication
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    // JWT token processing
    implementation("io.jsonwebtoken:jjwt-api:0.12.3")
    implementation("io.jsonwebtoken:jjwt-impl:0.12.3")
    implementation("io.jsonwebtoken:jjwt-jackson:0.12.3")

    // Basic testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    
    // Unit Testing
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("com.google.truth:truth:1.1.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    
    // Hilt Testing
    testImplementation("com.google.dagger:hilt-android-testing:2.56")
    kaptTest("com.google.dagger:hilt-android-compiler:2.56")
    
    // Instrumented Testing
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.8")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.56")
    androidTestImplementation("androidx.navigation:navigation-testing:2.8.5")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.google.code.gson:gson:2.8.5")
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:2.56")
}

// Allow references to generated code
kapt {
    correctErrorTypes = true
    arguments {
        arg("dagger.hilt.disableModulesHaveInstallInCheck", "true")
    }
}