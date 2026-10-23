package com.meldiq.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.meldiq.android.data.repo.HourFormat
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.di.AppContainer
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    /**
     * Batch B12: the app's language (wrapped like the activity, see [AppLanguage]) and the phone's 12/24-hour choice.
     * Set by MainActivity; null before that and in JVM tests, where the English words and a 12-hour clock are used.
     */
    @Volatile private var res: android.content.Context? = null

    fun init(context: android.content.Context) { res = context }

    private fun word(id: Int, english: String, vararg args: Any): String = res?.getString(id, *args) ?: english.format(*args)

    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }

    /** Snapshot state, so times on screen redraw when the setting changes. Set from the settings in AppRoot. */
    var hourFormat: HourFormat by androidx.compose.runtime.mutableStateOf(HourFormat.AUTO)

    /** The time of day in the chosen format ([hourFormat]; the phone's when automatic), or 24-hour when [use24]. */
    fun clock(t: Long, use24: Boolean = false): String {
        val format = if (use24) HourFormat.H24 else hourFormat
        val r = res
        if (format == HourFormat.AUTO && r != null) return DateFormat.getTimeFormat(r).format(Date(t))
        val c = cal(t)
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        if (format == HourFormat.H24) return "%02d:%02d".format(h, m)
        if (r != null) return java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
        return "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
    }

    fun sameDay(a: Long, b: Long): Boolean {
        val x = cal(a)
        val y = cal(b)
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }

    private fun yesterday() = word(com.meldiq.android.R.string.b12_yesterday, "Yesterday")

    /** Conversation-list style: time today, weekday this week, else short date. */
    fun listStamp(t: Long): String {
        if (t <= 0) return ""
        val now = System.currentTimeMillis()
        return when {
            sameDay(t, now) -> clock(t)
            sameDay(t, now - 86_400_000L) -> yesterday()
            now - t < 6 * 86_400_000L -> DateFormat.format("EEE", Date(t)).toString()
            else -> DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"), Date(t)).toString()
        }
    }

    fun dayHeader(t: Long): String {
        val now = System.currentTimeMillis()
        return when {
            sameDay(t, now) -> word(com.meldiq.android.R.string.b12_today, "Today")
            sameDay(t, now - 86_400_000L) -> yesterday()
            else -> DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Date(t)).toString()
        }
    }

    /** How long ago, in whole units: under a minute, minutes, hours or days (pure; unit-tested). */
    enum class Ago { NOW, MINUTES, HOURS, DAYS }

    fun ago(seconds: Long): Pair<Ago, Long> = when {
        seconds < 60 -> Ago.NOW to 0
        seconds < 3600 -> Ago.MINUTES to seconds / 60
        seconds < 86400 -> Ago.HOURS to seconds / 3600
        else -> Ago.DAYS to seconds / 86400
    }

    fun relative(t: Long?): String {
        if (t == null || t <= 0) return word(com.meldiq.android.R.string.b12_while_ago, "a while ago")
        val (unit, n) = ago((System.currentTimeMillis() - t) / 1000)
        return when (unit) {
            Ago.NOW -> word(com.meldiq.android.R.string.b12_just_now, "just now")
            Ago.MINUTES -> word(com.meldiq.android.R.string.b12_min_ago, "%1${'$'}d min ago", n)
            Ago.HOURS -> word(com.meldiq.android.R.string.b12_h_ago, "%1${'$'}d h ago", n)
            Ago.DAYS -> word(com.meldiq.android.R.string.b12_d_ago, "%1${'$'}d d ago", n)
        }
    }

    fun duration(sec: Long): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

    fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> word(com.meldiq.android.R.string.b12_morning, "Good morning")
        in 12..16 -> word(com.meldiq.android.R.string.b12_afternoon, "Good afternoon")
        in 17..21 -> word(com.meldiq.android.R.string.b12_evening, "Good evening")
        else -> word(com.meldiq.android.R.string.b12_hello, "Hello")
    }
}
