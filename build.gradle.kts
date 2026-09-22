plugins {
    // No kotlin.android alias — AGP 9 provides Kotlin support itself.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.protobuf) apply false
}
