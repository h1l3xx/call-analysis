package com.malikov.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneParserTest {

    // ─── detectCallDirection ──────────────────────────────────────────────────

    @Test
    fun `входящий внешний звонок — direction external_incoming`() {
        val filename = "01.01.2024_10-00-00_89248330131 , 1640 (796490)_Входящий.mp3"
        assertEquals("external_incoming", PhoneParser.detectCallDirection(filename))
    }

    @Test
    fun `исходящий внешний звонок — direction external_outgoing`() {
        val filename = "01.01.2024_10-00-00_1640 (796490), 89248330131_Исходящий.mp3"
        assertEquals("external_outgoing", PhoneParser.detectCallDirection(filename))
    }

    @Test
    fun `внутренний входящий — direction internal_incoming`() {
        val filename = "01.01.2024_10-00-00_1722 (796490), 1727 (796490)_Входящий.mp3"
        assertEquals("internal_incoming", PhoneParser.detectCallDirection(filename))
    }

    @Test
    fun `внутренний исходящий — direction internal_outgoing`() {
        val filename = "01.01.2024_10-00-00_1722 (796490), 1727 (796490)_Исходящий.mp3"
        assertEquals("internal_outgoing", PhoneParser.detectCallDirection(filename))
    }

    @Test
    fun `incall-формат — direction external_incoming`() {
        val filename = "incall_20240101_1000_79248330131_71234567890.mp3"
        assertEquals("external_incoming", PhoneParser.detectCallDirection(filename))
    }

    @Test
    fun `outcall-формат — direction external_outgoing`() {
        val filename = "outcall_20240101_1000_71234567890_79248330131.mp3"
        assertEquals("external_outgoing", PhoneParser.detectCallDirection(filename))
    }

    // ─── extractManagerIdentifiers ────────────────────────────────────────────

    @Test
    fun `входящий внешний — первый идентификатор это внутренний номер после запятой`() {
        val filename = "01.01.2024_10-00-00_89248330131 , 1640 (796490)_Входящий.mp3"
        val ids = PhoneParser.extractManagerIdentifiers(filename)
        assertEquals("1640", ids.first())
    }

    @Test
    fun `исходящий внешний — первый идентификатор это внутренний номер с PBX`() {
        val filename = "01.01.2024_10-00-00_1640 (796490), 89248330131_Исходящий.mp3"
        val ids = PhoneParser.extractManagerIdentifiers(filename)
        assertEquals("1640", ids.first())
    }

    @Test
    fun `внутренний звонок — оба внутренних номера в идентификаторах`() {
        val filename = "01.01.2024_10-00-00_1722 (796490), 1727 (796490)_Исходящий.mp3"
        val ids = PhoneParser.extractManagerIdentifiers(filename)
        assertTrue(ids.containsAll(listOf("1722", "1727")), "Expected both extensions, got: $ids")
    }

    @Test
    fun `incall-формат — второй номер идёт первым (он менеджер)`() {
        // incall: numA = клиент, numB = менеджер → numB первый
        val filename = "incall_20240101_1000_79248330131_71234567890.mp3"
        val ids = PhoneParser.extractManagerIdentifiers(filename)
        assertEquals("71234567890", ids.first())
    }

    @Test
    fun `outcall-формат — первый номер идёт первым (он менеджер)`() {
        // outcall: numA = менеджер, numB = клиент → numA первый
        val filename = "outcall_20240101_1000_71234567890_79248330131.mp3"
        val ids = PhoneParser.extractManagerIdentifiers(filename)
        assertEquals("71234567890", ids.first())
    }

    @Test
    fun `нормализация 8-номера в 7-номер`() {
        val filename = "outcall_20240101_1000_89248330131_71234567890.mp3"
        val ids = PhoneParser.extractManagerIdentifiers(filename)
        // 8924... → 7924...
        assertTrue(ids.first().startsWith("7"), "Expected normalization 8→7, got: ${ids.first()}")
        assertEquals("79248330131", ids.first())
    }

    @Test
    fun `нет PBX-номера и нет мобильного — пустой список`() {
        val filename = "just_some_random_file.mp3"
        val ids = PhoneParser.extractManagerIdentifiers(filename)
        assertTrue(ids.isEmpty(), "Expected empty identifiers, got: $ids")
    }

    // ─── extractInternalCallKey ───────────────────────────────────────────────

    @Test
    fun `внутренний звонок — ключ содержит оба номера отсортированно`() {
        val filename = "01.01.2024_10-00-00_1727 (796490), 1722 (796490)_Исходящий.mp3"
        val key = PhoneParser.extractInternalCallKey(filename)
        assertEquals("01.01.2024_10-00-00_1722_1727", key)
    }

    @Test
    fun `внешний звонок — ключ дедупликации не формируется`() {
        val filename = "01.01.2024_10-00-00_1640 (796490), 89248330131_Исходящий.mp3"
        assertNull(PhoneParser.extractInternalCallKey(filename))
    }

    // ─── detectCallType ───────────────────────────────────────────────────────

    @Test
    fun `два PBX-номера — тип INTERNAL`() {
        val filename = "01.01.2024_10-00-00_1722 (796490), 1727 (796490)_Входящий.mp3"
        assertEquals(CallType.INTERNAL, PhoneParser.detectCallType(filename))
    }

    @Test
    fun `один PBX-номер входящий — тип EXTERNAL_INCOMING`() {
        val filename = "01.01.2024_10-00-00_89248330131 , 1640 (796490)_Входящий.mp3"
        assertEquals(CallType.EXTERNAL_INCOMING, PhoneParser.detectCallType(filename))
    }

    @Test
    fun `один PBX-номер исходящий — тип EXTERNAL_OUTGOING`() {
        val filename = "01.01.2024_10-00-00_1640 (796490), 89248330131_Исходящий.mp3"
        assertEquals(CallType.EXTERNAL_OUTGOING, PhoneParser.detectCallType(filename))
    }
}
