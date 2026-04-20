package com.smarttask.app.ui.screens.log

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smarttask.app.util.LogEntry
import com.smarttask.app.util.LogcatManager
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(LogcatManager.getLogs()) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // 实时刷新日志
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            logs = LogcatManager.getLogs()
            if (autoScroll && logs.isNotEmpty()) {
                listState.animateScrollToItem(logs.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实时日志") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Text(
                            text = if (autoScroll) "🔒" else "🔓",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    IconButton(onClick = { LogcatManager.clear(); logs = emptyList() }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空")
                    }
                    IconButton(onClick = {
                        val logText = logs.joinToString("\n") {
                            "${it.formattedTime} ${it.level}/${it.tag}: ${it.message}"
                        }
                        val sendIntent = android.content.Intent().apply {
                            action = android.content.Intent.ACTION_SEND
                            putExtra(android.content.Intent.EXTRA_TEXT, logText)
                            type = "text/plain"
                        }
                        context.startActivity(android.content.Intent.createChooser(sendIntent, "分享日志"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "分享")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 错误日志计数
            val errorCount = logs.count { it.level == "E" }
            val warnCount = logs.count { it.level == "W" }
            if (errorCount > 0 || warnCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (errorCount > 0) {
                        Text(
                            text = "❌ $errorCount 错误",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (warnCount > 0) {
                        Text(
                            text = "⚠️ $warnCount 警告",
                            color = Color(0xFFFF9800),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "共 ${logs.size} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(logs, key = { "${it.timestamp}-${it.tag}-${it.message.hashCode()}" }) { log ->
                    LogItem(log)
                }
            }
        }
    }
}

@Composable
private fun LogItem(log: LogEntry) {
    val backgroundColor = when (log.level) {
        "E" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        "W" -> Color(0xFFFF9800).copy(alpha = 0.1f)
        "D" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    val textColor = when (log.level) {
        "E" -> MaterialTheme.colorScheme.error
        "W" -> Color(0xFFFF9800)
        "D" -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = log.formattedTime,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )

        Text(
            text = "${log.level}/${log.tag}",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier.width(100.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = log.message,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            maxLines = 1
        )
    }
}