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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.utils.Helper;
import baritone.api.event.events.TickEvent;
import baritone.command.argument.ArgConsumer;
import baritone.command.manager.CommandManager;
import baritone.utils.bot.TrustedControl;
import baritone.utils.bot.AutonomyPolicy;
import baritone.utils.mining.MiningRouteAccess;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import java.nio.file.Path;
import java.util.*;

/** Remote commands never reach the general command interpreter without sender, channel, and command checks. */
public final class BotControlBehavior extends Behavior {
    private record Reply(UUID player, String message,long queued) {}
    private Path root;
    private TrustedControl config;
    private final Map<UUID, Long> lastCommand = new LinkedHashMap<>();
    private final Set<String> received = new LinkedHashSet<>();
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final Deque<Reply> reports = new ArrayDeque<>();
    private long nextReply;
    private long nextError;
    public BotControlBehavior(Baritone bot) { super(bot); }
    public TrustedControl config() {
        Path here = MiningRouteAccess.root(ctx);
        if (!here.equals(root)) { TrustedControl next = new TrustedControl(here); next.players(); config = next; root = here; lastCommand.clear(); received.clear(); replies.clear(); reports.clear(); }
        return config;
    }
    public boolean isTrusted(UUID id) {
        try { return config().trusted(id); } catch (RuntimeException e) { return false; }
    }
    private String type(ChatType.Bound bound) {
        return bound.chatType().unwrapKey().map(key -> key.identifier().toString()).orElse("");
    }
    public void onSignedWhisper(PlayerChatMessage message, GameProfile sender, ChatType.Bound bound) {
        if (message.signature() == null || sender == null || !config().whisperType(type(bound))) return;
        String key = sender.id() + ":" + message.signature();
        if (!received.add(key)) return;
        while (received.size() > 64) received.remove(received.iterator().next());
        receive(sender.id(), message.signedContent()); // Unsigned display overrides cannot change executable text.
    }
    public void onServerMessage(String text) {
        var whisper = config().parseServerWhisper(text);
        if (whisper == null || ctx.minecraft().getConnection() == null) return;
        ctx.minecraft().getConnection().getOnlinePlayers().stream()
                .filter(info -> info.getProfile().name().equalsIgnoreCase(whisper.getKey())).findFirst()
                .ifPresent(info -> receive(info.getProfile().id(), whisper.getValue()));
    }
    private void receive(UUID player, String text) {
        if (ctx.player() == null || player.equals(ctx.player().getUUID()) || !config().trusted(player)) return;
        String command = TrustedControl.command(text);
        if (command == null) return;
        long now = System.currentTimeMillis();
        if (now - lastCommand.getOrDefault(player, 0L) < 1000) return;
        lastCommand.put(player, now);
        while (lastCommand.size() > 64) lastCommand.remove(lastCommand.keySet().iterator().next());
        try {
            var expanded = CommandManager.expand(command);
            String label = expanded.first().toLowerCase(Locale.ROOT);
            var args = new ArgConsumer(baritone.getCommandManager(), expanded.second());
            if (label.equals("status")) { args.requireMax(0); reply(player, baritone.getJobManager().status()); reply(player, baritone.getCompanionProcess().status()); reply(player, baritone.getStalkProcess().status()); return; }
            if (label.equals("job") && args.hasAny() && args.peekString().equalsIgnoreCase("list")) {
                args.getString(); args.requireMax(0);
                var queued = baritone.getJobManager().jobs(); reply(player, baritone.getJobManager().status());
                for (var job : queued.stream().limit(12).toList()) reply(player, "Job #" + job.id() + ": mine " + job.quantity() + " " + String.join(" ", job.blocks()));
                if (queued.size() > 12) reply(player, "Showing the first 12 jobs of " + queued.size());
                return;
            }
            if (label.equals("deposit")) {
                String action = expanded.second().isEmpty() ? "now" : expanded.second().get(0).getValue().toLowerCase(Locale.ROOT);
                if (!Set.of("now", "all", "status", "list").contains(action)) throw new IllegalArgumentException("Storage edits are local; use deposit now/all/status/list remotely");
                if (action.equals("status") || action.equals("list")) {
                    args.getString(); args.requireMax(0);
                    var saved = MiningRouteAccess.deposits(ctx);
                    reply(player, saved.size() + " storage chest(s); " + (baritone.getDepositProcess().isActive() ? baritone.getDepositProcess().displayName() : "No active storage trip"));
                    for (var chest : saved.stream().limit(12).toList()) reply(player, "Storage #" + chest.id() + " " + chest.rule().label() + " [" + chest.rule().kind() + "] " + chest.endpoint());
                    if (saved.size() > 12) reply(player, "Showing the first 12 storage entries of " + saved.size());
                    return;
                }
            }
            if (label.equals("mine")) {
                args.requireMin(2); int quantity = args.getAs(Integer.class); List<String> blocks = new ArrayList<>();
                while (args.hasAny()) blocks.add(args.getString());
                long[] id = new long[1];
                baritone.getJobManager().withOwner(player, () -> id[0] = baritone.getJobManager().submit(quantity, blocks));
                baritone.getAutonomy().afterCommand(label,expanded.second().get(0).getValue().toLowerCase(Locale.ROOT));
                reply(player, "Queued mining job #" + id[0] + ": " + quantity + " " + String.join(" ", blocks));
                return;
            } else {
                var target = baritone.getCommandManager().getCommand(label);
                if (target == null) throw new IllegalArgumentException("Unknown remote command");
                baritone.getJobManager().withOwner(player, () -> {
                    try {String first=expanded.second().isEmpty()?"":expanded.second().get(0).getValue().toLowerCase(Locale.ROOT);baritone.getAutonomy().beforeCommand(label,first);target.execute(label, args);baritone.getAutonomy().afterCommand(label,first);} catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
                });
            }
            reply(player, "Accepted #" + command + "; " + baritone.getJobManager().status());
        } catch (Exception e) { reply(player, "Command failed: " + e.getMessage()); }
    }
    public void reply(UUID player, String message) {
        if (replies.size() >= 16) replies.removeFirst();
        String clean = message == null ? "Unknown error" : message.replaceAll("[\\p{Cntrl}]", " ");
        replies.addLast(new Reply(player, clean.substring(0, Math.min(190, clean.length())),System.currentTimeMillis()));
    }
    public void report(UUID player,String message,long now){
        config();if(!config.trusted(player))return;reports.removeIf(p->p.player().equals(player));
        if(reports.size()>=128)reports.removeFirst();String clean=message.replaceAll("[\\p{Cntrl}]"," ");reports.addLast(new Reply(player,clean.substring(0,Math.min(190,clean.length())),now));
    }
    @Override public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || ctx.world() == null || ctx.player() == null) {
            if (!baritone.getPortalTravelProcess().canPreserveTransition(null)) { root = null; config = null; replies.clear(); reports.clear(); lastCommand.clear(); received.clear(); }
            return;
        }
        if ((replies.isEmpty()&&reports.isEmpty()) || System.currentTimeMillis() < nextReply || ctx.minecraft().getConnection() == null) return;
        try {
            config();
            if (replies.isEmpty()&&reports.isEmpty()) return;
            Reply reply = replies.isEmpty()?reports.removeFirst():replies.removeFirst();
            if (!config.trusted(reply.player())) return;
            var info = ctx.minecraft().getConnection().getPlayerInfo(reply.player());
            if (info == null || !info.getProfile().name().matches("[A-Za-z0-9_]{1,16}")) return;
            if(!AutonomyPolicy.mayReport(config.trusted(reply.player()),true,System.currentTimeMillis(),reply.queued()))return;
            ctx.minecraft().getConnection().sendCommand("msg " + info.getProfile().name() + " [Bot] " + reply.message());
            nextReply = System.currentTimeMillis() + 1000;
        } catch (RuntimeException e) { reportError(e); }
    }
    public void reportError(RuntimeException e) {
        if (System.currentTimeMillis() > nextError) { Helper.HELPER.logDirect("Whisper control disabled for this message: " + e.getMessage()); nextError = System.currentTimeMillis() + 30000; }
    }
}
