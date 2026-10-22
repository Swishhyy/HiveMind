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
import hivemind.utils.network.RoamPolicy;
import hivemind.api.behavior.IBehavior;
import hivemind.api.event.events.TickEvent;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.api.utils.Rotation;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.BotInventory;
import hivemind.utils.bot.BotWork;
import hivemind.utils.bot.SurvivalPolicy;
import hivemind.utils.mining.MiningRouteAccess;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Temporary care and resupply preserve the original mining filter and deposit credit. */
public final class SurvivalProcess extends HiveMindProcessHelper implements IBehavior {
    private enum Stage { IDLE, CARE, SUPPLY, REPAIR, WAIT }
    private Stage stage = Stage.IDLE;
    private String reason = "Ready";
    private int ticks;
    private int swapDelay;
    private int previousSlot = -1;
    private boolean ownsUse;
    private int careTicks;
    private long stalkCareRetryAt;

    public SurvivalProcess(HiveMind bot) { super(bot); }
    @Override public boolean isActive() { return stage != Stage.IDLE; }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return -0.2; }
    public String status() { return stage + ": " + reason; }
    public boolean holdsUse() { return ownsUse && controlled(); }
    private boolean controlled() { return hivemind.getPathingControlManager().mostRecentInControl().orElse(null) == this; }

    private String shortage() {
        if (BotInventory.needsFood(ctx) && (!hivemind.getCompanionProcess().isRecovering() || ctx.player().getFoodData().getFoodLevel()<=6 || ctx.player().getHealth()<HiveMind.settings().botMinimumHealth.value)) return "Need safe food (minimum " + SurvivalPolicy.bounded(HiveMind.settings().botMinimumFood.value, 1, 64) + ")";
        if (hivemind.getMineProcess().isActive() && !hivemind.getRepairProcess().isActive()) {
            if (BotInventory.slot(ctx, item -> BotInventory.pickaxe(item, hivemind.getMineProcess().targetsAncientDebris())) < 0)
                return hivemind.getMineProcess().targetsAncientDebris() ? "Need a diamond/netherite pickaxe with usable durability" : "Need a pickaxe with usable durability";
            if (BotInventory.needsBlocks(ctx)) return "Need building blocks (minimum " + SurvivalPolicy.bounded(HiveMind.settings().botMinimumBlocks.value, 0, 128) + ")";
            boolean full = BotInventory.slot(ctx, ItemStack::isEmpty) < 0;
            if (full && !hivemind.getMineProcess().isCleaningWaste() && (!HiveMind.settings().mineAutoDeposit.value || MiningRouteAccess.deposits(ctx).isEmpty())) return "Inventory full; enable deposits and register storage or free inventory space";
        }
        return null;
    }
    private boolean careNeeded() {
        return ctx.player().getHealth() < SurvivalPolicy.bounded(HiveMind.settings().botMinimumHealth.value, 2, 20)
                || SurvivalPolicy.shouldEat(ctx.player().getFoodData().getFoodLevel(), ctx.player().getHealth(), ctx.player().getMaxHealth(),
                HiveMind.settings().botEatAtHunger.value);
    }
    public boolean beforeMining() {
        if (!HiveMind.settings().botSurvival.value || ctx.player() == null || ctx.player().isCreative()) return false;
        if (isActive()) return true;
        if (hivemind.getMineProcess().isActive() && !careNeeded() && !BotInventory.needsFood(ctx)
                && hivemind.getRepairProcess().tryAutomatic()) { stage=Stage.REPAIR;reason="Repairing Mending pickaxe";return true; }
        String missing = shortage();
        if (missing != null || careNeeded()) {
            reason = missing == null ? "Eating and recovering health" : missing;
            stage = Stage.CARE; ticks = careTicks = 0;
            hivemind.getInputOverrideHandler().clearAllKeys(); ctx.playerController().resetBlockRemoving();
            return true;
        }
        int pick = BotInventory.slot(ctx, item -> BotInventory.pickaxe(item, hivemind.getMineProcess().targetsAncientDebris()));
        if (hivemind.getMineProcess().isActive() && pick >= 9) {
            stage = Stage.CARE; reason = "Moving replacement pickaxe to hotbar"; ticks = careTicks = 0;
            return true;
        }
        return false;
    }
    public void retry() {
        releaseFood(); stage = Stage.IDLE; ticks = swapDelay = careTicks = 0; reason = "Checking supplies again";
    }
    public void supplyFailed(String message) { releaseFood(); waitForHelp("Resupply failed: " + message); }
    private boolean persistentCare(){return hivemind.getStalkProcess().isActive()&&!hivemind.getStalkProcess().hunting()||hivemind.getAutonomy().order()!=null;}
    private void waitForHelp(String message) {
        stage = Stage.WAIT; reason = message;
        boolean watching = persistentCare();
        stalkCareRetryAt = watching ? System.currentTimeMillis() + 30000 : 0;
        hivemind.getJobManager().fail(message);
        logDirect("Survival paused: " + message + (watching ? ". Tracking order remains; checking care again in 30 seconds." : ". Use survival retry after fixing it."));
    }
    @Override public PathingCommand onTick(boolean failed, boolean safe) {
        if (!HiveMind.settings().botSurvival.value || ctx.player() == null || !ctx.player().isAlive()) { onLostControl(); return null; }
        if (stage == Stage.REPAIR && hivemind.getRepairProcess().isActive() && !careNeeded() && !BotInventory.needsFood(ctx)) return new PathingCommand(null,PathingCommandType.DEFER);
        if (stage == Stage.REPAIR) { stage=Stage.CARE;reason=hivemind.getRepairProcess().result(); }
        if (stage == Stage.SUPPLY && hivemind.getDepositProcess().isActive()) return new PathingCommand(null, PathingCommandType.DEFER);
        if (!safe) return pause();
        hivemind.getInputOverrideHandler().clearAllKeys(); ctx.playerController().resetBlockRemoving();
        if (stage == Stage.WAIT) {
            if (!persistentCare()
                    || stalkCareRetryAt == 0 || System.currentTimeMillis() < stalkCareRetryAt) return pause();
            stage = Stage.CARE; ticks = careTicks = 0; stalkCareRetryAt = 0;
        }
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) { waitForHelp("Close the open container before continuing"); return pause(); }
        if (++ticks > 2400) { waitForHelp("Care timed out; check hunger, food use, regeneration, and supplies"); return pause(); }
        if (stage == Stage.SUPPLY) { stage = Stage.CARE; reason = "Checking restocked supplies"; }
        String missing = shortage();
        boolean hungry = SurvivalPolicy.shouldEat(ctx.player().getFoodData().getFoodLevel(), ctx.player().getHealth(), ctx.player().getMaxHealth(), HiveMind.settings().botEatAtHunger.value);
        if (hungry && BotInventory.slot(ctx, BotInventory::food) >= 0) {
            eat(); return pause();
        }
        releaseFood();
        if (missing != null) {
            if (missing.startsWith("Inventory full")) { waitForHelp(missing); return pause(); }
            if (!HiveMind.settings().botAutoResupply.value) { waitForHelp(missing + "; automatic resupply is disabled"); return pause(); }
            if(hivemind.getShulkerProcess().requestSupplies()){reason="Using carried shulker supplies";return new PathingCommand(null,PathingCommandType.DEFER);}
            if (BotInventory.slot(ctx, ItemStack::isEmpty) < 0) {
                if (HiveMind.settings().mineAutoDeposit.value && hivemind.getMineProcess().isActive()) {
                    try { hivemind.getDepositProcess().start(false);
                        if (!hivemind.getDepositProcess().isActive()) { waitForHelp("Inventory is full of retained supplies; free a slot for resupply"); return pause(); }
                        stage = Stage.SUPPLY; return pause(); }
                    catch (RuntimeException e) { waitForHelp(missing + "; no room to restock: " + e.getMessage()); return pause(); }
                }
                waitForHelp(missing + "; free an inventory slot to restock"); return pause();
            }
            try { hivemind.getDepositProcess().startResupply(); stage = Stage.SUPPLY; reason = missing; return pause(); }
            catch (RuntimeException e) { waitForHelp(missing + "; " + e.getMessage()); return pause(); }
        }
        if (ctx.player().getHealth() < SurvivalPolicy.bounded(HiveMind.settings().botMinimumHealth.value, 2, 20)) {
            reason = "Waiting for health regeneration";
            if (++careTicks > 1200) waitForHelp("Health did not recover; check food, status effects, and server regeneration settings");
            return pause();
        }
        if (hivemind.getMineProcess().isActive()) {
            int pick = BotInventory.slot(ctx, item -> BotInventory.pickaxe(item, hivemind.getMineProcess().targetsAncientDebris()));
            if (pick >= 9) { moveToHotbar(pick, 0); return pause(); }
        }
        onLostControl(); return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }
    private void moveToHotbar(int slot, int hotbar) {
        if (swapDelay-- > 0 || ctx.player().getDeltaMovement().multiply(1, 0, 1).lengthSqr() > 0.0001) return;
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, slot, hotbar, ContainerInput.SWAP, ctx.player());
        swapDelay = 10;
    }
    private void eat() {
        reason = "Eating safe food";
        int slot = BotInventory.slot(ctx, BotInventory::food);
        if (slot < 0) return;
        if (slot >= 9) { moveToHotbar(slot, 6); return; }
        if (previousSlot < 0) previousSlot = ctx.player().getInventory().getSelectedSlot();
        ctx.player().getInventory().setSelectedSlot(slot); ctx.playerController().syncHeldItem();
        hivemind.getLookBehavior().updateTarget(new Rotation(ctx.player().getYRot(), -70), true);
        ownsUse = true;
        if (!ctx.player().isUsingItem()) ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
    }
    private void releaseFood() {
        boolean release = ownsUse; ownsUse = false;
        if (ctx.player() != null) {
            if (release && ctx.player().isUsingItem() && ctx.minecraft().gameMode != null) ctx.minecraft().gameMode.releaseUsingItem(ctx.player());
            if (previousSlot >= 0) { ctx.player().getInventory().setSelectedSlot(previousSlot); if (ctx.minecraft().gameMode != null) ctx.playerController().syncHeldItem(); }
        }
        previousSlot = -1;
    }
    private PathingCommand pause() { return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE); }
    @Override public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || ctx.player() == null || ctx.world() == null) { releaseFood(); return; }
        if (ownsUse && !controlled()) releaseFood();
        if (!isActive() && BotWork.active(hivemind) && !hivemind.getDepositProcess().isActive() && !hivemind.getConflictProcess().isActive() && !hivemind.getWorkstationProcess().isActive()
                && !hivemind.getStalkProcess().suppressesCare()
                && (BotWork.foreground(hivemind)||RoamPolicy.needsCare(ctx.player().getHealth(),ctx.player().getFoodData().getFoodLevel()))) beforeMining();
    }
    @Override public void onLostControl() { releaseFood(); stage = Stage.IDLE; reason = "Ready"; ticks = swapDelay = careTicks = 0; stalkCareRetryAt = 0; }
    @Override public void onPlayerDeath() { onLostControl(); }
    @Override public String displayName0() { return "Survival " + status(); }
}
