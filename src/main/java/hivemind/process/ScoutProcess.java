/* HiveMind, LGPL-3.0. */
package hivemind.process;
import hivemind.HiveMind;
import hivemind.api.process.*;
import hivemind.api.pathing.goals.GoalXZ;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.*;
import hivemind.utils.network.TaskQueue;
import hivemind.utils.mining.MiningRouteAccess;
import net.minecraft.core.BlockPos;
import java.nio.file.Path;
import java.util.*;
/** Durable user itinerary; finite surveys, waypoint checks and final storage returns. */
public final class ScoutProcess extends HiveMindProcessHelper implements AerialSurvey {
    private TaskQueue queue;
    private Path root;
    private TaskQueue.Task current;
    private List<SurveyPolicy.Offset> points=List.of();
    private int index,failures,skipped;
    private long retryAt,waitUntil;
    private boolean depositing;
    private String reason="No map order";
    public ScoutProcess(HiveMind bot){super(bot);}
    private void load(){if(ctx.player()==null||ctx.world()==null)return;Path here=MiningRouteAccess.root(ctx);if(!here.equals(root)){root=here;queue=new TaskQueue(here);current=null;points=List.of();depositing=false;}}
    public TaskQueue queue(){load();return queue;}
    public void start(MapOrder next){load();queue.add(next.kind(),new hivemind.utils.mining.WorldRoutes.Endpoint(next.dimension(),next.x(),64,next.z()),next.radius(),true);reset();}
    public void add(String kind,hivemind.utils.mining.WorldRoutes.Endpoint point,int radius){load();queue.add(kind,point,radius,false);}
    public void hold(boolean held){load();queue.pause(held);reset();}
    public void reset(){current=null;points=List.of();index=failures=skipped=0;retryAt=waitUntil=0;depositing=false;}
    @Override public boolean isActive(){if(hivemind.isPlayerClient()||ctx.player()==null||ctx.world()==null)return false;load();return !queue.paused()&&queue.first()!=null;}
    @Override public double priority(){return -1;}
    public BlockPos destination(){if(!isActive())return null;var t=current==null?queue.first():current;return new BlockPos(t.point().x(),ctx.playerFeet().getY(),t.point().z());}
    private BlockPos point(){var p=points.get(index);return new BlockPos(p.x(),ctx.playerFeet().getY(),p.z());}
    public String status(){load();if(queue==null||queue.first()==null)return reason;if(queue.paused())return "Map queue paused · "+queue.tasks().size()+" tasks";var t=current==null?queue.first():current;return "Map #"+t.id()+" "+t.kind()+" "+t.point().x()+" "+t.point().z()+" · "+(index+1)+"/"+Math.max(1,points.size())+" · "+queue.tasks().size()+" tasks";}
    private void complete(){if(current!=null)queue.complete(current.id());reason="Map task completed"+(skipped>0?" with "+skipped+" blocked survey points skipped":"");reset();}
    private boolean advance(){if(++index<points.size()){failures=0;return true;}complete();return false;}
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        if(current==null){current=queue.first();points=new MapOrder(current.kind().equals("explore")?"explore":"go",current.point().dimension(),current.point().x(),current.point().z(),current.radius()).waypoints(hivemind.getFlight().capable()?128:64);index=0;}
        if(!MiningRouteAccess.dimension(ctx).equals(current.point().dimension())){queue.pause(true);reason="Map queue held: next task is in "+current.point().dimension();reset();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}
        long now=System.currentTimeMillis();if(now<retryAt)return pause();
        if(depositing){if(hivemind.getDepositProcess().isActive())return new PathingCommand(null,PathingCommandType.DEFER);String error=hivemind.getDepositProcess().lastDepositError();if(error!=null){queue.pause(true);reason="Storage return held: "+error;reset();return pause();}complete();return pause();}
        if(failed){retryAt=now+1500;if(++failures>=3&&current.kind().equals("explore")){skipped++;if(!advance())return pause();}return pause();}
        BlockPos p=point();if(!ctx.world().getWorldBorder().isWithinBounds(p)){queue.pause(true);reason="Destination outside world border";reset();return pause();}
        if(Math.hypot(p.getX()-ctx.player().getX(),p.getZ()-ctx.player().getZ())<8){
            if(current.kind().equals("check")){if(waitUntil==0)waitUntil=now+10000;if(now<waitUntil)return pause();}
            if(current.kind().equals("return")){try{hivemind.getDepositProcess().startFinalDeposit();depositing=true;return pause();}catch(RuntimeException error){reason="Storage return held: "+error.getMessage();queue.pause(true);reset();return pause();}}
            if(!advance())return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);p=point();
        }
        if(hivemind.getFlight().capable()&&(current.kind().equals("explore")?hivemind.getFlight().survey(this,p):hivemind.getFlight().travel(this,p)))return pause();
        return new PathingCommand(new GoalXZ(p.getX(),p.getZ()),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    private PathingCommand pause(){return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
    @Override public BlockPos nextSurveyWaypoint(){if(!isActive()||current==null||!MiningRouteAccess.dimension(ctx).equals(current.point().dimension())||!advance())return null;return point();}
    @Override public void onLostControl(){if(queue!=null&&queue.first()!=null)queue.pause(true);reset();}
    @Override public String displayName0(){return status();}
}
