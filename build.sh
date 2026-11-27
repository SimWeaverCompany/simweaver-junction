#!/bin/sh
# Compiles everything for Java 11 into out/classes (main) and out/test (tests), and packs the
# application into out/artifacts/simweaverrift/simweaverrift.jar (for simwrift/rtdir/lib). Stops on errors.
# sh build.sh --release also makes the release: the program folder and its zip in out/release (0.14.5).
# Modules: simwcommon, simwjunction, simwrift, each with src/ and test/.
cd "$(dirname "$0")"
# the HLA API jars (IEEE 1516-2010 and 1516-2025), e.g. from your RTI; set HLA_API_CP to override
API=${HLA_API_CP:-/home/claude/build/api-1516e.jar:/home/claude/build/api-1516-2025.jar}
rm -rf out/classes out/test && mkdir -p out/classes out/test
find simwcommon/src simwjunction/src simwrift/src -name '*.java' > out/main.txt
javac --release 11 -encoding UTF-8 -Xlint:-options -nowarn -cp "$API" -d out/classes @out/main.txt > out/javac.log 2>&1
rc=$?; grep -v -e JAVA_TOOL -e '^Note:' out/javac.log; [ $rc -eq 0 ] || { echo "MAIN BUILD FAILED"; exit 1; }
find simwcommon/test simwjunction/test simwrift/test -name '*.java' > out/test.txt 2>/dev/null
if [ -s out/test.txt ]; then
  javac --release 11 -encoding UTF-8 -Xlint:-options -nowarn -cp "$API:out/classes" -d out/test @out/test.txt > out/javac.log 2>&1
  rc=$?; grep -v -e JAVA_TOOL -e '^Note:' out/javac.log; [ $rc -eq 0 ] || { echo "TEST BUILD FAILED"; exit 1; }
fi
mkdir -p out/artifacts/simweaverrift
jar --create --file out/artifacts/simweaverrift/simweaverrift.jar --main-class com.simweaver.rift.Server -C out/classes . \
  || { echo "JAR FAILED"; exit 1; }
# 0.14.5: junctions.dist (in the program folder) holds every sample gateway configuration as delivered;
# etc.dist/junctions the selection a new site gets. Each file in etc.dist/junctions must be the same as its
# copy in junctions.dist.
R=simwrift/rtdir
( cd $R/etc.dist/junctions && find . -type f ) | while read f; do
  cmp -s "$R/etc.dist/junctions/$f" "$R/junctions.dist/$f" || echo "WARNING: $R/etc.dist/junctions/${f#./} is not the same in $R/junctions.dist (copy it there when the sample changed on purpose)"
done
# 0.14.5: sh build.sh --release makes the program folder out/release/simweaver-junction-<version> and its zip
# (written with jar, so the same on every platform; no exec bits: the services run the scripts with sh)
if [ "$1" = "--release" ]; then
  V=$(sed -n 's/.*String VERSION = "\(.*\)";.*/\1/p' simwjunction/src/com/simweaver/junction/Version.java)
  D=out/release/simweaver-junction-$V
  rm -rf out/release && mkdir -p "$D/lib"
  cp out/artifacts/simweaverrift/simweaverrift.jar "$D/lib/"
  for x in webroot doc fom enumerations mappings etc.dist junctions.dist deploy run.sh run.bat run.command keystore-gen-line.txt LICENSE; do
    cp -R "$R/$x" "$D/"
  done
  if [ -d "$R/maps/overlays" ]; then mkdir -p "$D/maps" && cp -R "$R/maps/overlays" "$D/maps/"; fi
  cp README.md "$D/"
  # never in a release: a site's own files
  rm -rf "$D/etc" "$D/log" "$D/logs" "$D/cache" "$D/keystore.jks"
  ( cd out/release && jar --create --no-manifest --file "simweaver-junction-$V.zip" "simweaver-junction-$V" ) || { echo "RELEASE FAILED"; exit 1; }
  echo "RELEASE out/release/simweaver-junction-$V.zip"
fi
echo BUILD OK
