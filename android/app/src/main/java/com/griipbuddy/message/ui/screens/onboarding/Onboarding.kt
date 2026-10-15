package com.griipbuddy.message.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.griipbuddy.message.GriipApp
import com.griipbuddy.message.data.repo.PendingPairing
import com.griipbuddy.message.ui.LocalContainer
import com.griipbuddy.message.ui.art.HeroBubbleArt
import com.griipbuddy.message.ui.components.Avatar
import com.griipbuddy.message.ui.components.BannerKind
import com.griipbuddy.message.ui.components.ButtonTone
import com.griipbuddy.message.ui.components.CircleIconButton
import com.griipbuddy.message.ui.components.EmptyState
import com.griipbuddy.message.ui.components.GlassCard
import com.griipbuddy.message.ui.components.GlassSheet
import com.griipbuddy.message.ui.components.GriipTextField
import com.griipbuddy.message.ui.components.GriipTopBar
import com.griipbuddy.message.ui.components.IconTile
import com.griipbuddy.message.ui.components.PageDots
import com.griipbuddy.message.ui.components.PillButton
import com.griipbuddy.message.ui.components.StatusBanner
import com.griipbuddy.message.ui.components.pressable
import com.griipbuddy.message.ui.friendly
import com.griipbuddy.message.ui.theme.AvatarGradients
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes
import com.griipbuddy.message.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Step { Welcome, Create, Restore, Link }

@Composable
fun OnboardingFlow(pendingInvite: String?) {
    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    var showServer by remember { mutableStateOf(false) }
    AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "onboarding") { s ->
        when (s) {
            Step.Welcome -> WelcomeScreen(onStart = { step = Step.Create }, onRestore = { step = Step.Restore }, onLink = { step = Step.Link }, onServer = { showServer = true })
            Step.Create -> CreateIdentityScreen(onBack = { step = Step.Welcome }, onServer = { showServer = true })
            Step.Restore -> RestoreScreen(onBack = { step = Step.Welcome })
            Step.Link -> LinkDeviceScreen(onBack = { step = Step.Welcome })
        }
    }
    if (showServer) ServerSheet { showServer = false }
}

private data class Slide(val title: String, val accent: String, val body: String)

@Composable
private fun WelcomeScreen(onStart: () -> Unit, onRestore: () -> Unit, onLink: () -> Unit, onServer: () -> Unit) {
    val c = Griip.colors
    val slides = listOf(
        Slide("Talk freely.", "Work together.", "Private messages, team channels, calls and screen sharing — built for GriipBuddy teams."),
        Slide("Your keys stay", "on your phone.", "Messages are encrypted on your device before they leave it. History lives on your phone, not on our server."),
        Slide("Branches, teams", "and channels.", "Find your colleagues, join your branch and keep every team conversation in one calm place."),
    )
    val pager = rememberPagerState { slides.size }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            CircleIconButton(Icons.Outlined.Dns, "Server settings", onServer)
        }
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center) {
                HeroBubbleArt(Modifier.fillMaxWidth().aspectRatio(1.05f))
                Spacer(Modifier.height(8.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.textPrimary)) { append(slides[i].title + "\n") }
                        withStyle(SpanStyle(color = c.primaryDeep)) { append(slides[i].accent) }
                    },
                    style = Griip.type.display,
                )
                Spacer(Modifier.height(12.dp))
                Text(slides[i].body, style = Griip.type.body, color = c.textSecondary)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PillButton("Get started", onStart, Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Already have an account? ", style = Griip.type.bodySmall, color = c.textSecondary)
                Text("Restore", style = Griip.type.bodySmall.copy(fontWeight = FontWeight.Bold), color = c.primaryDeep, modifier = Modifier.clip(Shapes.pill).pressable(onRestore).padding(4.dp))
                Text(" · ", style = Griip.type.bodySmall, color = c.textTertiary)
                Text("Link device", style = Griip.type.bodySmall.copy(fontWeight = FontWeight.Bold), color = c.primaryDeep, modifier = Modifier.clip(Shapes.pill).pressable(onLink).padding(4.dp))
            }
            Spacer(Modifier.height(14.dp))
            PageDots(slides.size, pager.currentPage)
        }
    }
}

@Composable
private fun CreateIdentityScreen(onBack: () -> Unit, onServer: () -> Unit) {
    val c = Griip.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val server by container.settings.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableIntStateOf((0..7).random()) }
    var availability by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(name) {
        if (username.isEmpty() || username == suggest(name.dropLast(1))) username = suggest(name)
    }
    LaunchedEffect(username, server.serverUrl) {
        available = null
        availability = null
        if (username.length < 3) return@LaunchedEffect
        delay(400)
        runCatching { container.session.usernameAvailable(server.serverUrl, username) }
            .onSuccess { available = it.available; availability = if (it.available) "@${it.username} is available" else it.reason ?: "That username is taken" }
            .onFailure { availability = "Can't reach the server to check"; available = null }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        GriipTopBar("Create your identity", onBack = onBack, actions = { CircleIconButton(Icons.Outlined.Dns, "Server settings", onServer) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth(), shape = Shapes.hero) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name.ifBlank { "You" }, color, size = 64.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name.ifBlank { "Your name" }, style = Griip.type.title, color = if (name.isBlank()) c.textTertiary else c.textPrimary, maxLines = 1)
                        Text(if (username.isBlank()) "@username" else "@$username", style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AvatarGradients.take(8).forEachIndexed { i, (a, _) ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(a).pressable({ color = i }),
                            contentAlignment = Alignment.Center,
                        ) { if (i == color) Icon(Icons.Rounded.Check, "Selected colour", tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            GriipTextField(name, { name = it.take(60) }, "Display name", placeholder = "e.g. Nusrat Jahan", leadingIcon = Icons.Rounded.Person,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
            Spacer(Modifier.height(16.dp))
            GriipTextField(
                username, { username = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '_' }.take(24) }, "Username",
                placeholder = "nusrat", leadingIcon = Icons.Rounded.AlternateEmail,
                helper = availability ?: "3–24 characters: letters, numbers, . and _",
                error = if (available == false) availability else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailing = { if (available == true) Icon(Icons.Rounded.Check, "Available", tint = c.success, modifier = Modifier.size(20.dp)) },
            )
            Spacer(Modifier.height(20.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(Icons.Outlined.Shield, c.primary, c.primarySoft, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Keys are created on this phone", style = Griip.type.titleSmall, color = c.textPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Your private keys never leave this device and are protected by Android Keystore. Save your recovery key afterwards — if you lose every device and the recovery key, the account can't be recovered.",
                            style = Griip.type.bodySmall, color = c.textSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Server: ${server.serverUrl}", style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(start = 4.dp))
            if (server.serverUrl.startsWith("http://")) {
                Spacer(Modifier.height(8.dp))
                StatusBanner("Unencrypted connection to the server (testing only). Message content stays end-to-end encrypted.", BannerKind.Warning, icon = Icons.Rounded.Warning)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                StatusBanner(it, BannerKind.Error, icon = Icons.Rounded.Warning)
            }
            Spacer(Modifier.height(24.dp))
        }
        PillButton(
            if (busy) "Creating keys…" else "Create my identity",
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { container.session.register(server.serverUrl, username, name, color) }
                        .onFailure { error = it.friendly() }
                    busy = false
                }
            },
            enabled = name.isNotBlank() && username.length >= 3 && available != false,
            loading = busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter).navigationBarsPadding().padding(bottom = 16.dp),
        )
    }
}

private fun suggest(name: String) = name.lowercase().trim().replace(Regex("\\s+"), ".").filter { it.isLetterOrDigit() || it == '.' || it == '_' }.take(24)

@Composable
private fun RestoreScreen(onBack: () -> Unit) {
    val c = Griip.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val server by container.settings.state.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().imePadding()) {
        GriipTopBar("Restore account", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Row {
                    IconTile(Icons.Outlined.Key, c.lavender, c.lavenderSoft, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Use the recovery key you saved from Profile → Security. This signs this phone into your account with your existing identity. Messages from your old device can't be restored — history is stored only on devices.",
                        style = Griip.type.bodySmall, color = c.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            GriipTextField(username, { username = it.lowercase().trim() }, "Username", placeholder = "nusrat", leadingIcon = Icons.Rounded.AlternateEmail)
            Spacer(Modifier.height(16.dp))
            GriipTextField(code, { code = it.uppercase() }, "Recovery key", placeholder = "ABCD-EFGH-…", leadingIcon = Icons.Rounded.Lock, singleLine = false, minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            error?.let {
                Spacer(Modifier.height(12.dp))
                StatusBanner(it, BannerKind.Error, icon = Icons.Rounded.Warning)
            }
        }
        PillButton(
            "Restore", {
                busy = true; error = null
                scope.launch {
                    runCatching { container.session.restore(server.serverUrl, username, code) }.onFailure { error = it.friendly() }
                    busy = false
                }
            },
            enabled = username.length >= 3 && code.length >= 20, loading = busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter).navigationBarsPadding().padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun LinkDeviceScreen(onBack: () -> Unit) {
    val c = Griip.colors
    val container = LocalContainer.current
    val server by container.settings.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<PendingPairing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        pending = null
        runCatching { container.session.startPairing(server.serverUrl) }
            .onSuccess { p ->
                pending = p
                while (true) {
                    delay(2000)
                    val done = runCatching { container.session.pollPairing(p) }.getOrElse { e -> error = e.friendly(); break }
                    if (done) break
                }
            }
            .onFailure { error = it.friendly() }
    }
    Column(Modifier.fillMaxSize()) {
        GriipTopBar("Link this device", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            IconTile(Icons.Outlined.PhonelinkSetup, c.primary, c.primarySoft, size = 72.dp)
            Spacer(Modifier.height(16.dp))
            Text("On your other phone, open Profile → Devices → Link a new device and enter this code.", style = Griip.type.body, color = c.textSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            GlassCard(Modifier.fillMaxWidth(), shape = Shapes.hero) {
                val p = pending
                if (p == null && error == null) {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.primary) }
                } else if (p != null) {
                    Text("Link code", style = Griip.type.label, color = c.textTertiary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        p.code.chunked(5).joinToString(" "),
                        style = Griip.type.display.copy(fontFamily = FontFamily.Monospace, fontSize = 30.sp, letterSpacing = 2.sp),
                        color = c.primaryDeep, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = c.primary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Waiting for approval…", style = Griip.type.caption, color = c.textSecondary)
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                StatusBanner(it, BannerKind.Error, icon = Icons.Rounded.Warning, action = "Retry", onAction = { attempt++ })
            }
            Spacer(Modifier.height(16.dp))
            Text("Your other phone sends this device your identity key encrypted end-to-end (HPKE). The server only relays ciphertext.", style = Griip.type.caption, color = c.textTertiary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ServerSheet(onDismiss: () -> Unit) {
    val c = Griip.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(settings.serverUrl) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    GlassSheet(onDismiss, title = "Server") {
        Text("Griip Message connects to your organisation's relay. Internal beta default: the GriipBuddy test server.", style = Griip.type.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(16.dp))
        GriipTextField(url, { url = it.trim() }, "Server address", placeholder = "https://msg.example.com", leadingIcon = Icons.Outlined.Dns)
        status?.let { (ok, msg) ->
            Spacer(Modifier.height(10.dp))
            StatusBanner(msg, if (ok) BannerKind.Success else BannerKind.Error)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Test", {
                busy = true
                scope.launch {
                    status = runCatching { container.session.serverInfo(url) }.fold(
                        { true to "Connected · v${it.version} · calls ${if (it.calling) "available" else "unavailable"}" },
                        { false to it.friendly() },
                    )
                    busy = false
                }
            }, Modifier.weight(1f), tone = ButtonTone.Glass, loading = busy, height = 50.dp)
            PillButton("Save", { container.settings.update { it.copy(serverUrl = url) }; onDismiss() }, Modifier.weight(1f), showKnob = false, height = 50.dp,
                enabled = url.startsWith("http://") || url.startsWith("https://"))
        }
    }
}

@Composable
fun RevokedScreen(reason: String) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), verticalArrangement = Arrangement.Center) {
        EmptyState(
            Icons.Outlined.Badge, "This device was signed out", "$reason. For your security, local data on this phone should be erased before you continue.",
            action = "Erase & start over", onAction = { (ctx.applicationContext as GriipApp).eraseEverything() }, tint = Griip.colors.danger,
        )
    }
}

