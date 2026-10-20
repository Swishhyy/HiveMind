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
import baritone.api.utils.BlockOptionalMeta;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.bot.BotWork;
import baritone.utils.bot.JobStore;
import baritone.utils.mining.MiningRouteAccess;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.Path;
import java.util.*;

/** A persistent queue supervises real mining processes; interruptions retain the same mining request. */
public final class JobManager extends BaritoneProcessHelper implements IBehavior {
    private Path root;
    private JobStore store;
    private final List<JobStore.Job> queue = new ArrayList<>();
    private JobStore.Job current;
    private boolean paused = true;
    private boolean completedMining;
    private boolean launching;
    private long nextId = 1;
    private String reason = "No jobs";
    private UUID requestOwner;
    private long nextError;

    public JobManager(Baritone bot) { super(bot); }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return -0.01; }
    @Override public boolean isActive() { return paused && current != null && BotWork.active(baritone); }
    @Override public PathingCommand onTick(boolean failed, boolean safe) {
        if (safe) { baritone.getInputOverrideHandler().clearAllKeys(); ctx.playerController().resetBlockRemoving(); }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
    public void ensureLoaded() {
        Path here = MiningRouteAccess.root(ctx);
        if (here.equals(root)) return;
        JobStore next = new JobStore(here);
        List<JobStore.Job> saved = next.read(); // A bad file cannot erase the previous queue.
        root = here; store = next; queue.clear(); queue.addAll(saved); current = null;
        nextId = Math.max(System.currentTimeMillis(), queue.stream().mapToLong(JobStore.Job::id).max().orElse(0) + 1);
        paused = !queue.isEmpty(); completedMining = false;
        reason = queue.isEmpty() ? "No jobs" : "Saved jobs are paused; use job resume";
    }
    public void withOwner(UUID owner, Runnable task) {
        UUID old = requestOwner; requestOwner = owner;
        try { task.run(); } finally { requestOwner = old; }
    }
    public long submit(int quantity, List<String> requested) {
        ensureLoaded();
        if (queue.size() >= 64) throw new IllegalStateException("Job queue is full (64)");
        List<String> blocks = requested.stream().map(name -> name.equalsIgnoreCase("netherite")
                || name.equalsIgnoreCase("minecraft:netherite") ? "minecraft:ancient_debris" : name).map(name -> {
            BlockOptionalMeta block = new BlockOptionalMeta(name);
            if (block.getBlock() == Blocks.AIR) throw new IllegalArgumentException("Air is not a mining target");
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
        }).toList();
        JobStore.Job job = new JobStore.Job(nextId++, quantity, blocks, 0, requestOwner);
        boolean empty = queue.isEmpty(); queue.add(job);
        if (empty) { paused = false; reason = "Waiting for current bot work to finish"; }
        save(); return job.id();
    }
    public void pauseJobs() {
        ensureLoaded(); paused = true; reason = "Paused by controller"; save();
        baritone.getPathingBehavior().softCancelIfSafe();
    }
    public void resumeJobs() {
        ensureLoaded(); if (queue.isEmpty()) throw new IllegalStateException("No queued jobs");
        baritone.getSurvivalProcess().retry(); paused = false; reason = "Ready to resume"; save();
    }
    public void cancelJobs() {
        ensureLoaded(); stopOwnedWork(); queue.clear(); current = null; paused = true; completedMining = false;
        reason = "Jobs canceled"; save();
    }
    public void remove(long id) {
        ensureLoaded();
        if (queue.stream().noneMatch(j -> j.id() == id)) throw new IllegalArgumentException("Unknown job #" + id);
        if (current != null && current.id() == id) { stopOwnedWork(); current = null; completedMining = false; }
        queue.removeIf(j -> j.id() == id); save();
    }
    private void stopOwnedWork() {
        if (current == null) return;
        launching = true;
        try { baritone.getPathingBehavior().cancelEverything(); } finally { launching = false; }
    }
    public List<JobStore.Job> jobs() { ensureLoaded(); return List.copyOf(queue); }
    public String status() {
        ensureLoaded();
        String task = baritone.getPathingControlManager().mostRecentInControl().map(p -> p.displayName()).orElse("Idle");
        String result = (queue.isEmpty() ? "Idle" : (paused ? "Paused" : "Running")) + "; " + queue.size() + " job(s); " + reason + "; " + task;
        if (current != null) result += "; job #" + current.id() + " " + (completedMining ? current.quantity() : baritone.getMineProcess().jobProgress()) + "/" + current.quantity();
        if (ctx.player() != null) result += "; health " + Math.round(ctx.player().getHealth()) + "/" + Math.round(ctx.player().getMaxHealth())
                + "; hunger " + ctx.player().getFoodData().getFoodLevel() + "/20";
        return result;
    }
    public void miningFinished() {
        if (current == null) return;
        current = current.withDeposited(Math.max(current.deposited(), current.quantity())); queue.set(0, current);
        completedMining = true; reason = "Mining target reached; finishing storage trip"; save();
    }
    public void recordDeposit(int quantity) {
        if (current == null) return;
        current = current.withDeposited(quantity); queue.set(0, current); save();
    }
    public void fail(String message) {
        if (current == null) return;
        boolean changed = !paused || !reason.equals(message);
        paused = true; reason = message; save();
        if (changed) notifyOwner(current.owner(), "Job #" + current.id() + " paused: " + message);
    }
    private void notifyOwner(UUID owner, String message) {
        logDirect(message);
        if (owner != null && baritone.getBotControl() != null) baritone.getBotControl().reply(owner, message);
    }
    private void save() { if (store != null) store.write(queue); }
    @Override public void onLostControl() {
        if (launching || current == null) return;
        paused = true; reason = "Stopped or disconnected; use job resume"; current = null; completedMining = false; save();
    }
    @Override public void onPlayerDeath() { fail("Player died; inspect equipment before resuming"); onLostControl(); }
    @Override public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || ctx.world() == null || ctx.player() == null) return;
        try {
            ensureLoaded();
            if (paused || queue.isEmpty()) return;
            if (current != null) {
                if (baritone.getMineProcess().isActive() || baritone.getDepositProcess().isActive()
                        || baritone.getPortalTravelProcess().isActive() || baritone.getSurvivalProcess().isActive()) return;
                if (!completedMining) { fail("Mining stopped before the requested quantity was reached"); return; }
                notifyOwner(current.owner(), "Finished job #" + current.id() + " (" + current.quantity() + " " + String.join(",", current.blocks()) + ")");
                queue.remove(0); current = null; completedMining = false; reason = "Ready for next job"; save();
            }
            if (queue.isEmpty() || BotWork.active(baritone) || baritone.getConflictProcess().isActive() || baritone.getSculkProcess().isActive()
                    || baritone.getPathingControlManager().mostRecentInControl().map(p -> p.priority() >= 0).orElse(false)) return;
            launching = true;
            try {
                baritone.getPathingBehavior().cancelEverything();
                current = queue.get(0);
                baritone.getMineProcess().mineByName(current.quantity(), current.blocks().toArray(String[]::new));
                baritone.getMineProcess().restoreDepositCredit(current.deposited());
                reason = "Mining " + String.join(",", current.blocks());
                notifyOwner(current.owner(), "Started job #" + current.id() + ": " + current.quantity() + " " + String.join(",", current.blocks()));
            } finally { launching = false; }
        } catch (RuntimeException e) {
            paused = true; reason = "Job error: " + e.getMessage();
            if (System.currentTimeMillis() > nextError) { logDirect(reason); nextError = System.currentTimeMillis() + 30000; }
        }
    }
    @Override public String displayName0() { return "Job paused: " + reason; }
}
