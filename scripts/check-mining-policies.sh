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
    src/main/java/baritone/utils/bot/BotLocations.java \
    src/main/java/baritone/utils/bot/MiningZone.java \
    src/main/java/baritone/utils/bot/GatherPlanner.java \
    src/main/java/baritone/utils/bot/CompanionPolicy.java \
    src/test/java/baritone/utils/bot/CompanionPolicyChecks.java \
    src/test/java/baritone/utils/bot/BotPolicyChecks.java \
    src/main/java/baritone/utils/mining/WorldRoutes.java \
    src/main/java/baritone/utils/mining/WastePolicy.java \
    src/main/java/baritone/utils/social/SocialPolicy.java \
    src/main/java/baritone/utils/social/StealthPolicy.java \
    src/main/java/baritone/utils/social/WatchPolicy.java \
    src/main/java/baritone/utils/social/CombatStyle.java \
    src/main/java/baritone/utils/social/BlastPolicy.java \
    src/main/java/baritone/utils/social/TraversalPolicy.java \
    src/main/java/baritone/utils/social/CombatTerrainPolicy.java \
    src/main/java/baritone/utils/social/PlayerMemory.java \
    src/main/java/baritone/utils/network/NetworkProtocol.java \
    src/main/java/baritone/utils/network/ClientNetworkKey.java \
    src/main/java/baritone/utils/network/ControlPackets.java \
    src/main/java/baritone/utils/network/TeamPackets.java \
    src/main/java/baritone/utils/network/Teamwork.java \
    src/main/java/baritone/utils/network/RoamPolicy.java \
    src/main/java/baritone/utils/network/TerrainPackets.java \
    src/main/java/baritone/utils/network/TerrainStore.java \
    src/main/java/baritone/utils/network/ControlRules.java \
    src/main/java/baritone/utils/network/NetworkSettings.java \
    src/main/java/baritone/utils/network/WebControlAction.java \
    src/main/java/baritone/utils/network/SharedSightings.java \
    src/main/java/baritone/utils/network/NetworkConfig.java \
    src/main/java/baritone/utils/network/NetworkAtlas.java \
    src/main/java/baritone/utils/network/BotNetwork.java \
    src/main/java/baritone/utils/network/LocalNetworkViewer.java \
    src/test/java/baritone/utils/network/NetworkChecks.java \
    src/test/java/baritone/utils/network/TeamChecks.java \
    src/test/java/baritone/utils/network/ControlChecks.java \
    src/test/java/baritone/utils/social/StealthChecks.java \
    src/test/java/baritone/utils/mining/MiningPolicyChecks.java \
    src/test/java/baritone/utils/mining/WorldRouteChecks.java \
    src/test/java/baritone/utils/mining/WastePolicyChecks.java \
    src/test/java/baritone/utils/social/SocialPolicyChecks.java
java -cp "$task_classes" baritone.utils.mining.MiningPolicyChecks
java -cp "$task_classes" baritone.utils.mining.WorldRouteChecks
java -cp "$task_classes" baritone.utils.mining.WastePolicyChecks
java -cp "$task_classes" baritone.utils.social.SocialPolicyChecks

java -cp "$task_classes" baritone.utils.bot.BotPolicyChecks
java -cp "$task_classes" baritone.utils.bot.CompanionPolicyChecks

java -cp "$task_classes" baritone.utils.social.StealthChecks
java -cp "$task_classes:src/main/resources" baritone.utils.network.NetworkChecks
java -cp "$task_classes:src/main/resources" baritone.utils.network.ControlChecks

java -cp "$task_classes:src/main/resources" baritone.utils.network.TeamChecks
