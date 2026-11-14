/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

group = "com.openappslabs.jotter"
version = "2.5.0-linux.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.jetbrains.navigation.compose)

    testImplementation(kotlin("test"))
}

ksp {
    arg("room.generateKotlin", "true")
}

compose.desktop {
    application {
        mainClass = "com.openappslabs.jotter.MainKt"

        jvmArgs += listOf(
            "-Dapple.awt.application.appearance=system",
            "-Dfile.encoding=UTF-8"
        )

        nativeDistributions {
            // Arch/KDE: jpackage builds here usually ship without the deb/rpm
            // bundlers, so AppImage is the portable artifact. `packageDeb` also
            // works on Debian/Ubuntu machines that have dpkg tooling available.
            targetFormats(TargetFormat.AppImage)
            packageName = "jotter"
            packageVersion = "2.5.0"
            description = "Jotter notes app for Linux"
            vendor = "Open Apps Labs"

            // DataStore Preferences stores its file with protobuf-lite, which
            // uses sun.misc.Unsafe. Without `jdk.unsupported` in the jlinked
            // runtime the UI thread dies at startup ("Lite gencode is
            // primarily intended for Android use...") and the window renders
            // blank. java.sql/management are pulled in by Room/SQLite.
            // `java.net.http` is used by the sync client: without it the app
            // dies with NoClassDefFoundError on the UI thread at startup and
            // both windows show up empty. `jdk.crypto.ec` provides the SunEC
            // provider, needed for the TLS ciphers of an https server.
            modules(
                "jdk.unsupported",
                "java.sql",
                "java.naming",
                "java.management",
                "java.net.http",
                "jdk.crypto.ec"
            )

            linux {
                menuGroup = "Utility;TextTools;"
                appCategory = "Utility"
                iconFile.set(project.file("src/main/resources/icons/jotter.png"))
                shortcut = true
            }
        }
    }
}
