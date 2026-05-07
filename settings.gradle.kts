//==============================================================================
// settings.gradle.kts - SafeBase
//==============================================================================
//
// foojay-resolver-convention lets Gradle's toolchain machinery find / download
// JDKs from Foojay if we ever need a version that isn't on PATH. We pin the
// local JDK 26 via gradle.properties, so Foojay typically does not have to do
// anything ; it stays as a fallback.
//
//==============================================================================

plugins {
    id( "org.gradle.toolchains.foojay-resolver-convention" ) version "1.0.0"
}

rootProject.name = "safebase"
