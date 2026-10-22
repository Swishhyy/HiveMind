/* HiveMind, LGPL-3.0. */
package hivemind.behavior;
import hivemind.HiveMind;
import hivemind.api.HiveMindAPI;
import hivemind.api.event.events.TickEvent;
import hivemind.api.event.events.type.EventState;
import hivemind.utils.bot.SessionRuntime;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
public final class SessionBehavior extends Behavior {
    private float health=20;
    private boolean wasOnline;
    private long resumeAt;
    public SessionBehavior(HiveMind bot){super(bot);}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE||hivemind!=HiveMindAPI.getProvider().getPrimaryHiveMind())return;
        var mc=ctx.minecraft();var session=SessionRuntime.get(mc.gameDirectory.toPath());long now=System.currentTimeMillis();
        boolean online=ctx.player()!=null&&ctx.world()!=null&&mc.getConnection()!=null;
        var server=mc.getCurrentServer();session.automatic(HiveMind.settings().botAutoReconnect.value);
        session.observe(online,server==null?null:server.ip,server==null?null:server.name,now,HiveMind.settings().botReconnectDelayMS.value);
        if(online){
            if(!wasOnline)resumeAt=now+5000;
            if(session.resumeJob()!=0&&now>=resumeAt&&ctx.player().isAlive()&&!hivemind.getAutonomy().paused()){
                var jobs=hivemind.getJobManager();var queued=jobs.jobs();
                if(!queued.isEmpty()&&queued.get(0).id()==session.resumeJob())jobs.resumeJobs();
                session.resumeJob(0);
            }
            float current=ctx.player().getHealth();if(wasOnline&&current<health)session.damage(now);health=current;
            session.hostiles((int)ctx.world().players().stream().filter(p->p!=ctx.player()&&p.isAlive()&&!p.isSpectator()&&!p.isCreative()
                &&!hivemind.getNetwork().isTeammate(p.getUUID())&&!hivemind.getBotControl().isTrusted(p.getUUID())&&p.distanceToSqr(ctx.player())<=48*48).count());
        }
        wasOnline=online;
        if(session.takeDisconnect()&&online){
            hivemind.getFlight().cancel();hivemind.getInputOverrideHandler().clearAllKeys();
            if(ctx.world() instanceof net.minecraft.client.multiplayer.ClientLevel level)level.disconnect(Component.literal("[HiveMind] "+session.status()));return;
        }
        boolean controlled=hivemind.getNetwork().controllerConnected();
        if(!online&&session.shouldConnect(now,controlled)&&(mc.gui.screen()==null||mc.gui.screen() instanceof TitleScreen||mc.gui.screen() instanceof DisconnectedScreen)){
            session.attempted(now,HiveMind.settings().botReconnectDelayMS.value);
            try{
                var data=new ServerData(session.name(),session.server(),ServerData.Type.OTHER);
                ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(session.server()),data,false,null);
            }catch(RuntimeException e){session.failed("Reconnect failed: "+e.getClass().getSimpleName());}
        }
    }
}
