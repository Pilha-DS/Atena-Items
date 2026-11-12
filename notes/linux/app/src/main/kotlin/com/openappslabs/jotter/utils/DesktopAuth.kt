/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.jotter.utils

import com.openappslabs.jotter.data.model.BiometricAuthType
import com.openappslabs.jotter.platform.PinStore

/**
 * Desktop counterpart of the Android biometric support check. A PIN is the
 * "device credential" here; there is no fingerprint sensor abstraction yet.
 */
data class AuthSupport(
    val hasFingerprint: Boolean,
    val hasDeviceCredential: Boolean,
    val availableType: BiometricAuthType
)

object BiometricAuthUtil {

    fun getAuthenticationSupport(): AuthSupport {
        val hasPin = PinStore.hasPin()
        return AuthSupport(
            hasFingerprint = false,
            hasDeviceCredential = hasPin,
            availableType = if (hasPin) BiometricAuthType.DEVICE_CREDENTIAL else BiometricAuthType.NONE
        )
    }

    fun isBiometricAvailable(): Boolean = PinStore.hasPin()
}
