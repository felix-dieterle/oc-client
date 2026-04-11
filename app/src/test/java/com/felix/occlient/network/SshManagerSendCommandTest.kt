package com.felix.occlient.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SshManagerSendCommandTest {

    @Test
    fun sendCommand_writesCarriageReturn_notNewline() = runBlocking {
        val manager = SshManager()
        val out = ByteArrayOutputStream()

        setPrivateField(manager, "outputStream", out)

        val result = manager.sendCommand("hallo")

        assertTrue(result.isSuccess)
        assertArrayEquals("hallo\r".toByteArray(Charsets.UTF_8), out.toByteArray())
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
