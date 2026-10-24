/* HiveMind, LGPL-3.0. */
package hivemind.behavior;
import hivemind.HiveMind;
import hivemind.api.utils.Helper;
import hivemind.api.HiveMindAPI;
import hivemind.api.event.events.TickEvent;
import hivemind.api.event.events.type.EventState;
import hivemind.utils.update.UpdateRuntime;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
public final class UpdateBehavior extends Behavior {
    private boolean showing,announced;
    private long bootObserved;
    private boolean healthy;
    public UpdateBehavior(HiveMind bot){super(bot);}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE||hivemind!=HiveMindAPI.getProvider().getPrimaryHiveMind()||!UpdateRuntime.ready())return;
        var service=UpdateRuntime.service();service.pulse();
        long now=System.currentTimeMillis();if(bootObserved==0)bootObserved=now;if(!healthy&&now-bootObserved>=60000){service.healthy();healthy=true;}
        if(event.getType()!=TickEvent.Type.IN||ctx.player()==null){showing=false;return;}
        if(!ctx.player().isAlive())return;
        if(!service.asked()&&!showing&&ctx.minecraft().gui.screen()==null){
            showing=true;ctx.minecraft().gui.setScreen(new ConfirmScreen(yes->{
                service.consent(yes);showing=false;ctx.minecraft().gui.setScreen(null);
            },Component.literal("Enable HiveMind automatic updates?"),Component.literal("Download new Fabric 26.3 releases from Swishhyy/HiveMind. Supervised bots can restart at a safe checkpoint; other clients install after Minecraft closes. You can change this with #update or the controller.")));
        }
        if(!announced&&Boolean.parseBoolean(service.snapshot().get("restart"))){Helper.HELPER.logDirect("HiveMind update staged. A supervised bot waits for its safe restart grant; otherwise close and relaunch Minecraft to install.");announced=true;}
        if(!hivemind.isPlayerClient()&&HiveMind.settings().botUpdateRestart.value&&Boolean.parseBoolean(service.snapshot().get("restart"))&&service.restartAuthorized(hivemind.getNetwork().expectsController())&&service.supervised()&&!hivemind.getReliability().checkpoint().held()&&hivemind.getReliability().canResumeAfterRestart()){
            hivemind.getReliability().updateHold(true);
            if(hivemind.getFlight().flying()&&hivemind.getElytraProcess() instanceof hivemind.process.ElytraProcess flight){flight.requestLanding();return;}
            if(ctx.player().getHealth()>=HiveMind.settings().botMinimumHealth.value&&ctx.player().getFoodData().getFoodLevel()>=8&&!hivemind.getShulkerProcess().isActive()&&!hivemind.getShulkerProcess().hasPendingRecovery()&&!hivemind.getDepositProcess().isActive()&&!hivemind.getWorkstationProcess().isActive()&&hivemind.getCompanionProcess().restartSafe()&&hivemind.getShulkerProcess().safeForResupply()&&ctx.player().onGround()&&!ctx.player().isUsingItem()&&ctx.minecraft().gui.screen()==null&&ctx.player().containerMenu==ctx.player().inventoryMenu&&ctx.player().inventoryMenu.getCarried().isEmpty()){
                service.requestRestart();ctx.minecraft().stop();
            }
        }else hivemind.getReliability().updateHold(false);
    }
}
