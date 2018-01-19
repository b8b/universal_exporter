package rfc5424

enum class Facility(val description: String) {
    KERN("kernel messages"),
    USER("user-level messages"),
    MAIL("mail system"),
    DAEMON("system daemons"),
    AUTH("security/authorization messages"),
    SYSLOG("messages generated internally by syslogd"),
    LPR("line printer subsystem"),
    NEWS("network news subsystem"),
    UUCP("UUCP subsystem"),
    CRON("clock daemon"),
    AUTHPRIV("security/authorization messages"),
    FTP("FTP daemon"),
    NTP("NTP subsystem"),
    AUDIT("log audit"),
    ALERT("log alert"),
    CRON2("clock daemon"),
    LOCAL0("locally used facilities"),
    LOCAL1("locally used facilities"),
    LOCAL2("locally used facilities"),
    LOCAL3("locally used facilities"),
    LOCAL4("locally used facilities"),
    LOCAL5("locally used facilities"),
    LOCAL6("locally used facilities"),
    LOCAL7("locally used facilities"),
    ;
}