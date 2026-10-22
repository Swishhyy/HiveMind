/*
 * This file is part of HiveMind.
 *
 * HiveMind is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * HiveMind is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with HiveMind.  If not, see <https://www.gnu.org/licenses/>.
 */

package hivemind.utils.mining;

import org.junit.Test;

public final class MiningPoliciesTest {
    @Test public void portableSuppliesRetainTheBoxAndClutchesUseTheActualFallWindow() throws Exception {hivemind.utils.bot.CombatSupplyChecks.runAll();}
    @Test public void surveysExpandAndResumeWithoutRepeatingOldAnchors() throws Exception {hivemind.utils.bot.SurveyChecks.runAll();}
    @Test public void flightCombatAndExplosiveCadenceRespondToLiveConditions(){hivemind.utils.bot.FlightCombatChecks.runAll();}
    @Test public void autonomousOrdersMarkersAndReportsSurviveOnlyIntendedInterruptions() throws Exception {hivemind.utils.bot.AutonomyChecks.runAll();}
    @Test
    public void miningPoliciesPreserveSuppliesAndContainerBoundaries() {
        MiningPolicyChecks.runAll();
    }

    @Test
    public void routesPersistWithoutCrossingServerOrDimensionBoundaries() throws java.io.IOException {
        WorldRouteChecks.runAll();
    }

    @Test
    public void wasteDisposalPreservesResourcesAndBuildingStacks() {
        WastePolicyChecks.runAll();
    }
    @Test
    public void botTrustStorageJobsAndSurvivalPreserveBoundaries() throws Exception {
        hivemind.utils.bot.BotPolicyChecks.runAll();
    }

    @Test
    public void companionRecipesRecoveryHandoffsAndBoundariesAreFinite() throws Exception {
        hivemind.utils.bot.CompanionPolicyChecks.runAll();
    }

    @Test
    public void stealthGazeAmbushAndSightingMemoryRespectLimits() throws Exception {
        hivemind.utils.social.StealthChecks.runAll();
    }

    @Test
    public void standOffAndRetreatRespectDistanceAndSearchLimits() {
        hivemind.utils.social.SocialPolicyChecks.runAll();
    }
    @Test
    public void networkRelayHistoryReconnectsAndLocalViewerPreserveBoundaries() throws Exception {
        hivemind.utils.network.NetworkChecks.runAll();
    }
    @Test
    public void teamClaimsAutonomyAndTerrainStayCoordinated() throws Exception {hivemind.utils.network.TeamChecks.runAll();}
    @Test
    public void controlsPersistGlobalAndIndividualPoliciesAndRouteOnlyValidatedRequests() throws Exception {
        hivemind.utils.network.ControlChecks.runAll();
    }
}
