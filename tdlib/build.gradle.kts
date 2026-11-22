// Thin wrapper around TDLib's generated Java API (org.drinkless.tdlib) and its JNI libraries.
// The sources and .so files are not committed: run scripts/build-tdlib.sh, or download the
// "tdlib-*" artifacts from a CI run and run scripts/install-tdlib.sh.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.drinkless.tdlib"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}

val tdApi = file("src/main/java/org/drinkless/tdlib/TdApi.java")
tasks.named("preBuild") {
    doFirst {
        if (!tdApi.exists()) {
            throw GradleException(
                "TDLib is missing. Run scripts/build-tdlib.sh (or scripts/install-tdlib.sh <dir> with CI artifacts) first."
            )
        }
    }
}
