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

package com.openappslabs.jotter

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.openappslabs.jotter.data.model.AppTheme
import com.openappslabs.jotter.data.repository.UserPreferencesRepository
import com.openappslabs.jotter.navigation.AppNavHost
import com.openappslabs.jotter.ui.theme.JotterTheme
import com.openappslabs.jotter.utils.BiometricAuthUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val userPreferences by userPreferencesRepository.userPreferencesFlow.collectAsStateWithLifecycle(initialValue = null)
            val lifecycleOwner = LocalLifecycleOwner.current
            val systemInDarkTheme = isSystemInDarkTheme()

            val darkTheme = when (userPreferences?.appTheme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> systemInDarkTheme
                null -> systemInDarkTheme
            }
            
            var isAppAuthenticated by rememberSaveable { mutableStateOf(false) }
            var authTrigger by rememberSaveable { mutableIntStateOf(0) }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            if (!isChangingConfigurations) {
                                isAppAuthenticated = false
                            }
                        }
                        Lifecycle.Event.ON_START -> {
                            authTrigger++
                        }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            LaunchedEffect(userPreferences?.isAppLockEnabled) {
                if (userPreferences?.isAppLockEnabled == false) {
                    isAppAuthenticated = true
                }
            }

            LaunchedEffect(authTrigger, userPreferences == null) {
                if (userPreferences?.isAppLockEnabled == true && !isAppAuthenticated) {
                    BiometricAuthUtil.authenticate(
                        activity = this@MainActivity,
                        title = "Jotter Locked",
                        subtitle = "Authenticate To Open The App",
                        onSuccess = { isAppAuthenticated = true },
                        onError = { finish() }
                    )
                }
            }

            LaunchedEffect(userPreferences?.isSecureMode) {
                if (userPreferences?.isSecureMode == true) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            JotterTheme(
                isDarkTheme = darkTheme,
                isTrueBlackEnabled = userPreferences?.isTrueBlackEnabled ?: false,
                isDynamicColor = userPreferences?.isDynamicColor ?: true,
                isHapticEnabled = userPreferences?.isHapticEnabled ?: true
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavHost(navController = navController)
                }
            }
        }
    }
}
