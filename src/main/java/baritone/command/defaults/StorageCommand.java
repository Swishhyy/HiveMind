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
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import java.util.*;
import java.util.stream.Stream;
import baritone.utils.bot.StorageRule;
import baritone.utils.mining.MiningRouteAccess;
import baritone.utils.mining.WorldRoutes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public final class StorageCommand extends Command {
    public StorageCommand(IBaritone bot) { super(bot, "storage"); }
    @Override public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "list";
        try {
            var saved = MiningRouteAccess.deposits(ctx);
            if (action.equals("list")) {
                args.requireMax(0);
                for (var entry : saved) logDirect("#" + entry.id() + " " + entry.rule().label() + " [" + entry.rule().kind().name().toLowerCase(Locale.ROOT)
                        + ", priority " + entry.rule().priority() + "] " + entry.endpoint() + (entry.rule().items().isEmpty() ? "" : " " + entry.rule().items()));
                if (saved.isEmpty()) logDirect("No storage registered; look at a chest and use deposit add"); return;
            }
            if (((Baritone) baritone).getDepositProcess().isActive()) throw new IllegalStateException("Finish or stop the storage trip before changing its rules");
            args.requireMin(2); int id = args.getAs(Integer.class);
            WorldRoutes.Storage entry = saved.stream().filter(storage -> storage.id() == id).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown storage #" + id));
            StorageRule old = entry.rule(), rule;
            switch (action) {
                case "label" -> {
                    List<String> words = new ArrayList<>(); while (args.hasAny()) words.add(args.getString());
                    rule = new StorageRule(String.join(" ", words), old.kind(), old.items(), old.priority());
                }
                case "priority" -> { int priority = args.getAs(Integer.class); args.requireMax(0); rule = new StorageRule(old.label(), old.kind(), old.items(), priority); }
                case "rule" -> {
                    StorageRule.Kind kind = StorageRule.parse(args.getString()); Set<String> items = new HashSet<>();
                    if (kind == StorageRule.Kind.ITEMS) {
                        args.requireMin(1);
                        while (args.hasAny()) {
                            String item = args.getString(); Identifier key = Identifier.parse(item.contains(":") ? item : "minecraft:" + item);
                            if (!BuiltInRegistries.ITEM.containsKey(key)) throw new IllegalArgumentException("Unknown item " + key);
                            items.add(key.toString());
                        }
                    } else args.requireMax(0);
                    rule = new StorageRule(old.label(), kind, items, old.priority());
                }
                default -> throw new IllegalArgumentException("Use storage list, label <id> <name>, priority <id> <-100..100>, or rule <id> <any|ores|building|junk|supplies|items|disabled> [items...]");
            }
            MiningRouteAccess.store(ctx).configureStorage(id, rule); logDirect("Storage #" + id + " updated: " + rule);
        } catch (IllegalArgumentException | IllegalStateException e) { throw new CommandInvalidStateException(e.getMessage()); }
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.hasAtMostOne() ? new TabCompleteHelper().append("list", "label", "rule", "priority").filterPrefix(args.getString()).stream() : Stream.empty();
    }
    @Override public String getShortDesc() { return "Label chests and assign item rules or resupply roles"; }
    @Override public List<String> getLongDesc() { return List.of("Register/remove containers using deposit add/remove/list.",
            "storage label 1 Ores; storage rule 1 ores; storage priority 1 10.",
            "storage rule 2 supplies - Food, replacement tools and building supplies; resupply visits this role first.",
            "storage rule 3 items diamond ancient_debris - Explicit item allowlist.",
            "Specific matching rules precede any fallback chests, then priority, same dimension, and distance.",
            "Rules apply to deposit all too. Unsupported/malformed rules are disabled rather than accepting arbitrary items."); }
}
