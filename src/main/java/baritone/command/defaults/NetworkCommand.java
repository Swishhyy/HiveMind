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

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.*;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.utils.network.NetworkConfig;
import baritone.utils.network.NetworkProtocol;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import java.net.URI;
import java.util.*;
import java.util.stream.Stream;

public final class NetworkCommand extends Command {
    public NetworkCommand(IBaritone bot) { super(bot, "network", "net"); }
    @Override public void execute(String label, IArgConsumer args) throws CommandException {
        var bot = (Baritone) baritone; var network = bot.getNetwork();
        try {
            if (bot.getJobManager().requestOwner() != null) throw new IllegalStateException("Network setup, keys, and local page access are local commands");
            String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
            var options = network.config().options();
            switch (action) {
                case "status" -> { args.requireMax(0); logDirect(network.status()); }
                case "key" -> {
                    String value = args.hasAny() ? args.getString() : options.key().isEmpty() ? "new" : options.key(); args.requireMax(0);
                    if (value.equalsIgnoreCase("new")) value = NetworkProtocol.newKey();
                    network.configure(options.key(value));
                    logDirect(false, Component.literal("Network key: ").withStyle(ChatFormatting.GRAY)
                            .append(copy(value, value, "Click to copy the network key"))
                            .append(" ").append(copy("[Copy key]", value, "Copy the key to your clipboard")));
                }
                case "host" -> {
                    int port = args.hasAny() ? args.getAs(Integer.class) : NetworkConfig.DEFAULT_PORT; args.requireMax(0);
                    network.configure(options.mode(NetworkConfig.Mode.HOST, options.host(), port)); logDirect(network.status());
                }
                case "join", "connect" -> {
                    String host = args.getString();int port=NetworkConfig.DEFAULT_PORT;String key=null;
                    if(args.hasAny()){
                        String next=args.getString();
                        if(next.matches("[0-9]{1,5}")){port=Integer.parseInt(next);if(args.hasAny())key=args.getString();}
                        else key=next;
                    }
                    args.requireMax(0);if(key!=null)options=options.key(key);
                    network.configure(options.mode(NetworkConfig.Mode.JOIN, host, port)); logDirect(network.status());
                }
                case "off", "disconnect" -> { args.requireMax(0); network.configure(options.mode(NetworkConfig.Mode.OFF, options.host(), options.port())); logDirect("Client network disabled; saved atlas retained"); }
                case "web", "map" -> {
                    String port = args.hasAny() ? args.getString() : options.webPort() == 0 ? "28767" : "" + options.webPort(); args.requireMax(0);
                    network.configure(options.web(port.equalsIgnoreCase("off") ? 0 : Integer.parseInt(port)));
                    if (port.equalsIgnoreCase("off")) logDirect("Local atlas page stopped");
                    else {
                        String url = network.pageUrl(); URI uri = URI.create(url);
                        MutableComponent link = Component.literal(uri.resolve("/").toString()).withStyle(style -> style
                                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                                .withClickEvent(new ClickEvent.OpenUrl(uri))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the local atlas in your browser"))));
                        logDirect(false, Component.literal("Local atlas: ").withStyle(ChatFormatting.GRAY).append(link)
                                .append(" ").append(copy("[Copy link]", url, "Copy the complete atlas address, including its access token")));
                    }
                }
                case "peers" -> {
                    args.requireMax(0); var peers = network.peers(); logDirect(peers.isEmpty() ? "No directly connected peers" : "Authenticated direct peers:");
                    for (var peer : peers) logDirect(peer.name() + " (" + peer.id() + ")");
                }
                case "sightings", "marks" -> {
                    String name = args.hasAny() ? args.getString() : null; args.requireMax(0); var marks = network.sightings(name);
                    logDirect(marks.isEmpty() ? "No recent peer sightings" : "Recent shared sightings:");
                    for (var mark : marks) logDirect(mark.playerName() + " at " + mark.point().x() + " " + mark.point().y() + " " + mark.point().z() + " (" + mark.point().dimension() + "), seen by " + mark.reporterName() + " " + Math.max(0, (System.currentTimeMillis() - mark.observedAt()) / 1000) + "s ago");
                }
                case "bind" -> { String bind = args.getString(); args.requireMax(0); network.configure(options.bind(bind)); logDirect("Network host bind address updated"); }
                case "scope" -> { String scope = args.getString(); args.requireMax(0); network.configure(options.scope(scope.equalsIgnoreCase("auto") ? "" : scope)); logDirect("Minecraft server scope updated"); }
                case "reload" -> { args.requireMax(0); network.reload(); logDirect(network.status()); }
                case "clear" -> { args.requireMax(0); network.clearHistory(); logDirect("Cleared this client's atlas and peer marks; connected peers retain their own histories"); }
                default -> throw new IllegalArgumentException("Use network key/host/join/status/peers/sightings/web/off; help network lists all options");
            }
        } catch (IllegalArgumentException | IllegalStateException e) { throw new CommandInvalidStateException(e.getMessage()); }
    }
    private MutableComponent copy(String label, String value, String hint) {
        return Component.literal(label).withStyle(style -> style.withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(value))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hint))));
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.hasAtMostOne() ? new TabCompleteHelper().append("status", "key", "host", "join", "connect", "off", "peers", "sightings", "web", "bind", "scope", "reload", "clear").filterPrefix(args.getString()).stream() : Stream.empty();
    }
    @Override public String getShortDesc() { return "Connect bot clients, share visual sightings, and open a local exploration atlas"; }
    @Override public List<String> getLongDesc() {
        return List.of("network key [key|new] - Generate/show a key locally; click the key or [Copy key] to copy it.",
                "network host [port] - Run a relay in this client (default TCP 28766).",
                "Every client generates a persistent key on first launch; network host works immediately.",
                "network join <host> [port] [host-key] - Join in one command; reconnects automatically.",
                "network status/peers/sightings [player] - Inspect connections and recent marks.",
                "network web [port|off] - Start the localhost atlas (default 28767); click its address to open it or [Copy link] to copy it.",
                "network off - Disconnect and disable automatic networking for this Minecraft server; keeps the atlas.",
                "network bind <address>; network scope <server-label|auto>; network reload; network clear.",
                "Bots must use the same Minecraft server address or explicit scope. Keys, endpoints, and page settings are saved per server.",
                "Only real visual sightings are shared. Peers' recent marks help stalk searches but never count as firsthand base evidence.",
                "The page saves visited chunks and a bounded 30-day sighting history, including reporting bots and frequent areas.",
                "Connected bots recognize teammates and coordinate mining/hunts. Idle scouts roam automatically; roam on/off/status controls it.",
                "The page renders sampled terrain and base markers, issues quick commands, changes trust, and applies settings to one bot or all bots.");
    }
}
