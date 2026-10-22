/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.utils.bot;

import hivemind.utils.bot.FlightPolicy.Mode;
import hivemind.utils.bot.FlightPolicy.Point;
import hivemind.utils.social.CombatCadence;
import hivemind.utils.social.CombatPolicy;
import java.util.List;

public final class FlightCombatChecks {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args){runAll();System.out.println("Flight/combat checks passed: escape supplies and headings, moving pursuit, observation expiry, mob triage, weapon choice and action cadence");}
    public static void runAll(){
        double watched=AwarenessPolicy.priority(true,true,1000*1000),player=AwarenessPolicy.priority(true,false,500*500);
        for(int mob=0;mob<2000;mob++)check(watched<player&&player<AwarenessPolicy.priority(false,false,mob),"Crowded farms must not push the watched player or other players out of the awareness budget");
        check(AwarenessPolicy.within(1024*1024,1024)&&!AwarenessPolicy.within(1025*1025,1024),"Awareness honors its configured range");
        check(!AwarenessPolicy.within(Double.NaN,2048)&&AwarenessPolicy.range(Integer.MAX_VALUE)==2048,"Invalid contacts cannot produce an unbounded scan");
        check(!FlightPolicy.supplied(Mode.TRAVEL,200,10,8,12,5),"Departure must honor the native landing reserve, otherwise it immediately lands far from its goal");
        check(FlightPolicy.supplied(Mode.TRAVEL,200,14,8,12,5),"A sufficiently supplied normal trip can depart");
        check(FlightPolicy.supplied(Mode.ESCAPE,12,5,8,12,5)&&!FlightPolicy.supplied(Mode.TRAVEL,12,5,8,12,5),"Combat escape can use equipment/reserves a normal trip would decline");
        check(!FlightPolicy.supplied(Mode.ESCAPE,9,5,8,5,5)&&!FlightPolicy.supplied(Mode.ESCAPE,100,3,8,5,5),"Emergency flight still needs usable wings and departure rockets above its final reserve");
        Point from=new Point(0,80,0),enemy=new Point(10,80,0);
        Point escape=FlightPolicy.escape(from,List.of(enemy,new Point(10,90,10)),2048,0);
        check(escape.x()<0&&escape.z()<0&&escape.distanceSquared(enemy)>2000*2000,"A long escape leg increases separation from multiple observed attackers");
        Point overlapping=FlightPolicy.escape(from,List.of(from),2048,Math.PI);
        check(overlapping.x()<-2000&&Double.isFinite(overlapping.z()),"Overlapping positions still produce a finite escape direction");
        Point moved=new Point(-20,80,-20);
        Point next=FlightPolicy.escape(moved,List.of(new Point(-10,80,-10)),2048,0);
        check(next.x()<moved.x()&&next.z()<moved.z(),"A pursuing opponent updates the next escape leg instead of sending the bot back to its first waypoint");
        check(FlightPolicy.shouldPursue(true,true,true,60,0),"An airborne opponent inside the old 128-block landing radius is still pursued");
        check(FlightPolicy.shouldPursue(true,true,true,4,40),"Vertical separation is a reason to take off even with little horizontal distance");
        check(!FlightPolicy.shouldPursue(false,true,true,100,40)&&!FlightPolicy.shouldPursue(true,true,false,100,0),"Watching stays distinct from deliberate airborne pursuit");
        Point intercept=FlightPolicy.intercept(new Point(100,150,100),new Point(3,.5,1));
        check(intercept.x()>100&&intercept.z()>100&&intercept.distanceSquared(new Point(100,150,100))<=48*48+.001,"Pursuit leads a moving target by a bounded amount");
        check(!FlightPolicy.replan(400,0,from,new Point(20,80,0))&&FlightPolicy.replan(500,0,from,new Point(20,80,0)),"Moving opponents can update a route twice a second without rebuilding the flight solver every tick");
        check(FlightPolicy.fresh(10000,3000)&&!FlightPolicy.fresh(12000,3000)&&!FlightPolicy.fresh(1000,3000),"Missing targets get a brief last-sighting continuation, never unlimited position knowledge");
        check(CombatPolicy.fightMob(true,20,20,3,false,false),"An armed healthy bot can clear ordinary hostile mobs");
        check(!CombatPolicy.fightMob(false,20,20,1,false,false)&&!CombatPolicy.fightMob(true,8,20,1,false,false),"No weapon or low health selects retreat");
        check(!CombatPolicy.fightMob(true,20,20,4,false,false)&&!CombatPolicy.fightMob(true,20,20,1,true,false)
                &&!CombatPolicy.fightMob(true,20,20,1,false,true),"Crowds, dangerous mobs and a player threat take priority over routine mob fighting");
        check(CombatPolicy.weaponScore("netherite_sword",true,false,0,false)>CombatPolicy.weaponScore("wooden_sword",true,false,0,false),"A stronger carried weapon is preferred over the first hotbar slot");
        check(CombatPolicy.weaponScore("iron_axe",false,true,0,true)>CombatPolicy.weaponScore("netherite_sword",true,false,5,true),"Raised shields favor an axe");
        CombatCadence cadence=new CombatCadence();
        check(cadence.claim(100,1),"Obsidian placement starts a cycle");
        check(!cadence.claim(100,1),"Fast state transitions cannot send another action in the same client tick");
        check(cadence.claim(101,1),"A confirmed base can take a crystal on the next tick without an extra cooldown tick");
        check(cadence.claim(102,0),"A newly observed crystal can be attacked on the following tick");
        check(!cadence.claim(102,0),"Zero configured delay still cannot burst multiple actions in a tick");
        cadence.reset();check(cadence.claim(200,4)&&!cadence.ready(203)&&cadence.ready(204),"Explicit slower action pacing remains configurable");
    }
}
