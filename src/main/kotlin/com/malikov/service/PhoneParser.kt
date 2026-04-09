package com.malikov.service

import java.text.Normalizer
import java.util.Locale

/**
 * Извлекает из имени файла записи звонка идентификаторы для поиска менеджера
 * (внутренний номер или мобильный в [managers.phone_number] / [managers.extension]).
 *
 * Формат АТС: `ДД.ММ.ГГГГ_ЧЧ-ММ-СС_СТОРОНА1 (АТС), СТОРОНА2 (АТС)_Направление.ext`
 *
 * Ключевой признак менеджера — наличие `(pbxId)` после его внутреннего номера.
 * Клиентский номер идёт без скобок и автоматически отсеивается.
 *
 * Примеры:
 * - Внутренний:         `…_1722 (796490), 1727 (796490)_Исходящий.mp3`  → [1722, 1727]
 * - Входящий от клиента: `…_89248330131 , 1640 (796490)_Входящий.mp3`   → [1640]
 * - Исходящий клиенту:  `…_1640 (796490), 89248330131_Исходящий.mp3`    → [1640]
 */
enum class CallType { INTERNAL, EXTERNAL_INCOMING, EXTERNAL_OUTGOING, UNKNOWN }

object PhoneParser {

    private val PBX_EXT_IN_PARENS = Regex("""(\d{3,5})\s*\(\d{2,}\)""")
    private val MANAGER_AFTER_COMMA = Regex(""",\s*(\d{3,5})\s*\(""")
    private val GENERIC_PHONE_CHUNK = Regex("""\+?[\d\-\s()]{7,}""")
    private val TIMESTAMP_PREFIX = Regex("""\d{2}\.\d{2}\.\d{4}_\d{2}-\d{2}-\d{2}""")
    private val IN_OUT_PATTERN = Regex("""(?i)^(incall|outcall)_\d{8}_\d{4}_(\d{10,15})_(\d{10,15})(?:_|$)""")

    fun detectCallType(filename: String): CallType {
        val nameOnly = filename.substringBeforeLast('.')
        val inOutMatch = IN_OUT_PATTERN.find(nameOnly)
        if (inOutMatch != null) {
            return when (inOutMatch.groupValues[1].lowercase(Locale.ROOT)) {
                "incall" -> CallType.EXTERNAL_INCOMING
                "outcall" -> CallType.EXTERNAL_OUTGOING
                else -> CallType.UNKNOWN
            }
        }

        val forKind = nameOnly.normalizedForKindDetection()
        val isIncoming = forKind.contains("входящ")
        val isOutgoing = forKind.contains("исходящ")
        val pbxCount = PBX_EXT_IN_PARENS.findAll(nameOnly).count()

        return when {
            pbxCount >= 2 -> CallType.INTERNAL
            pbxCount == 1 && isIncoming -> CallType.EXTERNAL_INCOMING
            pbxCount == 1 && isOutgoing -> CallType.EXTERNAL_OUTGOING
            else -> CallType.UNKNOWN
        }
    }

    /**
     * Упорядоченный список кандидатов для поиска менеджера (первый совпавший в БД выигрывает).
     */
    fun extractManagerIdentifiers(filename: String): List<String> {
        val nameOnly = filename.substringBeforeLast('.')
        val inOutMatch = IN_OUT_PATTERN.find(nameOnly)
        if (inOutMatch != null) {
            // Формат: incall/outcall_DATE_TIME_numA_numB.
            // Для incall обычно второй номер - менеджер, для outcall - первый.
            val numA = normalizeRussianPhone(inOutMatch.groupValues[2].replace(Regex("[^0-9]"), ""))
            val numB = normalizeRussianPhone(inOutMatch.groupValues[3].replace(Regex("[^0-9]"), ""))
            return when (inOutMatch.groupValues[1].lowercase(Locale.ROOT)) {
                "incall" -> listOf(numB, numA).distinct()
                "outcall" -> listOf(numA, numB).distinct()
                else -> listOf(numA, numB).distinct()
            }
        }

        val forKind = nameOnly.normalizedForKindDetection()
        val isIncoming = forKind.contains("входящ")
        val isOutgoing = forKind.contains("исходящ")

        val ordered = LinkedHashSet<String>()

        if (isIncoming) {
            MANAGER_AFTER_COMMA.find(nameOnly)?.groupValues?.get(1)?.let { ordered.add(it) }
        }

        if (isOutgoing) {
            PBX_EXT_IN_PARENS.findAll(nameOnly).forEach { m ->
                ordered.add(m.groupValues[1])
            }
        }

        if (isIncoming) {
            PBX_EXT_IN_PARENS.findAll(nameOnly).forEach { m ->
                ordered.add(m.groupValues[1])
            }
        }

        if (ordered.isEmpty()) {
            extractRussianPhones(nameOnly).forEach { ordered.add(it) }
        }

        return ordered.toList()
    }

    /**
     * Для внутренних звонков возвращает ключ дедупликации: "timestamp_ext1_ext2"
     * (номера отсортированы). Два файла одного разговора дадут одинаковый ключ.
     * Для не-internal возвращает null.
     */
    fun extractInternalCallKey(filename: String): String? {
        val nameOnly = filename.substringBeforeLast('.')
        if (detectCallType(filename) != CallType.INTERNAL) return null
        val extensions = PBX_EXT_IN_PARENS.findAll(nameOnly)
            .map { it.groupValues[1] }
            .toList()
            .sorted()
        if (extensions.size < 2) return null
        val ts = TIMESTAMP_PREFIX.find(nameOnly)?.value ?: nameOnly.take(19)
        return "${ts}_${extensions.joinToString("_")}"
    }

    /**
     * Извлекает все внутренние номера (с PBX) из имени файла.
     */
    fun extractAllPbxExtensions(filename: String): List<String> {
        val nameOnly = filename.substringBeforeLast('.')
        return PBX_EXT_IN_PARENS.findAll(nameOnly).map { it.groupValues[1] }.toList()
    }

    /** @deprecated Используйте [extractManagerIdentifiers]; оставлено для обратной совместимости. */
    fun extractPhone(filename: String): String? =
        extractManagerIdentifiers(filename).firstOrNull()

    private fun String.normalizedForKindDetection(): String {
        val nfc = Normalizer.normalize(this, Normalizer.Form.NFC)
        val stripped = nfc.replace(Regex("\\p{M}+"), "")
        return stripped.lowercase(Locale.ROOT)
    }

    private fun extractRussianPhones(nameOnly: String): List<String> =
        GENERIC_PHONE_CHUNK.findAll(nameOnly)
            .map { it.value.replace(Regex("[^0-9]"), "") }
            .filter { it.length in 10..15 && looksLikeRussianPhone(it) }
            .map { normalizeRussianPhone(it) }
            .toList()

    /** Не принимать склейки из даты/времени (например 27032026054317). */
    private fun looksLikeRussianPhone(digits: String): Boolean = when (digits.length) {
        11 -> digits.startsWith("7") || digits.startsWith("8")
        10 -> digits.startsWith("9")
        else -> false
    }

    private fun normalizeRussianPhone(digits: String): String {
        if (digits.length == 11 && digits.startsWith("8")) {
            return "7" + digits.substring(1)
        }
        return digits
    }
}
