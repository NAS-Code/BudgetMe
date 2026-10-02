plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.budgetme"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.budgetme"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") } // duplicated by the SDK's Apache jars
    sourceSets["main"].assets.directories.add("../config") // categories.json ships as the seed category set
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("com.anthropic:anthropic-java:2.34.0") // LLM tier for unrecognized merchants
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814") // real org.json for unit tests (Android's is a stub off-device)
}
