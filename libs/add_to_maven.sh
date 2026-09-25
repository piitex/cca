#!/bin/bash
# Installs the RenGL jars in this folder into the local Maven repository (~/.m2) so cca can build against them.
# Extra arguments go to Maven, e.g. ./add_to_maven.sh -o
set -e
cd "$(dirname "$0")"

MVN="../mvnw"
VERSION="1.0-SNAPSHOT"

# The main jar carries the classes, sources and javadoc. The pom is what tells Maven about RenGL's own
# dependencies (LWJGL, Skiko, log4j...). Without it those aren't on the classpath, hence the NoClassDefFoundErrors.
"$MVN" "$@" install:install-file \
  -Dfile="RenGL-$VERSION.jar" \
  -Dsources="RenGL-$VERSION-sources.jar" \
  -Djavadoc="RenGL-$VERSION-javadoc.jar" \
  -DpomFile="RenGL-$VERSION.pom"

# Each natives jar needs its classifier. Without one it lands on top of the main jar and the classes are gone.
for platform in linux linux-arm64 macos macos-arm64 windows windows-arm64; do
  "$MVN" "$@" install:install-file \
    -Dfile="RenGL-$VERSION-natives-$platform.jar" \
    -DgroupId=me.piitex.engine \
    -DartifactId=RenGL \
    -Dversion="$VERSION" \
    -Dpackaging=jar \
    -Dclassifier="natives-$platform" \
    -DgeneratePom=false
done
