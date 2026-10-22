/* This file is part of Baritone, distributed under the LGPL-3.0 license. */
package baritone.utils.social;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RotationUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/** Shared weapon selection and normal-reach aiming for hunts and opportunistic mob defense. */
public final class MeleeCombat {
    private MeleeCombat() {}
    private static double score(ItemStack stack,boolean shield){
        if(stack.isDamageableItem()&&stack.getMaxDamage()-stack.getDamageValue()<=Math.max(1,Baritone.settings().botToolReserve.value))return -1;
        int sharpness=0;var enchantments=stack.getEnchantments();
        for(var enchantment:enchantments.keySet())if(enchantment.is(Enchantments.SHARPNESS))sharpness=enchantments.getLevel(enchantment);
        return CombatPolicy.weaponScore(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),stack.is(ItemTags.SWORDS),stack.is(ItemTags.AXES),sharpness,shield);
    }
    public static int weapon(IPlayerContext ctx,boolean shield){
        int best=-1;double score=-1;
        for(int i=0;i<9;i++){double value=score(ctx.player().getInventory().getItem(i),shield);if(value>score){best=i;score=value;}}
        return best;
    }
    public static boolean prepare(IPlayerContext ctx,boolean shield){
        if(!Baritone.settings().allowInventory.value||ctx.minecraft().gameMode==null||ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())return false;
        int current=weapon(ctx,shield),source=-1,destination=current;
        double best=current<0?-1:score(ctx.player().getInventory().getItem(current),shield);
        for(int i=9;i<36;i++){double value=score(ctx.player().getInventory().getItem(i),shield);if(value>best){source=i;best=value;}}
        if(source<0)return false;
        if(destination<0)for(int i=0;i<9;i++)if(ctx.player().getInventory().getItem(i).isEmpty()){destination=i;break;}
        if(destination<0)for(int i=8;i>=0;i--){
            ItemStack stack=ctx.player().getInventory().getItem(i);
            if(!stack.is(Items.TOTEM_OF_UNDYING)&&!stack.is(Items.SHIELD)&&!stack.is(Items.END_CRYSTAL)&&!stack.is(Items.RESPAWN_ANCHOR)
                    &&!stack.is(Items.GLOWSTONE)&&!stack.is(Items.OBSIDIAN)&&!stack.is(Items.GOLDEN_APPLE)&&!stack.is(Items.ENCHANTED_GOLDEN_APPLE)){destination=i;break;}
        }
        if(destination<0)return false;
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,source,destination,ContainerInput.SWAP,ctx.player());return true;
    }
    public static boolean aim(IPlayerContext ctx,Baritone bot,LivingEntity target){
        bot.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),target.getEyePosition(),ctx.playerRotations()),true);
        var eye=ctx.playerHead();
        return ctx.player().hasLineOfSight(target)&&target.getBoundingBox().inflate(.06)
                .clip(eye,eye.add(RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations()).scale(3))).isPresent();
    }
}
