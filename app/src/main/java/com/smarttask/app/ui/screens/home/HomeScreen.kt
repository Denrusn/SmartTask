package com.smarttask.app.ui.screens.home

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smarttask.app.receiver.AlarmReceiver
import com.smarttask.app.ui.theme.SmartTaskTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ForceAlarmActivity : ComponentActivity() {

    private var reminderTitle: String = ""
    private var reminderContent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reminderTitle = intent.getStringExtra("title") ?: "提醒"
        reminderContent = intent.getStringExtra("content") ?: ""

        playAlarm()
        showForceAlarmScreen()
    }

    private fun playAlarm() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 500, 500, 500, 500, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }

    private fun showForceAlarmScreen() {
        setContent {
            SmartTaskTheme {
                ForceAlarmScreen(
                    title = reminderTitle,
                    content = reminderContent,
                    onConfirm = {
                        stopAlarm()
                        finish()
                    },
                    onSnooze = {
                        stopAlarm()
                        snoozeReminder()
                        finish()
                    }
                )
            }
        }
    }

    private fun stopAlarm() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.cancel()
    }

    private fun snoozeReminder() {
        // 5分钟后再次提醒
        val snoozeIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = "com.smarttask.SNOOZE"
            putExtra("title", reminderTitle)
            putExtra("content", reminderContent)
        }
        sendBroadcast(snoozeIntent)
    }

    override fun onBackPressed() {
        // 禁用返回键
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForceAlarmScreen(
    title: String,
    content: String,
    onConfirm: () -> Unit,
    onSnooze: () -> Unit
) {
    var pressProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFF1744))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Color.White
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = title,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = content,
                fontSize = 20.sp,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(64.dp))

            // 长按确认按钮
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            Color.White.copy(
                                alpha = 0.3f + (pressProgress * 0.7f)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (pressProgress > 0) "${(pressProgress * 100).toInt()}%" else "按住确认",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "长按按钮2秒以上确认",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            TextButton(onClick = onSnooze) {
                Text(
                    text = "稍后提醒（5分钟后）",
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }
    }

    // 模拟长按进度
    LaunchedEffect(Unit) {
        while (pressProgress < 1f) {
            delay(40) // 2秒 = 2000ms, 每次增加 0.02
            pressProgress += 0.02f
            if (pressProgress >= 1f) {
                onConfirm()
                break
            }
        }
    }
}
