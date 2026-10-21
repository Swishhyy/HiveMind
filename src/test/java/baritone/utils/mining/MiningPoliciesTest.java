/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils.mining;

import org.junit.Test;

public final class MiningPoliciesTest {
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
        baritone.utils.bot.BotPolicyChecks.runAll();
    }

    @Test
    public void companionRecipesRecoveryHandoffsAndBoundariesAreFinite() throws Exception {
        baritone.utils.bot.CompanionPolicyChecks.runAll();
    }

    @Test
    public void stealthGazeAmbushAndSightingMemoryRespectLimits() throws Exception {
        baritone.utils.social.StealthChecks.runAll();
    }

    @Test
    public void standOffAndRetreatRespectDistanceAndSearchLimits() {
        baritone.utils.social.SocialPolicyChecks.runAll();
    }
    @Test
    public void networkRelayHistoryReconnectsAndLocalViewerPreserveBoundaries() throws Exception {
        baritone.utils.network.NetworkChecks.runAll();
    }
    @Test
    public void teamClaimsAutonomyAndTerrainStayCoordinated() throws Exception {baritone.utils.network.TeamChecks.runAll();}
    @Test
    public void controlsPersistGlobalAndIndividualPoliciesAndRouteOnlyValidatedRequests() throws Exception {
        baritone.utils.network.ControlChecks.runAll();
    }
}
