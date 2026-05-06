#!/bin/sh
# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup script (UNIX) — slimmed for this project.
# Downloads maven-wrapper.jar on first run (URL from .mvn/wrapper/maven-wrapper.properties),
# then delegates to the standard Maven wrapper bootstrap.
# ----------------------------------------------------------------------------
set -e

# Resolve project base dir (where this script lives)
SCRIPT_PATH="$0"
while [ -h "$SCRIPT_PATH" ]; do
  LS_OUT=$(ls -ld "$SCRIPT_PATH")
  LINK=$(expr "$LS_OUT" : '.*-> \(.*\)$')
  case "$LINK" in
    /*) SCRIPT_PATH="$LINK" ;;
    *)  SCRIPT_PATH="$(dirname "$SCRIPT_PATH")/$LINK" ;;
  esac
done
BASE_DIR=$(cd "$(dirname "$SCRIPT_PATH")" && pwd)

WRAPPER_JAR="$BASE_DIR/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_PROPS="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$WRAPPER_PROPS" ]; then
  echo "ERROR: Missing $WRAPPER_PROPS" >&2
  exit 1
fi

# Read wrapperUrl
WRAPPER_URL=$(grep '^wrapperUrl=' "$WRAPPER_PROPS" | cut -d '=' -f 2-)
[ -z "$WRAPPER_URL" ] && {
  echo "ERROR: wrapperUrl not set in maven-wrapper.properties" >&2
  exit 1
}

# Download wrapper jar if not present
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Downloading Maven Wrapper from $WRAPPER_URL ..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$WRAPPER_JAR" "$WRAPPER_URL" || { echo "Download failed."; exit 1; }
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$WRAPPER_JAR" "$WRAPPER_URL" || { echo "Download failed."; exit 1; }
  else
    echo "ERROR: need curl or wget to download the Maven wrapper jar." >&2
    exit 1
  fi
fi

# Locate Java
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="java"
else
  echo "ERROR: JAVA_HOME is not set and 'java' is not on the PATH." >&2
  exit 1
fi

# Run the wrapper
exec "$JAVA" $MAVEN_OPTS \
  -classpath "$WRAPPER_JAR" \
  "-Dmaven.multiModuleProjectDirectory=$BASE_DIR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
