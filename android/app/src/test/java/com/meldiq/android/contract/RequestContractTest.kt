package com.meldiq.android.contract

import com.meldiq.android.data.api.GuestExpiryReq
import com.meldiq.android.data.api.GuestPolicyReq
import com.meldiq.android.data.api.LayoutChannel
import com.meldiq.android.data.api.LayoutReq
import com.meldiq.android.data.api.PresenceReq
import com.meldiq.android.data.api.SendNulls
import com.meldiq.android.data.api.TimeoutReq
import com.meldiq.android.data.api.encodeBody
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The other direction of ContractFixturesTest: every request body the app sends (its typed `api.post/put/patch/delete<B, …>`
 * calls) against the route's request schema in docs/api/openapi.json. It reports, recursively into nested objects:
 * - a field the app sends that the schema doesn't have (a strict schema refuses the request; a loose one drops the field);
 * - a field the schema requires that the app may leave out (GriipJson omits nulls, defaults or not), and in a
 *   [SendNulls] model, a null the schema doesn't accept.
 * What it finds today is listed in src/test/resources/contract_request_allowlist.txt, which may only shrink.
 *
 * The table was generated from the app's typed calls (Architecture 1.0, F2); add a row when the app sends a new body.
 */
class RequestContractTest {
    private val openapi: JsonObject = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/api/openapi.json") }
        .firstOrNull { it.isFile }
        ?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        ?: error("docs/api/openapi.json not found above ${File("").absolutePath}")

    /** "METHOD /path/{param}" as the OpenAPI document names it, to the body the app sends there. */
    private val requests: List<Pair<String, KSerializer<*>>> = listOf(
        "DELETE /v1/conversations/{id}" to serializer<com.meldiq.android.data.api.DeleteChannelReq>(),
        "DELETE /v1/orgs/{orgId}" to serializer<com.meldiq.android.data.api.OrgDeleteReq>(),
        "DELETE /v2/account" to serializer<com.meldiq.android.data.account.DeleteAccountReq>(),
        "PATCH /v1/applications/{id}" to serializer<com.meldiq.android.data.api.DecideApplicationReq>(),
        "PATCH /v1/categories/{id}" to serializer<com.meldiq.android.data.api.NameReq>(),
        "PATCH /v1/conversations/{id}" to serializer<com.meldiq.android.data.api.PatchConversationReq>(),
        "PATCH /v1/expressions/{id}" to serializer<com.meldiq.android.data.api.RenameExpressionReq>(),
        "PATCH /v1/orgs/{orgId}" to serializer<com.meldiq.android.data.api.UpdateOrgReq>(),
        "PATCH /v1/orgs/{orgId}/members/{userId}" to serializer<com.meldiq.android.data.api.OrgRoleReq>(),
        "PATCH /v1/orgs/{orgId}/members/{userId}" to serializer<com.meldiq.android.data.api.OrgTitleReq>(),
        "PATCH /v1/reports/{id}" to serializer<com.meldiq.android.data.api.ReportStatusReq>(),
        "PATCH /v1/roles/{roleId}" to serializer<com.meldiq.android.data.api.PatchRoleReq>(),
        "PATCH /v1/users/me" to serializer<com.meldiq.android.data.api.UpdateMeReq>(),
        "PATCH /v2/account" to serializer<com.meldiq.android.data.account.UpdateAccountReq>(),
        "POST /v1/analytics/events" to serializer<com.meldiq.android.data.repo.AnalyticsBatchReq>(),
        "POST /v1/attachments/uploads" to serializer<com.meldiq.android.data.api.DirectUploadReq>(),
        "POST /v1/attachments/uploads/{id}/complete" to serializer<com.meldiq.android.data.api.CompleteUploadReq>(),
        "POST /v1/attachments/uploads/{id}/parts" to serializer<com.meldiq.android.data.api.PartsReq>(),
        "POST /v1/blocks" to serializer<com.meldiq.android.data.api.BlockReq>(),
        "POST /v1/calls" to serializer<com.meldiq.android.data.api.StartCallReq>(),
        "POST /v1/calls/{id}/decline" to serializer<com.meldiq.android.data.api.DeclineCallReq>(),
        "POST /v1/calls/{id}/hand" to serializer<com.meldiq.android.data.api.HandReq>(),
        "POST /v1/calls/{id}/moderate" to serializer<com.meldiq.android.data.api.ModerateReq>(),
        "POST /v1/communities/{id}/apply" to serializer<com.meldiq.android.data.api.ApplyReq>(),
        "POST /v1/connections" to serializer<com.meldiq.android.data.api.ConnectReq>(),
        "POST /v1/conversations/dm" to serializer<com.meldiq.android.data.api.DmReq>(),
        "POST /v1/conversations/group" to serializer<com.meldiq.android.data.api.GroupReq>(),
        "POST /v1/conversations/{id}/request" to serializer<com.meldiq.android.data.api.RequestAnswerReq>(),
        "POST /v1/invites/accept" to serializer<com.meldiq.android.data.api.CodeReq>(),
        "POST /v1/invites/preview" to serializer<com.meldiq.android.data.api.CodeReq>(),
        "POST /v1/meetings/{id}/respond" to serializer<com.meldiq.android.data.api.MeetingRespondReq>(),
        "POST /v1/messages" to serializer<com.meldiq.android.data.api.SendMessageReq>(),
        "POST /v1/orgs" to serializer<com.meldiq.android.data.api.CreateOrgReq>(),
        "POST /v1/orgs/{orgId}/bans" to serializer<com.meldiq.android.data.api.BanReq>(),
        "POST /v1/orgs/{orgId}/categories" to serializer<com.meldiq.android.data.api.NameReq>(),
        "POST /v1/orgs/{orgId}/channels" to serializer<com.meldiq.android.data.api.CreateChannelReq>(),
        "POST /v1/orgs/{orgId}/invites" to serializer<com.meldiq.android.data.api.InviteReq>(),
        "POST /v1/orgs/{orgId}/meetings" to serializer<com.meldiq.android.data.api.MeetingReq>(),
        "POST /v1/orgs/{orgId}/onboarding/answers" to serializer<com.meldiq.android.data.api.OnboardingAnswersReq>(),
        "POST /v1/orgs/{orgId}/roles" to serializer<com.meldiq.android.data.api.CreateRoleReq>(),
        "POST /v1/pairing/code/{code}/approve" to serializer<com.meldiq.android.data.api.PairingApproveReq>(),
        "POST /v1/queue/ack" to serializer<com.meldiq.android.data.api.AckReq>(),
        "POST /v1/reminders" to serializer<com.meldiq.android.data.repo.CreateReminderReq>(),
        "POST /v1/reminders/{id}/ack" to serializer<com.meldiq.android.data.repo.AckReminderReq>(),
        "POST /v1/reports" to serializer<com.meldiq.android.data.api.ReportReq>(),
        "POST /v1/users/devices" to serializer<com.meldiq.android.data.api.UserIdsReq>(),
        "POST /v2/account/password" to serializer<com.meldiq.android.data.account.ChangePasswordReq>(),
        "POST /v2/account/recovery-codes/revoke" to serializer<com.meldiq.android.data.account.RecoveryPasswordReq>(),
        "PUT /v1/conversations/{id}/members/{userId}" to serializer<com.meldiq.android.data.api.RoleReq>(),
        "PUT /v1/history/items" to serializer<com.meldiq.android.data.api.HistoryPutReq>(),
        "PUT /v1/me/preferences/{workspaceId}" to serializer<com.meldiq.android.data.api.PutNavPrefsReq>(),
        "PUT /v1/meetings/{id}" to serializer<com.meldiq.android.data.api.MeetingReq>(),
        "PUT /v1/orgs/{orgId}/guest-policy" to serializer<com.meldiq.android.data.api.GuestPolicyReq>(),
        "PUT /v1/orgs/{orgId}/layout" to serializer<com.meldiq.android.data.api.LayoutReq>(),
        "PUT /v1/orgs/{orgId}/members/{userId}/expiry" to serializer<com.meldiq.android.data.api.GuestExpiryReq>(),
        "PUT /v1/orgs/{orgId}/members/{userId}/timeout" to serializer<com.meldiq.android.data.api.TimeoutReq>(),
        "PUT /v1/orgs/{orgId}/onboarding" to serializer<com.meldiq.android.data.api.OnboardingPutReq>(),
        "PUT /v1/users/me/messaging" to serializer<com.meldiq.android.data.repo.ConnectionRepository.MessagingSettingsPatch>(),
        "PUT /v1/users/me/presence" to serializer<com.meldiq.android.data.api.PresenceReq>(),
        "PUT /v2/account/preferences" to serializer<com.meldiq.android.data.account.PreferencesReq>(),
    )

    private fun schemaFor(route: String): JsonObject {
        val (method, path) = route.split(" ", limit = 2)
        val op = openapi.getValue("paths").jsonObject[path]?.jsonObject?.get(method.lowercase())?.jsonObject ?: error("$route isn't in openapi.json")
        val body = op["requestBody"]?.jsonObject ?: error("$route takes no body")
        return body.getValue("content").jsonObject.getValue("application/json").jsonObject.getValue("schema").jsonObject
    }

    /**
     * The schema without its null alternative. An `anyOf` of objects (one of several bodies) becomes one object: any
     * variant's field may be sent, strict when every variant is, requiring only what every variant requires.
     */
    private fun unwrap(schema: JsonObject): JsonObject {
        val variants = (schema["anyOf"] ?: schema["oneOf"])?.jsonArray?.map { unwrap(it.jsonObject) }?.filter { type(it) != "null" } ?: return schema
        if (variants.size == 1) return variants.single()
        if (!variants.all { type(it) == "object" }) return schema
        val props = variants.flatMap { it["properties"]?.jsonObject?.entries.orEmpty() }.associate { it.key to it.value }
        val strict = variants.all { (it["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false }
        val required = variants.map { v -> v["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet() }.reduce { a, b -> a intersect b }
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"), "properties" to JsonObject(props),
                "additionalProperties" to JsonPrimitive(!strict), "required" to JsonArray(required.map(::JsonPrimitive)),
            ),
        )
    }

    private fun acceptsNull(schema: JsonObject): Boolean =
        (schema["anyOf"] ?: schema["oneOf"])?.jsonArray?.any { acceptsNull(it.jsonObject) } == true ||
            when (val t = schema["type"]) {
                is JsonPrimitive -> t.content == "null"
                is JsonArray -> t.any { it.jsonPrimitive.content == "null" }
                else -> false
            }

    private fun type(schema: JsonObject): String? = when (val t = schema["type"]) {
        is JsonPrimitive -> t.content
        is JsonArray -> t.map { it.jsonPrimitive.content }.firstOrNull { it != "null" }
        else -> null
    }

    /** [nulls]: the body is a [SendNulls] model, so its nulls are sent. */
    private fun check(where: String, d: SerialDescriptor, raw: JsonObject, out: MutableMap<String, String>, nulls: Boolean) {
        val schema = unwrap(raw)
        when (d.kind) {
            StructureKind.LIST -> {
                val items = schema["items"]?.jsonObject ?: return
                check("$where[]", d.getElementDescriptor(0), items, out, nulls)
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                if (type(schema) != "object") return
                val props = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
                val strict = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
                val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                for (i in 0 until d.elementsCount) {
                    val name = d.getElementName(i)
                    val prop = props[name]?.jsonObject
                    if (prop == null) {
                        out["$where.$name"] = if (strict) "sent, but the strict schema refuses it" else "sent, but the server drops it"
                        continue
                    }
                    // GriipJson leaves every null out (explicitNulls = false), with or without a default; SendNulls models keep them.
                    if (name in required && d.getElementDescriptor(i).isNullable && !nulls) out["$where.$name"] = "required, but left out when null (mark the model @SendNulls)"
                    if (nulls && d.getElementDescriptor(i).isNullable && !acceptsNull(prop)) out["$where.$name"] = "sent as null, which the schema refuses"
                    check("$where.$name", d.getElementDescriptor(i), prop, out, nulls)
                }
                for (name in required) if (d.getElementIndex(name) < 0) out["$where.$name"] = "required, and the app never sends it"
            }
            else -> Unit
        }
    }

    private fun findings(): Map<String, String> {
        val out = sortedMapOf<String, String>()
        for ((route, ser) in requests) {
            val nulls = ser.descriptor.annotations.any { it is SendNulls }
            check("$route ${ser.descriptor.serialName.substringAfterLast('.')}", ser.descriptor, schemaFor(route), out, nulls)
        }
        return out
    }

    @Test
    fun `every request body fits its route's schema, or is a known gap`() {
        val now = findings()
        val allowed = javaClass.getResource("/contract_request_allowlist.txt")?.readText().orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        if (System.getenv("MELDIQ_WRITE_REQUEST_ALLOWLIST") == "1") {
            File("src/test/resources/contract_request_allowlist.txt").writeText(
                "# Request-body gaps known when written (RequestContractTest); may only shrink.\n" + now.entries.joinToString("") { "${it.key}\t# ${it.value}\n" },
            )
            return
        }
        val keys = allowed.map { it.substringBefore('\t').trim() }.toSet()
        assertEquals("new request-body gaps", emptyMap<String, String>(), now.filterKeys { it !in keys })
        assertEquals("allowlisted gaps that are fixed now: remove them", emptySet<String>(), keys - now.keys)
    }

    @Test
    fun `SendNulls bodies keep their nulls, others leave them out`() {
        assertEquals("""{"until":null,"reason":null}""", encodeBody(serializer<TimeoutReq>(), TimeoutReq(null)))
        assertEquals("""{"channels":[{"id":"c","categoryId":null,"position":0}]}""", encodeBody(serializer<LayoutReq>(), LayoutReq(categories = emptyList(), channels = listOf(LayoutChannel("c", null, 0)))).replace("\"categories\":[],", ""))
        assertEquals("""{"guestsAllowed":true,"guestMaxDays":null}""", encodeBody(serializer<GuestPolicyReq>(), GuestPolicyReq(true, null)))
        assertEquals("""{"expiresAt":null}""", encodeBody(serializer<GuestExpiryReq>(), GuestExpiryReq(null)))
        assertEquals("""{"mode":"online"}""", encodeBody(serializer<PresenceReq>(), PresenceReq("online")))
    }

    @Test
    fun `every route in the table takes a body`() {
        val missing = requests.map { it.first }.distinct().filter { runCatching { schemaFor(it) }.isFailure }
        assertEquals(emptyList<String>(), missing)
        assertTrue(requests.size >= 60)
    }
}
