/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import hivemind.HiveMind;
import hivemind.api.utils.IPlayerContext;
import hivemind.utils.network.SupplyReserves;
import hivemind.process.elytra.ElytraBehavior;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import java.util.*;
/** Counts compatible food and rocket variants, and includes usable worn wings / offhand totems once. */
public final class ExpeditionLoadout {
    private ExpeditionLoadout(){}
    public static boolean matches(ItemStack stack,String key){if(stack.isEmpty())return false;return switch(key){
        case SupplyReserves.FOOD->BotInventory.food(stack);
        case SupplyReserves.ROCKETS->ElytraBehavior.isFireworks(stack);
        case SupplyReserves.ELYTRA->stack.is(Items.ELYTRA)&&stack.getMaxDamage()-stack.getDamageValue()>=Math.max(32,HiveMind.settings().elytraMinimumDurability.value+1);
        case SupplyReserves.TOTEMS->stack.is(Items.TOTEM_OF_UNDYING);
        case SupplyReserves.BLOCKS->BotInventory.building(stack);
        default->BotItems.id(stack).equals(key)&&BotItems.expeditionSupply(stack);
    };}
    public static boolean portable(ItemStack stack){return stack.getItem() instanceof BlockItem block&&block.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock;}
    public static int count(IPlayerContext ctx,String key){int total=BotInventory.count(ctx,s->matches(s,key));if(key.equals(SupplyReserves.ELYTRA)&&matches(ctx.player().getItemBySlot(EquipmentSlot.CHEST),key))total++;if(key.equals(SupplyReserves.TOTEMS)&&matches(ctx.player().getOffhandItem(),key))total++;return total;}
    public static Map<String,Integer> targets(IPlayerContext ctx){Map<String,Integer> targets=new LinkedHashMap<>(SupplyReserves.targets(HiveMind.settings().botExpeditionRockets.value,HiveMind.settings().botExpeditionFood.value,HiveMind.settings().botExpeditionElytras.value,HiveMind.settings().botExpeditionTotems.value));if(!HiveMind.settings().botUseElytra.value){targets.remove(SupplyReserves.ROCKETS);targets.remove(SupplyReserves.ELYTRA);}if(!HiveMind.settings().botAutoTotem.value)targets.remove(SupplyReserves.TOTEMS);return Map.copyOf(targets);}
    public static Map<String,Integer> missing(IPlayerContext ctx){Map<String,Integer> targets=targets(ctx);Map<String,Integer> counts=new HashMap<>();targets.keySet().forEach(key->counts.put(key,count(ctx,key)));return SupplyReserves.missing(targets,counts);}
}
