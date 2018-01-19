package rfc5424

import java.time.OffsetDateTime

data class LogRecord(
        val pri: Int? = null,
        val ts: OffsetDateTime,
        val host: String? = null,
        val app: String? = null,
        val proc: Long? = null,
        val msgid: String? = null,
        val msg: String?
) {
    val facility: Facility?
        get() = pri?.let {
            Facility.values().getOrNull(it shr 3)
        }

    val severity: Severity?
        get() = pri?.let {
            Severity.values().getOrNull(it and 0x7)
        }
}