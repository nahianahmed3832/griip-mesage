package com.griipbuddy.message.ui.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T4.2: the in-app scanner accepts only Griip invite codes, and decodes the QR codes the invite screen draws. */
class InviteScanTest {
    @Test
    fun acceptsTheInviteLinkTheSharedTextAndABareCode() {
        assertEquals("ABCD-EFGH-JK23", InviteCodes.fromScan("griip://invite/ABCD-EFGH-JK23"))
        assertEquals("ABCD-EFGH-JK23", InviteCodes.fromScan("GRIIP://INVITE/abcdefghjk23"))
        assertEquals(
            "ABCD-EFGH-JK23",
            InviteCodes.fromScan("Join Acme on Griip Message with invite code ABCD-EFGH-JK23 (griip://invite/ABCD-EFGH-JK23)"),
        )
        assertEquals("ABCD-EFGH-JK23", InviteCodes.fromScan("  abcd-efgh-jk23\n"))
    }

    @Test
    fun refusesAnythingThatIsNotAGriipInvite() {
        for (s in listOf(
            "https://example.com/invite/ABCD-EFGH-JK23", // a web link is never followed
            "http://griip.invalid/ABCDEFGHJK23",
            "griip://invite/ABC", // too short
            "WIFI:S:office;T:WPA;P:secret;;",
            "hello world",
            "",
        )) assertNull(s, InviteCodes.fromScan(s))
    }

    @Test
    fun decodesTheQrCodeTheInviteScreenDraws() {
        val text = "griip://invite/ABCD-EFGH-JK23"
        val (lum, w, h) = render(text, scale = 4, quiet = 40)
        val decoded = QrDecoder.decode(lum, w, w, h)
        assertEquals(text, decoded)
        assertEquals("ABCD-EFGH-JK23", InviteCodes.fromScan(decoded!!))
    }

    @Test
    fun decodesFromAPaddedCameraRow() {
        // Camera planes often have a row stride wider than the image.
        val (lum, w, h) = render("griip://invite/WXYZ-2345-6789", scale = 3, quiet = 30)
        val stride = w + 64
        val padded = ByteArray(stride * h) { 0x7F }
        for (y in 0 until h) System.arraycopy(lum, y * w, padded, y * stride, w)
        assertEquals("griip://invite/WXYZ-2345-6789", QrDecoder.decode(padded, stride, w, h))
    }

    @Test
    fun aFrameWithoutACodeGivesNull() {
        assertNull(QrDecoder.decode(ByteArray(320 * 240) { (it % 251).toByte() }, 320, 320, 240))
    }

    /** Renders [text] as a QR code into an 8-bit luminance image (dark = 0, light = 255). */
    private fun render(text: String, scale: Int, quiet: Int): Triple<ByteArray, Int, Int> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
        val w = m.width * scale + 2 * quiet
        val h = m.height * scale + 2 * quiet
        val out = ByteArray(w * h) { 0xFF.toByte() }
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) {
            for (dy in 0 until scale) for (dx in 0 until scale) out[(quiet + y * scale + dy) * w + quiet + x * scale + dx] = 0
        }
        return Triple(out, w, h)
    }
}
