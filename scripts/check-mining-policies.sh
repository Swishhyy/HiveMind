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
    src/main/java/hivemind/utils/mining/ConstructionPolicy.java \
    src/main/java/hivemind/utils/mining/DepositPolicy.java \
    src/main/java/hivemind/utils/bot/StorageRule.java \
    src/main/java/hivemind/utils/bot/AtomicProperties.java \
    src/main/java/hivemind/utils/bot/TrustedControl.java \
    src/main/java/hivemind/utils/bot/AutonomyPolicy.java \
    src/main/java/hivemind/utils/bot/AwarenessPolicy.java \
    src/main/java/hivemind/utils/bot/FlightPolicy.java \
    src/main/java/hivemind/utils/bot/LandingRetry.java \
    src/main/java/hivemind/utils/bot/TakeoffState.java \
    src/test/java/hivemind/utils/bot/TakeoffChecks.java \
    src/main/java/hivemind/utils/bot/PortableSupplyPolicy.java \
    src/main/java/hivemind/utils/bot/PortableBoxStore.java \
    src/main/java/hivemind/utils/social/ClutchPolicy.java \
    src/test/java/hivemind/utils/bot/CombatSupplyChecks.java \
    src/main/java/hivemind/utils/bot/MapOrder.java \
    src/main/java/hivemind/utils/network/ClientRole.java \
    src/main/java/hivemind/utils/network/PlayerBotOrders.java \
    src/test/java/hivemind/utils/network/OperationsChecks.java \
    src/main/java/hivemind/utils/network/AssistancePolicy.java \
    src/main/java/hivemind/utils/network/SupplyReserves.java \
    src/main/java/hivemind/utils/network/BotBaseStore.java \
    src/test/java/hivemind/utils/network/LoadoutChecks.java \
    src/main/java/hivemind/utils/network/TaskQueue.java \
    src/main/java/hivemind/utils/network/RecoveryPolicy.java \
    src/main/java/hivemind/utils/network/OperationsPackets.java \
    src/main/java/hivemind/utils/network/ObservationView.java \
    src/main/java/hivemind/utils/network/ControlView.java \
    src/test/java/hivemind/utils/bot/MapPlayerChecks.java \
    src/main/java/hivemind/utils/bot/SurveyPolicy.java \
    src/main/java/hivemind/utils/bot/SurveyRoute.java \
    src/test/java/hivemind/utils/bot/SurveyChecks.java \
    src/main/java/hivemind/utils/social/CombatPolicy.java \
    src/main/java/hivemind/utils/social/CombatCadence.java \
    src/test/java/hivemind/utils/bot/FlightCombatChecks.java \
    src/main/java/hivemind/utils/bot/TrackingOrderStore.java \
    src/main/java/hivemind/utils/bot/JobStore.java \
    src/main/java/hivemind/utils/bot/SurvivalPolicy.java \
    src/main/java/hivemind/utils/bot/SculkPolicy.java \
    src/main/java/hivemind/utils/bot/DeathInventory.java \
    src/main/java/hivemind/utils/bot/BotLocations.java \
    src/main/java/hivemind/utils/bot/MiningZone.java \
    src/main/java/hivemind/utils/bot/GatherPlanner.java \
    src/main/java/hivemind/utils/bot/CompanionPolicy.java \
    src/test/java/hivemind/utils/bot/CompanionPolicyChecks.java \
    src/test/java/hivemind/utils/bot/BotPolicyChecks.java \
    src/test/java/hivemind/utils/bot/AutonomyChecks.java \
    src/main/java/hivemind/utils/mining/WorldRoutes.java \
    src/main/java/hivemind/utils/mining/WastePolicy.java \
    src/main/java/hivemind/utils/social/SocialPolicy.java \
    src/main/java/hivemind/utils/social/StealthPolicy.java \
    src/main/java/hivemind/utils/social/WatchPolicy.java \
    src/main/java/hivemind/utils/social/CombatStyle.java \
    src/main/java/hivemind/utils/social/BlastPolicy.java \
    src/main/java/hivemind/utils/social/TraversalPolicy.java \
    src/main/java/hivemind/utils/social/CombatTerrainPolicy.java \
    src/main/java/hivemind/utils/social/PlayerMemory.java \
    src/main/java/hivemind/utils/network/NetworkProtocol.java \
    src/main/java/hivemind/utils/network/ClientNetworkKey.java \
    src/main/java/hivemind/utils/network/ControlPackets.java \
    src/main/java/hivemind/utils/network/TeamPackets.java \
    src/main/java/hivemind/utils/network/Teamwork.java \
    src/main/java/hivemind/utils/network/RoamPolicy.java \
    src/main/java/hivemind/utils/network/TerrainPackets.java \
    src/main/java/hivemind/utils/network/TerrainStore.java \
    src/main/java/hivemind/utils/network/TerrainRegions.java \
    src/main/java/hivemind/utils/network/ControlRules.java \
    src/main/java/hivemind/utils/network/NetworkSettings.java \
    src/main/java/hivemind/utils/network/WebControlAction.java \
    src/main/java/hivemind/utils/network/SharedSightings.java \
    src/main/java/hivemind/utils/network/NetworkConfig.java \
    src/main/java/hivemind/utils/network/NetworkAtlas.java \
    src/main/java/hivemind/utils/network/BotNetwork.java \
    src/main/java/hivemind/utils/network/LocalNetworkViewer.java \
    src/test/java/hivemind/utils/network/NetworkChecks.java \
    src/test/java/hivemind/utils/network/TeamChecks.java \
    src/test/java/hivemind/utils/network/ControlChecks.java \
    src/test/java/hivemind/utils/social/StealthChecks.java \
    src/test/java/hivemind/utils/mining/MiningPolicyChecks.java \
    src/test/java/hivemind/utils/mining/WorldRouteChecks.java \
    src/test/java/hivemind/utils/mining/WastePolicyChecks.java \
    src/test/java/hivemind/utils/social/SocialPolicyChecks.java
java -cp "$task_classes" hivemind.utils.network.LoadoutChecks
java -cp "$task_classes" hivemind.utils.network.OperationsChecks
java -cp "$task_classes" hivemind.utils.bot.MapPlayerChecks
java -cp "$task_classes" hivemind.utils.mining.MiningPolicyChecks
java -cp "$task_classes" hivemind.utils.mining.WorldRouteChecks
java -cp "$task_classes" hivemind.utils.mining.WastePolicyChecks
java -cp "$task_classes" hivemind.utils.social.SocialPolicyChecks

java -cp "$task_classes" hivemind.utils.bot.BotPolicyChecks
java -cp "$task_classes" hivemind.utils.bot.CompanionPolicyChecks

java -cp "$task_classes" hivemind.utils.social.StealthChecks
java -cp "$task_classes:src/main/resources" hivemind.utils.network.NetworkChecks
java -cp "$task_classes:src/main/resources" hivemind.utils.network.ControlChecks

java -cp "$task_classes:src/main/resources" hivemind.utils.network.TeamChecks

java -cp "$task_classes:src/main/resources" hivemind.utils.bot.AutonomyChecks
java -cp "$task_classes:src/main/resources" hivemind.utils.bot.FlightCombatChecks
java -cp "$task_classes:src/main/resources" hivemind.utils.bot.SurveyChecks
java -cp "$task_classes:src/main/resources" hivemind.utils.bot.CombatSupplyChecks

java -cp "$task_classes:src/main/resources" hivemind.utils.bot.TakeoffChecks
