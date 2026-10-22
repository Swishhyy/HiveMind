/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;

import hivemind.utils.bot.FlightPolicy.Point;

/** Ground approach ends once a jump starts; brief wing deployment is not a completed takeoff. */
public final class TakeoffState {
    public enum Action { APPROACH, WAIT, JUMP, RELEASE, FLY, RECOVER, FINISHED }
    private final Point spot;
    private Point origin,progress;
    private int approachTicks,launchTicks,groundTicks,airTicks,glideTicks,stalledTicks;
    private boolean completed,failed;
    public TakeoffState(Point spot){this.spot=spot;}
    public boolean completed(){return completed;}
    public boolean launching(){return origin!=null&&!completed&&!failed;}
    public boolean failed(){return failed;}
    public Action advance(Point position,boolean grounded,boolean gliding,boolean collision,boolean pathReady,double verticalSpeed){
        if(completed)return grounded?Action.FINISHED:Action.FLY;
        if(failed)return Action.RECOVER;
        if(origin==null){
            if(++approachTicks>400){failed=true;return Action.RECOVER;}
            boolean atSpot=spot==null||Math.hypot(position.x()-spot.x(),position.z()-spot.z())<=.8&&Math.abs(position.y()-spot.y())<=.6;
            if(!gliding&&spot!=null&&(!grounded||!atSpot))return Action.APPROACH;
            // Latch the launch phase. Rising a block must never send us walking back to spot.y.
            origin=progress=position;
        }
        launchTicks++;
        glideTicks=gliding&&!grounded?glideTicks+1:0;
        if(glideTicks>=10&&!collision&&(Math.hypot(position.x()-origin.x(),position.z()-origin.z())>=10||position.y()-origin.y()>=6)){
            completed=true;return Action.FLY;
        }
        if(position.distanceSquared(progress)>=4){progress=position;stalledTicks=0;}else stalledTicks++;
        // This clock also advances while gliding, unlike the old jump-only timeout.
        if(launchTicks>=120||collision&&stalledTicks>=40){failed=true;return Action.RECOVER;}
        if(gliding)return Action.FLY;
        if(!pathReady)return Action.WAIT;
        if(grounded){airTicks=0;return ++groundTicks%12==1?Action.JUMP:Action.RELEASE;}
        groundTicks=0;
        return ++airTicks%4==0&&verticalSpeed<-.02?Action.JUMP:Action.RELEASE;
    }
    public static boolean sameFailedArea(Point site,Point failed){
        return Math.abs(site.y()-failed.y())<=4&&Math.hypot(site.x()-failed.x(),site.z()-failed.z())<=4;
    }
}
