package com.andrej.glasstv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Current time in millis, updated on every minute boundary (TIME_TICK) and on clock changes. */
@Composable
fun rememberMinute(): State<Long> {
    val context = LocalContext.current
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                now.longValue = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    // Backup for devices that throttle TIME_TICK to background receivers.
    LaunchedEffect(Unit) {
        while (true) {
            val t = System.currentTimeMillis()
            now.longValue = t
            delay(60_000 - t % 60_000 + 50)
        }
    }
    return now
}

fun formatTime(context: Context, millis: Long): String =
    SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(millis))

fun formatDate(strings: Strings, millis: Long): String =
    SimpleDateFormat(strings.datePattern, strings.locale).format(Date(millis))
        .replaceFirstChar { it.titlecase(strings.locale) }
