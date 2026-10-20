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
import baritone.api.behavior.IBehavior;
import baritone.api.event.events.TickEvent;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.bot.BotInventory;
import baritone.utils.bot.BotWork;
import baritone.utils.bot.SurvivalPolicy;
import baritone.utils.mining.MiningRouteAccess;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Temporary care and resupply preserve the original mining filter and deposit credit. */
public final class SurvivalProcess extends BaritoneProcessHelper implements IBehavior {
    private enum Stage { IDLE, CARE, SUPPLY, REPAIR, WAIT }
    private Stage stage = Stage.IDLE;
    private String reason = "Ready";
    private int ticks;
    private int swapDelay;
    private int previousSlot = -1;
    private boolean ownsUse;
    private int careTicks;

    public SurvivalProcess(Baritone bot) { super(bot); }
    @Override public boolean isActive() { return stage != Stage.IDLE; }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return -0.2; }
    public String status() { return stage + ": " + reason; }
    public boolean holdsUse() { return ownsUse && controlled(); }
    private boolean controlled() { return baritone.getPathingControlManager().mostRecentInControl().orElse(null) == this; }

    private String shortage() {
        if (BotInventory.needsFood(ctx) && (!baritone.getCompanionProcess().isRecovering() || ctx.player().getFoodData().getFoodLevel()<=6 || ctx.player().getHealth()<Baritone.settings().botMinimumHealth.value)) return "Need safe food (minimum " + SurvivalPolicy.bounded(Baritone.settings().botMinimumFood.value, 1, 64) + ")";
        if (baritone.getMineProcess().isActive() && !baritone.getRepairProcess().isActive()) {
            if (BotInventory.slot(ctx, item -> BotInventory.pickaxe(item, baritone.getMineProcess().targetsAncientDebris())) < 0)
                return baritone.getMineProcess().targetsAncientDebris() ? "Need a diamond/netherite pickaxe with usable durability" : "Need a pickaxe with usable durability";
            if (BotInventory.needsBlocks(ctx)) return "Need building blocks (minimum " + SurvivalPolicy.bounded(Baritone.settings().botMinimumBlocks.value, 0, 128) + ")";
            boolean full = BotInventory.slot(ctx, ItemStack::isEmpty) < 0;
            if (full && !baritone.getMineProcess().isCleaningWaste() && (!Baritone.settings().mineAutoDeposit.value || MiningRouteAccess.deposits(ctx).isEmpty())) return "Inventory full; enable deposits and register storage or free inventory space";
        }
        return null;
    }
    private boolean careNeeded() {
        return ctx.player().getHealth() < SurvivalPolicy.bounded(Baritone.settings().botMinimumHealth.value, 2, 20)
                || SurvivalPolicy.shouldEat(ctx.player().getFoodData().getFoodLevel(), ctx.player().getHealth(), ctx.player().getMaxHealth(),
                Baritone.settings().botEatAtHunger.value);
    }
    public boolean beforeMining() {
        if (!Baritone.settings().botSurvival.value || ctx.player() == null || ctx.player().isCreative()) return false;
        if (isActive()) return true;
        if (baritone.getMineProcess().isActive() && !careNeeded() && !BotInventory.needsFood(ctx)
                && baritone.getRepairProcess().tryAutomatic()) { stage=Stage.REPAIR;reason="Repairing Mending pickaxe";return true; }
        String missing = shortage();
        if (missing != null || careNeeded()) {
            reason = missing == null ? "Eating and recovering health" : missing;
            stage = Stage.CARE; ticks = careTicks = 0;
            baritone.getInputOverrideHandler().clearAllKeys(); ctx.playerController().resetBlockRemoving();
            return true;
        }
        int pick = BotInventory.slot(ctx, item -> BotInventory.pickaxe(item, baritone.getMineProcess().targetsAncientDebris()));
        if (baritone.getMineProcess().isActive() && pick >= 9) {
            stage = Stage.CARE; reason = "Moving replacement pickaxe to hotbar"; ticks = careTicks = 0;
            return true;
        }
        return false;
    }
    public void retry() {
        releaseFood(); stage = Stage.IDLE; ticks = swapDelay = careTicks = 0; reason = "Checking supplies again";
    }
    public void supplyFailed(String message) { releaseFood(); waitForHelp("Resupply failed: " + message); }
    private void waitForHelp(String message) {
        stage = Stage.WAIT; reason = message;
        baritone.getJobManager().fail(message); logDirect("Survival paused: " + message + ". Use survival retry after fixing it.");
    }
    @Override public PathingCommand onTick(boolean failed, boolean safe) {
        if (!Baritone.settings().botSurvival.value || ctx.player() == null || !ctx.player().isAlive()) { onLostControl(); return null; }
        if (stage == Stage.REPAIR && baritone.getRepairProcess().isActive() && !careNeeded() && !BotInventory.needsFood(ctx)) return new PathingCommand(null,PathingCommandType.DEFER);
        if (stage == Stage.REPAIR) { stage=Stage.CARE;reason=baritone.getRepairProcess().result(); }
        if (stage == Stage.SUPPLY && baritone.getDepositProcess().isActive()) return new PathingCommand(null, PathingCommandType.DEFER);
        if (!safe) return pause();
        baritone.getInputOverrideHandler().clearAllKeys(); ctx.playerController().resetBlockRemoving();
        if (stage == Stage.WAIT) return pause();
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) { waitForHelp("Close the open container before continuing"); return pause(); }
        if (++ticks > 2400) { waitForHelp("Care timed out; check hunger, food use, regeneration, and supplies"); return pause(); }
        if (stage == Stage.SUPPLY) { stage = Stage.CARE; reason = "Checking restocked supplies"; }
        String missing = shortage();
        boolean hungry = SurvivalPolicy.shouldEat(ctx.player().getFoodData().getFoodLevel(), ctx.player().getHealth(), ctx.player().getMaxHealth(), Baritone.settings().botEatAtHunger.value);
        if (hungry && BotInventory.slot(ctx, BotInventory::food) >= 0) {
            eat(); return pause();
        }
        releaseFood();
        if (missing != null) {
            if (missing.startsWith("Inventory full")) { waitForHelp(missing); return pause(); }
            if (!Baritone.settings().botAutoResupply.value) { waitForHelp(missing + "; automatic resupply is disabled"); return pause(); }
            if (BotInventory.slot(ctx, ItemStack::isEmpty) < 0) {
                if (Baritone.settings().mineAutoDeposit.value && baritone.getMineProcess().isActive()) {
                    try { baritone.getDepositProcess().start(false);
                        if (!baritone.getDepositProcess().isActive()) { waitForHelp("Inventory is full of retained supplies; free a slot for resupply"); return pause(); }
                        stage = Stage.SUPPLY; return pause(); }
                    catch (RuntimeException e) { waitForHelp(missing + "; no room to restock: " + e.getMessage()); return pause(); }
                }
                waitForHelp(missing + "; free an inventory slot to restock"); return pause();
            }
            try { baritone.getDepositProcess().startResupply(); stage = Stage.SUPPLY; reason = missing; return pause(); }
            catch (RuntimeException e) { waitForHelp(missing + "; " + e.getMessage()); return pause(); }
        }
        if (ctx.player().getHealth() < SurvivalPolicy.bounded(Baritone.settings().botMinimumHealth.value, 2, 20)) {
            reason = "Waiting for health regeneration";
            if (++careTicks > 1200) waitForHelp("Health did not recover; check food, status effects, and server regeneration settings");
            return pause();
        }
        if (baritone.getMineProcess().isActive()) {
            int pick = BotInventory.slot(ctx, item -> BotInventory.pickaxe(item, baritone.getMineProcess().targetsAncientDebris()));
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
        baritone.getLookBehavior().updateTarget(new Rotation(ctx.player().getYRot(), -70), true);
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
        if (!isActive() && BotWork.active(baritone) && !baritone.getDepositProcess().isActive() && !baritone.getConflictProcess().isActive() && !baritone.getWorkstationProcess().isActive()) beforeMining();
    }
    @Override public void onLostControl() { releaseFood(); stage = Stage.IDLE; reason = "Ready"; ticks = swapDelay = careTicks = 0; }
    @Override public void onPlayerDeath() { onLostControl(); }
    @Override public String displayName0() { return "Survival " + status(); }
}
