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

package hivemind.process;

import hivemind.HiveMind;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.api.pathing.goals.GoalXZ;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.bot.BotWork;
import hivemind.utils.bot.AerialSurvey;
import hivemind.utils.bot.SurveyRoute;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.network.RoamPolicy;
import hivemind.utils.network.TeamPackets.Kind;
import net.minecraft.core.BlockPos;
import java.nio.file.Path;
import java.util.*;

/** Lowest-priority, terrain-preserving scouting; foreground work always takes over. */
public final class RoamProcess extends HiveMindProcessHelper implements hivemind.api.behavior.IBehavior,AerialSurvey {
    private Path root;
    private boolean held;
    private BlockPos destination;
    private long deadline,retryAt;
    private SurveyRoute survey;
    private boolean airRoute;
    private String dimension;
    private final Map<BlockPos,Long> blocked=new HashMap<>();
    public RoamProcess(HiveMind bot){super(bot);}
    private void load(){
        Path here=MiningRouteAccess.root(ctx);String dim=MiningRouteAccess.dimension(ctx);if(here.equals(root)&&dim.equals(dimension))return;
        held=Boolean.parseBoolean(AtomicProperties.read(here.resolve("bot-roam.properties")).getProperty("held","false"));root=here;dimension=dim;destination=null;blocked.clear();survey=null;
    }
    public void hold(boolean hold){
        load();Properties p=new Properties();p.setProperty("held",Boolean.toString(hold));AtomicProperties.write(root.resolve("bot-roam.properties"),p);held=hold;destination=null;retryAt=0;
    }
    @Override public boolean isActive(){
        if(ctx.player()==null||ctx.world()==null||ctx.minecraft().getConnection()==null)return false;
        load();return RoamPolicy.eligible(HiveMind.settings().botAutoRoam.value,held,ctx.player().isAlive(),BotWork.foreground(hivemind),hivemind.getJobManager().hasQueuedJobs(),ctx.minecraft().gui.screen()!=null);
    }
    public boolean parked(){load();return held;}
    public BlockPos destination(){return destination;}
    public boolean terrainLocked(){return isActive();}
    public String status(){if(!isActive())return held?"Roaming parked":"Roaming standby";return "Roaming outward"+(survey==null?"":" leg "+survey.step())+(destination==null?": choosing unexplored terrain":" toward "+destination.getX()+" "+destination.getZ());}
    @Override public boolean isTemporary(){return true;}
    @Override public double priority(){return -2;}
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        long now=System.currentTimeMillis();
        if(now<retryAt)return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
        if(RoamPolicy.needsCare(ctx.player().getHealth(),ctx.player().getFoodData().getFoodLevel())&&hivemind.getSurvivalProcess().beforeMining())return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
        if(destination!=null&&(failed||now>deadline)){blocked.put(destination,now+120000);destination=null;retryAt=now+2000;return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
        if(destination!=null&&Math.hypot(destination.getX()-ctx.player().getX(),destination.getZ()-ctx.player().getZ())<8)destination=null;
        if(destination==null){
            destination=chooseSurvey(hivemind.getFlight().capable());
            if(destination==null){retryAt=now+5000;return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
            deadline=now+300000;
        }
        if(hivemind.getFlight().capable()){
            if(hivemind.getFlight().survey(this,destination))return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalXZ(destination.getX(),destination.getZ()),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    private BlockPos chooseSurvey(boolean aerial){
        long now=System.currentTimeMillis();blocked.values().removeIf(until->until<now);
        if(survey==null||aerial!=airRoute){airRoute=aerial;survey=new SurveyRoute(root,aerial?"roam-air-frontier":"roam-ground-frontier",MiningRouteAccess.endpoint(ctx,ctx.playerFeet()),
                aerial?HiveMind.settings().botFlightSurveySpacing.value:64,hivemind.getNetwork().team().heading(Kind.ROAM,null));}
        BlockPos fallback=null;
        for(int attempt=0;attempt<64;attempt++){
            BlockPos point=MiningRouteAccess.position(survey.nextFrontier(aerial?HiveMind.settings().botFlightRoamDistance.value:192));
            if(blocked.containsKey(point)||!ctx.world().getWorldBorder().isWithinBounds(point)||hivemind.getNetwork().team().roamOccupied(point)||point.distSqr(ctx.playerFeet())<48*48)continue;
            fallback=point;if(!hivemind.getNetwork().mapped(point)){survey.save();return point;}
        }
        survey.save();return fallback;
    }
    @Override public BlockPos nextSurveyWaypoint(){
        if(!isActive()||!hivemind.getFlight().capable())return null;
        destination=chooseSurvey(true);deadline=System.currentTimeMillis()+300000;return destination;
    }
    @Override public void onLostControl(){destination=null;deadline=0;}
    @Override public void onPlayerDeath(){if(!HiveMind.settings().botAutoRespawn.value)hold(true);onLostControl();}
    @Override public String displayName0(){return status();}
}
