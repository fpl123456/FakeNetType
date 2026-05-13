#!/bin/sh

# ------------------------------------------------------------------------------
#  Gradle Startup Script for Unix
# ------------------------------------------------------------------------------

DEFAULT_JVM_OPTS=""

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Attempt to set APP_HOME
APP_HOME=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd -P)

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
        exit 1
    fi
else
    JAVACMD="java"
    command -v java >/dev/null 2>&1 || { echo "ERROR: JAVA_HOME is not set and no 'java' command could be found."; exit 1; }
fi

# Find classpath
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Execute Gradle
exec "$JAVACMD" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
