package com.smarttask.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.smarttask.app.ui.SmartTaskNavHost
import com.smarttask.app.ui.screens.permission.PermissionScreen
import com.smarttask.app.ui.theme.SmartTaskTheme
import com.smarttask.app.util.PermissionUtils

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmartTaskTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showPermissionCheck by remember { mutableStateOf(true) }
                    var hasAllPermissions by remember { mutableStateOf(PermissionUtils.hasAllCriticalPermissions(this)) }

                    LaunchedEffect(Unit) {
                        hasAllPermissions = PermissionUtils.hasAllCriticalPermissions(this@MainActivity)
                        showPermissionCheck = !hasAllPermissions
                    }

                    if (showPermissionCheck && !hasAllPermissions) {
                        PermissionScreen(
                            onAllGranted = {
                                hasAllPermissions = PermissionUtils.hasAllCriticalPermissions(this@MainActivity)
                                if (hasAllPermissions) {
                                    showPermissionCheck = false
                                }
                            }
                        )
                    } else {
                        SmartTaskNavHost()
                    }
                }
            }
        }
    }
}