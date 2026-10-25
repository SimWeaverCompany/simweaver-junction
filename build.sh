#!/bin/sh
# Compiles everything for Java 11 into out/classes (main) and out/test (tests), and packs the
# application into out/artifacts/simweaverrift/simweaverrift.jar (for simwrift/rtdir/lib). Stops on errors.
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
# etc/junctions.dist holds every sample gateway configuration as delivered; etc/junctions the ones
# in use. Each file in etc/junctions must be the same as its copy in etc/junctions.dist.
E=simwrift/rtdir/etc
( cd $E/junctions && find . -type f ) | while read f; do
  cmp -s "$E/junctions/$f" "$E/junctions.dist/$f" || echo "WARNING: $E/junctions/${f#./} is not the same in $E/junctions.dist (copy it there when the sample changed on purpose)"
done
echo BUILD OK
