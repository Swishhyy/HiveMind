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
package baritone.utils.network;

import java.util.*;

/** The same typed setting catalogue is used by the page, host validation, and receiving clients. */
public final class NetworkSettings {
    public record Spec(String name, String group, String type, long min, long max, List<String> choices) {
        public String normalize(String value) {
            if (value == null || value.length() > 128) throw new IllegalArgumentException("Invalid value for " + name);
            value = value.strip().toLowerCase(Locale.ROOT);
            if (type.equals("boolean")) {
                if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(name + " requires true or false");
            } else if (type.equals("choice")) {
                if (!choices.contains(value)) throw new IllegalArgumentException(name + " requires " + String.join(", ", choices));
            } else {
                long number;
                try { number = Long.parseLong(value); } catch (NumberFormatException e) { throw new IllegalArgumentException(name + " requires a whole number"); }
                if (number < min || number > max) throw new IllegalArgumentException(name + " requires " + min + "–" + max);
                value = "" + number;
            }
            return value;
        }
    }
    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();
    static {
        bool("Autonomy","botAutoRespawn botPersistentTracking botPeriodicReports botReportAllTrusted botUseElytra");
        number("Autonomy","long",1000,30000,"botRespawnDelayMS");
        number("Autonomy","long",30000,3600000,"botReportIntervalMS");
        number("Autonomy","integer",96,8192,"botFlightMinimumDistance botFlightRoamDistance");
        number("Autonomy","integer",2,64,"botFlightRocketReserve");
        bool("Movement", "allowBreak allowPlace allowSprint allowParkour allowWaterBucketFall");
        number("Movement","integer",3,1024,"maxFallHeightBucket");
        number("Stalking","integer",0,10000,"stalkTerrainPenalty");
        bool("Mining", "mineAutoDeposit mineDiscardWaste mineTravelBetweenDimensions preferAvoidConstruction protectContainers mineAvoidSculk");
        number("Mining","integer",0,16,"mineWasteKeepStacks");
        number("Mining","integer",1,64,"sculkAvoidRadius");
        bool("Survival","botSurvival botAutoResupply fleeConflicts");
        choice("Survival","botConflictMode","flee","defend");
        number("Survival","integer",1,20,"botMinimumHealth botEatAtHunger");
        number("Survival","integer",0,2304,"botMinimumFood botRestockFood botMinimumBlocks botRestockBlocks botToolReserve");
        number("Survival","integer",8,256,"conflictFleeDistance");
        bool("Stalking","stalkMinimalEvidence stalkPreferOpenAir stalkSneakWhileMoving stalkUseInvisibility stalkReportSightings");
        number("Stalking","integer",16,128,"stalkDistance");
        number("Stalking","integer",16,2048,"stalkSearchRadius");
        number("Stalking","long",10000,600000,"stalkSearchTimeoutMS");
        number("Stalking","integer",4,32,"stalkPositionTolerance");
        number("Stalking","integer",20,600,"stalkLostSightTicks");
        number("Stalking","long",1000,30000,"stalkRetryDelayMS");
        number("Stalking","integer",0,64,"stalkSneakHearingRange");
        number("Stalking","integer",30,90,"stalkSneakViewAngle");
        number("Stalking","integer",1,16,"stalkVerticalRange");
        number("Stalking","integer",1,90,"stalkViewConeDegrees");
        number("Stalking","integer",10,200,"stalkGazeTicks");
        number("Stalking","integer",20,2400,"stalkScatterTicks");
        number("Stalking","long",1000,3600000,"stalkReportIntervalMS");
        number("Stalking","integer",1,90,"stalkMemoryDays");
        bool("Combat","killAllowTrustedTargets killUseTotems killUseBlastCover killLureOpponent");
        choice("Combat","killCombatStyle","auto","melee","crystal","anchor");
        number("Combat","integer",1,20,"killMinimumHealth killRetreatHealth killMaximumSelfDamage killMinimumBlastDamage");
        number("Combat","integer",2,16,"killBlastHealthReserve killRetreatPlayers");
        number("Combat","integer",1,100,"killMaxAttempts");
        number("Combat","integer",0,20,"killExplosiveDelayTicks");
        number("Combat","integer",0,2,"killMaxSupportBlocks");
        number("Combat","long",0,180000,"killWatchMinimumMS killWatchMaximumMS");
        number("Combat","long",5000,600000,"killAttackTimeoutMS");
        number("Combat","long",60000,3600000,"killTimeoutMS");
        number("Combat","long",2000,15000,"killLureTimeoutMS");
        bool("Companions","escortDefendOwner deliveryRequireHandoff repairToolsBeforeReplace");
        number("Companions","integer",3,16,"escortDistance");
        number("Companions","integer",1,8,"deliveryApproachDistance");
        number("Companions","integer",1,32,"recoveryMaxAttempts");
        number("Companions","integer",1,128,"recoverySearchRadius");
        number("Companions","integer",0,2304,"repairAtDurability");
        number("Companions","integer",1,100,"repairTargetPercent");
        number("Companions","long",5000,3600000,"repairNoProgressTimeoutMS companionTaskTimeoutMS");
        number("Companions","integer",1,65536,"gatherMaxActions");
        bool("Sharing","botAutoRoam networkShareSightings networkShareExploration networkRenderSightings");
        number("Sharing","long",2000,60000,"networkSightingIntervalMS");
        number("Sharing","long",10000,600000,"networkSightingTTLMS");
    }
    private NetworkSettings() {}
    private static void bool(String group,String names) { for(String name:names.split(" ")) put(new Spec(name,group,"boolean",0,0,List.of())); }
    private static void number(String group,String type,long min,long max,String names) { for(String name:names.split(" ")) put(new Spec(name,group,type,min,max,List.of())); }
    private static void choice(String group,String name,String... options) { put(new Spec(name,group,"choice",0,0,List.of(options))); }
    private static void put(Spec spec) { if(SPECS.put(spec.name(),spec)!=null)throw new IllegalStateException("Duplicate network setting"); }
    public static List<Spec> all() { return List.copyOf(SPECS.values()); }
    public static Spec spec(String name) {
        Spec spec=SPECS.get(name);
        if(spec==null)throw new IllegalArgumentException("Setting is not available through the bot page: "+name);
        return spec;
    }
    public static Map<String,String> normalize(Map<String,String> values) {
        Map<String,String> result=new LinkedHashMap<>();
        values.forEach((name,value)->result.put(name,spec(name).normalize(value)));
        return Map.copyOf(result);
    }
}
