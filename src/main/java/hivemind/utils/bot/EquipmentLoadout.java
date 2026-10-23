/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import hivemind.HiveMind;
import hivemind.api.utils.IPlayerContext;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import java.util.*;
/** Armor reserves count worn gear once; inventory swaps preserve displaced equipment, including full inventories. */
public final class EquipmentLoadout {
    private EquipmentLoadout(){}
    public static final Map<String,EquipmentSlot> ARMOR=Map.of("@armor_helmet",EquipmentSlot.HEAD,"@armor_chestplate",EquipmentSlot.CHEST,"@armor_leggings",EquipmentSlot.LEGS,"@armor_boots",EquipmentSlot.FEET);
    public static boolean bound(ItemStack stack){return stack.getEnchantments().keySet().stream().anyMatch(h->h.is(Enchantments.BINDING_CURSE));}
    public static double score(ItemStack stack){
        if(stack.isEmpty())return -1;int protection=0;var ench=stack.getEnchantments();for(var h:ench.keySet())if(h.is(Enchantments.PROTECTION)||h.is(Enchantments.BLAST_PROTECTION))protection+=ench.getLevel(h);
        return EquipmentPolicy.score(BotItems.id(stack),stack.getMaxDamage()-stack.getDamageValue(),stack.getMaxDamage(),protection,bound(stack));
    }
    public static boolean matches(ItemStack stack,String key){return EquipmentPolicy.category(BotItems.id(stack)).equals(key)&&score(stack)>0;}
    public static int count(IPlayerContext ctx,String key){int count=BotInventory.count(ctx,s->matches(s,key));var slot=ARMOR.get(key);if(slot!=null&&matches(ctx.player().getItemBySlot(slot),key))count++;return count;}
    public static void addTargets(Map<String,Integer> targets){if(HiveMind.settings().botRestockArmor.value)ARMOR.keySet().forEach(key->targets.put(key,1));}
    public static boolean swap(IPlayerContext ctx,int source,EquipmentSlot equipment){
        int armor=switch(equipment){case HEAD->5;case CHEST->6;case LEGS->7;case FEET->8;default->throw new IllegalArgumentException("Not armor");};
        if(bound(ctx.player().getItemBySlot(equipment))||!ctx.player().inventoryMenu.slots.get(armor).mayPickup(ctx.player())||!ctx.player().inventoryMenu.slots.get(armor).mayPlace(ctx.player().getInventory().getItem(source)))return false;
        int menu=source<9?source+36:source;
        if(source>=9)ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,menu,8,ContainerInput.SWAP,ctx.player());
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,armor,source<9?source:8,ContainerInput.SWAP,ctx.player());
        if(source>=9)ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,menu,8,ContainerInput.SWAP,ctx.player());return true;
    }
    public static boolean equipOne(HiveMind bot){
        var ctx=bot.getPlayerContext();if(bot.isPlayerClient()||!HiveMind.settings().allowInventory.value||!HiveMind.settings().botAutoArmor.value||ctx.player()==null||ctx.world()==null||!ctx.player().isAlive()||ctx.minecraft().gameMode==null||ctx.minecraft().gui.screen()!=null||ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty()||!ctx.player().onGround()||ctx.player().isFallFlying()||ctx.player().isUsingItem()||bot.getFlight().flying()||bot.getElytraProcess().isActive()||bot.getAutonomy().paused())return false;
        boolean wings=HiveMind.settings().botUseElytra.value&&!bot.getStalkProcess().committedCombat()&&!bot.getConflictProcess().isActive();
        for(var entry:ARMOR.entrySet()){
            var worn=ctx.player().getItemBySlot(entry.getValue());if(bound(worn))continue;
            if(entry.getValue()==EquipmentSlot.CHEST&&wings){
                int best=-1,durability=Math.max(32,HiveMind.settings().elytraMinimumDurability.value+1);
                if(worn.is(Items.ELYTRA)&&worn.getMaxDamage()-worn.getDamageValue()>=durability)continue;
                for(int i=0;i<36;i++){var item=ctx.player().getInventory().getItem(i);int left=item.getMaxDamage()-item.getDamageValue();if(item.is(Items.ELYTRA)&&!bound(item)&&left>=durability){best=i;durability=left;}}
                if(best>=0)return swap(ctx,best,entry.getValue());
            }
            int best=-1;double score=score(worn);for(int i=0;i<36;i++){var item=ctx.player().getInventory().getItem(i);if(matches(item,entry.getKey())&&score(item)>score){best=i;score=score(item);}}
            if(best>=0)return swap(ctx,best,entry.getValue());
        }return false;
    }
}
