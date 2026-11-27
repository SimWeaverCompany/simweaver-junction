#!/bin/sh
# SimWeaver Junction - start the server in this console.
#
#   ./run.sh                                  the simple way: this folder is also the site (etc/, logs/)
#   ./run.sh --root=/var/lib/simweaver-junction   a site folder of its own (settings, logs, key store)
#   ./run.sh --root=<site> --init             make the site and stop
#   ./run.sh --root=<site> --new-token=<name> make an API token and stop
#   ./run.sh --root=<site> --health           does the server answer? (exit 0 / 1)
#
# The site can also be given as the environment variable JUNCTION_SITE.
# RTI_JAR: the HLA RTI's Java library (several: separate with ':'). Only HLA datasources need it;
# set it empty for DIS only. An environment variable RTI_JAR overrides the default below.
# JAVA_HOME (optional): the Java to use, else java from the PATH. JAVA_OPTS: options for Java, e.g. -Xmx2g.

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

APP_JAR="$HERE/lib/simweaverrift.jar"
RTI_JAR="${RTI_JAR-/opt/anrti/lib/hlaimpl.jar}"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi

exec "$JAVA" $JAVA_OPTS -cp "$APP_JAR${RTI_JAR:+:$RTI_JAR}" com.simweaver.rift.Server "$@"
