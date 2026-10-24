package com.meldiq.android.data.ai

import com.meldiq.android.data.api.GriipJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answers shaped exactly as the server's views write them (server/src/modules/workforce/views.ts, routes.ts) decode
 * into the app's models, including fields the app doesn't use and nulls.
 */
class AiModelsTest {
    private val install = """{"id":"agi_1","agentId":"content_writer","version":1,"state":"active","displayName":"Writer","avatar":null,"avatarColor":"#5B5BD6",
        "description":"Writes","roleTitle":"Writer","language":null,"style":"balanced","instructions":"","tools":["files.read"],"executionMode":"review",
        "effectiveMode":"review","maxJobsPerDay":null,"maxConcurrent":null,"invokers":{"everyone":true,"roles":[]},"rev":3,
        "skill":{"id":"content.write","version":1,"name":"Content Writer","category":"marketing"},"badge":"AI","createdAt":1,"updatedAt":2}"""
    private val file = """{"id":"aif_1","role":"artifact","jobId":"aij_1","installId":"agi_1","agentId":"content_writer","skillVersion":1,"kind":"document",
        "name":"post.md","mime":"text/markdown","size":120,"sha256":"ab","version":2,"supersedes":"aif_0","approvalState":"pending","createdAt":5,"expiresAt":99}"""
    private val summary = """{"id":"aij_2","agent":{"installId":"agi_1","agentId":"content_writer","version":1,"displayName":"Writer","badge":"AI"},
        "skillId":"content.write","state":"running","progress":40,"note":"Drafting","requestedBy":"u1","conversationId":null,"parentJobId":"aij_1",
        "rootJobId":"aij_1","depth":1,"revision":0,"attempts":1,"maxAttempts":3,"error":null,"createdAt":1,"startedAt":2,"finishedAt":null,"updatedAt":3}"""

    @Test
    fun `overview and installs`() {
        val o = GriipJson.decodeFromString<AiOverview>(
            """{"enabled":true,"paused":false,"aiRequestsOn":true,"approvalFloor":"standard","mediaEngine":{"available":true,"detail":"ffmpeg 7"},
            "permissions":{"canUse":true,"canManage":false,"canApprove":true},"agents":[$install],"notice":"Not end-to-end encrypted."}""",
        )
        assertTrue(o.enabled && o.permissions.canApprove)
        assertEquals("Writer", o.agents.single().displayName)
        assertEquals(3, o.agents.single().rev)
        assertNull(o.agents.single().avatar)
    }

    @Test
    fun `a job with files, approvals, steps and events`() {
        val j = GriipJson.decodeFromString<AiJobRes>(
            """{"job":{"id":"aij_1","agent":{"installId":"agi_1","agentId":"content_writer","version":1,"displayName":"Writer","badge":"AI"},"skillId":"content.write",
            "state":"awaiting_approval","progress":90,"note":null,"requestedBy":"u1","conversationId":"c1","parentJobId":null,"rootJobId":"aij_1","depth":0,
            "revision":1,"attempts":1,"maxAttempts":3,"error":{"code":"x","message":"y"},"createdAt":1,"startedAt":2,"finishedAt":null,"updatedAt":3,
            "input":{"brief":"Write"},"output":{"words":120},"files":[$file],"sharedMessages":20,
            "approvals":[{"id":"apr_1","jobId":"aij_1","action":"final.export","fileId":"aif_1","fileSha256":"ab","summary":"Export","state":"pending","requestedAt":4,"decidedBy":null,"decidedAt":null,"note":null}],
            "steps":[$summary],"events":[{"at":1,"kind":"state","detail":{"to":"running"}}]}}""",
        ).job
        assertEquals("awaiting_approval", j.state)
        assertEquals("apr_1", AgentRules.pendingApproval(j)?.id)
        assertEquals(20, j.sharedMessages)
        assertEquals(1, AgentRules.currentFiles(j.files).size)
        assertEquals(40, j.steps.single().progress)
    }

    @Test
    fun `catalog, channel agents and posts`() {
        val cat = GriipJson.decodeFromString<AiCatalog>(
            """{"agents":[{"agent":{"id":"content_writer","version":1,"name":"Content Writer","description":"d","roleTitle":"r","category":"marketing",
            "capabilities":["posts"],"examples":["Write a post"],"avatarColor":"#5B5BD6","skill":{"id":"content.write","version":1,"tools":[],"requiredTools":[],
            "modelCategories":["text"],"requiredModelCategories":["text"],"files":{"min":0,"max":5,"maxBytes":1000,"types":["text/plain"]},"readsMessages":true,
            "limits":{"maxSeconds":60},"approvals":[],"errors":[],"inputSchema":{"type":"object","properties":{"brief":{"type":"string"},"length":{"type":"integer"}}}},
            "integrations":[],"defaults":{"mode":"review"}},"available":true,"unavailableReason":null,"setup":{"ready":false,"missing":["No model"],"unavailableActions":[]},"installed":null}]}""",
        )
        val e = cat.agents.single()
        assertTrue(e.agent.skill.readsMessages)
        assertEquals(listOf("length"), SkillForm.fields(e.agent.skill.inputSchema).map { it.key })
        val ch = GriipJson.decodeFromString<AiChannelAgentsRes>("""{"agents":[${install.dropLast(1)},"activation":"command","canInvoke":true}],"permissions":{"canUse":true,"canManage":false,"canApprove":false}}""")
        assertEquals("command", ch.agents.single().activation)
        val posts = GriipJson.decodeFromString<AiPostsRes>(
            """{"posts":[{"id":"aip_1","conversationId":"c1","threadRoot":null,"kind":"result","body":"Done","jobId":"aij_1","approvalId":null,"authorId":null,
            "agent":{"installId":"agi_1","displayName":"Writer","avatarColor":null,"hasAvatar":false,"skill":"Content Writer","badge":"AI"},"files":[$file],"encrypted":false,"createdAt":9}]}""",
        )
        assertEquals("result", posts.posts.single().kind)
        assertEquals("post.md", posts.posts.single().files.single().name)
    }
}
