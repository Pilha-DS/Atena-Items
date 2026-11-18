/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Docs
 *
 * Atena Docs is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
}

group = "com.openappslabs.docs"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)

    testImplementation(kotlin("test"))
}

ksp {
    arg("room.generateKotlin", "true")
}

compose.desktop {
    application {
        mainClass = "com.openappslabs.docs.MainKt"

        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        nativeDistributions {
            // Arch/KDE: jpackage here usually ships without the deb/rpm
            // bundlers, so AppImage is the portable artifact.
            targetFormats(TargetFormat.AppImage)
            packageName = "docs"
            packageVersion = "0.1.0"
            description = "Atena Docs for Linux"
            vendor = "Open Apps Labs"

            // Link the whole JDK instead of only the modules jdeps guesses. The
            // image is bigger, but a missed module (java.net.http was one) kills
            // the installed app while `./gradlew :app:run` keeps working on the
            // build JDK. Trim it only after testing the installed app image.
            includeAllModules = true
            linux {
                menuGroup = "Office;"
                appCategory = "Office"
                iconFile.set(project.file("src/main/resources/icons/docs.png"))
                shortcut = true
            }
        }
    }
}
