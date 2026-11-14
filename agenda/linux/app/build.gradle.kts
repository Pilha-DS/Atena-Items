/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.openappslabs.agenda"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)

    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.openappslabs.agenda.MainKt"

        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dapple.awt.application.appearance=system",
        )

        nativeDistributions {
            // Same choice as the notes port: AppImage is the portable artifact
            // on Arch/KDE, where jpackage has no deb/rpm bundler.
            targetFormats(TargetFormat.AppImage)
            packageName = "agenda"
            packageVersion = "0.1.0"
            description = "Agenda for Linux (KDE Plasma 6 widget included)"
            vendor = "Open Apps Labs"

            // JDBC needs java.sql, the hub client needs java.net.http, and
            // JDK crypto providers have to be present for TLS to a https hub.
            modules(
                "java.sql",
                "java.naming",
                "java.management",
                "java.net.http",
                "jdk.crypto.ec"
            )

            linux {
                menuGroup = "Office;Calendar;"
                appCategory = "Office"
                iconFile.set(project.file("src/main/resources/icons/agenda.png"))
                shortcut = true
            }
        }
    }
}
