package com.griipbuddy.orbit.data.api

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The registration body must match the server's schema (T1.6): `inviteCode` sent when given, omitted otherwise. */
class RegisterReqTest {
    private fun req(invite: String?) = RegisterReq("nusrat", "Nusrat", "IDKEY", 3, DeviceReg("Phone", "SIGN", "ENC", "CERT"), "nonce", "sig", invite)

    @Test
    fun sendsTheInvitationCode() {
        val body = GriipJson.parseToJsonElement(GriipJson.encodeToString(RegisterReq.serializer(), req("ABCD-EFGH-JKMN"))).jsonObject
        assertEquals("ABCD-EFGH-JKMN", body["inviteCode"]!!.jsonPrimitive.content)
    }

    @Test
    fun omitsTheFieldWhenThereIsNoCode() {
        val body = GriipJson.parseToJsonElement(GriipJson.encodeToString(RegisterReq.serializer(), req(null))).jsonObject
        assertFalse(body.containsKey("inviteCode"))
    }
}
