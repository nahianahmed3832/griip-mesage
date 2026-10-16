package com.griipbuddy.orbit.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalAddressTest {
    @Test
    fun fullAddressesAreRecognised() {
        assertTrue(GlobalAddress.looksLike("@bob:b.example"))
        assertTrue(GlobalAddress.looksLike("bob:b.example"))
        assertTrue(GlobalAddress.looksLike("  @Bob.Smith:Chat.Example.org "))
        assertTrue(GlobalAddress.looksLike("@bob:localhost:18091"))
        assertFalse(GlobalAddress.looksLike("@bob")) // a local username: ordinary search
        assertFalse(GlobalAddress.looksLike("bob smith"))
        assertFalse(GlobalAddress.looksLike("@bo:b.example")) // usernames have 3–24 characters
        assertFalse(GlobalAddress.looksLike("@bob:"))
        assertFalse(GlobalAddress.looksLike("@bob:https://b.example"))
        assertFalse(GlobalAddress.looksLike("@bob:b.example/path"))
    }

    @Test
    fun remotePeopleAreTellableFromTheirUsernameOrHomeServer() {
        assertTrue(GlobalAddress.isRemote("bob:b.example", null))
        assertTrue(GlobalAddress.isRemote("bob", "gs1abc"))
        assertFalse(GlobalAddress.isRemote("bob", null))
        assertEquals("b.example", GlobalAddress.serverOf("bob:b.example"))
        assertEquals("localhost:18091", GlobalAddress.serverOf("bob:localhost:18091"))
        assertNull(GlobalAddress.serverOf("bob"))
    }
}
