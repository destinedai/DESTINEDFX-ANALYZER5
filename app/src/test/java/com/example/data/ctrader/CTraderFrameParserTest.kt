package com.example.data.ctrader

import com.xtrader.protocol.proto.commons.ProtoMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CTraderFrameParserTest {
    @Test
    fun parsesAValidFrameUsingOnlyItsExactSlice() {
        val message = ProtoMessage.newBuilder().setClientMsgId("one").setPayloadType(123).build()

        val parsed = parseCTraderFrames(frame(message))

        assertEquals(listOf(message), parsed)
    }

    @Test
    fun parsesMultipleMessagesInOneWebSocketFrame() {
        val first = ProtoMessage.newBuilder().setClientMsgId("one").setPayloadType(123).build()
        val second = ProtoMessage.newBuilder().setClientMsgId("two").setPayloadType(456).build()

        val parsed = parseCTraderFrames(frame(first, second))

        assertEquals(listOf(first, second), parsed)
    }

    @Test
    fun rejectsTruncatedLengthPrefixAndPayload() {
        val message = ProtoMessage.newBuilder().setPayloadType(123).build()
        val complete = frame(message)

        assertNull(parseCTraderFrames(complete.copyOf(3)))
        assertNull(parseCTraderFrames(complete.copyOf(complete.size - 1)))
    }

    @Test
    fun rejectsInvalidAndOversizedLengths() {
        val invalid = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array()
        val oversized = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(4 * 1024 * 1024 + 1).array()

        assertNull(parseCTraderFrames(invalid))
        assertNull(parseCTraderFrames(oversized))
    }

    @Test
    fun rejectsMalformedProtobufWithoutReturningEarlierMessages() {
        val valid = ProtoMessage.newBuilder().setPayloadType(123).build()
        val validBytes = valid.toByteArray()
        val malformed = byteArrayOf(0x0a, 0x05, 0x01)
        val bytes = ByteBuffer.allocate(4 + validBytes.size + 4 + malformed.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(validBytes.size).put(validBytes)
            .putInt(malformed.size).put(malformed)
            .array()

        assertNull(parseCTraderFrames(bytes))
    }

    private fun frame(vararg messages: ProtoMessage): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        messages.forEach { message ->
            val bytes = message.toByteArray()
            output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array())
            output.write(bytes)
        }
        return output.toByteArray()
    }
}
