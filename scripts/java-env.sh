#!/usr/bin/env bash
# Sourced by project scripts; honor an explicit Java 21 installation.
if [[ -z "${JAVA_HOME:-}" && -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java ]]; then
 export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if ! java -version 2>&1 | head -1 | grep -q '"21\.'; then
 echo 'Java 21 required. Set JAVA_HOME to a JDK 21 installation.' >&2; exit 1
fi
