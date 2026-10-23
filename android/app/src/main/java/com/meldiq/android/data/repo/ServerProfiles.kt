package com.meldiq.android.data.repo

import android.content.Context
import com.meldiq.android.data.api.ServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

/**
 * One account on one server, kept on this phone. Every profile has its own identity keys, Keystore key,
 * encrypted database, attachment folder and server address; nothing is shared, migrated or merged
 * between them. Switching profiles never moves keys from one server to another.
 */
@Serializable
data class ServerProfile(val id: String, val serverUrl: String, val createdAt: Long)

/**
 * Where a profile keeps its data. The original profile ([DEFAULT]) uses the names every install already
 * has, so an existing account keeps working unchanged when profiles arrive.
 */
data class ProfileStorage(
    val id: String,
    val dbName: String,
    val identityPrefs: String,
    val keystoreAlias: String,
    val attachmentsDir: String,
    val serverKey: String,
) {
    /**
     * Pictures downloaded for this profile (people's, communities' and groups' photos, community emoji and stickers),
     * kept in the app's files rather than its cache: Android empties the cache when space runs low, and each
     * emptying meant downloading them all again (the owner, 2026-10-23). Removed with the profile.
     */
    val picturesDir: String get() = if (id == DEFAULT) "pictures" else "pictures-$id"

    companion object {
        const val DEFAULT = "default"
        private val ID = Regex("[a-z0-9]{8,32}")

        fun of(id: String): ProfileStorage =
            if (id == DEFAULT) ProfileStorage(DEFAULT, "griip.db", "griip_identity", "griip_master_v1", "attachments", "serverUrl")
            else {
                require(ID.matches(id)) { "bad profile id" }
                ProfileStorage(id, "griip-$id.db", "griip_identity_$id", "griip_master_v1_$id", "attachments-$id", "serverUrl@$id")
            }
    }
}

/** The profiles on this phone and which one is active. Pure, so it's tested on the JVM. */
@Serializable
data class ProfileList(val extra: List<ServerProfile> = emptyList(), val active: String = ProfileStorage.DEFAULT) {
    val ids: List<String> get() = listOf(ProfileStorage.DEFAULT) + extra.map { it.id }

    fun has(id: String) = id == ProfileStorage.DEFAULT || extra.any { it.id == id }

    fun add(url: String, now: Long, id: String = newId()): ProfileList = copy(extra = extra + ServerProfile(id, url, now))

    fun activate(id: String): ProfileList {
        require(has(id)) { "unknown profile" }
        return copy(active = id)
    }

    /** The original profile and the active one can't be removed. */
    fun remove(id: String): ProfileList {
        require(id != ProfileStorage.DEFAULT && id != active) { "can't remove this profile" }
        return copy(extra = extra.filterNot { it.id == id })
    }

    /** A sanitised read: unknown or duplicate ids are dropped and a missing active profile falls back to the original. */
    fun sanitised(): ProfileList {
        val seen = HashSet<String>()
        val ok = extra.filter { p -> runCatching { ProfileStorage.of(p.id) }.isSuccess && p.id != ProfileStorage.DEFAULT && seen.add(p.id) }
        return ProfileList(ok, if (active == ProfileStorage.DEFAULT || ok.any { it.id == active }) active else ProfileStorage.DEFAULT)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    }
}

/** Stores the [ProfileList] in app-private preferences. It holds ids and server addresses only, no secrets. */
class ProfileRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("griip_profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ProfileList> = _state.asStateFlow()
    val current: ProfileList get() = _state.value

    private fun read(): ProfileList =
        prefs.getString("profiles", null)?.let { runCatching { json.decodeFromString(ProfileList.serializer(), it) }.getOrNull() }?.sanitised() ?: ProfileList()

    @Synchronized
    fun update(f: (ProfileList) -> ProfileList): ProfileList {
        val n = f(_state.value)
        prefs.edit().putString("profiles", json.encodeToString(ProfileList.serializer(), n)).commit()
        _state.value = n
        return n
    }

    /** The server a profile starts on (before it signs in); null for the original profile, which keeps its own setting. */
    fun initialUrl(id: String): String? = current.extra.firstOrNull { it.id == id }?.serverUrl
}

/** A server address typed by a person, checked before it's used. */
object ServerAddress {
    data class Parsed(val url: String, val cleartext: Boolean)

    private val LOCAL_CLEARTEXT = setOf("10.0.2.2", "localhost", "127.0.0.1")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /**
     * Normalises [input] ("msg.example.com" becomes "https://msg.example.com") or explains what's wrong.
     * Release builds connect over HTTPS only, apart from the emulator host and loopback (network security config).
     */
    fun parse(input: String, allowCleartext: Boolean): Result<Parsed> {
        val raw = input.trim()
        if (raw.isEmpty()) return Result.failure(IllegalArgumentException("Enter the server's address"))
        val withScheme = if (SCHEME.containsMatchIn(raw)) raw else "https://$raw"
        val u = runCatching { URI(withScheme) }.getOrElse { return Result.failure(IllegalArgumentException("That isn't a valid web address")) }
        val scheme = u.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return Result.failure(IllegalArgumentException("The address must start with https://"))
        val host = u.host?.lowercase()
        if (host.isNullOrEmpty()) return Result.failure(IllegalArgumentException("That address has no server name"))
        if (u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) {
            return Result.failure(IllegalArgumentException("Use just the server address, without a user name, ? or #"))
        }
        val cleartext = scheme == "http"
        if (cleartext && !allowCleartext && host !in LOCAL_CLEARTEXT) {
            return Result.failure(IllegalArgumentException("This app version connects to servers over HTTPS only"))
        }
        val port = if (u.port != -1) ":${u.port}" else ""
        val path = (u.rawPath ?: "").trimEnd('/')
        return Result.success(Parsed("$scheme://$host$port$path", cleartext))
    }

    /** Same server, ignoring case and a trailing slash. */
    fun same(a: String, b: String): Boolean = a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)
}

/** Whether this app can use a server, from its public `/v1/server-info`. */
object ServerCompat {
    /** The client API this app speaks. Servers older than the `api` field are treated as API 1. */
    const val CLIENT_API = 1

    sealed interface Verdict {
        data object Ok : Verdict
        data class Unsupported(val reason: String) : Verdict
    }

    fun check(info: ServerInfo): Verdict {
        if (!info.encryption.startsWith("griip-envelope-v1")) {
            return Verdict.Unsupported("This server uses a message encryption format this app doesn't support.")
        }
        val api = info.api ?: 1
        val minClient = info.minClientApi ?: 1
        if (minClient > CLIENT_API) return Verdict.Unsupported("This server needs a newer version of Meldiq.")
        if (api < CLIENT_API) return Verdict.Unsupported("This server is too old for this version of Meldiq.")
        return Verdict.Ok
    }
}
