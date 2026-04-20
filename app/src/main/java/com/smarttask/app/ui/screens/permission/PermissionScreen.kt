package com.smarttask.app.ui.screens.permission

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smarttask.app.util.PermissionInfo
import com.smarttask.app.util.PermissionUtils

@Composable
fun PermissionScreen(
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    var permissions by remember { mutableStateOf(PermissionUtils.getRequiredPermissions(context)) }

    // 刷新权限状态
    LaunchedEffect(Unit) {
        permissions = PermissionUtils.getRequiredPermissions(context)
    }

    val allGranted = permissions.all { it.isGranted }
    val grantedCount = permissions.count { it.isGranted }

    // 当所有权限都已授予时，自动触发完成回调
    LaunchedEffect(allGranted) {
        if (allGranted) {
            onAllGranted()
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // 标题
            Text(
                text = "权限设置",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 进度说明
            Text(
                text = "已授予 $grantedCount / ${permissions.size} 项权限",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (allGranted) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "所有权限已授予，提醒功能已就绪！",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 权限列表
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(permissions) { permission ->
                    PermissionCard(
                        permission = permission,
                        onRequestPermission = {
                            permission.action?.invoke(context)
                            // 延迟刷新权限状态
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                permissions = PermissionUtils.getRequiredPermissions(context)
                            }, 500)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 完成按钮
            Button(
                onClick = onAllGranted,
                modifier = Modifier.fillMaxWidth(),
                enabled = allGranted
            ) {
                Text(if (allGranted) "开始使用" else "请授予所有权限")
            }

            if (!allGranted) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点击每个权限项进行授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    permission: PermissionInfo,
    onRequestPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (permission.isGranted)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Icon(
                imageVector = if (permission.isGranted)
                    Icons.Default.CheckCircle
                else
                    Icons.Default.Warning,
                contentDescription = null,
                tint = if (permission.isGranted)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            // 权限信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = permission.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = permission.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 操作按钮
            if (!permission.isGranted && permission.action != null) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("授权")
                }
            }
        }
    }
}