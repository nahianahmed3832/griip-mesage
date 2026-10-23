package com.meldiq.android.ui.screens.onboarding

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.ui.AppLanguage
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes

/**
 * The app's language, chosen before signing in or up (the owner, 2026-10-23): a globe and the current language's own
 * name, opening the same choices as Settings › Language. The screen restarts in the new language; what was typed stays
 * (the account flow's state lives in its view model).
 */
@Composable
fun LanguageButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = Griip.colors
    val current = AppLanguage.current(ctx)
    val systemLabel = stringResource(R.string.lang_system)
    fun label(tag: String) = if (tag == "system") systemLabel else AppLanguage.nativeName(tag)
    var open by remember { mutableStateOf(false) }
    val title = stringResource(R.string.settings_language)
    Box(modifier) {
        Row(
            Modifier.heightIn(min = 48.dp).clip(Shapes.pill).pressable({ open = true }, onClickLabel = title).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Language, title, tint = c.textSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(label(current), style = Griip.type.bodySmall, color = c.textSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppLanguage.SUPPORTED.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(label(tag)) },
                    trailingIcon = { if (tag == current) Icon(Icons.Rounded.Check, null, tint = c.primary) },
                    onClick = {
                        open = false
                        if (tag != current) (ctx as? Activity)?.let { AppLanguage.apply(it, tag) }
                    },
                )
            }
        }
    }
}
