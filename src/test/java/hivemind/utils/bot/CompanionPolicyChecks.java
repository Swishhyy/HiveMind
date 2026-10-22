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

package hivemind.utils.bot;

import hivemind.utils.mining.WorldRoutes;
import java.nio.file.*;
import java.util.*;

/** Regression checks for dependency counts, real-item handoffs, recovery state, and dig boundaries. */
public final class CompanionPolicyChecks {
    public static void main(String[] args)throws Exception{runAll();System.out.println("Companion checks passed: recipes, handoff quantities, recovery state, repair targets, mining zones");}
    public static void runAll()throws Exception{
        var before=Map.of("minecraft:elytra",2,"minecraft:totem_of_undying",4,"minecraft:firework_rocket",128);
        check(DeathInventory.classify(before,before)==DeathInventory.Outcome.RETAINED,"Keep inventory must skip retrieval");
        check(DeathInventory.classify(before,Map.of())==DeathInventory.Outcome.LOST,"Empty synced respawn inventory must record losses");
        var after=Map.of("minecraft:elytra",2,"minecraft:totem_of_undying",3,"minecraft:bread",64);
        check(DeathInventory.classify(before,after)==DeathInventory.Outcome.PARTIAL,"Partial keep plugins must preserve retained gear");
        check(DeathInventory.missing(before,after).equals(Map.of("minecraft:totem_of_undying",1,"minecraft:firework_rocket",128)),"Only missing counts belong in recovery, ignoring extra respawn items");
        check(DeathInventory.classify(Map.of(),Map.of())==DeathInventory.Outcome.EMPTY,"Dying with no items must not imply keep inventory");
        check(!DeathInventory.ready("auto",false,30000,30000)&&!DeathInventory.ready("auto",true,2999,2000)&&!DeathInventory.ready("auto",true,4000,1499)&&DeathInventory.ready("auto",true,4000,1500),"Auto must await authoritative inventory sync and settle time");
        check(DeathInventory.ready("keep",false,4000,2000)&&DeathInventory.ready("drop",false,4000,2000),"Explicit overrides support unusual servers without full inventory packets");
        Path deathRoot=Files.createTempDirectory("hivemind-death-checks");try{
            var state=new DeathInventory(deathRoot);state.record(100,before);state=new DeathInventory(deathRoot);check(state.time()==100&&state.before().equals(before)&&state.outcome()==DeathInventory.Outcome.PENDING,"A disconnect must retain a pending pre-death snapshot");
            state.finish(DeathInventory.Outcome.RETAINED);check(new DeathInventory(deathRoot).outcome()==DeathInventory.Outcome.RETAINED,"Observed outcome persists");
            state.record(200,before);check(state.outcome()==DeathInventory.Outcome.PENDING,"A later death must be reassessed even after keep inventory was observed");
            check(new DeathInventory(deathRoot.resolve("other-server")).outcome()==DeathInventory.Outcome.UNKNOWN,"Server/save outcomes cannot leak");
        }finally{try(var paths=Files.walk(deathRoot)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}

        var zone=MiningZone.between(WorldRoutes.OVERWORLD,20,90,30,-20,-64,-30);
        check(zone.contains(WorldRoutes.OVERWORLD,-20,-64,30)&&zone.contains(WorldRoutes.OVERWORLD,20,90,-30),"Inclusive normalized corners");
        check(!zone.contains(WorldRoutes.NETHER,0,20,0)&&!zone.contains(WorldRoutes.OVERWORLD,21,20,0),"Dimensions and coordinates cannot escape a boundary");
        var points=zone.exploration(Integer.MIN_VALUE,1000,Integer.MAX_VALUE);Set<String> seen=new HashSet<>();
        for(int[] point:points){check(zone.contains(WorldRoutes.OVERWORLD,point[0],point[1],point[2]),"Exploration stays inside all bounds");check(seen.add(Arrays.toString(point)),"Exploration cannot loop on clamped duplicate corners");}
        check(!points.isEmpty()&&points.size()<=4096,"Exploration is finite");
        expect(()->new MiningZone(WorldRoutes.OVERWORLD,Integer.MIN_VALUE,0,0,0,1,1),"World-edge overflow is rejected");
        Path temp=Files.createTempDirectory("hivemind-companion-checks-");
        try{
            BotLocations locations=new BotLocations(temp);var point=new WorldRoutes.Endpoint(WorldRoutes.NETHER,-10,18,20);
            locations.setStation("repair",point);locations.setStation("furnace",point);locations.setZone(WorldRoutes.OVERWORLD,zone);
            var death=new BotLocations.Death(point,123456L,Map.of("minecraft:diamond_pickaxe",1,"minecraft:cobblestone",96),2);locations.setDeath(death);
            BotLocations reloaded=new BotLocations(temp);check(reloaded.station("repair").equals(point)&&reloaded.death().equals(death),"Repair points and counted death inventory survive restart");
            check(reloaded.zone(WorldRoutes.OVERWORLD).equals(zone)&&reloaded.zonesEnabled(),"Enabled mining bounds survive restart");
            check(reloaded.zone(WorldRoutes.NETHER)==null,"A missing dimension never inherits another zone");
            BotLocations other=new BotLocations(temp.resolve("other-server"));check(other.death()==null&&other.station("repair")==null&&!other.zonesEnabled(),"All endpoints and recovery state are server isolated");
            reloaded.setStation("furnace",null);reloaded.setDeath(null);reloaded.setZone(WorldRoutes.OVERWORLD,null);
            var cleared=new BotLocations(temp);check(cleared.death()==null&&cleared.station("furnace")==null&&!cleared.zonesEnabled()&&cleared.station("repair").equals(point),"Removal persists without changing unrelated endpoints");
            reloaded.setZone(WorldRoutes.OVERWORLD,zone);Properties malformed=AtomicProperties.read(temp.resolve("bot-locations.properties"));malformed.setProperty("zone.minecraft:overworld.minX","oops");AtomicProperties.write(temp.resolve("bot-locations.properties"),malformed);
            expect(()->new BotLocations(temp).zone(WorldRoutes.OVERWORLD),"Malformed boundaries fail closed");
        }finally{try(var paths=Files.walk(temp)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}

        for(int requested:new int[]{1,17,32,64,65,127,4096}){
            int delivered=0;
            while(delivered<requested){int stack=64,remaining=CompanionPolicy.remaining(requested,delivered);int drop=CompanionPolicy.dropButton(stack,remaining)==1?stack:1;check(drop<=remaining,"Handoffs never exceed the requested quantity");delivered+=drop;}
            check(delivered==requested,"Whole stacks and partial remainders hand off exactly");
        }
        check(CompanionPolicy.mayHandoff(true,true,true,true,true),"Confirmed nearby isolated recipient is eligible");
        check(!CompanionPolicy.mayHandoff(false,true,true,true,true)&&!CompanionPolicy.mayHandoff(true,false,true,true,true)&&!CompanionPolicy.mayHandoff(true,true,false,true,true)&&!CompanionPolicy.mayHandoff(true,true,true,false,true)&&!CompanionPolicy.mayHandoff(true,true,true,true,false),"Every handoff condition is required");
        check(CompanionPolicy.repairComplete(90,100,90)&&!CompanionPolicy.repairComplete(89,100,90),"Repairs use observed remaining durability");
        check(!CompanionPolicy.repairComplete(1,0,90)&&CompanionPolicy.repairComplete(10,10,100),"Repair targets do not accept invalid tools");
        expect(()->CompanionPolicy.quantity(0),"Reject unbounded requests");expect(()->CompanionPolicy.quantity(4097),"Bound task quantities");

        Map<String,List<GatherPlanner.Recipe>> recipes=new HashMap<>();
        add(recipes,smelt("glass","sand"));add(recipes,craft("coal",9,"coal_block"));add(recipes,craft("coal_block",1,Collections.nCopies(9,"coal").toArray(String[]::new)));
        add(recipes,craft("oak_planks",4,"oak_log"));add(recipes,craft("stick",4,"oak_planks","oak_planks"));add(recipes,craft("torch",4,"coal","stick"));
        add(recipes,smelt("iron_ingot","raw_iron"));add(recipes,craft("rail",16,"iron_ingot","iron_ingot","iron_ingot","iron_ingot","iron_ingot","iron_ingot","stick"));
        GatherPlanner planner=new GatherPlanner(item->recipes.getOrDefault(item,List.of()),4096);
        var glass=planner.plan(id("glass"),64,Map.of());check(acquired(glass,"sand")==64&&acquired(glass,"coal")==8,"A stack of glass needs 64 sand and eight coal");check(last(glass).method()==GatherPlanner.Method.SMELT&&last(glass).amount()==64,"Smelting follows acquisition");
        var reuse=planner.plan(id("glass"),64,Map.of(id("glass"),20,id("sand"),10,id("coal"),2));check(acquired(reuse,"sand")==34&&acquired(reuse,"coal")==4&&last(reuse).amount()==44,"Existing products, ingredients, and fuel reduce requirements");
        var torches=planner.plan(id("torch"),64,Map.of());check(acquired(torches,"coal")==16&&acquired(torches,"oak_log")==2&&last(torches).amount()==64,"Recursive four-item recipe batches count correctly");
        var rails=planner.plan(id("rail"),17,Map.of());check(acquired(rails,"raw_iron")==12&&acquired(rails,"coal")==2&&last(rails).amount()==32,"Recipe yields round up whole batches and fuel");
        var stocked=planner.plan(id("glass"),64,Map.of(id("glass"),64));check(stocked.actions().isEmpty(),"An already fulfilled request schedules no work");
        Map<String,List<GatherPlanner.Recipe>> cycle=Map.of(id("a"),List.of(craft("a",1,"b")),id("b"),List.of(craft("b",1,"a")));
        GatherPlanner cyclic=new GatherPlanner(item->cycle.getOrDefault(item,List.of()),4096);expect(()->cyclic.plan(id("a"),1,Map.of()),"A recipe cycle cannot become fake mined resources");
        check(cyclic.plan(id("a"),1,Map.of(id("b"),1)).actions().size()==1,"Existing ingredients can break a reversible recipe cycle");
        expect(()->new GatherPlanner(item->recipes.getOrDefault(item,List.of()),1).plan(id("glass"),64,Map.of()),"Dependency action limits stop oversized plans");
        for(String command:List.of("#recover now","#deliver Dylan diamond 32","#escort Dylan","#gather 64 glass","#repair now"))check(TrustedControl.command(command)!=null,"Companion commands are available to allowlisted whispers");
    }
    private static String id(String item){return "minecraft:"+item;}
    private static GatherPlanner.Recipe craft(String item,int count,String... ingredients){List<List<String>> grid=new ArrayList<>();for(String ingredient:ingredients)grid.add(List.of(id(ingredient)));while(grid.size()<9)grid.add(List.of());return new GatherPlanner.Recipe(id(item),id(item),count,GatherPlanner.Method.CRAFT,grid);}
    private static GatherPlanner.Recipe smelt(String item,String ingredient){return new GatherPlanner.Recipe(id(item),id(item),1,GatherPlanner.Method.SMELT,List.of(List.of(id(ingredient))));}
    private static void add(Map<String,List<GatherPlanner.Recipe>> map,GatherPlanner.Recipe recipe){map.computeIfAbsent(recipe.output(),key->new ArrayList<>()).add(recipe);}
    private static int acquired(GatherPlanner.Plan plan,String item){return plan.actions().stream().filter(action->action.method()==GatherPlanner.Method.ACQUIRE&&action.item().equals(id(item))).mapToInt(GatherPlanner.Action::amount).sum();}
    private static GatherPlanner.Action last(GatherPlanner.Plan plan){return plan.actions().get(plan.actions().size()-1);}
    private static void expect(Runnable action,String message){boolean failed=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){failed=true;}check(failed,message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
