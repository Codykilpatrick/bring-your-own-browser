#!/usr/bin/env bash
# Connected side, not shipped. Builds the desktop image, one image per JDK, the two test apps, and
# the ui-agent jar. Everything is linux/amd64 because Zulu 6 and 7 only exist for that platform.
#
#   ./build.sh            everything
#   ./build.sh images     desktop + jdk images only
#   ./build.sh apps       compile sonar-console and fire-control into */dist/
#   ./build.sh agent      build ../ui-agent/dist/ui-agent.jar
set -euo pipefail
cd "$(dirname "$0")"
what=${1:-all}
R=java-apps

images() {
  docker build --platform linux/amd64 -t $R/desktop:latest -f desktop/Containerfile desktop
  docker build --platform linux/amd64 -t $R/jdk6:latest  -f jdk/Containerfile --build-arg JDK_IMAGE=docker.io/azul/zulu-openjdk-debian:6 --build-arg JDK_HOME=/usr/lib/jvm/zulu-6-amd64 jdk
  docker build --platform linux/amd64 -t $R/jdk7:latest  -f jdk/Containerfile --build-arg JDK_IMAGE=docker.io/azul/zulu-openjdk-debian:7 --build-arg JDK_HOME=/usr/lib/jvm/zulu7-ca-amd64 jdk
  docker build --platform linux/amd64 -t $R/jdk8:latest  -f jdk/Containerfile --build-arg JDK_IMAGE=docker.io/azul/zulu-openjdk:8 --build-arg JDK_HOME=/usr/lib/jvm/zulu8 jdk
  docker build --platform linux/amd64 -t $R/jdk17:latest -f jdk/Containerfile --build-arg JDK_IMAGE=docker.io/bellsoft/liberica-openjdk-debian:17 --build-arg JDK_HOME=/usr/lib/jvm/jdk-17.0.21.0.3-bellsoft-x86_64 jdk
  docker build --platform linux/amd64 -t $R/jdk8fx:latest  -f jdk/jdk8fx.Containerfile jdk
  docker build --platform linux/amd64 -t $R/jdk17fx:latest -f jdk/jdk17fx.Containerfile jdk
}

# Compile inside a JDK image with the source tree mounted. $1 image, $2 app dir, rest: javac args.
compile() {
  local image=$1 app=$2; shift 2
  rm -rf "$app/dist" && mkdir -p "$app/dist/classes"
  docker run --rm --platform linux/amd64 --entrypoint sh -v "$PWD/$app:/src" -w /src "$image" -c "
    set -e
    javac -nowarn -encoding UTF-8 $* -d dist/classes \$(find src -name '*.java') &&
    [ -d lib ] && cp -R lib dist/
    jar cfm dist/$app.jar MANIFEST.MF -C dist/classes ."
}

apps() {
  mkdir -p sonar-console/lib
  [ -f sonar-console/lib/log4j-1.2.17.jar ] || curl -fsSLo sonar-console/lib/log4j-1.2.17.jar \
    https://repo1.maven.org/maven2/log4j/log4j/1.2.17/log4j-1.2.17.jar
  compile $R/jdk6:latest sonar-console -source 1.6 -target 1.6 -cp lib/log4j-1.2.17.jar
  compile $R/jdk8fx:latest fire-control -source 1.8 -target 1.8
}

agent() {
  (cd ../ui-agent && ./build.sh)
}

case $what in
  all) images; apps; agent ;;
  images) images ;;
  apps) apps ;;
  agent) agent ;;
  *) echo "usage: $0 [all|images|apps|agent]" >&2; exit 2 ;;
esac
