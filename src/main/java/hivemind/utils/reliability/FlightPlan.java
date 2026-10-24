/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;
import hivemind.utils.bot.FlightPolicy;
import hivemind.utils.bot.FlightPolicy.Point;

/** Every travel task shares one committed flight, rather than independently switching between walk/fly/land. */
public final class FlightPlan {
    public enum Phase { GROUND, TAKEOFF, CRUISE, LANDING }
    private Phase phase=Phase.GROUND;
    private Point destination;
    private long changed,cruise;
    private String reason="";
    public Phase phase(){return phase;}
    public void begin(Point point,long now){phase=Phase.TAKEOFF;destination=point;changed=now;cruise=0;reason="Launching";}
    public void airborne(long now){if(phase==Phase.TAKEOFF){phase=Phase.CRUISE;cruise=now;reason="Committed to the current flight leg";}}
    public boolean refillReady(long now,int rockets,int reserve){return phase==Phase.CRUISE&&(rockets<=reserve+2||now-cruise>=5000);}
    public boolean mayRetarget(Point point,long now){return phase!=Phase.LANDING&&FlightPolicy.replan(now,changed,destination,point);}
    public void retargeted(Point point,long now){destination=point;changed=now;}
    public void landing(String why){phase=Phase.LANDING;reason=why;}
    public void finished(){phase=Phase.GROUND;destination=null;reason="Ready for another trip";}
    public String summary(){return phase.name().toLowerCase()+": "+reason+(destination==null?"":" toward "+Math.round(destination.x())+" "+Math.round(destination.z()));}
}
