package com.meldiq.android.ui.screens.conversation

import com.meldiq.android.data.model.MentionSpan
import com.meldiq.android.data.model.MentionRules
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import com.meldiq.android.ui.media.CreateDocumentFor
import com.meldiq.android.ui.media.MediaAction
import com.meldiq.android.ui.media.MediaGallery
import com.meldiq.android.ui.media.MediaViewer
import com.meldiq.android.data.model.AttachmentRef
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import android.widget.Toast
import android.os.Build
import androidx.activity.result.PickVisualMediaRequest
import com.meldiq.android.data.model.AttachmentPolicy
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.Forward
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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.meldiq.android.data.model.ComposerAux
import com.meldiq.android.ui.media.BulkSave
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.outlined.NotificationsOff
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Search
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
import com.meldiq.android.call.CallPhase
import com.meldiq.android.data.db.MessageEntity
import com.meldiq.android.data.model.MessageStatus
import com.meldiq.android.data.repo.ConversationRepository
import com.meldiq.android.data.repo.GlobalAddress
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.containerViewModel
import com.meldiq.android.ui.screens.chats.ConversationAvatar
import com.meldiq.android.ui.screens.home.ConnectionBanner
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.voice.VoiceDraftController
import com.meldiq.android.voice.VoiceNote
import kotlinx.coroutines.launch

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "😮", "🙏")
/** Pause after typing before searching the conversation. */
private const val SEARCH_DEBOUNCE_MS = 200L

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
    // Text plus cursor: the cursor says where an @mention is being typed.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val text = field.text
    fun setText(t: String) { field = TextFieldValue(t, TextRange(t.length)) }
    val mentionMembers by vm.mentionMembers.collectAsStateWithLifecycle()
    // A send that failed before the message was stored gives its text back (only into an empty composer).
    val restore by vm.restoreText.collectAsStateWithLifecycle()
    LaunchedEffect(restore) { restore?.let { if (field.text.isEmpty()) setText(it); vm.restoreText.value = null } }
    val canMentionAll by vm.canMentionAll.collectAsStateWithLifecycle()
    // Swipe-to-reply and the menu's "Reply" put the cursor in the composer, showing the keyboard.
    val composerFocus = remember { FocusRequester() }
    val focusComposer: () -> Unit = { runCatching { composerFocus.requestFocus() } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    // Up to 10 photos, videos or files at once, reviewed in the send preview (BatchComposer) before anything is uploaded.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty()) vm.pick(ctx, uris) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachmentPolicy.MAX_ITEMS)) { uris -> if (uris.isNotEmpty()) vm.pick(ctx, uris) }
    var attachSheet by remember { mutableStateOf(false) }
    var aiSheet by remember { mutableStateOf<AttachAction?>(null) }
    // Sharing a link to an item in a connected app (docs/INTEGRATIONS.md).
    var shareFromApp by remember { mutableStateOf(false) }
    // Whose app connections previews of shared links use: the channel's workspace, else the one selected on Home.
    val homeOrg by vm.c.orgs.selectedOrg.collectAsStateWithLifecycle()
    val appLinkOrg = conv?.orgId ?: homeOrg
    // Connected apps' explicit actions on a message (docs/INTEGRATIONS.md › Actions), and the one being filled in.
    var appActions by remember { mutableStateOf<List<com.meldiq.android.ui.screens.workspace.apps.MessageAction>>(emptyList()) }
    var sendToApp by remember { mutableStateOf<Pair<com.meldiq.android.ui.screens.workspace.apps.MessageAction, String>?>(null) }
    LaunchedEffect(appLinkOrg) {
        val org = appLinkOrg ?: return@LaunchedEffect
        if (vm.c.features.has(com.meldiq.android.data.repo.ServerFeatures.INTEGRATIONS) != true) return@LaunchedEffect
        runCatching { vm.c.integrations.directory(org) }.onSuccess { d -> appActions = com.meldiq.android.ui.screens.workspace.apps.MessageActions.available(d.apps) }
    }
    val batch by vm.batch.collectAsStateWithLifecycle()
    val batchNote by vm.batchNote.collectAsStateWithLifecycle()
    val riskyOpen by vm.riskyOpen.collectAsStateWithLifecycle()
    val maxFile by vm.maxFile.collectAsStateWithLifecycle()
    val chatSettings by vm.c.settings.state.collectAsStateWithLifecycle()
    // Camera: a photo or a video taken now, reviewed in the send preview like a picked one. The camera permission is
    // asked only when the camera is used.
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> captureUri?.let { u -> if (ok) vm.pick(ctx, listOf(android.net.Uri.parse(u))) } }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok -> captureUri?.let { u -> if (ok) vm.pick(ctx, listOf(android.net.Uri.parse(u))) } }
    var pendingCamera by remember { mutableStateOf<Boolean?>(null) } // true: video
    fun launchCamera(video: Boolean) {
        val dir = java.io.File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = java.io.File(dir, "meldiq-${System.currentTimeMillis()}" + if (video) ".mp4" else ".jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        captureUri = uri.toString()
        runCatching { if (video) takeVideo.launch(uri) else takePhoto.launch(uri) }.onFailure { vm.error.value = "No camera app is available" }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val v = pendingCamera
        pendingCamera = null
        if (granted && v != null) launchCamera(v) else if (!granted) vm.error.value = "Meldiq needs the camera permission to take photos and videos."
    }
    fun openCamera(video: Boolean) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchCamera(video)
        else { pendingCamera = video; cameraPermission.launch(android.Manifest.permission.CAMERA) }
    }
    // Contact: one picked entry, previewed before sending.
    // Round 7: a Meldiq profile card (yours or someone you talk to), never an address-book entry or phone number.
    var profileSheet by remember { mutableStateOf(false) }
    // Location: once, after the person confirms.
    var locationSheet by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf<com.meldiq.android.data.model.SharedLocation?>(null) }
    var locating by remember { mutableStateOf(false) }
    var locationProblem by remember { mutableStateOf<String?>(null) }
    fun locate() {
        locating = true
        locationProblem = null
        scope.launch {
            val l = com.meldiq.android.media.OneShotLocation.current(ctx)
            location = l?.let { com.meldiq.android.data.model.RichRules.clean(com.meldiq.android.data.model.SharedLocation(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null)) }
            if (location == null) locationProblem = "Couldn't get your location. Check that location is turned on."
            locating = false
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) { locationSheet = true; locate() } else vm.error.value = "Meldiq needs location access to share where you are."
    }
    fun shareLocation() {
        val has = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (has) { locationSheet = true; locate() }
        else locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    var emojiSheet by remember { mutableStateOf(false) }
    var pollSheet by remember { mutableStateOf(false) }
    var remindFor by remember { mutableStateOf<MessageEntity?>(null) }
    val stickerPrefs = remember { com.meldiq.android.data.repo.StickerPrefs(ctx) }
    // Saved for later (Home › Later): which messages here are saved, and whether this server keeps them.
    val saved by vm.saved.collectAsStateWithLifecycle()
    val savingSupported by vm.savingSupported.collectAsStateWithLifecycle()
    val savedIds = remember(saved) { saved.map { it.messageId }.toSet() }
    val savedHere = remember(saved) { saved.count { it.conversationId == conversationId && !it.done } }
    // Search in this conversation: messages are decrypted only on this phone, so the search runs here.
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    LaunchedEffect(searchQuery, searching) {
        if (!searching) return@LaunchedEffect
        kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
        searchResults = vm.search(searchQuery)
    }
    androidx.activity.compose.BackHandler(enabled = searching) { searching = false; searchQuery = "" }
    val translations by vm.translations.collectAsStateWithLifecycle()
    var translateFor by remember { mutableStateOf<MessageEntity?>(null) }
    // Links: opened as the Chat settings say, after a confirmation when asked for or when the address looks misleading.
    var linkToOpen by remember { mutableStateOf<String?>(null) }
    val onOpenLink: (String) -> Unit = { url ->
        if (com.meldiq.android.ui.LinkOpener.needsConfirmation(url, chatSettings.linkOpening)) linkToOpen = url
        else if (!com.meldiq.android.ui.LinkOpener.open(ctx, url, chatSettings.linkOpening == com.meldiq.android.data.repo.LinkOpening.IN_APP)) vm.error.value = "No app can open this link"
    }
    val composerPreview by vm.linkPreview.collectAsStateWithLifecycle()
    // Received photos download by themselves when Settings › Chats › media allows (videos and files wait for a tap).
    LaunchedEffect(messages.firstOrNull()?.id, chatSettings.autoDownload) { vm.autoDownloadPhotos(messages.take(40), ctx) }
    var forwardFor by remember { mutableStateOf<MessageEntity?>(null) }
    var forwardTo by remember { mutableStateOf<com.meldiq.android.data.db.ConversationEntity?>(null) }
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
    val permission = com.meldiq.android.ui.screens.calls.rememberCallPermission()
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
            if (text.isEmpty() && editing == null) conv?.draft?.let { setText(it) }
        }
    }
    // The reply target follows its message: dropped if it's deleted, refreshed if it's edited.
    LaunchedEffect(messages, replyTo?.id) { replyTo?.let { t -> vm.refreshReplyTarget(messages.firstOrNull { it.id == t.id }) } }
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
    // Mentions are drawn from the message's mentioned ids, never from the text alone (docs/MENTIONS.md).
    fun mentionSpansFor(m: MessageEntity): List<MentionSpan> {
        if (m.text.isEmpty() || !m.text.contains('@')) return emptyList()
        val stored = MentionRules.fromStored(vm.c.messages.mentions(m))
        return MentionRules.spans(m.text, stored.ids, stored.all) { id -> users[id]?.username ?: mentionMembers.firstOrNull { it.id == id }?.username }
    }
    fun previewOf(m: MessageEntity) = m.text.ifEmpty { VoiceNote.label(vm.c.messages.attachments(m)) ?: "📎 Attachment" }
    /** Scrolls to a message (a search hit or a pin), loading older pages first when it isn't loaded yet. */
    fun jumpTo(id: String) {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
        else vm.loadUntil(id) { found -> scope.launch { listState.animateScrollToItem(found) } }
    }
    val peer = conv?.let { ConversationRepository.peer(it, me) }
    val onlineCount by vm.onlineCount.collectAsStateWithLifecycle()
    val onlineLabel: (Int) -> String = { n -> ctx.getString(com.meldiq.android.R.string.r3_online_count, n) }
    androidx.compose.runtime.LaunchedEffect(conversationId, conv?.type) {
        while (conv?.type == "CHANNEL") {
            vm.refreshOnline()
            kotlinx.coroutines.delay(ONLINE_REFRESH_MS)
        }
    }
    val peerUser = peer?.let { users[it.id] }
    val isGroup = conv?.type != "DM"
    // People on another server (federation): their presence isn't shared across servers, and calls with them aren't available yet.
    val remoteMembers = conv?.let { ConversationRepository.remoteMembers(it) }.orEmpty()
    val federation by vm.c.conversations.federation.collectAsStateWithLifecycle()
    val remoteServers = federation[conversationId]?.servers.orEmpty()
    var showSecurity by remember { mutableStateOf(false) }
    var showPinned by remember { mutableStateOf(false) }
    // The composer's attach/emoji buttons step aside while typing (round 7, [ComposerAux]); any touch on the messages
    // brings them back at once.
    val composerAux = remember { mutableStateOf(ComposerAux.State()) }
    // Saving several files at once (round 7): the selected messages (null while not selecting), and files the person
    // left out of the batch in the review sheet.
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    var bulkSheet by remember { mutableStateOf(false) }
    var bulkExcluded by remember { mutableStateOf(emptySet<String>()) }
    val bulkBatch by vm.bulk.batch.collectAsStateWithLifecycle()
    fun bulkPicked(): List<Pair<String, AttachmentRef>> = selected.orEmpty().let { ids ->
        messages.filter { it.id in ids }.sortedBy { it.sortTs }.flatMap { m -> vm.c.messages.attachments(m).map { m.id to it } }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val plan = BulkSave.plan(bulkPicked(), bulkExcluded)
        if (uri != null && plan is BulkSave.Plan.Ready) vm.bulk.start(plan.items, BulkSaver.Target.Folder(uri))
    }
    fun endSelection() {
        vm.bulk.close()
        bulkSheet = false
        selected = null
        bulkExcluded = emptySet()
    }
    androidx.activity.compose.BackHandler(selected != null) { if (bulkBatch?.summary?.running != true) endSelection() }
    val securityFacts = com.meldiq.android.data.model.ConversationSecurity.Facts(
        type = conv?.type ?: "DM",
        peerVerified = if (conv?.type == "DM") peerUser?.verified == true else null,
        changedMembers = changedMembers.size,
        otherServers = remoteServers.size.coerceAtLeast(if (remoteMembers.isNotEmpty()) 1 else 0),
        callMediaE2ee = chatSettings.mediaE2ee,
    )
    val subtitle = when {
        threadRoot != null -> "Thread"
        typing.isNotEmpty() -> if (isGroup) "${typing.first()} is typing…" else "typing…"
        conv?.type == "DM" && peer != null && remoteMembers.isNotEmpty() -> "@${peer.username}"
        conv?.type == "DM" -> listOfNotNull(
            if (peerUser?.online == true) "Online" else "Last seen ${TimeFmt.relative(peerUser?.lastSeenAt)}",
            // Round 3: their status as they show it to you.
            listOfNotNull(peer?.statusEmoji, peer?.statusText).joinToString(" ").ifBlank { null },
        ).joinToString(" · ")
        conv?.type == "CHANNEL" -> listOfNotNull(
            conv?.scopeName, "${conv?.memberCount ?: 0} members",
            // Round 3 (the reference's "2 Online"): servers with conversation-presence.
            onlineCount?.takeIf { it > 0 }?.let { onlineLabel(it) },
        ).joinToString(" · ")
        else -> "${conv?.memberCount ?: 0} members"
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Header (or the search bar while searching this conversation)
        if (selected != null) {
            BulkSelectionBar(
                files = bulkPicked().size, messages = selected.orEmpty().size,
                onClose = { endSelection() },
                onSelectAll = { selected = messages.filter { !it.deleted && vm.c.messages.attachments(it).isNotEmpty() }.map { it.id }.toSet() },
                onSave = { bulkExcluded = emptySet(); bulkSheet = true },
            )
        } else if (searching) {
            ConversationSearchBar(searchQuery, { searchQuery = it }, onClose = { searching = false; searchQuery = "" })
        } else Row(
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
            // Text channels have no call buttons (round 7): a workspace's calls happen in its voice rooms. DMs and
            // groups still call each other.
            val textChannel = conv?.type == "CHANNEL"
            if (threadRoot == null && conv?.removed != true && canCall && conv?.voice != true && !textChannel) {
                CircleIconButton(Icons.Rounded.Videocam, "Video call", { permission(true) { vm.startCall(true) } }, tint = c.primary, enabled = call.phase == CallPhase.Idle)
                CircleIconButton(Icons.Rounded.Call, "Voice call", { permission(false) { vm.startCall(false) } }, tint = c.primary, enabled = call.phase == CallPhase.Idle)
            }
            if (threadRoot == null) PinnedButton(pinned.size) { showPinned = true }
            if (threadRoot == null) SecurityPadlock(securityFacts) { showSecurity = true }
            CircleIconButton(Icons.Rounded.Search, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.conv_search), { searching = true }, tint = c.textSecondary)
        }
        if (showSecurity) {
            ConversationSecuritySheet(
                securityFacts, peerUser?.displayName ?: peer?.displayName,
                onVerify = peer?.let { p -> { showSecurity = false; nav.navigate(Routes.user(p.id)) } },
                onDismiss = { showSecurity = false },
            )
        }
        if (showPinned) {
            PinnedMessagesSheet(
                pinned, users, ::previewOf, canUnpin = conv?.canPost != false,
                onOpen = { m -> showPinned = false; jumpTo(m.id) },
                onUnpin = { m -> vm.pin(m) },
                onDismiss = { showPinned = false },
            )
        }
        if (!searching && threadRoot == null) {
            ConversationContextStrip(
                saved = savedHere,
                onFiles = { nav.navigate(Routes.info(conversationId)) },
                onSaved = { nav.navigate(com.meldiq.android.ui.Routes.LATER) },
            )
        }
        HorizontalDivider(thickness = 1.dp, color = c.border)
        ConnectionBanner(conn, Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) { vm.c.realtime.reconnectNow() }
        // Round 3 (the reference's "… has paused their notifications"): only when their last-seen setting lets you know.
        if (conv?.type == "DM" && threadRoot == null && peer?.notificationsPaused == true) {
            StatusBanner(
                androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.r3_peer_paused, peer.displayName),
                BannerKind.Info, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                icon = androidx.compose.material.icons.Icons.Outlined.NotificationsOff,
            )
        }
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
        // A voice room (round 6): who's in it, and Join (which opens it when nobody is there yet).
        if (conv?.voice == true && call.phase == CallPhase.Idle && threadRoot == null) {
            val here = conv?.let { com.meldiq.android.ui.screens.home.roomPeople(it).size } ?: 0
            StatusBanner(
                if (here > 0) androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.vr_banner_here, here)
                else androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.vr_banner_empty),
                if (here > 0) BannerKind.Success else BannerKind.Info,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), icon = Icons.Outlined.Headset,
                action = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.vr_join), onAction = { permission(false) { vm.startCall(false) } },
            )
        } else if (activeCall != null && call.phase == CallPhase.Idle && threadRoot == null) {
            StatusBanner(
                if (conv?.activeCallVideo == true) "A video call is in progress" else "A voice call is in progress", BannerKind.Success,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), icon = Icons.Rounded.Call, action = "Join", onAction = { permission(conv?.activeCallVideo == true) { vm.joinCall(activeCall, conv?.activeCallVideo == true) } },
            )
        }
        // What connected apps posted in this channel (server-visible, labelled as such).
        if (conv?.type == "CHANNEL" && threadRoot == null) ChannelAppUpdatesBar(conversationId)

        // Messages, over Settings › Chats › wallpaper.
        Box(
            Modifier.weight(1f).then(com.meldiq.android.ui.theme.Wallpapers.modifier(chatSettings.wallpaper, c))
                .observeTouches { composerAux.value = ComposerAux.onOutside(composerAux.value) },
        ) {
            if (messages.isEmpty() && threadRoot == null && conv?.type == "CHANNEL") {
                ChannelWelcome(
                    conv?.name.orEmpty(), conv?.topic, conv?.canManage == true,
                    onEdit = { nav.navigate(Routes.info(conversationId)) }, onAddPeople = { nav.navigate(Routes.info(conversationId)) },
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (messages.isEmpty() && threadRoot == null) {
                EmptyState(
                    Icons.Rounded.Lock, "Say hello 👋",
                    if (remoteMembers.isNotEmpty()) {
                        "${remoteMembers.first().displayName} is on another server (${GlobalAddress.serverOf(remoteMembers.first().username) ?: "elsewhere"}). Messages are end-to-end encrypted on your device; both servers only relay encrypted copies, kept for at most 7 days until delivered."
                    } else {
                        "Messages here are end-to-end encrypted on your device. The server relays encrypted copies and, if members keep their history there, stores it encrypted with keys only their own devices have."
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (searching && searchQuery.isNotBlank()) {
                ConversationSearchResults(
                    searchQuery, searchResults, users, me,
                    onOpen = { hit ->
                        searching = false
                        searchQuery = ""
                        jumpTo(hit.id)
                    },
                    modifier = Modifier.fillMaxSize().background(c.background),
                )
                return@Box
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
                    val hasFiles = !m.deleted && vm.c.messages.attachments(m).isNotEmpty()
                    Column(
                        Modifier.bulkSelectable(selected != null, m.id in selected.orEmpty(), hasFiles, c.primary) {
                            val now = selected.orEmpty()
                            selected = if (m.id in now) now - m.id else now + m.id
                        },
                    ) {
                        if (older == null || !TimeFmt.sameDay(older.sortTs, m.sortTs)) DayDivider(m.sortTs)
                        SwipeToReply(canSwipeReply && selected == null, onReply = { vm.replyTo.value = m; vm.editing.value = null; focusComposer() }) {
                        MessageBubble(
                            BubbleModel(
                                m, users[m.senderUserId], showSender = first, showAvatar = lastInRun, firstInGroup = first, reply = reply,
                                replySender = reply?.let { if (it.outgoing) "You" else users[it.senderUserId]?.displayName },
                                reactions = vm.c.messages.reactions(m), attachments = vm.c.messages.attachments(m),
                                threadCount = if (threadRoot == null) threadCounts[m.id] ?: 0 else 0, isGroupConv = isGroup,
                                replyPreview = reply?.let { previewOf(it) },
                                mentions = mentionSpansFor(m),
                                rich = vm.c.messages.rich(m),
                                translation = translations[m.id],
                                animateEmoji = chatSettings.animatedEmoji,
                                textScale = chatSettings.textScale,
                                stream = chatSettings.messageLayout == com.meldiq.android.data.repo.MessageLayout.STREAM,
                                mediaCards = chatSettings.mediaCards,
                                canVote = conv?.canPost != false,
                                saved = m.id in savedIds,
                            ),
                            me = me,
                            transfer = vm.c.messages.attachments(m).firstNotNullOfOrNull { transfers[it.id] } ?: transfers[m.id],
                            localFile = { vm.c.messages.localAttachment(it) },
                            onLongPress = { if (m.kind != "call") actionFor = m },
                            onReact = { vm.react(m, it) },
                            onVote = { vm.vote(m, it) },
                            onOpenAttachment = openAttachment,
                            onOpenThread = { nav.navigate(Routes.conversation(conversationId, m.id)) },
                            onRetry = { vm.retry(m) },
                            onReplyClick = { id -> val idx = messages.indexOfFirst { it.id == id }; if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) } },
                            onMentionClick = { id -> nav.navigate(Routes.user(id)) },
                            onOpenLink = onOpenLink,
                            linkDetails = { url -> AppLinkDetails(appLinkOrg, url) },
                            onCopyText = { t -> scope.launch { clipboard.setClipEntry(ClipData.newPlainText("contact", t).toClipEntry()) } },
                            onFindPhone = { ph -> vm.findByPhone(ph) { id -> nav.navigate(Routes.user(id)) } },
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
                                    BubbleModel(
                                        r, users[r.senderUserId], true, true, true, null, null, vm.c.messages.reactions(r), vm.c.messages.attachments(r), 0, isGroup,
                                        rich = vm.c.messages.rich(r), stream = chatSettings.messageLayout == com.meldiq.android.data.repo.MessageLayout.STREAM,
                                        mediaCards = chatSettings.mediaCards, canVote = conv?.canPost != false, saved = r.id in savedIds,
                                    ),
                                    me, null, { vm.c.messages.localAttachment(it) }, { actionFor = r }, { vm.react(r, it) }, openAttachment, null, { vm.retry(r) }, {},
                                    voice = voicePlayer,
                                    onVote = { vm.vote(r, it) },
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
            // @mention autocomplete (groups and channels): the members matching what's typed after "@".
            val query = if (conv?.type != null && conv?.type != "DM" && field.selection.collapsed) MentionRules.activeQuery(text, field.selection.start) else null
            val suggestions = remember(query, mentionMembers, canMentionAll) {
                query?.let { MentionRules.suggest(mentionMembers, it.query, me, canMentionAll) }.orEmpty()
            }
            if (query != null && suggestions.isNotEmpty()) {
                MentionSuggestions(suggestions) { choice ->
                    val (t, cursor) = MentionRules.insert(text, query, choice)
                    field = TextFieldValue(t, TextRange(cursor))
                    vm.typingChanged(t)
                }
            }
            composerPreview?.let { p ->
                Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { LinkPreviewCard(p, out = false, onOpen = {}) }
                    CircleIconButton(Icons.Outlined.Close, "Send without preview", { vm.dismissPreview() }, size = 36.dp, tint = c.textSecondary)
                }
            }
            Composer(
                text = field,
                onText = { field = it; vm.typingChanged(it.text) },
                replyLabel = replyTo?.let { "Replying to ${if (it.outgoing) "yourself" else users[it.senderUserId]?.displayName ?: ""}" to previewOf(it) }
                    ?: editing?.let { "Editing message" to it.text },
                onCancelContext = { vm.replyTo.value = null; vm.editing.value = null; if (editing != null) setText("") },
                onAttach = { attachSheet = true },
                onEmoji = { emojiSheet = true },
                onSend = { vm.send(text); setText("") },
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
                aux = composerAux,
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
                if (interactive) MenuRow(Icons.AutoMirrored.Rounded.Reply, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_reply), onClick = { vm.replyTo.value = m; vm.editing.value = null; actionFor = null; focusComposer() }, trailing = null)
                if (interactive && threadRoot == null) MenuRow(Icons.Outlined.Forum, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_reply_thread), onClick = { actionFor = null; nav.navigate(Routes.conversation(conversationId, m.id)) }, trailing = null)
                if (m.text.isNotEmpty() && vm.c.messages.rich(m)?.structured != true) {
                    if (translations[m.id] != null) MenuRow(Icons.Outlined.Translate, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_show_original), onClick = { vm.showOriginal(m); actionFor = null }, trailing = null)
                    else MenuRow(Icons.Outlined.Translate, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_translate), onClick = { translateFor = m; actionFor = null }, trailing = null)
                }
                if (m.text.isNotEmpty()) MenuRow(Icons.Outlined.ContentCopy, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_copy), onClick = {
                    scope.launch { clipboard.setClipEntry(ClipData.newPlainText("message", m.text).toClipEntry()) }
                    actionFor = null
                }, trailing = null)
                if (interactive && m.outgoing && m.status != MessageStatus.FAILED && m.status != MessageStatus.SENDING) MenuRow(Icons.Outlined.Edit, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_edit), onClick = {
                    vm.editing.value = m; vm.replyTo.value = null; setText(m.text); actionFor = null
                }, trailing = null)
                if (interactive) MenuRow(Icons.Outlined.PushPin, if (m.pinned) "Unpin" else "Pin for everyone", tint = c.peach, tintBg = c.peachSoft, onClick = { vm.pin(m); actionFor = null }, trailing = null)
                if (vm.c.messages.attachments(m).isNotEmpty()) MenuRow(Icons.Outlined.CheckCircle, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.bulk_menu_select), onClick = { selected = setOf(m.id); actionFor = null }, trailing = null)
                val pollOpen = vm.c.messages.rich(m)?.let { it.poll != null && it.pollClosed != true } == true
                if (interactive && m.outgoing && pollOpen) MenuRow(Icons.Outlined.Lock, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.poll_close), onClick = { vm.closePoll(m); actionFor = null }, trailing = null)
            }
            // Saved for later (Home › Later), with an optional reminder: personal, synced to the person's other devices.
            if (savingSupported && !m.deleted && m.kind == "text" && m.status != MessageStatus.SENDING && m.status != MessageStatus.FAILED) {
                if (m.id in savedIds) MenuRow(Icons.Outlined.BookmarkRemove, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.later_remove), onClick = { vm.removeSaved(m); actionFor = null }, trailing = null)
                else MenuRow(Icons.Outlined.BookmarkAdd, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.later_save), onClick = {
                    vm.saveForLater(m)
                    actionFor = null
                    Toast.makeText(ctx, ctx.getString(com.meldiq.android.R.string.later_saved_toast), Toast.LENGTH_SHORT).show()
                }, trailing = null)
                MenuRow(Icons.Outlined.Alarm, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.later_remind), onClick = { remindFor = m; actionFor = null }, trailing = null)
            }
            // Saving and sharing are explicit actions; tapping a photo only opens the viewer.
            val file = if (!m.deleted) vm.c.messages.attachments(m).firstOrNull { it.id != "pending" && !VoiceNote.isVoice(it) } else null
            if (file != null) {
                if (MediaGallery.kindOf(file) != null) MenuRow(Icons.Outlined.Download, "Save to Gallery", onClick = { actionFor = null; onMedia(MediaAction.SaveToGallery, file) }, trailing = null)
                MenuRow(Icons.Outlined.FolderOpen, "Save to Files", onClick = { actionFor = null; onMedia(MediaAction.SaveToFiles, file) }, trailing = null)
                MenuRow(Icons.Outlined.Share, "Share…", onClick = { actionFor = null; onMedia(MediaAction.Share, file) }, trailing = null)
            }
            if (!m.deleted && m.kind == "text" && m.status != MessageStatus.SENDING && vm.c.messages.attachments(m).none { it.id == "pending" }) {
                MenuRow(Icons.AutoMirrored.Outlined.Forward, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_forward), onClick = { forwardFor = m; actionFor = null }, trailing = null)
                // Explicit actions of connected apps (create a Jira issue, add a calendar event) on this message's text.
                if (m.text.isNotBlank()) appActions.forEach { a ->
                    MenuRow(Icons.Outlined.Apps, androidx.compose.ui.res.stringResource(a.label), subtitle = a.appName, tint = c.peach, tintBg = c.peachSoft, onClick = { sendToApp = a to m.text; actionFor = null }, trailing = null)
                }
            }
            if (m.outgoing && (m.status == MessageStatus.SENDING || m.status == MessageStatus.FAILED) && vm.c.messages.attachments(m).any { it.id == "pending" }) {
                MenuRow(Icons.Outlined.Close, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_cancel_upload), subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_cancel_upload_sub), tint = c.danger, tintBg = c.dangerSoft, onClick = { vm.cancelUpload(m); actionFor = null }, trailing = null)
            }
            if (m.status == MessageStatus.FAILED) MenuRow(Icons.Outlined.Refresh, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_retry), onClick = { vm.retry(m); actionFor = null }, trailing = null)
            if (m.outgoing) MenuRow(Icons.Outlined.Info, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_info), tint = c.lavender, tintBg = c.lavenderSoft, onClick = { infoFor = m; actionFor = null }, trailing = null)
            if (interactive && m.outgoing && !m.deleted) MenuRow(Icons.Outlined.DeleteOutline, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.menu_delete), tint = c.danger, tintBg = c.dangerSoft, onClick = { vm.delete(m); actionFor = null }, trailing = null)
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
    val aiStatus = com.meldiq.android.ui.screens.workspace.ai.rememberAiStatus(appLinkOrg)
    val aiOrg = appLinkOrg
    if (aiSheet == AttachAction.AiWrite && aiOrg != null) {
        com.meldiq.android.ui.screens.workspace.ai.AiWriteSheet(aiOrg, field.text, onUse = { t -> setText(t); aiSheet = null }, onDismiss = { aiSheet = null })
    } else if (aiSheet == AttachAction.AiCatchUp && aiOrg != null) {
        val lines = remember(messages, users) {
            com.meldiq.android.ui.screens.workspace.ai.AiRules.catchUp(messages.asReversed().map { m ->
                com.meldiq.android.ui.screens.workspace.ai.AiRules.Line(users[m.senderUserId]?.displayName ?: "?", m.text, m.sortTs, m.kind == "text" && !m.deleted)
            })
        }
        com.meldiq.android.ui.screens.workspace.ai.AiCatchUpSheet(aiOrg, conversationId, lines, onDismiss = { aiSheet = null })
    }
    if (attachSheet) {
        AttachPanel(maxFile, onAction = { action ->
            attachSheet = false
            when (action) {
                AttachAction.Gallery -> mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                AttachAction.Camera -> openCamera(false)
                AttachAction.RecordVideo -> openCamera(true)
                AttachAction.Files -> picker.launch("*/*")
                AttachAction.Contact -> profileSheet = true
                AttachAction.Location -> shareLocation()
                AttachAction.Poll -> pollSheet = true
                AttachAction.FromApp -> shareFromApp = true
                AttachAction.AiWrite, AttachAction.AiCatchUp -> aiSheet = action
            }
        }, onDismiss = { attachSheet = false }, aiWrite = aiStatus?.available == true, aiCatchUp = aiStatus?.available == true && conv?.type == "CHANNEL" && threadRoot == null)
    } else if (shareFromApp) {
        // A channel's own workspace; for DMs and groups, the workspace selected on Home (connections belong to one).
        ShareFromAppSheet(appLinkOrg, onShare = { text, card -> shareFromApp = false; vm.sendLink(text, card) }, onDismiss = { shareFromApp = false })
    } else if (batch.isNotEmpty()) {
        BatchComposer(
            batch, batchNote, onRemove = vm::unpick, onAddMore = { attachSheet = true },
            onSend = { q, vq, caption -> vm.sendBatch(q, vq, caption) }, onDismiss = vm::clearBatch,
            maxFile = maxFile,
        )
    }
    if (bulkSheet) {
        val picked = bulkPicked()
        BulkSaveSheet(
            plan = BulkSave.plan(picked, bulkExcluded),
            candidates = (BulkSave.plan(picked) as? BulkSave.Plan.Ready)?.items.orEmpty(),
            excluded = bulkExcluded,
            onToggle = { k -> bulkExcluded = if (k in bulkExcluded) bulkExcluded - k else bulkExcluded + k },
            batch = bulkBatch,
            onSaveToFolder = { runCatching { pickFolder.launch(null) }.onFailure { vm.error.value = ctx.getString(com.meldiq.android.R.string.bulk_no_picker) } },
            onSaveToGallery = { (BulkSave.plan(picked, bulkExcluded) as? BulkSave.Plan.Ready)?.let { vm.bulk.start(it.items, BulkSaver.Target.Gallery) } },
            onCancel = vm.bulk::cancel,
            onRetry = vm.bulk::retry,
            onDone = { endSelection() },
            onClose = { bulkSheet = false },
        )
    }
    if (profileSheet) {
        val people by vm.c.db.users().observeAll().collectAsStateWithLifecycle(emptyList())
        ProfileShareSheet(
            people, people.firstOrNull { it.id == me },
            onSend = { vm.sendRich(com.meldiq.android.data.model.RichContent(contact = it)); profileSheet = false },
            onDismiss = { profileSheet = false },
        )
    }
    if (locationSheet) {
        LocationShareSheet(
            location, locating, locationProblem, onRetry = ::locate,
            onSend = { vm.sendRich(com.meldiq.android.data.model.RichContent(location = it)); locationSheet = false; location = null },
            onDismiss = { locationSheet = false; location = null },
        )
    }
    if (emojiSheet) {
        EmojiPickerSheet(
            chatSettings.animatedEmoji,
            onInsert = { e -> val t = field.text; val sel = field.selection.start.coerceIn(0, t.length); field = TextFieldValue(t.substring(0, sel) + e + t.substring(sel), TextRange(sel + e.length)) },
            onSendAnimated = { e -> vm.sendRich(com.meldiq.android.data.model.RichContent(animoji = e)); emojiSheet = false },
            onDismiss = { emojiSheet = false },
            stickers = stickerPrefs,
            onSendSticker = { s -> vm.sendSticker(s); emojiSheet = false },
        )
    }
    if (pollSheet) PollComposerSheet(onSend = { poll -> vm.sendRich(com.meldiq.android.data.model.RichContent(poll = poll)); pollSheet = false }, onDismiss = { pollSheet = false })
    remindFor?.let { m ->
        com.meldiq.android.ui.screens.home.RemindSheet(
            current = saved.firstOrNull { it.messageId == m.id }?.remindAt,
            onPick = { at -> vm.remind(m, at); remindFor = null },
            onDismiss = { remindFor = null },
        )
    }
    sendToApp?.let { (action, text) ->
        val org = appLinkOrg
        if (org != null) com.meldiq.android.ui.screens.workspace.apps.SendToAppSheet(org, action, text, onDismiss = { sendToApp = null }, onDone = { title ->
            sendToApp = null
            android.widget.Toast.makeText(ctx, ctx.getString(com.meldiq.android.R.string.send_done, title), android.widget.Toast.LENGTH_SHORT).show()
        })
    }
    forwardFor?.let { m ->
        val all by vm.c.conversations.observeAll().collectAsStateWithLifecycle(emptyList())
        ForwardSheet(all, me, onPick = { forwardTo = it }, onDismiss = { forwardFor = null })
        forwardTo?.let { dest ->
            val destTitle = com.meldiq.android.data.repo.ConversationRepository.title(dest, me)
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { forwardTo = null },
                title = { Text("Forward to $destTitle?") },
                text = { Text(if (vm.c.messages.attachments(m).isNotEmpty()) "The files are encrypted again for that chat and uploaded." else "It's sent as a new message, marked as forwarded.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        vm.forward(m, dest.id) { vm.notice.value = "Forwarded to $destTitle" }
                        forwardTo = null
                        forwardFor = null
                    }) { Text("Forward") }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { forwardTo = null }) { Text("Cancel") } },
            )
        }
    }
    linkToOpen?.let { url ->
        com.meldiq.android.ui.LinkOpenSheet(
            url,
            onOpen = { inApp -> linkToOpen = null; if (!com.meldiq.android.ui.LinkOpener.open(ctx, url, inApp)) vm.error.value = "No app can open this link" },
            onCopy = { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("link", url).toClipEntry()) }; linkToOpen = null },
            onDismiss = { linkToOpen = null },
        )
    }
    translateFor?.let { m ->
        GlassSheet({ translateFor = null }, title = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.translate_title)) {
            if (!vm.c.translator.consented) {
                Text(androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.translate_consent), style = Griip.type.bodySmall, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
            }
            Text(androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.translate_into), style = Griip.type.label, color = c.textSecondary)
            vm.c.translator.targets.forEach { t ->
                MenuRow(
                    Icons.Outlined.Translate, com.meldiq.android.ui.AppLanguage.nativeName(if (t == "zh") "zh-CN" else t),
                    trailing = { if (t == vm.c.translator.lastTarget) Icon(Icons.Rounded.Check, null, tint = c.primary) },
                    onClick = { vm.c.translator.consent(); vm.translate(m, t); translateFor = null },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    riskyOpen?.let { (ref, why) ->
        GlassSheet({ vm.riskyOpen.value = null }, title = "Open “${ref.name}”?") {
            Text(why, style = Griip.type.body, color = Griip.colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Files are end-to-end encrypted, so the server can't scan them for viruses. Open it only if you trust the sender and expected this file.",
                style = Griip.type.bodySmall, color = Griip.colors.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
            com.meldiq.android.ui.components.PillButton("Cancel", { vm.riskyOpen.value = null }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            com.meldiq.android.ui.components.PillButton(
                "Open anyway", { vm.riskyOpen.value = null; vm.openAttachment(ctx, ref, confirmed = true) }, Modifier.fillMaxWidth(),
                tone = com.meldiq.android.ui.components.ButtonTone.Danger,
            )
            Spacer(Modifier.height(16.dp))
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

/** Past this many characters the composer's corners tighten, as it is about to wrap onto a second line. */
private const val COMPOSER_PILL_CHARS = 40

@Composable
private fun Composer(
    text: TextFieldValue,
    onText: (TextFieldValue) -> Unit,
    replyLabel: Pair<String, String>?,
    onCancelContext: () -> Unit,
    onAttach: () -> Unit,
    onEmoji: () -> Unit,
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
    aux: MutableState<ComposerAux.State> = remember { mutableStateOf(ComposerAux.State()) },
) {
    val c = Griip.colors
    // Round 7: typing hides attach and emoji; 2 s without typing brings them back. Keyed on the generation, so newer
    // typing or an outside touch cancels a running timer instead of letting it fire late ([ComposerAux]).
    val auxState = aux.value
    LaunchedEffect(auxState.generation) {
        if (auxState.hidden) {
            kotlinx.coroutines.delay(ComposerAux.IDLE_MS)
            aux.value = ComposerAux.onIdle(aux.value, auxState.generation)
        }
    }
    // Emptied from elsewhere (sent, edit cancelled): show them at once.
    if (text.text.isEmpty() && auxState.hidden) SideEffect { aux.value = ComposerAux.onOutside(aux.value) }
    val showAux = !auxState.hidden
    // The field grows line by line (up to six, then scrolls inside); the growth is animated unless animations are off.
    val reduced = com.meldiq.android.ui.theme.rememberReducedMotion()
    val grow = if (reduced) androidx.compose.animation.core.snap() else com.meldiq.android.ui.theme.Motion.quick<androidx.compose.ui.unit.IntSize>()
    val multiline = text.text.contains('\n') || text.text.length > COMPOSER_PILL_CHARS
    val fieldShape = RoundedCornerShape(if (multiline) 18.dp else 24.dp)
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
                AnimatedVisibility(
                    showAux,
                    enter = if (reduced) EnterTransition.None else expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = if (reduced) ExitTransition.None else shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    Row {
                        ComposerIcon(Icons.Outlined.AttachFile, "Attach a photo, video, file, contact or location", onAttach)
                        ComposerIcon(Icons.Outlined.EmojiEmotions, "Emoji and animated emoji", onEmoji)
                    }
                }
                if (!showAux) Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).animateContentSize(grow).clip(fieldShape)
                        .background(c.surfaceVariant).border(1.dp, c.border, fieldShape)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.text.isEmpty()) Text(placeholder, style = Griip.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(
                        value = text,
                        onValueChange = { new -> aux.value = ComposerAux.onTextChanged(aux.value, text.text, new.text); onText(new) },
                        textStyle = Griip.type.body.copy(color = c.textPrimary), cursorBrush = SolidColor(c.primary), maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                            .onFocusChanged { if (!it.isFocused) aux.value = ComposerAux.onOutside(aux.value) }
                            .semantics { contentDescription = placeholder },
                    )
                }
                Spacer(Modifier.width(6.dp))
                val enabled = text.text.isNotBlank()
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

/**
 * Calls [onTouch] when a finger goes down anywhere in this area, without taking the touch from what's underneath (it
 * listens in the initial pass and consumes nothing): messages, menus and scrolling behave as before.
 */
private fun Modifier.observeTouches(onTouch: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onTouch()
    }
}
