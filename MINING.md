# Fabric 26.3 mining improvements

This fork builds Fabric by default (`available_loaders=fabric`). Use Java 25 and install the Fabric jar on the Minecraft client that controls the bot account.

## Deposit chest

1. Look at a chest, trapped chest, copper chest, or barrel and run `#deposit add`. Repeat for each storage chest. `#deposit set` is an alias for adding. All copper chest oxidation stages are supported, both waxed and unwaxed, as single or double chests. You can also use `#deposit set x y z` while that container is loaded. A saved copper chest remains valid if it oxidizes or its wax is changed.
2. Run your usual mining command, for example `#mine diamond_ore deepslate_diamond_ore`.
3. When two or fewer main-inventory slots are empty, mining pauses, visits a matching saved container, unloads resources, returns to the mining position, and resumes the same job.

The numbered storage list is saved per server/save, with a dimension for each entry. IDs remain stable after entries are removed. An older single chest point is imported automatically. It never chooses an unsaved nearby chest. Specific matching item rules and configured priorities take precedence, then local chests and distance; full, missing, blocked, or unreachable chests are skipped for the next saved entry. A trip can use several chests before returning to the mining site. Double chests are supported. Worn armor and offhand equipment are not transferred.

| Command | Action |
| --- | --- |
| `#deposit add [x y z]` / `#deposit set [x y z]` | Add the selected container or exact coordinates; duplicates keep their existing ID |
| `#deposit list` | List storage IDs, dimensions, and coordinates |
| `#deposit remove <id>` | Remove one saved entry |
| `#deposit` or `#deposit now` | Deposit now, keeping supplies |
| `#deposit all` | Stop mining and empty main inventory and hotbar, including tools and food |
| `#deposit status` | Show the storage list and current trip |
| `#deposit clear` | Forget every storage entry for this server/save |
| `#pause` / `#resume` | Pause/resume the entire trip |
| `#stop` | Cancel the trip and mining |

Ordinary deposits retain hotbar supplies, damageable equipment, food, equippable items, and the items in `depositKeepItems`. Output from the current mining job is unloaded even when Minecraft picked it up into a hotbar slot. Explicitly reserved items still stay with the bot. Put a stack of building blocks in the hotbar to reserve it unless that block is itself the current mining target. Configurable settings:

- `mineAutoDeposit` (default `true`): automatic chest trips while mining, once a destination is saved.
- `mineDepositFreeSlots` (default `2`): empty-slot threshold, clamped to 0–35.
- `depositKeepHotbar` / `depositKeepSupplies` (default `true`): retain the hotbar and equipment/food.
- `depositKeepItems`: extra retained items; defaults to torches, water buckets, empty buckets, and shields.
- `depositProtectionRadius` (default `3`): protected cube around the saved chest.

The bot only counts server-confirmed inventory changes as deposits. It reopens storage as needed to obtain authoritative contents when a correctly predicted transfer produces no server slot update. If every saved container is full, blocked, missing, inaccessible, or unreachable, mining stops and reports the problem. An unconfirmed transfer also stops the trip. Deposit trips do not drop items. An expected portal transition preserves the job; unexpected dimension changes, disconnects, death, and `#stop` cancel it. Fix the problem and restart a direct mining command, or use `#job resume` for a queued job. Numeric mining goals retain credit for confirmed deposits across return trips; completing a quantity goal makes a final deposit without returning to the mining site.

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

The cycle is Overworld portal → Nether mining → Overworld chest → Nether mining site. When the requested quantity is complete, it makes a final deposit and stays at the chest. Add Nether storage if you want to unload locally; local entries are preferred over Overworld entries. Keep only Overworld entries to require return trips there.

The actual arrival portal is recorded for the return trip; destination coordinates are not inferred from coordinate scaling. The bot leaves the arrival portal before continuing and waits outside it when a return crossing needs a cooldown. `#portal status` shows saved endpoints; `#portal clear` forgets the saved endpoint in the current dimension. Routes are stored per server/save in `mining-routes.properties` and survive restarts. Save the chest again once when upgrading if it was previously configured only in a dimension you are not currently visiting.

`mineTravelBetweenDimensions` defaults to `true`. `miningPortalTimeoutMS` defaults to 45000 (bounded to 10–300 seconds). Missing/extinguished portals, unavailable routes, unsafe exits, unsupported dimensions, and failed crossings stop the job and report the reason. This feature uses existing active portals; it does not construct or light new ones. Only Overworld–Nether travel is supported.

Old-world ore scans are invalidated after a dimension change. Stop, death, disconnect, an expired transition, or arrival in an unexpected dimension cannot resurrect the mining job on a later connection.

## Conflict retreat

`fleeConflicts` defaults to `true`. While a bot task is active, nearby hostile mobs or a server damage event identifying an attacker trigger a temporary retreat. Player attackers are tracked by UUID; ordinary bystanders and environmental damage are not treated as attacks. Known neutral endermen/piglins require detected aggression or an actual attack. This does not automatically take over normal manual gameplay when no bot task is running.

The bot returns any owned crafting cursor stack and closes an empty-cursor container, interrupts active work, and paths away using ordinary movement safety settings. `conflictMobRadius` defaults to 12 (bounded to 4–32); `conflictFleeDistance` defaults to 32 (bounded to 16–128), with at least 64 for wardens. Further attacks extend separation beyond the current attack distance, up to 128 blocks, so repeated ranged hits keep the bot moving. Once safely separated for five seconds, the interrupted job resumes. Repeated escape-path failures stop bot work and report the problem. Retreat is the default. Optional defensive retaliation is described below; neither mode guarantees escape from every terrain layout.

Use `#set fleeConflicts false` to disable it. `#pause` and `#stop` retain priority. Death, disconnects, and world changes clear remembered threats.

## Stealth stalking and player sightings

`#stalk <player>` watches an online UUID; `#stalk` / `#stalk random` chooses one player, preferring a tracked nearby player. The viewing distance defaults to 40 blocks. The bot chooses loaded, walkable vantage points with a clear ray to the player's eyes, prefers the rear/side of their facing direction, and remains within `stalkVerticalRange` (default five blocks) vertically. `stalkPreferOpenAir=true` rejects enclosed roofs/hillside cavities while allowing leaf cover up to 12 blocks overhead. It does not tunnel into a mountain to see the player. If terrain offers no reachable open sight line, it repositions or stops with a reason.

It crouches while watching and, by default, while moving quietly. Sprint is disabled during quiet tracking. Player head direction plus an unobstructed sight line estimates whether the bot is being watched: `stalkViewConeDegrees=18` is the half-angle, and `stalkGazeTicks=40` requires about two seconds. Looking away reduces suspicion. A sustained stare triggers a scatter, normal-speed escape to at least 64 blocks (more for a larger viewing distance), a quiet wait, and a new viewing approach. `stalkScatterTicks=240` means at least 12 seconds; a blocked/failed escape has a finite stop. These are estimates from server-sent head rotations, not proof that the human noticed the bot.

`stalkMinimalEvidence=true` is scoped to a running stalking/hunt task. Path calculations prohibit breaking, placing, bucket falls, closed-door operation, and parkour; avoid farmland/turtle eggs; and do not use Frost Walker. Live block-action guards also stop stale path actions. Start with Frost Walker gear removed. The bot does not place torches, dump waste, open a target's containers, or loot as part of stalking. It uses existing passages and stops when the terrain-preserving route is unavailable. Normal mining/settings return after tracking ends. Other survival work may still use the bot's registered supply chests.

This minimizes world changes. Ordinary footsteps, item pickup by proximity, equipment effects, server logs, name tags, and vanilla kill messages cannot be made invisible by a client mod. Visible players and targets in unloaded terrain still follow the server's tracking limits.

| Command | Behavior |
| --- | --- |
| `#stalk status` / `#stalk stop` | Inspect or stop tracking and ambush work |
| `#stalk sightings [player]` / `history [player]` | Inspect the last 12 matching saved places |
| `#stalk check <player>` | Visit saved places and look for the player, including when they are offline |
| `#stalk base <player> [x y z]` | Locally confirm a base at supplied/currently tracked/previously observed coordinates |
| `#stalk forget <player\|all>` | Locally remove stored observations while tracking is stopped |
| `#stalk report <trusted-player>` | Locally choose who receives private reports from locally started tracking |
| `#stalk report off` / `status` | Clear or inspect that optional recipient |

Only an actual unobstructed player sighting records persistent observations. `player-sightings.properties` stores UUID/name, dimension, previously observed place coordinates, distinct visits, observed dwell, and last-seen time for this server/save. Places cluster within 32 blocks of a fixed observed anchor, with at most 12 places per UUID and 512 places total. Three separate visits plus three observed minutes mark a **frequent spot / possible base**. This is an inference; the client cannot prove block ownership. Explicitly confirmed bases are labelled separately.

A newly seen target is reported as `Found PlayerName at x y z (dimension)`. Repeated coordinate reports require both the cooldown (`stalkReportIntervalMS=120000`) and at least 64 blocks of movement. Output is local client text plus a private message to the trusted controller who started the task, or the configured trusted report recipient for a local start. It never publishes sighting coordinates to public chat. Disable automatic coordinate messages with `stalkReportSightings=false`; history still records observations. `stalkMemoryDays=30` expires ordinary sightings (bounded 1–90 days); manually confirmed bases remain until forgotten. Reports to revoked/offline recipients are discarded by the existing reply handler.

When a live player leaves client tracking, the bot checks a recent last-seen position, then saved bases/frequent spots/sightings in this dimension, then the existing bounded local search. `#stalk check` checks saved places only. The search remains bounded by `stalkSearchRadius` and `stalkSearchTimeoutMS`; it cannot discover arbitrary hidden coordinates. A normal target leaving the player list, death, disconnect, world changes, stop, or exhausted search ends the live task. Persistent sightings remain available for later commands.

## Named-player ambush

`#kill PlayerName` explicitly selects one online player, observes at the normal stalking distance, waits for an isolated player looking away, approaches from behind, and uses ordinary melee with a hotbar sword/axe. It does not use the server's administrative `/kill` command, spawn damage, or bypass PvP permissions. There is no random-target kill mode.

The watch interval is randomized between `killWatchMinimumMS=12000` and `killWatchMaximumMS=30000`, followed by at least three seconds looking away. Approaching is limited to 45 seconds. If the target turns toward the approach, the bot scatters and can retry up to `killMaxAttempts=3`. More players within 12 blocks, low health/hunger, a near-broken/missing weapon, sculk escape, blocked paths, or an unfavorable fight cause withdrawal. `killMinimumHealth=16` defaults to eight hearts. Nearby hostile mobs retain normal conflict retreat.

Actual attacks require normal close melee range, unobstructed sight, aim, and a charged vanilla attack cooldown. While engaged with that explicit target, suitable retaliation is handled by the hunt; unrelated threats still use conflict handling. `killAttackTimeoutMS=15000` limits combat, and `killTimeoutMS=600000` limits the whole hunt. Lack of observed health reduction stops an ineffective/protected fight. A death is reported only if the target is actually observed dead; disappearance or logout is not reported as a kill. It withdraws afterward and does not deliberately loot the drops. No bot can guarantee that a player is unsuspecting or that an ambush succeeds.

Trusted controllers are protected by default. A local opt-in `#set killAllowTrustedTargets true` permits deliberate targeting of them. `#kill status`, `#kill stop`, and global pause/stop remain available. Trusted whisper controllers can issue `#kill PlayerName`; sighting/base/report configuration edits remain local. All new settings are in the existing **Mods > Baritone > Configure** form and the regular `#set` interface.

## Avoiding player builds

Minecraft clients do not receive historical ownership for ordinary blocks. A stone block in a player's wall looks the same as natural stone. Observing a block update also does not reliably identify who caused it.

`preferAvoidConstruction=true` adds a large cost (default `constructionBreakPenalty=1000` ticks **per block**) for breaking likely construction: planks, stairs, slabs, bricks, glass, concrete, wool, doors, fences, redstone devices, and similar materials. `additionalConstructionBlocks` extends this to other blocks. This is a strong preference: it can still choose breaking when a detour is too costly or unavailable. Generated structures may contain the same materials and receive the same penalty.

Storage containers are protected against breaking by default (`protectContainers=true`). This includes chests (including every waxed and unwaxed copper variant), barrels, shulker boxes, furnaces, hoppers, and similar storage. Turning off the construction preference does not turn off container or marked-area protection.

For a base that must never be used as a mining shortcut, stand near its center and run `#protect 32` (choose a radius covering the build). This saves a cube extending 32 blocks in every direction. Pathfinding can walk through existing entrances but cannot plan block breaking or placement inside it. `#protect list` lists protection areas and `#protect clear` removes manual areas in this dimension. Each saved storage chest has automatic protection; removing its entry removes that automatic area. Manual protection areas are separate.

Protected areas also prevent explicitly selecting those blocks as mining targets. Protection markers are persistent user waypoints with reserved names; do not rename them manually. Historical player-placement detection is unavailable to a normal client. Whisper authorization and optional defense are separate behaviors described below.

## Trusted whisper control

On the bot's own client, run `#trust add YourPlayerName` while that player is online. This saves the exact player UUID for this server/save, rather than trusting a nickname or a rendered chat prefix. Use `#trust list`, `#trust remove <name-or-UUID>`, and `#trust reload` to inspect, revoke, or reload access. UUIDs can be entered directly for offline players.

From that player's account, send `/msg BotAccount #mine 32 netherite`. The bot queues the job and sends private acknowledgements, start/completion notices, and interruption reasons. Commands require the `#` prefix. The accepted remote commands are:

| Whisper body | Action |
| --- | --- |
| `#mine 32 netherite` | Queue a finite mining job; a positive quantity is required remotely |
| `#job mine 64 diamond_ore deepslate_diamond_ore` | Queue another mining request |
| `#job status` / `#status` | Current task, queue size, progress, health, hunger, and waiting reason |
| `#job list`, `pause`, `resume`, `remove <id>`, `cancel` | Manage the job queue |
| `#pause` / `#resume` / `#stop` | Normal global task controls |
| `#deposit now` / `all` / `status` / `list` | Deposit or query storage; configuration edits remain local |
| `#stalk PlayerName` / `random` / `status` / `stop` | Stealth watching, gaze-triggered scatter, and saved sightings |
| `#kill PlayerName` / `status` / `stop` | Explicit stalking and a bounded melee ambush |
| `#stalk sightings [player]` / `check <player>` | Inspect or revisit saved places |
| `#recover now` / `status` / `stop` | Recover the recorded dropped inventory after respawning |
| `#deliver PlayerName diamond 32 [x y z]` / `handoff` / `status` / `stop` | Fetch and hand over items to one recipient |
| `#escort PlayerName` / `status` / `stop` | Follow and optionally protect a visible player |
| `#gather 64 glass` / `plan 64 glass` / `status` / `stop` | Fetch/mine ingredients, craft/smelt, and deposit the requested product |
| `#repair now` / `status` | Visit the configured Mending/XP point |
| `#station status` / `#minezone status` | Inspect configured stations and current-dimension boundaries |

By default only signed, client-validated private chat with the incoming whisper chat type is accepted. Public chat, unsigned display overrides, untrusted senders, and arbitrary administrative commands cannot control the bot. Trust, settings, chest rules, station/boundary edits, and recovery-attempt resets are local operations. Incoming commands and outgoing replies are paced and bounded; revoked/offline controllers do not receive queued replies.

`bot-control.properties` is stored beside `mining-routes.properties` in the server/save's Baritone cache directory. It contains `trusted` (comma-separated UUIDs), `whisperTypes` (default `minecraft:msg_command_incoming`), `allowUnsignedServerWhispers=false`, and `unsignedWhisperFormat=`. Custom authenticated whisper chat types can be added to `whisperTypes`.

Some server plugins replace whispers with unsigned system text. An explicit fallback is available by setting `allowUnsignedServerWhispers=true` and an exact format such as `unsignedWhisperFormat=[{sender} -> me] {command}`, then running `#trust reload`. Placeholders must appear once each in that order; the sender must resolve to a currently online profile whose UUID is trusted. The parser matches the whole message. This fallback trusts the server/plugin to prevent players from forging that format; it cannot authenticate arbitrary displayed text. Keep it disabled when the server preserves signed whispers. Profileless disguised-chat packets are not used as an authenticated source.

## Jobs and interruptions

Use `#job mine <quantity> <blocks...>` to queue a finite mining request. For example:

```text
#job mine 32 netherite
#job mine 64 diamond_ore deepslate_diamond_ore
#job status
#job list
```

A maximum of 64 jobs is saved per server/save in `bot-jobs.properties`. New jobs wait for existing bot work and run in order. Mining quantity follows Baritone's existing semantics: matching carried items plus credited deposits, rather than a count of newly broken blocks. The same request survives normal storage, portal, survival, and conflict interruptions. A quantity already achieved remains credited if the client stops during its final deposit.

`#job pause` holds the active job; `#job resume` retries its survival/sculk checks and continues the queue. `#job remove <id>` removes one request; `#job cancel` clears the queue and stops its active work. `#stop` stops current work and leaves saved requests paused. Starting another direct task also leaves queued work paused rather than allowing it to restart behind that command. If `#pause` was used, `#resume` is still needed to release that separate global pause.

On client restart, death, disconnect, or an unexpected world change, saved requests require `#job resume`. They do not automatically start on reconnect. Status includes the actual controlling process, including a portal/deposit/retreat interruption. Failures pause the queue for inspection rather than silently skipping to the next job. Jobs submitted by a trusted controller send that controller concise private notifications.

## Storage labels, rules, and supplies

Containers are still registered with `#deposit add` and removed with `#deposit remove <id>`. Use the returned IDs to configure their purpose:

```text
#storage label 1 Ores
#storage rule 1 ores
#storage label 2 Supplies
#storage rule 2 supplies
#storage priority 1 10
#storage list
```

| Rule | Accepted deposits |
| --- | --- |
| `any` | General fallback, the default for existing/new entries |
| `ores` | Vanilla ores, raw metals, ingots/nuggets, and common mineral drops including ancient debris |
| `building` | Block items outside the ore category |
| `junk` | Items in `mineWasteItems` |
| `supplies` | Food, equipment, explicitly reserved items, and usable building blocks |
| `items diamond ancient_debris` | Exact item allowlist; namespace IDs support modded items |
| `disabled` | No automated storage use |

Syntax is `#storage rule <id> <rule> [items...]`, `#storage label <id> <name...>`, or `#storage priority <id> <-100..100>`. Specific matching categories precede `any` fallback chests. Higher priority wins before dimension/distance ordering within that group; overlapping categories can be resolved using priorities. Chest labels do not affect matching. A full/rejected chest is skipped for another matching registered chest. Rules also apply to `#deposit all`; that command overrides item reserves, not destination filters. Rule edits are blocked during a storage trip. Corrupt rules disable that entry until corrected.

Resupply checks registered storage only, preferring the `supplies` role, then other enabled compatible storage. Ore-only and disabled entries are not supply sources. It withdraws only currently needed allowed food, usable replacement pickaxes, and safe building blocks, using whole-stack transfers and fresh server contents. It does not equip worn armor, use crafting/armor/offhand slots, or take unrelated resources. A restock target may be exceeded by part of one stack. Once minimum supplies are available, it returns to the saved work position. Missing, empty, protected, or unreachable supplies pause work with a reason.

## Survival checks

Survival is enabled by default (`botSurvival=true`). Before mining and during other active bot work, the bot checks food and health. Mining also requires a usable pickaxe, building reserves when placement is enabled, and an inventory-space/deposit plan. Ancient debris requires a diamond or netherite pickaxe. Near-broken tools are excluded from automatic tool selection and replacement selection. An available replacement is moved to the hotbar while stationary.

The bot pauses to eat allowed food and recover low health. It never assumes eating or regeneration succeeded: the player's updated hunger/health drives the next decision. If supplies are low, `botAutoResupply=true` uses the saved supply route, including supported portal travel. Full inventories are unloaded first when possible. An inventory full of retained items, unavailable supplies, stalled food use, or absent regeneration pauses for attention. Use `#survival status` and `#survival retry` after fixing a direct task, or `#job resume` for a paused queued task.

| Setting | Default |
| --- | --- |
| `botMinimumHealth` | 12 health points (6 hearts) |
| `botEatAtHunger` | 16/20; food is also used for recovery below full health when hunger permits |
| `botMinimumFood` / `botRestockFood` | 4 / 16 allowed food items |
| `botMinimumBlocks` / `botRestockBlocks` | 16 / 128 building blocks; the minimum is skipped if placement is disabled |
| `botToolReserve` | 32 durability remaining |
| `botFoodItems` | Cooked meats/fish, bread, baked potatoes, carrots, golden carrots, and apples |

Supply thresholds and timeouts are bounded in code. Food use keeps vanilla item-use held only while the care process controls the bot; pause, stop, conflict, and death release it. Ordinary deposits retain the fullest building stacks (using `mineWasteKeepStacks`, default two) while survival checks are enabled, including blocks restocked into the main inventory. Mining targets and `deposit all` keep their existing output/unload rules. The bot does not farm food or automatically craft replacement tools. A registered Mending station is tried before replacement supplies when repair is enabled; see below.

## Sculk-aware paths

`mineAvoidSculk=true` is enabled by default. Loaded surroundings are scanned for ordinary/calibrated sensors and shriekers, with bounded same-world memory. Path calculations use an immutable hazard snapshot and add `sculkPathPenalty` (default 5000, increasing nearer a trigger). Automated block breaking/placement in those zones, and breaking sculk blocks themselves, is prohibited. Execution checks also protect against a previously planned breaking/placement action after discovery.

`sculkAvoidRadius` defaults to 12 blocks and is bounded to 4–24. Calibrated sensors have at least a 20-block buffer. Encountering a nearby trigger or active shrieker interrupts work, closes the owned container, and selects a quiet exit. The bot crouches and disables sprint during the exit, waits briefly outside the zone, then resumes. Combat retreat can take priority while retaining the quiet movement preference. Repeated quiet-path failures pause work; `#stop` releases the task for manual movement, and `#job resume` retries a queued job after the route is fixed. Storage points inside a known sculk zone are skipped.

The client cannot inspect unloaded terrain or prevent every server vibration. Crouching does not silence every action. This is conservative avoidance and an escape attempt, not a guarantee that a Warden can never spawn. Disabling sculk avoidance explicitly permits mining/placing in those areas again.

## Optional defensive retaliation

`#defense flee` is the default; `#defense defend` enables limited retaliation. `#defense status` reports the mode. The existing `fleeConflicts` switch controls conflict monitoring in either mode.

Defend requires a confirmed, living attacker within close melee range, line of sight, more than 12 health, sufficient hunger, one nearby threat, and a usable sword/axe already on the hotbar. It uses normal attack cooldowns and an equipped offhand shield between attacks when available. Outside escort protection it does not pursue targets. It never attacks a trusted controller, and it never chooses melee against a Warden/creeper. Low health, multiple nearby threats, sculk escape, missing equipment, loss of range/sight, or the ten-second defense limit return to retreat. Food/recovery can run after separation. This is basic defensive behavior, not a full PvP combat system.

## Settings through Mod Menu

Commands remain the task controls. Install **Mod Menu 21.0.0 for Fabric 26.3**, then open **Mods > Baritone > Configure** for a paged settings form. There is no bot dashboard, gameplay control screen, extra hotkey, or patrol mode. Mod Menu is optional; without it all commands and the existing `#set` interface still work. Values are saved only with **Save**; **Cancel** or Escape discards edits. Existing `#set <setting> <value>` commands and Baritone's settings file use the same settings.

The form includes deposits, waste reserves, dimension travel, construction/sculk avoidance, survival, flee/defend, stalking/escort distance, delivery confirmation, recovery limits, Mending repair, and gathering limits. Trust and world endpoints remain in their server/save-scoped command configuration.

## Death recovery

After respawning, run `#recover now`. The death position, inventory item IDs/counts, time, and attempts are saved in `bot-locations.properties` for this server/save. The bot follows registered portal routes when necessary, searches for matching dropped items around the recorded point, and returns to the position where recovery started. It compares carried inventory against its starting inventory; simply revisiting the coordinates does not count as recovery. Equipped armor/offhand items are included in the death snapshot.

The local search radius defaults to 16 blocks (`recoverySearchRadius`, bounded 4–32); attempts default to three (`recoveryMaxAttempts`, bounded 1–10). No remaining visible matching drops after a bounded search produces a partial report and a return trip. Full inventory, an unreachable site, unsafe conditions, absent portals, and the task timeout stop the task with a reason. Healthy recovery can begin without the ordinary food-stock minimum, but hunger/low-health checks still apply. A matching item ID cannot prove historical ownership of a particular dropped stack; named/enchanted items are not deliberately discarded or handed over as ordinary courier stock.

`#recover status` inspects the record. `#recover stop` cancels the active recovery. Local `#recover reset` clears the attempt counter after inspecting the site; it retains the recorded location and item counts. The bot does not automatically respawn or start another recovery after dying. `keepInventory`, destroyed/despawned drops, and inaccessible chunks can prevent recovery.

## Deliveries

`#deliver PlayerName diamond 32` uses ordinary, unmodified carried stock and fetches missing items from enabled registered storage. Supply chests are preferred; courier stock can also come from an ore/item/general chest. Withdrawals use confirmed whole-stack transfers, so spare stock can remain with the bot. The amount handed over is limited to the requested quantity.

The recipient must be online. If their position is unknown to the client, provide a meeting point in the bot's current dimension: `#deliver PlayerName diamond 32 100 64 -200`. Visible players update the destination as they move; a recent client-observed position is also usable. It does not query hidden server coordinates.

When the bot arrives, use `#deliver handoff` locally or from the trusted original controller/recipient. `deliveryRequireHandoff` defaults to true. The bot waits until it is stationary, within four blocks with line of sight, and no other player is within eight blocks, then faces the recipient and releases one stack or one remainder item at a time. Only server pickup packets identifying that recipient count toward completion. Another collector stops the task; re-collection by the bot requires confirmation again. Unconfirmed pickup times out after one minute. A delivery can still be intercepted by a mob or a player moving into range after a drop; the bot reports only pickups it confirmed.

`#deliver status` reports progress and `#deliver stop` cancels. `deliveryApproachDistance` defaults to three (bounded 2–6); if configured above four it can wait outside handoff range until the recipient approaches. On completion the bot stays at the meeting point. Items already released are ordinary world drops and cannot be recalled by `#stop`.

## Escort and limited bodyguard behavior

`#escort PlayerName` starts beside a player currently visible to the client. The bot follows a ring around them at `escortDistance` (default five, bounded 3–16), backs away when too close, and keeps the existing pathfinding protections. If the target leaves client tracking, it visits the last observed point and waits up to `stalkSearchTimeoutMS` before stopping.

With `escortDefendOwner=true` and `#defense defend`, a server-observed attack on that player can trigger limited bodyguard defense. The attacker must stay within eight blocks of the escorted player and within 16 blocks of the bot for an approach. Existing health, hunger, weapon, line-of-sight, single-threat, ten-second, trusted-player, Warden, and creeper restrictions still apply. Retreat takes over when defense is unsuitable. The default flee mode retreats from the conflict. `#escort status`, `#escort stop`, and global pause/stop work normally.

## Mining boundaries

Mark two corners using `#minezone pos1 [x y z]` and `#minezone pos2 [x y z]`, or use `#minezone set x1 y1 z1 x2 y2 z2`. Without coordinates each corner uses the bot's feet. Saving both corners enables an inclusive cuboid for this dimension. `#minezone on`, `off`, `status`, and `clear` manage it.

Walking, portal travel, and chest trips may leave the zone; automated block breaking while a mining request is active must stay inside it. Loaded/cached ore candidates, dropped-item targets, exploration points, path costs, and live breaking checks all apply the boundary. With boundaries enabled, a missing zone in a target mining dimension stops mining rather than inheriting another dimension's coordinates. Create an Overworld and Nether zone before a bounded debris route, or turn boundaries off. Zones and corners survive restart; malformed bounds stop digging. Exploration uses a finite grid within the zone and stops when its points and known targets are exhausted. Existing construction/container/sculk protections still apply within a zone.

## Mending repair station

Stand at an existing XP collection point and run `#repair set` (or `#station repair [x y z]`). `#repair now` selects a damaged Mending pickaxe, visits that point, holds the pickaxe, collects nearby available XP, and waits for actual durability improvement. It returns to the starting point after reaching `repairTargetPercent` (default 90). It uses existing XP sources; it does not generate XP, repair unenchantable tools, or spend invented repair materials.

During mining, `repairToolsBeforeReplace=true` tries this route before replacement supplies when remaining durability falls below `repairAtDurability` (default 64, also accounting for the tool reserve). A usable Mending pickaxe already meeting the configured repair target does not trigger repeated trips. `repairNoProgressTimeoutMS` defaults to two minutes; no progress makes automatic repair back off for ten minutes so normal replacement-supply checks can proceed. Food and conflict handling can interrupt the trip. Missing stations/tools, changed inventory, failed portals, and the overall task deadline report a stop. `#repair status`, local `#repair clear`, and global pause/stop are available.

## Recipe-aware gathering

Register the stations once while looking at them:

```text
#station crafting
#station furnace
#gather plan 64 glass
#gather 64 glass
```

The planner reads bundled vanilla shaped/shapeless crafting and furnace-smelting recipes, reuses ordinary carried inventory, chooses concrete ingredient alternatives, rounds recipe batches up, and accounts for coal/charcoal fuel. It first tries registered stock for products and ingredients, mines supported missing resource sources, then uses the actual crafting/furnace menus. A stack of glass plans 64 sand and eight coal when nothing is carried. `#gather 32 rail` and `#gather 64 torch` also demonstrate recursive recipes. Result stacks and reopened server inventory contents confirm progress. Recipe inputs/fuel/products are reserved against ordinary deposits and waste cleanup while the task runs.

Gathering uses a selected empty crafting table and a regular furnace with empty input/output, plus at least one free inventory slot. Register their coordinates explicitly with `#station crafting x y z` / `#station furnace x y z` if needed. These points and the XP station receive an automatic three-block construction-protection area. `#station status` lists them and local `#station clear <crafting|furnace|repair>` removes a point. A furnace may retain compatible fuel from earlier use. Crafting interruptions return owned cursor contents and close only a clear cursor; uncertain inventory state stops rather than continuing a guessed recipe.

Supported resources include ordinary self-dropping blocks, common mineral drops/raw metals, cobblestone, cobbled deepslate, clay balls, and Nether quartz. Resource mining uses normal loot/tool requirements, survival checks, boundaries, sculk avoidance, and existing Overworld–Nether portals. Unsupported acquisition requires stock in a registered chest. Use a suitable pickaxe without Silk Touch when requesting mineral/raw-metal drops. Smithing, brewing, stonecutting, special crafting recipes, arbitrary mod/datapack recipes, farms, and custom-component items require supplies or manual work. The real server result must match the planned product/yield; changed recipes stop the operation.

When enough final product is carried, the bot deposits that product into matching registered storage. With no storage registered it finishes carrying the product. Whole recipe batches can produce a few extras, which are deposited too; tools, other ingredients, and spare fetched stock stay in the inventory. `#gather status` / `stop` and global pause/stop are available. The plan/action count defaults to 4096 (`gatherMaxActions`, bounded 1–4096); quantity requests are 1–4096. `companionTaskTimeoutMS` defaults to 30 minutes (bounded 1–60 minutes) for finite companion tasks and workstations. Escort refreshes its deadline while the target remains visible.

Companion tasks are issued directly by command. Starting one replaces current work and leaves persistent mining jobs paused. An expected portal transition, conflict retreat, or global pause preserves a running task. Stop, death, disconnect, or an unexpected world change cancels live task execution; only saved endpoints, boundaries, and the death record persist. Inspect the reason and issue a fresh command instead of assuming reconnect will resume a handoff or crafting click.

## Validation

Run `bash scripts/check-mining-policies.sh` for dependency-free inventory, storage migration/rules, trust, job persistence, survival, sculk, construction, observation/retreat, recipe batching/fuel, recovery persistence, exact handoff quantities, repair targets, and mining-boundary policy checks (JDK 17 or later). Full Fabric validation requires Java 25 and the Gradle/Minecraft dependencies:

```sh
./gradlew test :fabric:remapJar
```

In-game checks before relying on unattended mining:

- Fill a single chest and a double chest partially; confirm main-inventory and hotbar transfers go into storage rather than back into the inventory.
- Repeat deposits with waxed/unwaxed copper chests at each oxidation stage, including a double copper chest and a saved chest whose wax or oxidation changes.
- Enable waste cleanup, fill the inventory with mixed waste and valuable drops, and confirm the two fullest building stacks survive cleanup and ordinary deposits. Check a waste block that is itself the mining target, named blocks, explicit reserves, a blocked throwing direction, an open menu/cursor, and `#stop` during cleanup.
- Fill or obstruct the destination, remove it while traveling, and deny access with a server protection plugin; confirm it tries the next registered chest, or stops and reports the problem when none remain.
- Interrupt a trip with pause, stop, death, disconnect, and an unexpected dimension change.
- Start `#mine 32 netherite` in the Overworld. Verify entry into the Nether, debris mining, deposit trips, return to the original mining site, and a final deposit when the goal is complete.
- Use a portal link whose arrival is offset from the scaled source coordinates; verify the actual return portal is saved.
- Extinguish/remove a saved portal, obstruct its exit, and disconnect during the transition screen; verify a clear stop without automatic resumption on reconnect.
- Mine across more than one trip with a numeric quantity goal; verify previously deposited items still count.
- Compare an open detour with a route through planks/stone bricks; verify a marked natural-stone base cannot be tunneled through.

Additional gameplay checks for multiple storage and passive behavior:

- Add three chests, partially fill the nearest one, and confirm unloading continues into another. Block/remove a chest and confirm it is skipped. Remove an entry by ID, restart, and confirm its ID/location stays removed while remaining IDs do not change.
- Trigger a hostile mob and a player attack during mining, during a chest transfer, and while watching. Confirm retreat closes the menu, reaches separation, and resumes the interrupted task after the quiet period. Test pause, stop, death, and disconnect during retreat.
- Stalk a named visible player and a random player; check backing away, following, crouching, facing, obstructed sight, and a lost target. Confirm unloaded players use the bounded local search and that stop releases crouch and movement.

Additional gameplay checks for control, jobs, supplies, and defense:

- Trust one account, verify private commands work, and verify public chat, a different UUID, and a removed controller do not control the bot. Check the optional unsigned format only on a server whose plugin controls it.
- Queue two finite jobs; interrupt with pause, storage, portals, low supplies, retreat, and death. Restart during the final deposit, verify the completed quantity remains credited, and confirm reconnect requires an explicit resume.
- Assign ores/building/supplies/custom filters. Verify mixed inventory routes to matching chests, overlapping priorities are honored, and deposit all still obeys filters.
- Run low on food, building blocks, and tool durability. Check successful restocking and an empty/protected/missing supply chest, including a full reserved inventory. Verify failed resupply does not discard equipment or take unrelated items.
- Enter sensor/shrieker areas, including calibrated sensors, and compare a safe detour with a short noisy route. Check discovery during an existing path, blocked quiet exits, storage in hazard zones, and that stop releases crouch.
- Compare flee and defend with one attacker, low health, multiple threats, a trusted controller, a ranged attacker, a creeper, and a Warden. Verify the time limit, attack cooldown, shield release, and no pursuit.

Additional gameplay checks for companion work and Mod Menu:

- Open Baritone Configure through Mod Menu 21.0.0. Save changed toggles/numbers, cancel another edit, resize across GUI scales, and verify commands still work without Mod Menu installed.
- Record a death with equipment, respawn, recover through a portal, and confirm matched inventory/return behavior. Check a full inventory, inaccessible/despawned drops, repeated death, restart, and the attempt limit.
- Deliver one full stack and a partial quantity to a trusted test account. Confirm no release before handoff, exactly requested quantities, server-confirmed recipient pickup, interruption/recollection, another collector, an unknown player position, and an offline target.
- Escort a moving player. Trigger a confirmed attacker near the player, compare flee/defend, and verify health/threat/distance/time limits. Unload the target, pause/stop, die, and disconnect.
- Select a small mining cuboid with reversed/negative corners, place targets just outside every face, and verify paths and live breaking stay inside. Try storage outside the zone and a missing Nether zone.
- Hold a damaged Mending pickaxe at an XP station, check real durability progress, food interruption, no-XP timeout/replacement fallback, changed inventory, and stop.
- Gather glass, rails, and torches with partial existing stock, exact recipe batches, and alternate ingredients. Interrupt during a cursor transfer and furnace smelting; test occupied/protected stations, insufficient fuel/space, changed server recipes, final-storage filters/fullness, portal failure, and disconnect.

Gameplay validation still needs a Minecraft 26.3 client and a test world/server; policy checks and a successful build do not simulate server inventories, combat, portals, or GUI rendering.
\nGameplay checks for stealth and hunts:\n\n- Watch a player on open terrain, near trees, beside a hillside, and underground. Check actual crouched eye-level sight lines, close vertical range, and rejection of enclosed viewing sites.\n- Compare brief glances, blocked sight lines, and a sustained stare; verify scatter separation, normal-speed escape, quiet wait, re-approach, blocked escape, pause, and stop.\n- Put a closed door, a required tunnel/bridge, farmland, and a water gap on the route. Confirm no block actions/buckets/door operation and that original mining behavior resumes after stop.\n- Observe separate visits to a spot; verify dwell/visit requirements, possible-base labels, explicit confirmation, persistence, dimension/server isolation, forgetting, and private report cooldowns.\n- Issue a named hunt against a test account. Compare rear opportunity, target turning around, multiple players, health/hunger/tool failures, PvP protection, attack cooldowns, combat timeout, a real observed death, and target disappearance. Confirm no deliberate looting and no public coordinate reports.\n