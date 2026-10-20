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

package baritone.utils.social;

import baritone.utils.bot.AtomicProperties;
import baritone.utils.bot.TrustedControl;
import baritone.utils.mining.WorldRoutes;
import java.nio.file.*;
import java.util.*;

/** Checks gaze geometry, ambush eligibility, and persistent evidence without a running game. */
public final class StealthChecks {
    public static void main(String[] args) throws Exception {
        runAll();System.out.println("Stealth/combat checks passed: gaze, ambush gates, memory, server isolation, crystal/anchor damage, cover, dimension limits");
    }
    public static void runAll() throws Exception {
        check(StealthPolicy.gazeDot(0,0,1,0,0,40)==1,"Normalize the target's facing vector");
        check(StealthPolicy.gazeDot(0,0,1,0,0,-40)==-1,"Rear positions are outside the facing cone");
        check(StealthPolicy.gazeDot(1,0,0,0,0,40)==0,"Perpendicular positions do not count as a stare");
        check(!StealthPolicy.looking(1,false,18),"Walls prevent a stare even if angles align");
        check(StealthPolicy.looking(1,true,18)&&!StealthPolicy.looking(0.8,true,18),"Gaze requires the configured cone and direct sight");
        check(!StealthPolicy.looking(Double.NaN,true,18),"Invalid angles cannot create detection");
        check(StealthPolicy.gazeDot(0,0,0,1,1,1)==-1,"A zero facing vector does not invent observation");
        int gaze=0;for(int i=0;i<39;i++)gaze=StealthPolicy.gazeTicks(gaze,true,40);
        check(gaze==39,"One brief look does not reach a two-second threshold");
        gaze=StealthPolicy.gazeTicks(gaze,true,40);check(gaze==40,"Sustained observation reaches the scatter threshold");
        for(int i=0;i<20;i++)gaze=StealthPolicy.gazeTicks(gaze,false,40);check(gaze==0,"Looking away clears suspicion");
        check(StealthPolicy.openHeight(-5,5)&&!StealthPolicy.openHeight(6,5),"Observation height stays close to the player");
        check(StealthPolicy.clearancePenalty(0,24)>StealthPolicy.clearancePenalty(16,24)&&StealthPolicy.clearancePenalty(24,24)==0,"Scatter paths prefer clearance instead of crossing through the watched player");
        check(StealthPolicy.readyToAmbush(60,60,-0.4,true,true,true,20,20,16),"A healthy armed bot can approach an isolated player looking away");
        check(!StealthPolicy.readyToAmbush(59,60,-0.4,true,true,true,20,20,16),"Observe before approaching");
        check(!StealthPolicy.readyToAmbush(60,60,0,true,true,true,20,20,16),"A side/front view is not a rear ambush");
        check(!StealthPolicy.readyToAmbush(60,60,-0.4,false,true,true,20,20,16),"An unseen player is not an ambush opportunity");
        check(!StealthPolicy.readyToAmbush(60,60,-0.4,true,false,true,20,20,16),"Other nearby players prevent approach");
        check(!StealthPolicy.readyToAmbush(60,60,-0.4,true,true,false,20,20,16),"A missing weapon prevents approach");
        check(!StealthPolicy.mayContinueCombat(15,20,16,true,true)&&!StealthPolicy.mayContinueCombat(20,6,16,true,true),"Low health or hunger ends combat");
        check(!StealthPolicy.frequentPlace(1,10000)&&!StealthPolicy.frequentPlace(3,179)&&StealthPolicy.frequentPlace(3,180),"Possible bases need separate visits plus observed dwell");
        check(StealthPolicy.shouldReport(1000,0,0,120000),"The first real sighting is reportable");
        check(!StealthPolicy.shouldReport(119000,1000,100000,120000),"Movement does not bypass the report cooldown");
        check(!StealthPolicy.shouldReport(121000,1000,100,120000),"Stationary observations do not spam repeated coordinate messages");
        check(StealthPolicy.shouldReport(121000,1000,4096,120000),"A sufficiently moved player can be reported after the cooldown");
        check(TrustedControl.command("#kill Target")!=null,"Allowlisted whisper controls include the explicit kill command");
        check(TrustedControl.command("#stalk sightings Target")!=null,"Sighting queries retain the explicit command prefix");


        check(CombatStyle.parse("CRYSTAL")==CombatStyle.CRYSTAL&&CombatStyle.parse("anchor")==CombatStyle.ANCHOR,"Combat style names are case insensitive");
        expect(()->CombatStyle.parse("admin"),"Unknown combat styles cannot become commands");
        check(BlastPolicy.anchorDimension(WorldRoutes.OVERWORLD)&&BlastPolicy.anchorDimension("minecraft:the_end"),"Anchors can be considered in vanilla explosive dimensions");
        check(!BlastPolicy.anchorDimension(WorldRoutes.NETHER)&&!BlastPolicy.anchorDimension("mod:unknown"),"Nether and unknown anchor rules fail closed");
        check(BlastPolicy.rawDamage(6,0,1)==85&&BlastPolicy.rawDamage(5,0,1)==71,"Crystal/anchor power uses the vanilla explosion curve");
        check(BlastPolicy.rawDamage(6,12.01,1)==0&&BlastPolicy.rawDamage(6,4,0.4)<BlastPolicy.rawDamage(6,4,1),"Distance and cover lower predicted damage");
        check(BlastPolicy.afterArmor(40,20,12,16)<BlastPolicy.afterArmor(40,20,12,0)&&BlastPolicy.afterArmor(40,0,0,0)==40,"Armor and bounded protection reduce the estimate");
        check(BlastPolicy.blockedByCube(0,0,0,-1,0.5,0.5,2,0.5,0.5),"Planned cover blocks rays through its interior");
        check(!BlastPolicy.blockedByCube(0,0,0,-1,2,0.5,2,2,0.5),"Cover does not block rays over its roof");
        check(!BlastPolicy.blockedByCube(0,0,0,-2,1.5,0.5,0.5,1,0.5),"A crystal on the top face does not hide every ray at the endpoint");
        check(BlastPolicy.blockedByCube(0,0,0,-2,0.2,0.5,0.5,1,0.5),"An obsidian base protects lower body rays");
        check(BlastPolicy.mayBlast(4,12,20,0,20,10,4,6,false,false),"A favorable blast with health reserve is eligible");
        check(!BlastPolicy.mayBlast(11,12,20,0,20,10,4,6,false,false),"Maximum self damage applies before detonation");
        check(!BlastPolicy.mayBlast(9,12,14,0,20,10,4,6,false,false),"Blast cannot consume the reserved health");
        check(!BlastPolicy.mayBlast(2,3,20,0,20,10,4,6,false,false),"Negligible target damage is rejected");
        check(!BlastPolicy.mayBlast(4,12,20,0,20,10,4,6,true,false)&&!BlastPolicy.mayBlast(4,12,20,0,20,10,4,6,false,true),"Bystanders and protected areas prevent explosives");
        check(!BlastPolicy.mayBlast(Double.NaN,12,20,0,20,10,4,6,false,false),"An invalid estimate cannot authorize detonation");

        Path root=Files.createTempDirectory("baritone-stealth-checks-");
        try {
            UUID target=UUID.randomUUID(),other=UUID.randomUUID(),controller=UUID.randomUUID();long time=1000000;
            var first=new WorldRoutes.Endpoint(WorldRoutes.OVERWORLD,-100,64,200);
            var away=new WorldRoutes.Endpoint(WorldRoutes.OVERWORLD,200,64,200);
            PlayerMemory memory=new PlayerMemory(root);memory.reportTo(controller);
            memory.observe(target,"Target",first,time,0,30);
            for(int tick=1;tick<=12;tick++)memory.observe(target,"Target",first,time+tick*15000,15,30);
            var continuous=memory.places(target,WorldRoutes.OVERWORLD,time+180000,30).get(0);
            check(continuous.visits()==1&&continuous.observedSeconds()==180&&!continuous.possibleBase(),"Standing still is one visit, not repeated evidence of a base");
            memory.observe(target,"Target",away,time+200000,15,30);
            check(memory.places(target,WorldRoutes.OVERWORLD,time+200000,30).size()==2,"Leaving an area preserves its earlier coordinates");
            memory.observe(target,"Target",first,time+400000,30,30);
            memory.observe(target,"Target",away,time+420000,15,30);
            var repeated=memory.observe(target,"Target",first,time+620000,30,30);
            check(repeated.visits()==3&&repeated.observedSeconds()==180&&repeated.possibleBase(),"Offline/travel gaps cannot fabricate dwell time");
            check(repeated.point().equals(first),"Observed place anchors do not drift along the player's route");
            memory.observe(other,"Target",away,time+625000,0,30);
            check(memory.all(time+625000,30).stream().anyMatch(place->place.player().equals(other)),"Same display names cannot merge different UUIDs");
            memory.confirm(target,"Renamed",first,time+630000,30);
            PlayerMemory reloaded=new PlayerMemory(root);
            check(reloaded.reportTo().equals(controller),"The explicitly configured private recipient survives restart");
            var confirmed=reloaded.places(target,WorldRoutes.OVERWORLD,time+640000,30).get(0);
            check(confirmed.confirmedBase()&&confirmed.name().equals("Renamed"),"A confirmed base survives restart and follows the UUID through name changes");
            check(reloaded.places(target,WorldRoutes.NETHER,time+640000,30).isEmpty(),"Bases do not cross dimension boundaries");
            check(new PlayerMemory(root.resolve("other-server")).all(time+640000,30).isEmpty(),"Observations never cross server/save roots");
            var expired=reloaded.all(time+31*86400000L,30);
            check(expired.size()==1&&expired.get(0).confirmedBase(),"Ordinary sightings expire while manually confirmed bases persist");
            reloaded.forget(other);check(reloaded.all(time+640000,30).stream().noneMatch(place->place.player().equals(other)),"Forgetting removes only the requested UUID");
            for(int n=0;n<15;n++)reloaded.observe(other,"Other",new WorldRoutes.Endpoint(WorldRoutes.OVERWORLD,n*100,64,-100),time+650000+n*15000,0,30);
            check(reloaded.all(time+1000000,30).stream().filter(place->place.player().equals(other)).count()==12,"Per-player place history is bounded");
            reloaded.forget(null);check(new PlayerMemory(root).all(time+1000000,30).isEmpty(),"Forget all survives reload");
            check(new PlayerMemory(root).reportTo().equals(controller),"Clearing observations preserves explicit report configuration");
            Properties bad=new Properties();bad.setProperty("count","513");AtomicProperties.write(root.resolve("player-sightings.properties"),bad);
            expect(()->new PlayerMemory(root),"Malformed history fails closed instead of being silently overwritten");
        } finally {
            try(var files=Files.walk(root)) { for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file); }
        }
    }
    private static void expect(Runnable action,String message) {
        boolean failed=false;try { action.run(); }catch(IllegalArgumentException|IllegalStateException expected) { failed=true; }check(failed,message);
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
