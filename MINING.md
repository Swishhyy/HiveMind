# Fabric 26.3 mining improvements

This fork builds Fabric by default (`available_loaders=fabric`). Use Java 25 and install the Fabric jar on the Minecraft client that controls the bot account.

## Deposit chest

1. Look at a chest, trapped chest, copper chest, or barrel and run `#deposit set`. All copper chest oxidation stages are supported, both waxed and unwaxed, as single or double chests. You can also use `#deposit set x y z` while that container is loaded. A saved copper chest remains valid if it oxidizes or its wax is changed.
2. Run your usual mining command, for example `#mine diamond_ore deepslate_diamond_ore`.
3. When two or fewer main-inventory slots are empty, mining pauses, returns to that exact container, unloads resources, returns to the mining position, and resumes the same job.

The destination is saved with its dimension and shared across dimensions of the same server/save. Protected chest markers also use Baritone's dimension-scoped waypoint storage. It never chooses a nearby stranger's chest. Double chests are supported. Worn armor and offhand equipment are not transferred.

| Command | Action |
| --- | --- |
| `#deposit set [x y z]` | Save the selected container or exact coordinates |
| `#deposit` or `#deposit now` | Deposit now, keeping supplies |
| `#deposit all` | Stop mining and empty main inventory and hotbar, including tools and food |
| `#deposit status` | Show the destination and current trip |
| `#deposit clear` | Forget the destination |
| `#pause` / `#resume` | Pause/resume the entire trip |
| `#stop` | Cancel the trip and mining |

Ordinary deposits retain hotbar supplies, damageable equipment, food, equippable items, and the items in `depositKeepItems`. Output from the current mining job is unloaded even when Minecraft picked it up into a hotbar slot. Explicitly reserved items still stay with the bot. Put a stack of building blocks in the hotbar to reserve it unless that block is itself the current mining target. Configurable settings:

- `mineAutoDeposit` (default `true`): automatic chest trips while mining, once a destination is saved.
- `mineDepositFreeSlots` (default `2`): empty-slot threshold, clamped to 0–35.
- `depositKeepHotbar` / `depositKeepSupplies` (default `true`): retain the hotbar and equipment/food.
- `depositKeepItems`: extra retained items; defaults to torches, water buckets, empty buckets, and shields.
- `depositProtectionRadius` (default `3`): protected cube around the saved chest.

The bot only counts server-confirmed inventory changes as deposits. A full, blocked, missing, inaccessible, or unreachable container stops mining and reports the problem. Deposit trips do not drop items. An expected portal transition preserves the job; unexpected dimension changes, disconnects, death, and `#stop` cancel it. Fix the problem and restart the mining command. Numeric mining goals retain credit for confirmed deposits across return trips; completing a quantity goal makes a final deposit without returning to the mining site.

## Optional waste cleanup

Enable with `#set mineDiscardWaste true`; disable with `#set mineDiscardWaste false`. It is off by default. While mining, the bot tries cleanup when empty slots reach `mineDepositFreeSlots`, before deciding whether it still needs a deposit trip. It works without a saved chest or with automatic deposits disabled.

- `mineWasteKeepStacks` defaults to `2`: keep the two fullest ordinary building stacks **total**, across the hotbar and main inventory. These may be different block types. Partial stacks count as stacks; an equally full hotbar stack takes priority. Ordinary deposits also retain these stacks; `#deposit all` still empties everything.
- `mineWasteItems` is a separate configurable allowlist: cobblestone, cobbled deepslate, deepslate, dirt, stone, netherrack, granite, diorite, andesite, and tuff by default. The building-block defaults also allow these materials to be placed for bridging and pillaring.
- Mining targets, `depositKeepItems`, equipment, food, and items with custom component changes (including names) are protected from disposal. This protection overrides the waste allowlist, even if you deliberately mine cobblestone or another listed waste block. Ordinary ores and valuables are absent from the default allowlist.

Cleanup pauses at a safe stopping point and throws whole excess stacks into a clear tunnel behind the bot or to its side. It never clicks armor, offhand, crafting, or cursor slots, and does not run with a screen/container open or items on the cursor. Inventory swaps pause during cleanup; each drop is planned again from the live inventory and paced. Each slot is attempted at most once per pass, and cleanup has a timeout and cooldown. If there is no clear throwing direction, it falls back to the normal deposit decision. Waste drops never count toward mining quantity goals. Drops are ordinary world items and can be picked up again if the bot later returns through them.

## Ancient debris and travel between dimensions

1. In the Overworld, look at the deposit chest and run `#deposit set`.
2. Look at the purple interior of an active Nether portal and run `#portal set`. Saved or previously cached portals can also be used automatically. You do not need to register the Nether side in advance.
3. Run `#mine netherite` (continuous mining), `#mine ancient_debris`, or `#mine 32 netherite` (a quantity goal).

`netherite` is an alias for the ancient debris block. The bot travels to the Nether before searching for debris. When no known ore target is available, exploration heads toward `ancientDebrisYLevel` (default 15), respecting the usual exploration and pathfinding safety settings. It does not use beds or TNT.

The cycle is Overworld portal → Nether mining → Overworld chest → Nether mining site. When the requested quantity is complete, it makes a final deposit and stays at the chest. Set the deposit chest in the Nether instead if you want to unload locally.

The actual arrival portal is recorded for the return trip; destination coordinates are not inferred from coordinate scaling. The bot leaves the arrival portal before continuing and waits outside it when a return crossing needs a cooldown. `#portal status` shows saved endpoints; `#portal clear` forgets the saved endpoint in the current dimension. Routes are stored per server/save in `mining-routes.properties` and survive restarts. Save the chest again once when upgrading if it was previously configured only in a dimension you are not currently visiting.

`mineTravelBetweenDimensions` defaults to `true`. `miningPortalTimeoutMS` defaults to 45000 (bounded to 10–300 seconds). Missing/extinguished portals, unavailable routes, unsafe exits, unsupported dimensions, and failed crossings stop the job and report the reason. This feature uses existing active portals; it does not construct or light new ones. Only Overworld–Nether travel is supported.

Old-world ore scans are invalidated after a dimension change. Stop, death, disconnect, an expired transition, or arrival in an unexpected dimension cannot resurrect the mining job on a later connection.

## Avoiding player builds

Minecraft clients do not receive historical ownership for ordinary blocks. A stone block in a player's wall looks the same as natural stone. Observing a block update also does not reliably identify who caused it.

`preferAvoidConstruction=true` adds a large cost (default `constructionBreakPenalty=1000` ticks **per block**) for breaking likely construction: planks, stairs, slabs, bricks, glass, concrete, wool, doors, fences, redstone devices, and similar materials. `additionalConstructionBlocks` extends this to other blocks. This is a strong preference: it can still choose breaking when a detour is too costly or unavailable. Generated structures may contain the same materials and receive the same penalty.

Storage containers are protected against breaking by default (`protectContainers=true`). This includes chests (including every waxed and unwaxed copper variant), barrels, shulker boxes, furnaces, hoppers, and similar storage. Turning off the construction preference does not turn off container or marked-area protection.

For a base that must never be used as a mining shortcut, stand near its center and run `#protect 32` (choose a radius covering the build). This saves a cube extending 32 blocks in every direction. Pathfinding can walk through existing entrances but cannot plan block breaking or placement inside it. `#protect list` lists protection areas and `#protect clear` removes manual areas in this dimension. The deposit chest's automatic protection remains.

Protected areas also prevent explicitly selecting those blocks as mining targets. Protection markers are persistent user waypoints with reserved names; do not rename them manually. These features do not implement historical player-placement detection, private-message authorization, or combat.

## Validation

Run `bash scripts/check-mining-policies.sh` for dependency-free inventory and construction policy checks (JDK 17 or later). Full Fabric validation requires Java 25 and the Gradle/Minecraft dependencies:

```sh
./gradlew test :fabric:remapJar
```

In-game checks before relying on unattended mining:

- Fill a single chest and a double chest partially; confirm main-inventory and hotbar transfers go into storage rather than back into the inventory.
- Repeat deposits with waxed/unwaxed copper chests at each oxidation stage, including a double copper chest and a saved chest whose wax or oxidation changes.
- Enable waste cleanup, fill the inventory with mixed waste and valuable drops, and confirm the two fullest building stacks survive cleanup and ordinary deposits. Check a waste block that is itself the mining target, named blocks, explicit reserves, a blocked throwing direction, an open menu/cursor, and `#stop` during cleanup.
- Fill or obstruct the destination, remove it while traveling, and deny access with a server protection plugin; confirm mining stops and reports the problem.
- Interrupt a trip with pause, stop, death, disconnect, and an unexpected dimension change.
- Start `#mine 32 netherite` in the Overworld. Verify entry into the Nether, debris mining, deposit trips, return to the original mining site, and a final deposit when the goal is complete.
- Use a portal link whose arrival is offset from the scaled source coordinates; verify the actual return portal is saved.
- Extinguish/remove a saved portal, obstruct its exit, and disconnect during the transition screen; verify a clear stop without automatic resumption on reconnect.
- Mine across more than one trip with a numeric quantity goal; verify previously deposited items still count.
- Compare an open detour with a route through planks/stone bricks; verify a marked natural-stone base cannot be tunneled through.
