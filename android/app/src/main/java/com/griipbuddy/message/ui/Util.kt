package com.griipbuddy.message.ui

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.griipbuddy.message.data.api.ApiException
import com.griipbuddy.message.di.AppContainer
import java.util.Calendar
import java.util.Date

/** ViewModel bound to the current container, keyed so a recreated container gets a fresh instance. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = LocalContainer.current
    return viewModel(key = (key ?: VM::class.java.name) + "@" + System.identityHashCode(c), factory = viewModelFactory { initializer { create(c) } })
}

fun Throwable.friendly(): String = when (this) {
    is ApiException -> message
    else -> "Something went wrong. Please try again."
}

@Composable
fun rememberToast(): (String) -> Unit {
    val ctx = LocalContext.current
    return { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
}

object TimeFmt {
    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }

    fun clock(t: Long, use24: Boolean = false): String {
        val c = cal(t)
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        return if (use24) "%02d:%02d".format(h, m) else "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
    }

    fun sameDay(a: Long, b: Long): Boolean {
        val x = cal(a)
        val y = cal(b)
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }

    /** Conversation-list style: time today, weekday this week, else short date. */
    fun listStamp(t: Long): String {
        if (t <= 0) return ""
        val now = System.currentTimeMillis()
        return when {
            sameDay(t, now) -> clock(t)
            sameDay(t, now - 86_400_000L) -> "Yesterday"
            now - t < 6 * 86_400_000L -> DateFormat.format("EEE", Date(t)).toString()
            else -> DateFormat.format("d MMM", Date(t)).toString()
        }
    }

    fun dayHeader(t: Long): String {
        val now = System.currentTimeMillis()
        return when {
            sameDay(t, now) -> "Today"
            sameDay(t, now - 86_400_000L) -> "Yesterday"
            else -> DateFormat.format("EEEE, d MMMM", Date(t)).toString()
        }
    }

    fun relative(t: Long?): String {
        if (t == null || t <= 0) return "a while ago"
        val d = (System.currentTimeMillis() - t) / 1000
        return when {
            d < 60 -> "just now"
            d < 3600 -> "${d / 60} min ago"
            d < 86400 -> "${d / 3600} h ago"
            else -> "${d / 86400} d ago"
        }
    }

    fun duration(sec: Long): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

    fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Hello"
    }
}
