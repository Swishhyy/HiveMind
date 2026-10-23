#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/build-distributions.py all
python3 scripts/check-client-packaging.py
task_classes=$(mktemp -d)
trap 'rm -rf "$task_classes"' EXIT
task_javac=(javac)
if ! command -v javac >/dev/null 2>&1; then task_javac=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${task_javac[@]}" --release 17 -cp build/controller-classes -d "$task_classes" \
 src/main/java/hivemind/utils/bot/SessionState.java \
 src/main/java/hivemind/utils/update/*.java \
 src/test/java/hivemind/utils/update/UpdateChecks.java \
 controller/src/test/java/hivemind/controller/ControllerChecks.java \
 controller/src/test/java/hivemind/controller/ControllerUpdateChecks.java
"${task_javac[@]}" --release 17 -cp "$task_classes:build/controller-classes" -d "$task_classes" controller/src/test/java/hivemind/controller/SupervisorChecks.java
java -cp "$task_classes:build/controller-classes" hivemind.utils.update.UpdateChecks
java -cp "$task_classes:build/controller-classes" hivemind.controller.ControllerChecks

java -cp "$task_classes:build/controller-classes" hivemind.controller.ControllerUpdateChecks
java -cp "$task_classes:build/controller-classes" hivemind.controller.SupervisorChecks
python3 scripts/check-component-releases.py
