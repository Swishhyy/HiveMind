/* HiveMind, LGPL-3.0. */
package hivemind.process;
import hivemind.HiveMind;
import hivemind.api.process.*;
import hivemind.api.pathing.goals.GoalXZ;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.*;
import hivemind.utils.mining.MiningRouteAccess;
import net.minecraft.core.BlockPos;
import java.util.*;
/** User-selected atlas destination or finite area survey. Flight retains this parent task. */
public final class ScoutProcess extends HiveMindProcessHelper implements AerialSurvey {
    private MapOrder order;
    private List<SurveyPolicy.Offset> points=List.of();
    private int index,failures;
    private long retryAt;
    private String reason="No map order";
    public ScoutProcess(HiveMind bot){super(bot);}
    public void start(MapOrder next){order=next;points=next.waypoints(hivemind.getFlight().capable()?128:64);index=failures=0;retryAt=0;reason="Starting map order";}
    @Override public boolean isActive(){return order!=null&&!hivemind.isPlayerClient()&&ctx.player()!=null&&ctx.world()!=null;}
    @Override public double priority(){return -1;}
    private BlockPos point(){var p=points.get(index);return new BlockPos(p.x(),ctx.playerFeet().getY(),p.z());}
    public String status(){return order==null?reason:(order.kind().equals("go")?"Going to ":"Exploring ")+order.x()+" "+order.z()+" · "+(index+1)+"/"+points.size();}
    private boolean advance(){if(++index<points.size()){failures=0;return true;}order=null;reason="Map order completed";return false;}
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        if(!MiningRouteAccess.dimension(ctx).equals(order.dimension())){onLostControl();reason="Map order stopped: dimension changed";return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}
        long now=System.currentTimeMillis();if(now<retryAt)return pause();
        if(failed){retryAt=now+1500;if(++failures>=3&&order.kind().equals("explore")){if(!advance())return pause();}return pause();}
        BlockPos p=point();if(!ctx.world().getWorldBorder().isWithinBounds(p)){if(order.kind().equals("explore")){advance();return pause();}reason="Destination outside world border";order=null;return pause();}
        if(Math.hypot(p.getX()-ctx.player().getX(),p.getZ()-ctx.player().getZ())<8){if(!advance())return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);p=point();}
        if(hivemind.getFlight().capable()&&(order.kind().equals("explore")?hivemind.getFlight().survey(this,p):hivemind.getFlight().travel(this,p)))return pause();
        return new PathingCommand(new GoalXZ(p.getX(),p.getZ()),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    private PathingCommand pause(){return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
    @Override public BlockPos nextSurveyWaypoint(){if(!isActive()||!MiningRouteAccess.dimension(ctx).equals(order.dimension())||!advance())return null;return point();}
    @Override public void onLostControl(){order=null;points=List.of();reason="No map order";}
    @Override public String displayName0(){return status();}
}
