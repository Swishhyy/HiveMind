# Fabric 26.3 mining improvements

This fork builds Fabric by default (`available_loaders=fabric`). Use Java 25 and install the Fabric jar on the Minecraft client that controls the bot account.

## Deposit chest

1. Look at a chest, trapped chest, or barrel and run `#deposit set`. You can also use `#deposit set x y z` while that container is loaded.
2. Run your usual mining command, for example `#mine diamond_ore deepslate_diamond_ore`.
3. When two or fewer main-inventory slots are empty, mining pauses, returns to that exact container, unloads resources, returns to the mining position, and resumes the same job.

The destination is saved using Baritone's existing waypoint storage, separately for each server/world and dimension. It never chooses a nearby stranger's chest. Double chests are supported. Worn armor and offhand equipment are not transferred.

| Command | Action |
| --- | --- |
| `#deposit set [x y z]` | Save the selected container or exact coordinates |
| `#deposit` or `#deposit now` | Deposit now, keeping supplies |
| `#deposit all` | Stop mining and empty main inventory and hotbar, including tools and food |
| `#deposit status` | Show the destination and current trip |
| `#deposit clear` | Forget the destination |
| `#pause` / `#resume` | Pause/resume the entire trip |
| `#stop` | Cancel the trip and mining |

Ordinary deposits retain hotbar items, damageable equipment, food, equippable items, and the items in `depositKeepItems`. Put a stack of building blocks in the hotbar to reserve it. Configurable settings:

- `mineAutoDeposit` (default `true`): automatic chest trips while mining, once a destination is saved.
- `mineDepositFreeSlots` (default `2`): empty-slot threshold, clamped to 0–35.
- `depositKeepHotbar` / `depositKeepSupplies` (default `true`): retain the hotbar and equipment/food.
- `depositKeepItems`: extra retained items; defaults to torches, water buckets, empty buckets, and shields.
- `depositProtectionRadius` (default `3`): protected cube around the saved chest.

The bot only counts server-confirmed inventory changes as deposits. A full, blocked, missing, inaccessible, or unreachable container stops mining and reports the problem. Items are never intentionally dropped to make room. The trip also stops on a world/dimension change. Fix the problem and restart the mining command. Numeric mining goals retain credit for confirmed deposits across return trips.

## Avoiding player builds

Minecraft clients do not receive historical ownership for ordinary blocks. A stone block in a player's wall looks the same as natural stone. Observing a block update also does not reliably identify who caused it.

`preferAvoidConstruction=true` adds a large cost (default `constructionBreakPenalty=1000` ticks **per block**) for breaking likely construction: planks, stairs, slabs, bricks, glass, concrete, wool, doors, fences, redstone devices, and similar materials. `additionalConstructionBlocks` extends this to other blocks. This is a strong preference: it can still choose breaking when a detour is too costly or unavailable. Generated structures may contain the same materials and receive the same penalty.

Storage containers are protected against breaking by default (`protectContainers=true`). This includes chests, barrels, shulker boxes, furnaces, hoppers, and similar storage. Turning off the construction preference does not turn off container or marked-area protection.

For a base that must never be used as a mining shortcut, stand near its center and run `#protect 32` (choose a radius covering the build). This saves a cube extending 32 blocks in every direction. Pathfinding can walk through existing entrances but cannot plan block breaking or placement inside it. `#protect list` lists protection areas and `#protect clear` removes manual areas in this dimension. The deposit chest's automatic protection remains.

Protected areas also prevent explicitly selecting those blocks as mining targets. Protection markers are persistent user waypoints with reserved names; do not rename them manually. These features do not implement historical player-placement detection, private-message authorization, or combat.

## Validation

Run `bash scripts/check-mining-policies.sh` for dependency-free inventory and construction policy checks (JDK 17 or later). Full Fabric validation requires Java 25 and the Gradle/Minecraft dependencies:

```sh
./gradlew test :fabric:remapJar
```

In-game checks before relying on unattended mining:

- Fill a single chest and a double chest partially; confirm main-inventory and hotbar transfers go into storage rather than back into the inventory.
- Fill or obstruct the destination, remove it while traveling, and deny access with a server protection plugin; confirm mining stops and reports the problem.
- Interrupt a trip with pause, stop, death, disconnect, and a dimension change.
- Mine across more than one trip with a numeric quantity goal; verify previously deposited items still count.
- Compare an open detour with a route through planks/stone bricks; verify a marked natural-stone base cannot be tunneled through.
