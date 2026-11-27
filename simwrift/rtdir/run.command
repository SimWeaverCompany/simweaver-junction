#!/bin/sh
# SimWeaver Junction - start the server in a Terminal window (macOS).
# Double-click this file in Finder, or run ./run.command in Terminal. Ctrl+C stops the server.
#
# RTI_JAR: the HLA RTI's Java library (several: separate with ':'). Only HLA needs it; set it
# empty for DIS only. An environment variable RTI_JAR overrides the default below: change the
# default (a placeholder path) to where your RTI is installed.
# Java 11 or later: JAVA_HOME when set, else the newest one macOS knows (/usr/libexec/java_home),
# else java from the PATH.
# A site folder of its own (0.14.5): ./run.command --root=<folder>, or the environment variable
# JUNCTION_SITE. Without it, this folder is also the site (etc/, logs/).

# this script's folder, also when started through a link
self="$0"
while [ -h "$self" ]; do
    link=$(ls -ld "$self" | sed 's/.* -> //')
    case "$link" in
        /*) self="$link" ;;
        *) self="$(dirname "$self")/$link" ;;
    esac
done
HERE="$(cd "$(dirname "$self")" && pwd)" || exit 1

# the Terminal window's title
printf '\033]0;SimWeaver Junction\007'

APP_JAR="$HERE/lib/simweaverrift.jar"
RTI_JAR="${RTI_JAR-/opt/anrti/lib/hlaimpl.jar}"

if [ -z "$JAVA_HOME" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 11+ 2>/dev/null)"
fi
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA=java
else
    echo "SimWeaver Junction needs Java 11 or later, and none was found."
    echo "Install a JDK (e.g. Eclipse Temurin 11 or 17), or set JAVA_HOME, and start again."
    printf 'Press Enter to close. '
    read -r _
    exit 1
fi

if [ ! -f "$APP_JAR" ]; then
    echo "$APP_JAR is missing: this script must stay in the SimWeaver program folder (a link to it is fine)."
    printf 'Press Enter to close. '
    read -r _
    exit 1
fi

exec "$JAVA" $JAVA_OPTS -cp "$APP_JAR${RTI_JAR:+:$RTI_JAR}" com.simweaver.rift.Server "$@"
