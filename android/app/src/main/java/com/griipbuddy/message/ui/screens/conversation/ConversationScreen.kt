package com.griipbuddy.message.ui.screens.conversation

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.griipbuddy.message.call.CallPhase
import com.griipbuddy.message.data.db.MessageEntity
import com.griipbuddy.message.data.model.MessageStatus
import com.griipbuddy.message.data.repo.ConversationRepository
import com.griipbuddy.message.ui.Routes
import com.griipbuddy.message.ui.TimeFmt
import com.griipbuddy.message.ui.components.BannerKind
import com.griipbuddy.message.ui.components.CircleIconButton
import com.griipbuddy.message.ui.components.EmptyState
import com.griipbuddy.message.ui.components.GlassSheet
import com.griipbuddy.message.ui.components.MenuRow
import com.griipbuddy.message.ui.components.StatusBanner
import com.griipbuddy.message.ui.components.pressable
import com.griipbuddy.message.ui.containerViewModel
import com.griipbuddy.message.ui.screens.chats.ConversationAvatar
import com.griipbuddy.message.ui.screens.home.ConnectionBanner
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes
import kotlinx.coroutines.launch

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "😮", "🙏")

@Composable
fun ConversationScreen(nav: NavHostController, conversationId: String, threadRoot: String?) {
    val vm = containerViewModel(key = "conv:$conversationId:$threadRoot") { ConversationViewModel(it, conversationId, threadRoot) }
    val c = Griip.colors
    val ctx = LocalContext.current
    val conv by vm.conversation.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val root by vm.rootMessage.collectAsStateWithLifecycle()
    val users by vm.users.collectAsStateWithLifecycle()
    val typing by vm.typing.collectAsStateWithLifecycle()
    val threadCounts by vm.threadCounts.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val call by vm.call.collectAsStateWithLifecycle()
    val replyTo by vm.replyTo.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val changedMembers by vm.changedMembers.collectAsStateWithLifecycle()
    val heldIncoming by vm.heldIncoming.collectAsStateWithLifecycle()
    var actionFor by remember { mutableStateOf<MessageEntity?>(null) }
    var infoFor by remember { mutableStateOf<MessageEntity?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.attach(ctx, it) } }
    val permission = com.griipbuddy.message.ui.screens.calls.rememberCallPermission()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) vm.onVisible()
            if (e == Lifecycle.Event.ON_PAUSE) vm.onHidden()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.onHidden() }
    }
    // Restore a saved draft once, when the conversation first loads (not after every send).
    var draftRestored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(conv != null) {
        if (!draftRestored && conv != null) {
            draftRestored = true
            if (text.isEmpty() && editing == null) conv?.draft?.let { text = it }
        }
    }
    LaunchedEffect(messages.firstOrNull()?.id) {
        if (messages.isNotEmpty()) {
            if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
            vm.onVisible()
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= messages.size - 5) vm.loadMore()
        }
    }

    val me = vm.me
    val title = conv?.let { ConversationRepository.title(it, me) } ?: ""
    val peer = conv?.let { ConversationRepository.peer(it, me) }
    val peerUser = peer?.let { users[it.id] }
    val isGroup = conv?.type != "DM"
    val subtitle = when {
        threadRoot != null -> "Thread"
        typing.isNotEmpty() -> if (isGroup) "${typing.first()} is typing…" else "typing…"
        conv?.type == "DM" -> if (peerUser?.online == true) "Online" else "Last seen ${TimeFmt.relative(peerUser?.lastSeenAt)}"
        conv?.type == "CHANNEL" -> listOfNotNull(conv?.scopeName, "${conv?.memberCount ?: 0} members").joinToString(" · ")
        else -> "${conv?.memberCount ?: 0} members"
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Header
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { nav.popBackStack() })
            Spacer(Modifier.width(6.dp))
            Row(
                Modifier.weight(1f).clip(Shapes.tile).pressable({ nav.navigate(Routes.info(conversationId)) }, onClickLabel = "Conversation info").padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                conv?.let { ConversationAvatar(it, me, size = 42.dp, online = peerUser?.online) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (conv?.type == "CHANNEL") "#$title" else title, style = Griip.type.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = Griip.type.caption, color = if (typing.isNotEmpty() || peerUser?.online == true) c.primaryDeep else c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (threadRoot == null && conv?.removed != true) {
                CircleIconButton(Icons.Rounded.Call, "Voice call", { permission(false) { vm.startCall(false) } }, tint = c.primaryDeep, enabled = call.phase == CallPhase.Idle)
                CircleIconButton(Icons.Rounded.Videocam, "Video call", { permission(true) { vm.startCall(true) } }, tint = c.primaryDeep, enabled = call.phase == CallPhase.Idle)
            }
        }
        ConnectionBanner(conn, Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) { vm.c.realtime.reconnectNow() }
        if (changedMembers.isNotEmpty()) {
            val who = if (changedMembers.size == 1) "${changedMembers.first().displayName.ifEmpty { "A member" }}'s security key" else "${changedMembers.size} members' security keys"
            val waiting = if (heldIncoming > 0) " $heldIncoming incoming message${if (heldIncoming == 1) " is" else "s are"} waiting." else ""
            StatusBanner(
                "$who changed. Messages to and from them are paused until you check the safety number.$waiting",
                BannerKind.Warning, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), icon = Icons.Rounded.GppMaybe,
                action = "Review", onAction = { nav.navigate(Routes.user(changedMembers.first().id)) },
            )
        }
        val activeCall = conv?.activeCallId
        if (activeCall != null && call.phase == CallPhase.Idle && threadRoot == null) {
            StatusBanner(
                if (conv?.activeCallVideo == true) "A video call is in progress" else "A voice call is in progress", BannerKind.Success,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), icon = Icons.Rounded.Call, action = "Join", onAction = { permission(conv?.activeCallVideo == true) { vm.joinCall(activeCall, conv?.activeCallVideo == true) } },
            )
        }
        if (pinned.isNotEmpty() && threadRoot == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(Shapes.tile).background(c.glassStrong).border(1.dp, c.glassBorder, Shapes.tile)
                    .pressable({ nav.navigate(Routes.info(conversationId)) }).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PushPin, null, tint = c.peach, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("${pinned.size} pinned · ${pinned.first().text.ifEmpty { "Attachment" }}", style = Griip.type.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // Messages
        Box(Modifier.weight(1f)) {
            if (messages.isEmpty() && threadRoot == null) {
                EmptyState(
                    Icons.Rounded.Lock, "Say hello 👋",
                    "Messages here are end-to-end encrypted on your device and stored only on members' devices. The relay keeps encrypted copies for at most 7 days until delivered.",
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            val byId = remember(messages) { messages.associateBy { it.id } }
            LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(vertical = 10.dp)) {
                itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
                    val older = messages.getOrNull(i + 1)
                    val newer = messages.getOrNull(i - 1)
                    val first = older == null || older.senderUserId != m.senderUserId || m.sortTs - older.sortTs > 5 * 60_000 || older.kind == "call" || !TimeFmt.sameDay(older.sortTs, m.sortTs)
                    val lastInRun = newer == null || newer.senderUserId != m.senderUserId || newer.sortTs - m.sortTs > 5 * 60_000 || newer.kind == "call"
                    val reply = m.replyToId?.let { byId[it] }
                    Column {
                        if (older == null || !TimeFmt.sameDay(older.sortTs, m.sortTs)) DayDivider(m.sortTs)
                        MessageBubble(
                            BubbleModel(
                                m, users[m.senderUserId], showSender = first, showAvatar = lastInRun, firstInGroup = first, reply = reply,
                                replySender = reply?.let { if (it.outgoing) "You" else users[it.senderUserId]?.displayName },
                                reactions = vm.c.messages.reactions(m), attachments = vm.c.messages.attachments(m),
                                threadCount = if (threadRoot == null) threadCounts[m.id] ?: 0 else 0, isGroupConv = isGroup,
                            ),
                            me = me,
                            transfer = vm.c.messages.attachments(m).firstNotNullOfOrNull { transfers[it.id] } ?: transfers[m.id],
                            localFile = { vm.c.messages.localAttachment(it) },
                            onLongPress = { if (m.kind != "call") actionFor = m },
                            onReact = { vm.react(m, it) },
                            onOpenAttachment = { vm.openAttachment(ctx, it) },
                            onOpenThread = { nav.navigate(Routes.conversation(conversationId, m.id)) },
                            onRetry = { vm.retry(m) },
                            onReplyClick = { id -> val idx = messages.indexOfFirst { it.id == id }; if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) } },
                        )
                    }
                }
                if (threadRoot != null) {
                    item(key = "root") {
                        Column {
                            root?.let { r ->
                                MessageBubble(
                                    BubbleModel(r, users[r.senderUserId], true, true, true, null, null, vm.c.messages.reactions(r), vm.c.messages.attachments(r), 0, isGroup),
                                    me, null, { vm.c.messages.localAttachment(it) }, { actionFor = r }, { vm.react(r, it) }, { vm.openAttachment(ctx, it) }, null, { vm.retry(r) }, {},
                                )
                            }
                            Text("${messages.size} ${if (messages.size == 1) "reply" else "replies"}", style = Griip.type.label, color = c.textTertiary, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        // Typing
        AnimatedVisibility(typing.isNotEmpty()) { TypingRow(typing) }

        // Composer
        error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), action = "OK", onAction = { vm.error.value = null }) }
        if (conv != null && !conv!!.canPost) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).clip(Shapes.tile).background(c.glassStrong).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (conv?.kind == "ANNOUNCEMENT") Icons.Outlined.Campaign else Icons.Rounded.Lock, null, tint = c.textTertiary)
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        conv?.removed == true -> "You're no longer a member of this conversation."
                        conv?.archived == true -> "This channel is archived."
                        conv?.kind == "ANNOUNCEMENT" -> "Only managers can post in this announcement channel."
                        else -> "You can't post here."
                    },
                    style = Griip.type.bodySmall, color = c.textSecondary,
                )
            }
        } else {
            Composer(
                text = text,
                onText = { text = it; vm.typingChanged(it) },
                replyLabel = replyTo?.let { "Replying to ${if (it.outgoing) "yourself" else users[it.senderUserId]?.displayName ?: ""}" to it.text.ifEmpty { "📎 Attachment" } }
                    ?: editing?.let { "Editing message" to it.text },
                onCancelContext = { vm.replyTo.value = null; vm.editing.value = null; if (editing != null) text = "" },
                onAttach = { picker.launch("*/*") },
                onSend = { vm.send(text); text = "" },
                placeholder = if (threadRoot != null) "Reply in thread…" else if (conv?.type == "CHANNEL") "Message #$title" else "Message",
            )
        }
    }

    actionFor?.let { m ->
        GlassSheet({ actionFor = null }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                QUICK_REACTIONS.forEach { e ->
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(c.glass).border(1.dp, c.glassBorder, CircleShape).pressable({ vm.react(m, e); actionFor = null })
                            .semantics { contentDescription = "React $e" },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 22.sp) }
                }
            }
            if (!m.deleted && m.kind == "text") {
                MenuRow(Icons.AutoMirrored.Rounded.Reply, "Reply", onClick = { vm.replyTo.value = m; vm.editing.value = null; actionFor = null }, trailing = null)
                if (threadRoot == null) MenuRow(Icons.Outlined.Forum, "Reply in thread", onClick = { actionFor = null; nav.navigate(Routes.conversation(conversationId, m.id)) }, trailing = null)
                if (m.text.isNotEmpty()) MenuRow(Icons.Outlined.ContentCopy, "Copy text", onClick = {
                    scope.launch { clipboard.setClipEntry(ClipData.newPlainText("message", m.text).toClipEntry()) }
                    actionFor = null
                }, trailing = null)
                if (m.outgoing && m.status != MessageStatus.FAILED && m.status != MessageStatus.SENDING) MenuRow(Icons.Outlined.Edit, "Edit", onClick = {
                    vm.editing.value = m; vm.replyTo.value = null; text = m.text; actionFor = null
                }, trailing = null)
                MenuRow(Icons.Outlined.PushPin, if (m.pinned) "Unpin" else "Pin for everyone", tint = c.peach, tintBg = c.peachSoft, onClick = { vm.pin(m); actionFor = null }, trailing = null)
            }
            if (m.status == MessageStatus.FAILED) MenuRow(Icons.Outlined.Refresh, "Retry sending", onClick = { vm.retry(m); actionFor = null }, trailing = null)
            if (m.outgoing) MenuRow(Icons.Outlined.Info, "Message info", tint = c.lavender, tintBg = c.lavenderSoft, onClick = { infoFor = m; actionFor = null }, trailing = null)
            if (m.outgoing && !m.deleted) MenuRow(Icons.Outlined.DeleteOutline, "Delete for everyone", tint = c.danger, tintBg = c.dangerSoft, onClick = { vm.delete(m); actionFor = null }, trailing = null)
        }
    }
    infoFor?.let { m ->
        GlassSheet({ infoFor = null }, title = "Message info") {
            val delivered = vm.c.messages.deliveredTo(m).mapNotNull { users[it]?.displayName }
            val read = vm.c.messages.readBy(m).mapNotNull { users[it]?.displayName }
            InfoLine("Status", m.status.lowercase().replaceFirstChar { it.uppercase() })
            InfoLine("Sent", TimeFmt.clock(m.sentAt) + " · " + TimeFmt.dayHeader(m.sentAt))
            m.serverTs?.let { InfoLine("Accepted by relay", TimeFmt.clock(it)) }
            InfoLine("Delivered to", delivered.joinToString().ifEmpty { if (conv?.type == "CHANNEL") "Receipts aren't collected in channels" else "Not yet" })
            InfoLine("Read by", read.joinToString().ifEmpty { "Not yet" })
            InfoLine("Encryption", "griip-envelope-v1 · signed by this device")
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val c = Griip.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = Griip.type.label, color = c.textTertiary, modifier = Modifier.width(130.dp))
        Text(value, style = Griip.type.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DayDivider(t: Long) {
    val c = Griip.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
        Text(
            TimeFmt.dayHeader(t), style = Griip.type.caption, color = c.textSecondary,
            modifier = Modifier.clip(Shapes.pill).background(c.glassStrong).border(1.dp, c.glassBorder, Shapes.pill).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TypingRow(names: List<String>) {
    val c = Griip.colors
    val t = rememberInfiniteTransition(label = "typing")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dots")
    Row(Modifier.padding(start = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(Shapes.pill).background(c.glassStrong).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { i -> Box(Modifier.size(6.dp).clip(CircleShape).background(c.primary.copy(alpha = if (i == 1) a else 1.3f - a))) }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (names.size == 1) "${names[0]} is typing" else "${names.size} people are typing",
            style = Griip.type.caption, color = c.textTertiary,
        )
    }
}

@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    replyLabel: Pair<String, String>?,
    onCancelContext: () -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    placeholder: String,
) {
    val c = Griip.colors
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        replyLabel?.let { (label, preview) ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(Shapes.tile).background(c.primarySoft.copy(alpha = 0.8f)).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(30.dp).clip(Shapes.pill).background(c.primary))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = Griip.type.caption.copy(fontWeight = FontWeight.Bold), color = c.primaryDeep)
                    Text(preview, style = Griip.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CircleIconButton(Icons.Rounded.Close, "Cancel", onCancelContext, size = 32.dp)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Row(
                Modifier.weight(1f).heightIn(min = 52.dp)
                    .shadow(8.dp, Shapes.hero, ambientColor = c.shadow, spotColor = c.shadow)
                    .clip(Shapes.hero).background(c.glassStrong).border(1.dp, c.glassBorder, Shapes.hero)
                    .padding(start = 4.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerIcon(Icons.Outlined.AttachFile, "Attach a file", onAttach)
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (text.isEmpty()) Text(placeholder, style = Griip.type.body, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(
                        value = text, onValueChange = onText, textStyle = Griip.type.body.copy(color = c.textPrimary), cursorBrush = SolidColor(c.primary), maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            val enabled = text.isNotBlank()
            Box(
                Modifier.size(52.dp)
                    .shadow(if (enabled) 10.dp else 0.dp, CircleShape, ambientColor = c.primary.copy(alpha = 0.45f), spotColor = c.primary.copy(alpha = 0.45f))
                    .clip(CircleShape)
                    .background(if (enabled) Brush.linearGradient(listOf(c.actionStart, c.actionEnd)) else Brush.linearGradient(listOf(c.glassStrong, c.glassStrong)))
                    .pressable(if (enabled) onSend else null, onClickLabel = "Send")
                    .semantics { contentDescription = "Send message" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.Send, null, tint = if (enabled) Color.White else c.textTertiary, modifier = Modifier.size(22.dp)) }
        }
    }
}

@Composable
private fun ComposerIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Griip.colors
    Box(Modifier.size(44.dp).clip(CircleShape).pressable(onClick, onClickLabel = label).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(22.dp))
    }
}
