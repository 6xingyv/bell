// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mocharealm.bell.R
import com.mocharealm.bell.ui.composables.BellButton
import com.mocharealm.bell.ui.composables.BellPage
import com.mocharealm.bell.ui.composables.BellTheme
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    private var localNetworkGranted by mutableStateOf(false)
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            localNetworkGranted =
                Build.VERSION.SDK_INT < 37 ||
                    checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") ==
                        PackageManager.PERMISSION_GRANTED
        }

    override fun onStop() {
        if (localNetworkGranted) GlobalContext.getOrNull()?.get<BellStore>()?.persistPlaybackNow()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        io.github.neoruaa.glasmio.glass.render.configureGlassDynamicRange(window, false)
        localNetworkGranted =
            Build.VERSION.SDK_INT < 37 ||
                checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") ==
                    PackageManager.PERMISSION_GRANTED
        val requested = buildList {
            if (!localNetworkGranted) add("android.permission.ACCESS_LOCAL_NETWORK")
            if (
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            )
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (requested.isNotEmpty()) permissions.launch(requested.toTypedArray())
        setContent {
            if (localNetworkGranted) BellApp(GlobalContext.get().get())
            else
                BellTheme {
                    BellPage(getString(R.string.network_permission_title)) {
                        item {
                            Text(
                                getString(R.string.network_permission_description),
                                Modifier.padding(16.dp),
                            )
                        }
                        item {
                            BellButton(
                                getString(R.string.grant_network_permission),
                                {
                                    permissions.launch(
                                        arrayOf("android.permission.ACCESS_LOCAL_NETWORK")
                                    )
                                },
                            )
                        }
                    }
                }
        }
    }
}
