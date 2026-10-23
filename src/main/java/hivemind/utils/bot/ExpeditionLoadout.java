/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import hivemind.HiveMind;
import hivemind.api.utils.IPlayerContext;
import hivemind.utils.network.SupplyReserves;
import hivemind.process.elytra.ElytraBehavior;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.tags.ItemTags;
import java.util.*;
/** Counts compatible food and rocket variants, and includes usable worn wings / offhand totems once. */
public final class ExpeditionLoadout {
    private ExpeditionLoadout(){}
    public static boolean matches(ItemStack stack,String key){if(stack.isEmpty())return false;return switch(key){
        case SupplyReserves.FOOD->BotInventory.food(stack);
        case SupplyReserves.ROCKETS->ElytraBehavior.isFireworks(stack);
        case SupplyReserves.ELYTRA->stack.is(Items.ELYTRA)&&!EquipmentLoadout.bound(stack)&&stack.getMaxDamage()-stack.getDamageValue()>=Math.max(32,HiveMind.settings().elytraMinimumDurability.value+1);
        case SupplyReserves.TOTEMS->stack.is(Items.TOTEM_OF_UNDYING);
        case SupplyReserves.BLOCKS->BotInventory.building(stack);
        case "@pickaxe"->BotInventory.pickaxe(stack,false);
        case "@debris_pickaxe"->BotInventory.pickaxe(stack,true);
        case "@weapon"->(stack.is(ItemTags.SWORDS)||stack.is(ItemTags.AXES))&&(!stack.isDamageableItem()||stack.getMaxDamage()-stack.getDamageValue()>Math.max(1,HiveMind.settings().botToolReserve.value));
        case "@crystals"->stack.is(Items.END_CRYSTAL);
        case "@obsidian"->stack.is(Items.OBSIDIAN);
        case "@anchors"->stack.is(Items.RESPAWN_ANCHOR);
        case "@glowstone"->stack.is(Items.GLOWSTONE);
        default->EquipmentLoadout.ARMOR.containsKey(key)?EquipmentLoadout.matches(stack,key):BotItems.id(stack).equals(key)&&BotItems.expeditionSupply(stack);
    };}
    public static boolean portable(ItemStack stack){return stack.getItem() instanceof BlockItem block&&block.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock;}
    public static int count(IPlayerContext ctx,String key){if(EquipmentLoadout.ARMOR.containsKey(key))return EquipmentLoadout.count(ctx,key);int total=BotInventory.count(ctx,s->matches(s,key));if(key.equals(SupplyReserves.ELYTRA)&&matches(ctx.player().getItemBySlot(EquipmentSlot.CHEST),key))total++;if(key.equals(SupplyReserves.TOTEMS)&&matches(ctx.player().getOffhandItem(),key))total++;return total;}
    public static Map<String,Integer> counts(IPlayerContext ctx,Collection<String> keys){Map<String,Integer> result=new LinkedHashMap<>();keys.forEach(key->result.put(key,count(ctx,key)));return Map.copyOf(result);}
    /** Box contents count for expedition planning, never for a rocket click or an equipped item. */
    public static Map<String,Integer> boxedCounts(IPlayerContext ctx,Collection<String> keys){
        Map<String,Integer> result=new LinkedHashMap<>();
        for(int slot=0;slot<36;slot++){
            ItemStack box=ctx.player().getInventory().getItem(slot);if(!portable(box))continue;
            var contents=box.get(net.minecraft.core.component.DataComponents.CONTAINER);if(contents==null)continue;
            var items=net.minecraft.core.NonNullList.<ItemStack>withSize(27,ItemStack.EMPTY);contents.copyInto(items);
            for(ItemStack item:items)if(!item.isEmpty())for(String key:keys)if(matches(item,key))result.merge(key,item.getCount()*box.getCount(),Integer::sum);
        }
        return Map.copyOf(result);
    }
    public static Map<String,Integer> targets(IPlayerContext ctx){Map<String,Integer> targets=new LinkedHashMap<>(SupplyReserves.targets(HiveMind.settings().botExpeditionRockets.value,HiveMind.settings().botExpeditionFood.value,HiveMind.settings().botExpeditionElytras.value,HiveMind.settings().botExpeditionTotems.value));if(!HiveMind.settings().botUseElytra.value){targets.remove(SupplyReserves.ROCKETS);targets.remove(SupplyReserves.ELYTRA);}if(!HiveMind.settings().botAutoTotem.value)targets.remove(SupplyReserves.TOTEMS);EquipmentLoadout.addTargets(targets);return Map.copyOf(targets);}
    public static Map<String,Integer> missing(IPlayerContext ctx){Map<String,Integer> targets=targets(ctx);Map<String,Integer> counts=new HashMap<>();targets.keySet().forEach(key->counts.put(key,count(ctx,key)));return SupplyReserves.missing(targets,counts);}
}
