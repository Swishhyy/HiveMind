#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_classes=$(mktemp -d)
trap 'rm -rf "$task_classes"' EXIT
task_javac=(javac)
if ! command -v javac >/dev/null 2>&1; then
    task_javac=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${task_javac[@]}" -d "$task_classes" \
    src/main/java/baritone/utils/mining/ConstructionPolicy.java \
    src/main/java/baritone/utils/mining/DepositPolicy.java \
    src/main/java/baritone/utils/bot/StorageRule.java \
    src/main/java/baritone/utils/bot/AtomicProperties.java \
    src/main/java/baritone/utils/bot/TrustedControl.java \
    src/main/java/baritone/utils/bot/JobStore.java \
    src/main/java/baritone/utils/bot/SurvivalPolicy.java \
    src/main/java/baritone/utils/bot/SculkPolicy.java \
    src/test/java/baritone/utils/bot/BotPolicyChecks.java \
    src/main/java/baritone/utils/mining/WorldRoutes.java \
    src/main/java/baritone/utils/mining/WastePolicy.java \
    src/main/java/baritone/utils/social/SocialPolicy.java \
    src/test/java/baritone/utils/mining/MiningPolicyChecks.java \
    src/test/java/baritone/utils/mining/WorldRouteChecks.java \
    src/test/java/baritone/utils/mining/WastePolicyChecks.java \
    src/test/java/baritone/utils/social/SocialPolicyChecks.java
java -cp "$task_classes" baritone.utils.mining.MiningPolicyChecks
java -cp "$task_classes" baritone.utils.mining.WorldRouteChecks
java -cp "$task_classes" baritone.utils.mining.WastePolicyChecks
java -cp "$task_classes" baritone.utils.social.SocialPolicyChecks

java -cp "$task_classes" baritone.utils.bot.BotPolicyChecks
