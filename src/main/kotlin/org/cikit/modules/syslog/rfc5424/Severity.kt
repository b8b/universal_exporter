package org.cikit.modules.syslog.rfc5424

enum class Severity(val description: String) {
    EMERG("Emergency: system is unusable"),
    ALERT("Alert: action must be taken immediately"),
    CRIT("Critical: critical conditions"),
    ERR("Error: error conditions"),
    WARNING("Warning: warning conditions"),
    NOTICE(": normal but significant condition"),
    INFO("Informational: informational messages"),
    DEBUG("Debug: debug-level messages"),
    NOPRI("Unspecified"),
    ;
}