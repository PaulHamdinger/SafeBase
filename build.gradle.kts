//==============================================================================
// build.gradle.kts - SafeBase
//==============================================================================
//
// Toolchain : JDK 26 (locally installed at /usr/local/bin/jdk-26).
// Bytecode  : --release 21 (the NetherCore server runs on Java 21).
// Paper API : paperweight-userdev with paperDevBundle pinned to the exact
//             Minecraft version the server runs (1.21.11-R0.1-SNAPSHOT).
//
//==============================================================================


//= Plugins ====================================================================

plugins {
    `java-library`
    id( "io.papermc.paperweight.userdev" ) version "2.0.0-beta.21"
}


//= Project metadata ===========================================================

group       = "com.nethercore.safebase"
version     = "5.0.0"
description = "NetherCore SafeBase - Player base protection"


//= Java toolchain & compile target ============================================
//
// Toolchain : compile / run with JDK 26 (what we have installed).
// Release   : emit Java 21 bytecode so the JAR loads on the server.
//
//==============================================================================

java {
    toolchain.languageVersion = JavaLanguageVersion.of( 26 )
}

tasks.compileJava {
    options.release  = 21
    options.encoding = "UTF-8"
}

tasks.javadoc {
    options.encoding = "UTF-8"
}


//= Dependencies ===============================================================

dependencies {
    paperweight.paperDevBundle( "1.21.11-R0.1-SNAPSHOT" )
}
