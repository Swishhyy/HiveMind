/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
/** Progress windows avoid treating intentional watching, eating or menus as a stuck route. */
public final class RecoveryPolicy {
    public enum Action { NONE, REPATH, SIDESTEP, WALK }
    private String task="";private double x,z,lastX,lastZ,walked;private long since,lastAction;private int attempts;
    public Action sample(String work,double px,double pz,boolean moving,long now){
        if(!moving||!task.equals(work)||Math.hypot(px-x,pz-z)>8){task=work;x=lastX=px;z=lastZ=pz;since=now;walked=0;if(!moving)attempts=0;return Action.NONE;}
        walked+=Math.hypot(px-lastX,pz-lastZ);lastX=px;lastZ=pz;
        if(now-since<15000||now-lastAction<15000)return Action.NONE;
        if(Math.hypot(px-x,pz-z)<2||walked>32){since=lastAction=now;walked=0;return switch(++attempts%3){case 1->Action.REPATH;case 2->Action.SIDESTEP;default->Action.WALK;};}
        return Action.NONE;
    }
    public int attempts(){return attempts;}
}
