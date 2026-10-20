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
import baritone.utils.mining.MiningRouteAccess;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import java.nio.file.Path;
import java.util.*;

/** Remote commands never reach the general command interpreter without sender, channel, and command checks. */
public final class BotControlBehavior extends Behavior {
    private record Reply(UUID player, String message) {}
    private Path root;
    private TrustedControl config;
    private final Map<UUID, Long> lastCommand = new LinkedHashMap<>();
    private final Set<String> received = new LinkedHashSet<>();
    private final Deque<Reply> replies = new ArrayDeque<>();
    private long nextReply;
    private long nextError;
    public BotControlBehavior(Baritone bot) { super(bot); }
    public TrustedControl config() {
        Path here = MiningRouteAccess.root(ctx);
        if (!here.equals(root)) { TrustedControl next = new TrustedControl(here); next.players(); config = next; root = here; lastCommand.clear(); received.clear(); replies.clear(); }
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
            if (label.equals("status")) { args.requireMax(0); reply(player, baritone.getJobManager().status()); return; }
            if (label.equals("deposit")) {
                String action = expanded.second().isEmpty() ? "now" : expanded.second().get(0).getValue().toLowerCase(Locale.ROOT);
                if (!Set.of("now", "all", "status", "list").contains(action)) throw new IllegalArgumentException("Storage edits are local; use deposit now/all/status/list remotely");
            }
            if (label.equals("mine")) {
                args.requireMin(2); int quantity = args.getAs(Integer.class); List<String> blocks = new ArrayList<>();
                while (args.hasAny()) blocks.add(args.getString());
                baritone.getJobManager().withOwner(player, () -> baritone.getJobManager().submit(quantity, blocks));
            } else {
                var target = baritone.getCommandManager().getCommand(label);
                if (target == null) throw new IllegalArgumentException("Unknown remote command");
                baritone.getJobManager().withOwner(player, () -> {
                    try { target.execute(label, args); } catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
                });
            }
            reply(player, "Accepted #" + command + "; " + baritone.getJobManager().status());
        } catch (Exception e) { reply(player, "Command failed: " + e.getMessage()); }
    }
    public void reply(UUID player, String message) {
        if (replies.size() >= 16) replies.removeFirst();
        String clean = message == null ? "Unknown error" : message.replaceAll("[\\p{Cntrl}]", " ");
        replies.addLast(new Reply(player, clean.substring(0, Math.min(190, clean.length()))));
    }
    @Override public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || ctx.world() == null || ctx.player() == null) {
            if (!baritone.getPortalTravelProcess().canPreserveTransition(null)) { root = null; config = null; replies.clear(); lastCommand.clear(); received.clear(); }
            return;
        }
        if (replies.isEmpty() || System.currentTimeMillis() < nextReply || ctx.minecraft().getConnection() == null) return;
        Reply reply = replies.removeFirst();
        try {
            if (!config().trusted(reply.player())) return;
            var info = ctx.minecraft().getConnection().getPlayerInfo(reply.player());
            if (info == null || !info.getProfile().name().matches("[A-Za-z0-9_]{1,16}")) return;
            ctx.minecraft().getConnection().sendCommand("msg " + info.getProfile().name() + " [Bot] " + reply.message());
            nextReply = System.currentTimeMillis() + 1000;
        } catch (RuntimeException e) { reportError(e); }
    }
    public void reportError(RuntimeException e) {
        if (System.currentTimeMillis() > nextError) { Helper.HELPER.logDirect("Whisper control disabled for this message: " + e.getMessage()); nextError = System.currentTimeMillis() + 30000; }
    }
}
