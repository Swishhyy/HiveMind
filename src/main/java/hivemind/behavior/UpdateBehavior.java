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
    public UpdateBehavior(HiveMind bot){super(bot);}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE||hivemind!=HiveMindAPI.getProvider().getPrimaryHiveMind()||!UpdateRuntime.ready())return;
        var service=UpdateRuntime.service();service.pulse();
        if(event.getType()!=TickEvent.Type.IN||ctx.player()==null||!ctx.player().isAlive())return;
        if(!service.asked()&&!showing&&ctx.minecraft().gui.screen()==null){
            showing=true;ctx.minecraft().gui.setScreen(new ConfirmScreen(yes->{
                service.consent(yes);showing=false;ctx.minecraft().gui.setScreen(null);
            },Component.literal("Enable HiveMind automatic updates?"),Component.literal("Download new Fabric 26.3 releases from Swishhyy/HiveMind. Install after Minecraft closes; activate next launch. You can change this with #update or the controller.")));
        }
        if(!announced&&Boolean.parseBoolean(service.snapshot().get("restart"))){Helper.HELPER.logDirect("HiveMind update downloaded. Close and relaunch Minecraft to finish installing.");announced=true;}
    }
}
