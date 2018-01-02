#!/bin/sh

# PROVIDE: universal_exporter
# REQUIRE: LOGIN
# KEYWORD: shutdown
#
# Add the following lines to /etc/rc.conf.local or /etc/rc.conf
# to enable this service:
#
# universal_exporter_enable (bool):       Set to NO by default
#                                         Set it to YES to enable prometheus
# universal_exporter_daemonargs (string): Set additional jvm arguments
#                                         Default is "-c -u prometheus"
# universal_exporter_javavm (string):     Set path to java 
#                                         Default is "@PREFIX@/openjdk8/bin/java"
# universal_exporter_javaargs (string):   Set additional jvm arguments
#                                         Default is "-XX:CICompilerCount=2 -XX:+UseSerialGC -Xmx20m"
# universal_exporter_args (string):       Set additional command line arguments
#                                         Default is "-config=@PREFIX@/etc/universal_exporter.conf"

. /etc/rc.subr

name=universal_exporter
rcvar=universal_exporter_enable

load_rc_config $name

: ${universal_exporter_enable:="NO"}
: ${universal_exporter_daemonargs:="-c -u prometheus"}
: ${universal_exporter_javavm:="@PREFIX@/openjdk8/bin/java"}
: ${universal_exporter_javaargs:="-XX:CICompilerCount=2 -XX:+UseSerialGC -Xmx20m"}
: ${universal_exporter_args:="-config=@PREFIX@/etc/universal_exporter.conf"}

pidfile=/var/run/universal_exporter.pid
command="/usr/sbin/daemon"
procname="${universal_exporter_javavm}"
command_args="-p ${pidfile} ${universal_exporter_daemonargs} \
  ${procname} \
  -Dvertx.cacheDirBase=/tmp/.vertx \
  -cp @PREFIX@/share/universal_exporter/universal_exporter-@VERSION@.jar \
  ${universal_exporter_javaargs} \
  exporter.VerticleKt ${universal_exporter_args} \
  > /var/log/universal_exporter/main.out 2>&1"

load_rc_config $name
run_rc_command "$1"
