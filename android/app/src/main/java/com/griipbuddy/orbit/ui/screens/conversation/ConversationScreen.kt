package com.griipbuddy.orbit.ui.screens.conversation

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import com.griipbuddy.orbit.ui.media.CreateDocumentFor
import com.griipbuddy.orbit.ui.media.MediaAction
import com.griipbuddy.orbit.ui.media.MediaGallery
import com.griipbuddy.orbit.ui.media.MediaViewer
import com.griipbuddy.orbit.data.model.AttachmentRef
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import android.widget.Toast
import android.os.Build
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.heading
import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.rounded.Mic
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
import com.griipbuddy.orbit.call.CallPhase
import com.griipbuddy.orbit.data.db.MessageEntity
import com.griipbuddy.orbit.data.model.MessageStatus
import com.griipbuddy.orbit.data.repo.ConversationRepository
import com.griipbuddy.orbit.data.repo.GlobalAddress
import com.griipbuddy.orbit.ui.Routes
import com.griipbuddy.orbit.ui.TimeFmt
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.CircleIconButton
import com.griipbuddy.orbit.ui.components.EmptyState
import com.griipbuddy.orbit.ui.components.GlassSheet
import com.griipbuddy.orbit.ui.components.MenuRow
import com.griipbuddy.orbit.ui.components.StatusBanner
import com.griipbuddy.orbit.ui.components.pressable
import com.griipbuddy.orbit.ui.containerViewModel
import com.griipbuddy.orbit.ui.screens.chats.ConversationAvatar
import com.griipbuddy.orbit.ui.screens.home.ConnectionBanner
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Shapes
import com.griipbuddy.orbit.voice.VoiceDraftController
import com.griipbuddy.orbit.voice.VoiceNote
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
    val voiceDraft by vm.voiceDraft.state.collectAsStateWithLifecycle()
    val voicePlayer by vm.voicePlayer.collectAsStateWithLifecycle()
    var actionFor by remember { mutableStateOf<MessageEntity?>(null) }
    var infoFor by remember { mutableStateOf<MessageEntity?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    // Swipe-to-reply and the menu's "Reply" put the cursor in the composer, showing the keyboard.
    val composerFocus = remember { FocusRequester() }
    val focusComposer: () -> Unit = { runCatching { composerFocus.requestFocus() } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.attach(ctx, it) } }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.attach(ctx, it) } }
    var attachSheet by remember { mutableStateOf(false) }
    // Photos and videos open in the in-app viewer; other files are opened explicitly with another app.
    var viewerFor by rememberSaveable { mutableStateOf<String?>(null) }
    var saveTarget by remember { mutableStateOf<AttachmentRef?>(null) }
    val notice by vm.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) { notice?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.notice.value = null } }
    val saveDocument = rememberLauncherForActivityResult(CreateDocumentFor()) { uri -> val ref = saveTarget; if (uri != null && ref != null) vm.saveToDocument(ref, uri) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val ref = saveTarget
        if (ok && ref != null) vm.saveToGallery(ref) else if (!ok) vm.error.value = "Saving to the gallery needs storage access on this Android version."
    }
    val onMedia: (MediaAction, AttachmentRef) -> Unit = { action, ref ->
        saveTarget = ref
        when (action) {
            MediaAction.SaveToGallery ->
                if (MediaGallery.needsStoragePermission(Build.VERSION.SDK_INT) &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.saveToGallery(ref)
            MediaAction.SaveToFiles -> saveDocument.launch(MediaGallery.safeName(ref.name, ref.mime) to ref.mime)
            MediaAction.Share -> vm.share(ref) { intent -> runCatching { ctx.startActivity(intent) } }
        }
    }
    val openAttachment: (AttachmentRef) -> Unit = { ref -> if (MediaGallery.kindOf(ref) != null) viewerFor = ref.id else vm.openAttachment(ctx, ref) }
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }
    val permission = com.griipbuddy.orbit.ui.screens.calls.rememberCallPermission()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice() else vm.error.value = VoiceDraftController.PERMISSION
    }
    val onMic = {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVoice()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) vm.onVisible()
            if (e == Lifecycle.Event.ON_PAUSE) vm.onHidden()
            if (e == Lifecycle.Event.ON_STOP) vm.interruptVoice()
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
    fun previewOf(m: MessageEntity) = m.text.ifEmpty { VoiceNote.label(vm.c.messages.attachments(m)) ?: "📎 Attachment" }
    val peer = conv?.let { ConversationRepository.peer(it, me) }
    val peerUser = peer?.let { users[it.id] }
    val isGroup = conv?.type != "DM"
    // People on another server (federation): their presence isn't shared across servers, and calls with them aren't available yet.
    val remoteMembers = conv?.let { ConversationRepository.remoteMembers(it) }.orEmpty()
    val federation by vm.c.conversations.federation.collectAsStateWithLifecycle()
    val remoteServers = federation[conversationId]?.servers.orEmpty()
    val subtitle = when {
        threadRoot != null -> "Thread"
        typing.isNotEmpty() -> if (isGroup) "${typing.first()} is typing…" else "typing…"
        conv?.type == "DM" && peer != null && remoteMembers.isNotEmpty() -> "@${peer.username}"
        conv?.type == "DM" -> if (peerUser?.online == true) "Online" else "Last seen ${TimeFmt.relative(peerUser?.lastSeenAt)}"
        conv?.type == "CHANNEL" -> listOfNotNull(conv?.scopeName, "${conv?.memberCount ?: 0} members").joinToString(" · ")
        else -> "${conv?.memberCount ?: 0} members"
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().height(64.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { nav.popBackStack() })
            Row(
                Modifier.weight(1f).clip(Shapes.small).pressable({ nav.navigate(Routes.info(conversationId)) }, onClickLabel = "Open conversation info").padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                conv?.let { ConversationAvatar(it, me, size = 40.dp, online = peerUser?.online) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (conv?.type == "CHANNEL") "#$title" else title, style = Griip.type.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = Griip.type.caption, color = if (typing.isNotEmpty() || peerUser?.online == true) c.primary else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // With people on another server, calls need this server to host them (it says so per conversation).
            val canCall = remoteMembers.isEmpty() || federation[conversationId]?.calls == true
            if (threadRoot == null && conv?.removed != true && canCall) {
                CircleIconButton(Icons.Rounded.Videocam, "Video call", { permission(true) { vm.startCall(true) } }, tint = c.primary, enabled = call.phase == CallPhase.Idle)
                CircleIconButton(Icons.Rounded.Call, "Voice call", { permission(false) { vm.startCall(false) } }, tint = c.primary, enabled = call.phase == CallPhase.Idle)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = c.border)
        ConnectionBanner(conn, Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) { vm.c.realtime.reconnectNow() }
        remoteServers.firstOrNull { it.state == "decommissioned" || it.state == "revoked" }?.let { s ->
            StatusBanner(
                "${s.domain ?: "Their server"} has shut down. Messages in this conversation can't be delivered.",
                BannerKind.Error, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } ?: remoteServers.firstOrNull { it.state == "unreachable" }?.let { s ->
            StatusBanner(
                "${s.domain ?: "Their server"} isn't reachable right now. Your messages wait for up to 7 days and are delivered when it's back.",
                BannerKind.Warning, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
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
                Modifier.fillMaxWidth().background(c.surface)
                    .pressable({ nav.navigate(Routes.info(conversationId)) }, onClickLabel = "Show pinned messages").padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PushPin, null, tint = c.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("${pinned.size} pinned · ${pinned.first().let { p -> p.text.ifEmpty { VoiceNote.label(vm.c.messages.attachments(p)) ?: "Attachment" } }}", style = Griip.type.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(thickness = 1.dp, color = c.border)
        }

        // Messages
        Box(Modifier.weight(1f)) {
            if (messages.isEmpty() && threadRoot == null) {
                EmptyState(
                    Icons.Rounded.Lock, "Say hello 👋",
                    if (remoteMembers.isNotEmpty()) {
                        "${remoteMembers.first().displayName} is on another server (${GlobalAddress.serverOf(remoteMembers.first().username) ?: "elsewhere"}). Messages are end-to-end encrypted on your device; both servers only relay encrypted copies, kept for at most 7 days until delivered."
                    } else {
                        "Messages here are end-to-end encrypted on your device and stored only on members' devices. The relay keeps encrypted copies for at most 7 days until delivered."
                    },
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
                    // Same rule as the long-press menu's "Reply": a posted text/attachment message where you may post.
                    val canSwipeReply = conv?.canPost != false && !m.deleted && m.kind == "text"
                    Column {
                        if (older == null || !TimeFmt.sameDay(older.sortTs, m.sortTs)) DayDivider(m.sortTs)
                        SwipeToReply(canSwipeReply, onReply = { vm.replyTo.value = m; vm.editing.value = null; focusComposer() }) {
                        MessageBubble(
                            BubbleModel(
                                m, users[m.senderUserId], showSender = first, showAvatar = lastInRun, firstInGroup = first, reply = reply,
                                replySender = reply?.let { if (it.outgoing) "You" else users[it.senderUserId]?.displayName },
                                reactions = vm.c.messages.reactions(m), attachments = vm.c.messages.attachments(m),
                                threadCount = if (threadRoot == null) threadCounts[m.id] ?: 0 else 0, isGroupConv = isGroup,
                                replyPreview = reply?.let { previewOf(it) },
                            ),
                            me = me,
                            transfer = vm.c.messages.attachments(m).firstNotNullOfOrNull { transfers[it.id] } ?: transfers[m.id],
                            localFile = { vm.c.messages.localAttachment(it) },
                            onLongPress = { if (m.kind != "call") actionFor = m },
                            onReact = { vm.react(m, it) },
                            onOpenAttachment = openAttachment,
                            onOpenThread = { nav.navigate(Routes.conversation(conversationId, m.id)) },
                            onRetry = { vm.retry(m) },
                            onReplyClick = { id -> val idx = messages.indexOfFirst { it.id == id }; if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) } },
                            voice = voicePlayer,
                            onVoiceToggle = { ref -> vm.toggleVoice(m.id, ref) },
                            onVoiceSeek = { ref, pos -> vm.seekVoice(m.id, ref, pos) },
                        )
                        }
                    }
                }
                if (threadRoot != null) {
                    item(key = "root") {
                        Column {
                            root?.let { r ->
                                MessageBubble(
                                    BubbleModel(r, users[r.senderUserId], true, true, true, null, null, vm.c.messages.reactions(r), vm.c.messages.attachments(r), 0, isGroup),
                                    me, null, { vm.c.messages.localAttachment(it) }, { actionFor = r }, { vm.react(r, it) }, openAttachment, null, { vm.retry(r) }, {},
                                    voice = voicePlayer,
                                    onVoiceToggle = { ref -> vm.toggleVoice(r.id, ref) },
                                    onVoiceSeek = { ref, pos -> vm.seekVoice(r.id, ref, pos) },
                                )
                            }
                            Text("${messages.size} ${if (messages.size == 1) "reply" else "replies"}", style = Griip.type.label, color = c.textTertiary, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            if (showJump) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = c.surface, contentColor = c.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).semantics { contentDescription = "Jump to latest message" },
                ) { Icon(Icons.Rounded.KeyboardArrowDown, null) }
            }
        }

        // Typing
        AnimatedVisibility(typing.isNotEmpty()) { TypingRow(typing) }

        // Composer
        error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), action = "OK", onAction = { vm.error.value = null }) }
        if (conv != null && !conv!!.canPost) {
            Row(
                Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
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
                replyLabel = replyTo?.let { "Replying to ${if (it.outgoing) "yourself" else users[it.senderUserId]?.displayName ?: ""}" to previewOf(it) }
                    ?: editing?.let { "Editing message" to it.text },
                onCancelContext = { vm.replyTo.value = null; vm.editing.value = null; if (editing != null) text = "" },
                onAttach = { attachSheet = true },
                onSend = { vm.send(text); text = "" },
                placeholder = if (threadRoot != null) "Reply in thread…" else if (conv?.type == "CHANNEL") "Message #$title" else "Message",
                voice = VoiceComposerState(
                    draft = voiceDraft,
                    // Voice messages go to the conversation itself, like files, so the microphone isn't offered in a thread.
                    available = threadRoot == null && editing == null,
                    playback = voicePlayer.takeIf { it.key == vm.draftKey },
                ),
                onMic = onMic,
                onStopRecording = vm::stopVoice,
                onCancelVoice = vm::cancelVoice,
                onSendVoice = vm::sendVoice,
                onToggleDraft = vm::toggleDraftPlayback,
                onSeekDraft = vm::seekDraft,
                focus = composerFocus,
            )
        }
    }

    actionFor?.let { m ->
        // Where the user can't post, the server refuses reactions, pins, edits and deletes too.
        val interactive = conv?.canPost != false
        GlassSheet({ actionFor = null }) {
            if (interactive) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                QUICK_REACTIONS.forEach { e ->
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(c.surfaceVariant).pressable({ vm.react(m, e); actionFor = null })
                            .semantics { contentDescription = "React $e" },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 22.sp) }
                }
            }
            if (!m.deleted && m.kind == "text") {
                if (interactive) MenuRow(Icons.AutoMirrored.Rounded.Reply, "Reply", onClick = { vm.replyTo.value = m; vm.editing.value = null; actionFor = null; focusComposer() }, trailing = null)
                if (interactive && threadRoot == null) MenuRow(Icons.Outlined.Forum, "Reply in thread", onClick = { actionFor = null; nav.navigate(Routes.conversation(conversationId, m.id)) }, trailing = null)
                if (m.text.isNotEmpty()) MenuRow(Icons.Outlined.ContentCopy, "Copy text", onClick = {
                    scope.launch { clipboard.setClipEntry(ClipData.newPlainText("message", m.text).toClipEntry()) }
                    actionFor = null
                }, trailing = null)
                if (interactive && m.outgoing && m.status != MessageStatus.FAILED && m.status != MessageStatus.SENDING) MenuRow(Icons.Outlined.Edit, "Edit", onClick = {
                    vm.editing.value = m; vm.replyTo.value = null; text = m.text; actionFor = null
                }, trailing = null)
                if (interactive) MenuRow(Icons.Outlined.PushPin, if (m.pinned) "Unpin" else "Pin for everyone", tint = c.peach, tintBg = c.peachSoft, onClick = { vm.pin(m); actionFor = null }, trailing = null)
            }
            // Saving and sharing are explicit actions; tapping a photo only opens the viewer.
            val file = if (!m.deleted) vm.c.messages.attachments(m).firstOrNull { it.id != "pending" && !VoiceNote.isVoice(it) } else null
            if (file != null) {
                if (MediaGallery.kindOf(file) != null) MenuRow(Icons.Outlined.Download, "Save to Gallery", onClick = { actionFor = null; onMedia(MediaAction.SaveToGallery, file) }, trailing = null)
                MenuRow(Icons.Outlined.FolderOpen, "Save to Files", onClick = { actionFor = null; onMedia(MediaAction.SaveToFiles, file) }, trailing = null)
                MenuRow(Icons.Outlined.Share, "Share…", onClick = { actionFor = null; onMedia(MediaAction.Share, file) }, trailing = null)
            }
            if (m.status == MessageStatus.FAILED) MenuRow(Icons.Outlined.Refresh, "Retry sending", onClick = { vm.retry(m); actionFor = null }, trailing = null)
            if (m.outgoing) MenuRow(Icons.Outlined.Info, "Message info", tint = c.lavender, tintBg = c.lavenderSoft, onClick = { infoFor = m; actionFor = null }, trailing = null)
            if (interactive && m.outgoing && !m.deleted) MenuRow(Icons.Outlined.DeleteOutline, "Delete for everyone", tint = c.danger, tintBg = c.dangerSoft, onClick = { vm.delete(m); actionFor = null }, trailing = null)
        }
    }
    viewerFor?.let { id ->
        val media = remember(messages, root) { MediaGallery.items(messages + listOfNotNull(root)) { vm.c.messages.attachments(it) } }
        MediaViewer(
            items = media, startAttachmentId = id,
            senderName = { item -> if (item.outgoing) "You" else users[item.senderUserId]?.displayName ?: "Someone" },
            load = vm::mediaFile,
            progress = { transfers[it] },
            canPlayVideo = vm::videoBlocked,
            onPlay = vm::pauseVoice,
            onAction = { action, item -> onMedia(action, item.ref) },
            onDismiss = { viewerFor = null },
        )
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
    if (attachSheet) {
        GlassSheet({ attachSheet = false }, title = "Send") {
            MenuRow(
                Icons.Outlined.Image, "Photo or video", subtitle = "Encrypted on this phone before upload · up to 25 MB",
                onClick = { attachSheet = false; mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, trailing = null,
            )
            MenuRow(
                Icons.Outlined.Description, "File", subtitle = "Any document · up to 25 MB",
                onClick = { attachSheet = false; picker.launch("*/*") }, trailing = null,
            )
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
            modifier = Modifier.clip(Shapes.pill).background(c.surface).border(1.dp, c.border, Shapes.pill).padding(horizontal = 12.dp, vertical = 4.dp).semantics { heading() },
        )
    }
}

@Composable
private fun TypingRow(names: List<String>) {
    val c = Griip.colors
    val t = rememberInfiniteTransition(label = "typing")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dots")
    Row(Modifier.padding(start = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(Shapes.pill).background(c.surface).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { i -> Box(Modifier.size(6.dp).clip(CircleShape).background(c.primary.copy(alpha = if (i == 1) a else 1.3f - a))) }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (names.size == 1) "${names[0]} is typing" else "${names.size} people are typing",
            style = Griip.type.caption, color = c.textSecondary,
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
    voice: VoiceComposerState,
    onMic: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelVoice: () -> Unit,
    onSendVoice: () -> Unit,
    onToggleDraft: () -> Unit,
    onSeekDraft: (Long) -> Unit,
    focus: FocusRequester? = null,
) {
    val c = Griip.colors
    when (val d = voice.draft) {
        is VoiceDraftController.Draft.Recording -> {
            VoiceRecordingBar(d.startedAt, onCancelVoice, onStopRecording)
            return
        }
        is VoiceDraftController.Draft.Ready -> {
            VoicePreviewBar(d, voice.playback, onCancelVoice, onToggleDraft, onSeekDraft, onSendVoice)
            return
        }
        VoiceDraftController.Draft.Idle -> Unit
    }
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        HorizontalDivider(thickness = 1.dp, color = c.border)
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
            replyLabel?.let { (label, preview) ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp).height(IntrinsicSize.Min).clip(Shapes.small).background(c.surfaceVariant),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.primary))
                    Column(Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                        Text(label, style = Griip.type.caption.copy(fontWeight = FontWeight.Bold), color = c.primaryDeep)
                        Text(preview, style = Griip.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CircleIconButton(Icons.Rounded.Close, "Cancel", onCancelContext, size = 36.dp, tint = c.textSecondary)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                ComposerIcon(Icons.Outlined.AttachFile, "Attach a photo, video or file", onAttach)
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(c.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text(placeholder, style = Griip.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(
                        value = text, onValueChange = onText, textStyle = Griip.type.body.copy(color = c.textPrimary), cursorBrush = SolidColor(c.primary), maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier).semantics { contentDescription = placeholder },
                    )
                }
                Spacer(Modifier.width(6.dp))
                val enabled = text.isNotBlank()
                if (!enabled && voice.available) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(c.actionStart)
                            .pressable(onMic, onClickLabel = "Record a voice message")
                            .semantics { contentDescription = "Record a voice message" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Mic, null, tint = c.onAction, modifier = Modifier.size(24.dp)) }
                    return@Row
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(if (enabled) c.actionStart else c.surfaceVariant)
                        .pressable(if (enabled) onSend else null, onClickLabel = "Send")
                        .semantics { contentDescription = "Send message" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.Send, null, tint = if (enabled) c.onAction else c.textTertiary, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}

@Composable
private fun ComposerIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Griip.colors
    Box(Modifier.size(48.dp).clip(CircleShape).pressable(onClick, onClickLabel = label).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(24.dp))
    }
}
