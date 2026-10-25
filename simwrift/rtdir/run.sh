#!/bin/sh
# SimWeaver Rift - start the server in this console.
#
# RTI_JAR: the HLA RTI's Java library (several: separate with ':'). Only HLA datasources need it;
# set it empty for DIS only. An environment variable RTI_JAR overrides the default below.

cd "$(dirname "$0")" || exit 1

APP_JAR=lib/simweaverrift.jar
RTI_JAR="${RTI_JAR-/opt/prti1516e/lib/prti1516e.jar}"

exec java $JAVA_OPTS -cp "$APP_JAR${RTI_JAR:+:$RTI_JAR}" com.simweaver.rift.Server "$@"
