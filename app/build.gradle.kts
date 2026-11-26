plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "hammer.mobile"
    compileSdk = 34
    defaultConfig { applicationId = "hammer.mobile"; minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "0.2" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-ktx:1.9.2")
}
