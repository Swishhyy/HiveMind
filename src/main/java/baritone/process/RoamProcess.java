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

package baritone.process;

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.pathing.goals.GoalXZ;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.bot.AtomicProperties;
import baritone.utils.bot.BotWork;
import baritone.utils.mining.MiningRouteAccess;
import baritone.utils.network.RoamPolicy;
import baritone.utils.network.TeamPackets.Kind;
import net.minecraft.core.BlockPos;
import java.nio.file.Path;
import java.util.*;

/** Lowest-priority, terrain-preserving scouting; foreground work always takes over. */
public final class RoamProcess extends BaritoneProcessHelper implements baritone.api.behavior.IBehavior {
    private Path root;
    private boolean held;
    private BlockPos destination;
    private long deadline,retryAt;
    private int leg;
    private final Map<BlockPos,Long> blocked=new HashMap<>();
    public RoamProcess(Baritone bot){super(bot);}
    private void load(){
        Path here=MiningRouteAccess.root(ctx);if(here.equals(root))return;
        held=Boolean.parseBoolean(AtomicProperties.read(here.resolve("bot-roam.properties")).getProperty("held","false"));root=here;destination=null;blocked.clear();leg=0;
    }
    public void hold(boolean hold){
        load();Properties p=new Properties();p.setProperty("held",Boolean.toString(hold));AtomicProperties.write(root.resolve("bot-roam.properties"),p);held=hold;destination=null;retryAt=0;
    }
    @Override public boolean isActive(){
        if(ctx.player()==null||ctx.world()==null||ctx.minecraft().getConnection()==null)return false;
        load();return RoamPolicy.eligible(Baritone.settings().botAutoRoam.value,held,ctx.player().isAlive(),BotWork.foreground(baritone),baritone.getJobManager().hasQueuedJobs(),ctx.minecraft().screen!=null);
    }
    public boolean parked(){load();return held;}
    public BlockPos destination(){return destination;}
    public boolean terrainLocked(){return isActive();}
    public String status(){if(!isActive())return held?"Roaming parked":"Roaming standby";return "Roaming"+(destination==null?": choosing unexplored terrain":" toward "+destination.getX()+" "+destination.getZ());}
    @Override public boolean isTemporary(){return true;}
    @Override public double priority(){return -2;}
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        long now=System.currentTimeMillis();
        if(now<retryAt)return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
        if(RoamPolicy.needsCare(ctx.player().getHealth(),ctx.player().getFoodData().getFoodLevel())&&baritone.getSurvivalProcess().beforeMining())return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
        if(destination!=null&&(failed||now>deadline)){blocked.put(destination,now+120000);destination=null;leg++;retryAt=now+2000;return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
        if(destination!=null&&Math.hypot(destination.getX()-ctx.player().getX(),destination.getZ()-ctx.player().getZ())<8){destination=null;leg++;}
        if(destination==null){
            blocked.values().removeIf(until->until<now);double best=Double.POSITIVE_INFINITY;
            double initial=baritone.getNetwork().team().heading(Kind.ROAM,null)+leg*.37;
            for(int radius:new int[]{64,96,144})for(int step=0;step<16;step++){
                double angle=initial+step*Math.PI/8;BlockPos point=ctx.playerFeet().offset((int)Math.round(Math.cos(angle)*radius),0,(int)Math.round(Math.sin(angle)*radius));
                if(blocked.containsKey(point)||!ctx.world().getWorldBorder().isWithinBounds(point))continue;
                boolean visited=baritone.getNetwork().visited(point),occupied=baritone.getNetwork().team().roamOccupied(point);
                double delta=Math.atan2(Math.sin(angle-initial),Math.cos(angle-initial));
                double score=RoamPolicy.frontierScore(visited,radius,delta,occupied);
                if(score<best){best=score;destination=point;}
            }
            if(destination==null){retryAt=now+5000;return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
            deadline=now+120000;
        }
        return new PathingCommand(new GoalXZ(destination.getX(),destination.getZ()),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    @Override public void onLostControl(){destination=null;deadline=0;}
    @Override public void onPlayerDeath(){hold(true);onLostControl();}
    @Override public String displayName0(){return status();}
}
