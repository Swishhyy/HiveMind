/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;

import hivemind.utils.bot.FlightPolicy.Point;
import hivemind.utils.bot.TakeoffState.Action;

public final class TakeoffChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){runAll();System.out.println("Takeoff checks passed: jump/release/glide handoff, latched launch position, real flight progress, obstructed recovery, path timeout and fresh departure after landing");}
    public static void runAll(){
        LandingRetry landing=new LandingRetry();check(landing.canSearch(0)&&landing.announce(0),"First landing search must start immediately");landing.exhausted(0);
        for(int ms=1;ms<2000;ms+=50)check(!landing.canSearch(ms)&&!landing.announce(ms),"Exhausted searches must neither rescan nor spam each tick");
        check(landing.canSearch(2000)&&!landing.announce(2000)&&landing.announce(5000),"Terrain retry and chat throttles must be independent");landing.reset();check(landing.canSearch(0)&&landing.announce(0),"A new flight must not inherit an old landing delay");
        check(LandingRetry.finished(true,true,false,false)&&LandingRetry.finished(true,false,true,false)&&LandingRetry.finished(true,false,false,true),"Ground contact must finish landing even if the exact waypoint was missed");
        check(!LandingRetry.finished(false,true,true,true)&&!LandingRetry.finished(true,false,false,false),"Airborne landing and initial ground takeoff must remain active");
        Point spot=new Point(.5,80,.5);TakeoffState launch=new TakeoffState(spot);
        check(!launch.launching(),"Ground approach must not request a launch boost");
        check(launch.advance(new Point(5.5,80,.5),true,false,false,true,0)==Action.APPROACH,"Walk to the launch spot before jumping");
        check(launch.advance(spot,true,false,false,true,0)==Action.JUMP,"Start a normal jump at the launch spot");
        double y=80,vertical=.42;boolean deployed=false;
        for(int tick=0;tick<16;tick++){
            y+=vertical;vertical=(vertical-.08)*.98;
            Action action=launch.advance(new Point(.5,y,.5),false,false,false,true,vertical);
            check(action!=Action.APPROACH,"Jumping above the original Y must not re-enable the walking path");
            if(action==Action.JUMP){check(vertical<-.02,"Glide activation should occur while falling");deployed=true;break;}
        }
        check(deployed,"The released jump must be pressed again during descent, before landing");
        check(!launch.completed()&&launch.launching(),"Jumping alone is not flight progress; the initial glide still needs its launch boost");
        for(int tick=0;tick<12;tick++)launch.advance(new Point(.5+tick*1.2,81+tick*.5,.5),false,true,false,true,.5);
        check(launch.completed()&&!launch.failed()&&!launch.launching(),"Sustained glide and actual travel complete the takeoff");
        check(launch.advance(new Point(1200,80,900),true,true,false,true,0)==Action.FINISHED,"Landing far from the original launch must release it, not walk back there");

        TakeoffState hopping=new TakeoffState(spot);Action last=null;
        for(int tick=0;tick<160;tick++){
            int cycle=tick%12;boolean grounded=cycle==0,gliding=cycle==8||cycle==9;
            last=hopping.advance(new Point(.5,80+(cycle==0?0:1),.5),grounded,gliding,false,true,cycle<6?.1:-.1);
            if(last==Action.RECOVER)break;
        }
        check(last==Action.RECOVER&&hopping.failed()&&!hopping.completed()&&!hopping.launching(),"Repeated one-tick glides at the same spot must not reset the launch deadline or count as takeoff");
        check(hopping.advance(spot,true,false,false,true,0)==Action.RECOVER,"A failed launch stays failed until a new departure is selected");

        TakeoffState canopy=new TakeoffState(spot);canopy.advance(spot,true,false,false,true,0);
        for(int tick=0;tick<45;tick++)last=canopy.advance(new Point(.5,81,.5),false,true,true,true,0);
        check(canopy.failed()&&!canopy.completed()&&last==Action.RECOVER,"A glider pushing against leaves at low speed must recover instead of staying in the flight branch forever");

        TakeoffState waiting=new TakeoffState(spot);
        for(int tick=0;tick<125;tick++){last=waiting.advance(spot,true,false,false,false,0);check(last!=Action.JUMP,"Do not jump without a prepared flight path");}
        check(waiting.failed(),"A missing path cannot hold the bot at its launch point indefinitely");

        TakeoffState alreadyFlying=new TakeoffState(spot);
        check(alreadyFlying.advance(new Point(100,100,100),false,true,false,true,.2)==Action.FLY,"Joining an existing glide must not route back to a ground launch point");
        for(int tick=1;tick<12;tick++)alreadyFlying.advance(new Point(100+tick*2,100,100),false,true,false,true,0);
        check(alreadyFlying.completed(),"Existing flight can complete its departure from its actual position");
        check(TakeoffState.sameFailedArea(new Point(3,82,2),spot)&&!TakeoffState.sameFailedArea(new Point(8,80,.5),spot),"Retry exclusion should cover adjacent launch blocks, while leaving a different area available");
    }
}
