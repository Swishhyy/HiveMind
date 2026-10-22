# HiveMind

**Autonomous companion bots for Minecraft 26.3 (Fabric), built on the Baritone pathfinder.**

[![Latest release](https://img.shields.io/github/v/release/Swishhyy/HiveMind)](https://github.com/Swishhyy/HiveMind/releases/latest)
![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-green)
![Fabric](https://img.shields.io/badge/loader-Fabric-blue)
[![License: LGPL-3.0](https://img.shields.io/badge/license-LGPL--3.0-lightgrey)](LICENSE)

HiveMind runs on the Minecraft client of a bot account and turns it into a worker you can direct from chat, whispers or a browser dashboard. Bots mine and manage storage, look after their own food, health and tools, run errands, watch or hunt named players, and share what they see with each other. An optional standalone controller coordinates a whole group of bots and keeps running when individual clients disconnect.

## Features

**Mining and logistics**
- Mining with automatic trips to saved storage chests (regular, trapped, copper of any oxidation, barrels), then back to the same spot.
- Persistent job queues (`#job mine 32 netherite`) that survive storage trips, portals, retreats and restarts.
- Storage labels, item rules and priorities: ores, building blocks, junk, supplies or exact item lists.
- Ancient debris runs through existing Overworld–Nether portals, with return trips to Overworld storage.
- Optional waste cleanup, mining boundaries (`#minezone`), and protection for bases, containers and likely player builds.

**Companion work and survival**
- Eats, recovers health, swaps worn-out tools and restocks food, tools and blocks from a supplies chest.
- Death recovery, item deliveries with a confirmed handoff, escorting a player, and Mending repair at an XP spot.
- Recipe-aware gathering: `#gather 64 glass` fetches or mines ingredients, crafts or smelts at registered stations, and stores the result.
- Retreats from hostile mobs and attackers by default; optional limited self-defense.
- Avoids sculk sensors and shriekers, and plans water-bucket clutches for long falls.

**Stealth and combat**
- `#stalk <player>` watches from cover at about 40 blocks, crouching only when someone could hear or see it, and scatters if stared at.
- Private sighting memory per server, with possible and confirmed bases.
- `#kill <player> [auto|melee|crystal|anchor]`: stalks, waits for an isolated target looking away, then fights with end crystals, respawn anchors or melee.
- Once engaged it only retreats on low health or when a second player shows up. It keeps a totem in the offhand and can drink invisibility potions.
- Trusted controllers are protected from being targeted by default.

**Bot network, controller and autonomy**
- An encrypted relay connects your bot clients. They share player sightings, explored chunks and map marks, recognize teammates, split up mining veins and coordinate hunts.
- A browser dashboard keeps a persistent terrain map with adjustable bot view-range rings, sightings and base markers. It can also send commands, manage trust, and change settings for one bot or all bots.
- The standalone controller (a Windows executable or a Java 17+ jar) hosts the relay and dashboard on its own, and manages sessions, reconnects and optional combat logging.
- Named watch/hunt orders persist across deaths, reconnects and restarts. Bots respawn automatically, scout while idle, and fly with elytra on long trips.
- Player-prioritized entity awareness and configurable ESP outlines through terrain, using entities actually loaded by the server.
- Opt-in automatic updates from GitHub Releases, verified before install.

**Baritone underneath**
- All of Baritone's own commands still work: `#goto`, `#mine`, `#follow`, `#explore`, `#farm`, `#build`/`#litematica`, `#elytra`, `#set` and the rest. See [USAGE.md](USAGE.md).

## Requirements

- Minecraft **26.3** with **Fabric Loader 0.19.5** or newer
- **Java 25** for the Minecraft client
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) 21.0.0 for an in-game settings screen
- Optional: Java 17 or newer to run the controller jar (the Windows build bundles its own Java)

## Install

1. For bot accounts, download `hivemind-<version>.jar`; for your own manual character, download `hivemind-player-<version>.jar` from the [latest release](https://github.com/Swishhyy/HiveMind/releases/latest) and put **one** edition in that Minecraft instance's `mods` folder. Remove the previous HiveMind JAR first; both editions use the same mod ID.
2. If you used a development build before 0.1.0.0, remove that jar first. Packages, the mod ID and config files are now `hivemind`; old configs are not migrated.
3. Optional controller: download `hivemind-controller-<version>-windows-x64.zip`, extract the whole folder and run `HiveMindController.exe`. On Linux, macOS or a server, run `java -jar hivemind-controller.jar`. Open the dashboard link it prints. Then copy the **Bot connection command** from **Settings** into each bot's chat once, while it is on the target server. See [CONTROLLER.md](CONTROLLER.md).

Each client asks once, on its first server join, whether to enable automatic updates. You can also update with `#update install`, or from the controller's **Updates** page.

## Quick start

Run these in the bot client's chat:

```text
#trust add YourName        lets your main account control the bot by whisper
#deposit add               look at a chest first to save it as storage
#mine 32 diamond_ore       mines 32, unloading to storage as needed
#job status                shows the current task, progress, health and hunger
#stalk PlayerName          watches a player from cover
#kill PlayerName auto      stalks, then attacks with crystals, anchors or melee
#network web               starts the local map and dashboard and prints a link to it
#stop                      stops everything
```

From your main account, whisper commands to the bot, for example `/msg BotName #mine 32 netherite`. Only private messages from trusted UUIDs are accepted, and whispers cannot change trust, storage or other settings. Change those on the bot itself, or through the dashboard.

## Command cheat sheet

All commands use the `#` prefix. `#help <command>` shows the full syntax.

| Area | Commands |
| --- | --- |
| General | `#pause`, `#resume`, `#stop` (`#cancel`), `#forcecancel`, `#set <setting> <value>`, `#help` |
| Mining | `#mine [amount] <blocks...>`, `#job mine <amount> <blocks...>`, `#job status\|list\|pause\|resume\|cancel\|remove <id>` |
| Storage | `#deposit add [x y z]`, `#deposit now\|all\|list\|status\|remove <id>\|clear`, `#storage label\|rule\|priority <id> ...`, `#storage list` |
| Travel and areas | `#portal set\|status\|clear`, `#minezone pos1\|pos2\|set\|on\|off\|status\|clear`, `#protect [radius]\|list\|clear` |
| Companion | `#recover now\|status\|stop`, `#deliver <player> <item> <amount> [x y z]`, `#deliver handoff`, `#escort <player>`, `#gather [plan] <amount> <item>`, `#station crafting\|furnace\|status`, `#repair set\|now\|status` |
| Survival and defense | `#survival status\|retry`, `#defense flee\|defend\|status` |
| Stalking | `#stalk <player>\|random`, `#stalk status\|stop`, `#stalk sightings [player]`, `#stalk check <player>`, `#stalk base <player> [x y z]` |
| Combat | `#kill <player> [auto\|melee\|crystal\|anchor]`, `#kill status\|stop` |
| Network | `#network controller <host> <port> <key> <scope>`, `#network host\|join\|key\|web\|status\|scope\|off`, `#mark add\|danger\|list\|remove`, `#roam on\|off\|status` |
| Sessions and updates | `#session logout [seconds]\|reconnect\|status`, `#update status\|check\|install`, `#update auto on\|off` |
| Trust | `#trust add\|remove <player or UUID>`, `#trust list\|reload` |

## Settings

Every option is available through `#set <setting> <value>`. With Mod Menu installed, open **Mods > HiveMind > Configure** for a paged settings form. The dashboard's **Settings** tab applies typed settings to one bot or the whole group, and **Trust** manages whisper controllers. Defaults and limits for each feature are listed in the documentation below.

## Documentation

- [MINING.md](MINING.md): mining, storage, survival, companion tasks, stalking, combat, whisper control and in-game test checklists.
- [NETWORK.md](NETWORK.md): the bot network, local dashboard and map, teamwork, autonomous orders, elytra flight and reporting.
- [CONTROLLER.md](CONTROLLER.md): the standalone controller, remote hosting, sessions, combat logging and automatic updates.
- [USAGE.md](USAGE.md): Baritone's general commands and settings.
- [RELEASE_NOTES.md](RELEASE_NOTES.md): changes in the latest release.

## Building from source

Building needs Java 25.

```sh
./gradlew build                                      # Shared Fabric build; CI packages the bot and player editions
python3 scripts/build-distributions.py controller    # standalone controller (Java 17)
bash scripts/check-mining-policies.sh                # behavior policy checks, no Minecraft needed
bash scripts/check-controller.sh                     # controller, network and updater checks
```

GitHub Actions builds every push. A successful build on `main` publishes a release for the version in `gradle.properties`, together with the Windows controller, checksums and the update manifest.

## Status and limits

- Live gameplay testing on real servers is still in progress. Automated checks cover the decision logic, but not real server inventories, latency or combat.
- A client only knows what it can see. It cannot locate unloaded players or prove who owns a base; possible bases are inferences.
- Combat damage estimates use client-visible state. Plugins, hidden effects and lag can change outcomes, and explosive combat leaves craters and placed blocks.
- Bots on different computers need a reachable controller or host: same LAN, a VPN such as Tailscale, or a forwarded port.
- Combat logging sends a normal disconnect and cannot undo server-side combat tags. Reconnecting cannot sign into accounts or restart a closed game.
- Only use HiveMind where bots, automation and PvP are allowed by the server rules.

## Credits and license

HiveMind is a fork of [Baritone](https://github.com/cabaletta/baritone) by leijurv, Brady and its contributors. It is distributed under the same [LGPL-3.0](LICENSE) license.

### Player sharing and map orders

Install `hivemind-player-<version>.jar` on your own client, then use the connection command from the controller's **Settings** page. The player edition starts in manual sharing mode and cannot switch to bot mode, even with an existing bot configuration. Your position and observations feed the shared map and the bots; HiveMind overlays and automation stay off on your screen. Nearby loaded players can be detected through ESP without displaying it to you. Those contacts expire after six seconds and are search hints, not recorded visual sightings or base evidence. Nothing scans entities that the server has not sent to your client.

The controller labels player clients separately and does not send them bot work, settings, trust policies or automatic session actions. The player JAR refuses `#client bot`. Install the standard `hivemind-<version>.jar` on bot accounts; it defaults to bot mode and retains existing local role choices during upgrades.

Right-click terrain on the atlas, select an online bot in that dimension and choose **Go here** or **Explore here**. Explore accepts a radius of 32–2048 blocks and surveys a finite grid by elytra or on foot. Commands are pinned to the clicked dimension, and will be rejected if the bot changes dimension before executing. Unmapped clicks are labeled approximate. Keyboard: focus the map and press Shift+F10 to command its center; Escape closes the menu.

Manual equivalents: `#scout go minecraft:overworld 100 -200`, `#scout explore minecraft:overworld 100 -200 256`, and `#scout status` / `#scout stop`.

### Reliable operations and itineraries (0.1.0.9)

The controller's Commands page explains each client's **Task**, **Blocker**, **Next** action and flight decision. It also shows round-trip latency, estimated bytes sent/received, queued frames and recovery count. Stale reports are labeled. Traffic totals refer to the current connection, not lifetime usage.

On the map, right-click and enable **Append to queue** to chain go/explore/check tasks. **Check here** visits a point and watches for ten seconds. Select one bot in **Map task queue** to reorder/remove entries, pause/resume/clear them, or append a **storage return** which deposits before advancing. Remaining tasks survive restart; an interrupted active survey restarts its current task, rather than pretending it was completed. Dimension mismatches hold the queue until resolved. Routes and search radii can be toggled in the map legend.

Commands: `#scout add explore minecraft:overworld 100 -200 256`, `#scout add check minecraft:overworld 150 -250`, `#scout add return`, `#scout list`, `#scout pause`, `#scout resume`, `#scout remove <id>`, `#scout move <id> <position>`, `#scout clear`. A new direct go/explore/check command replaces the itinerary; appending preserves it. Other manual work parks an active itinerary instead of silently discarding it.

Before long-distance travel, expedition checks use carried shulkers and registered storage for food, building blocks, rocket reserves, totems and replacements for nearly broken worn armor. Healthy matching spare armor is equipped through normal inventory actions. Item components and enchantments are retained. Storage shortages show a reason and a retry cooldown while the bot continues with available supplies; ordinary survival and flight reserve checks still apply. Configure `botExpeditionSupplies`, `botExpeditionRockets` (32), `botExpeditionTotems` (1), and `botStuckRecovery` through Settings or `#set`. Only actual accessible stock is used.

Player-mode clients can request `#meet`, `#backup`, or `#bring rockets 64` (`#bring <item> [quantity]` supports other items). Selection requires a healthy idle bot in the same dimension; a bot that becomes busy before the request arrives rejects it. Meet goes to your requested location. Backup meets and escorts under existing defense settings. Deliveries require the existing handoff confirmation. Your character remains manual and its overlays stay off.

Bots searching for the same target partition survey directions among live team members, share firsthand sightings and short-lived ESP hints, and spread support positions toward likely escape paths. ESP hints appear separately on the map and expire after six seconds; they do not create base evidence or historical sighting hotspots. Terrain scanning slows while stationary, unchanged tiles are suppressed, and network congestion defers terrain sampling while control messages keep priority.

Upgrade controller and clients together: this version uses network protocol 7. Keep existing controller data when replacing the standalone application.

### Long-range loadouts and bot bases

The default reserve bands are **2–4 stacks of rockets**, **1–2 stacks of allowed food**, **two usable elytras total**, and **3–4 totems**. Equipped usable wings and the offhand totem count toward those totals. Refills begin below the lower bound, rather than after every item consumed. Whole-stack transfers aim near the upper bound without requiring a cursor-full partial stack operation. Loadout settings expose the rocket/food upper targets, wing count and totem target.

Carry separate shulkers full of totems, food, rockets and wings. When safe and grounded, the bot opens a useful box, restocks, breaks and recovers that same box, then selects another category's box as needed. A five-second quiet window prevents repeated placements around approaching threats. One inventory slot is reserved for box recovery. Existing placement/break/inventory permissions still apply. If supplies are available in carried boxes during flight, the bot can deliberately land away from nearby untrusted players to refill; emergency escape takes priority. Ordinary deposits retain portable boxes and loose reserve items. Explicit `deposit all` retains its existing override behavior.

At your supply area run `#botbase set` (alias `#bot base set`). It saves the anchor and discovers supported loaded containers in a default 16-block radius; use `#botbase set 24` for a wider scan (maximum 32). Newly registered containers become supplies storage with base labels and priority 50. Existing rules remain intact. Copper variants, normal/trapped chests and barrels use the existing deposit adapter.

Use `#botbase resupply` to request a full reserve refill without replacing current work. The bot tries carried boxes and registered stock, or travels to a configured base to discover its containers. It returns to the interrupted location and resumes its task. Registered Nether portals are required for cross-dimension trips; arbitrary unloaded chests are never scanned remotely. Stock shortages and insufficient inventory room are reported rather than assumed successful.

Use `#botbase status`, `#botbase scan`, or `#botbase clear`. Clearing the anchor leaves registered chests intact; use `#deposit remove <id>` for containers you no longer want used. To configure one shared location for several bots, select **All bots** in the controller and send `#botbase set minecraft:overworld 100 64 -200 16`, substituting your coordinates. Bot base commands affect autonomous clients; player-mode clients remain manual.

Death recovery compares inventory after respawn rather than assuming all items dropped. `#recover status` shows pending, retained, lost, partial or empty observations; `#recover now` targets missing items only. Auto requires the server's full player inventory sync and a settling period. Set `botDeathInventoryMode` to `auto` (default), `keep`, or `drop` under controller **Companions**, or through `#set`, for unusual server plugins. Observations are per server/save and reassessed after each death; they do not claim access to a remote gamerule.

Separate client editions: the standard bot filename remains `hivemind-<version>.jar`, with the existing `hivemind-26.3-update.properties` update URL. The locked manual player edition is `hivemind-player-<version>.jar`, using `hivemind-player-26.3-update.properties`. Both support the same first-join automatic-update consent and `#update` controls. Downloads and install plans verify the edition as well as the checksum; updates replace the installed JAR in place and preserve its filename. Old standard JARs continue updating through the existing channel, including saved manual-role configurations. To adopt the locked player package, manually replace the old mod once, then its own updater handles later releases. Install only one edition per instance. The controller is still a separate application.

### Configure bots from the player client

Connect to the same controller/network, then use these on your manual player account. Replace `all` with a bot name or its UUID from `#bots list` to target one bot.

```text
#bots list
#bots all base set
#bots SeizureMike base status
#bots all chest add supplies
#bots all chest list
#bots all chest remove
#bots SeizureMike base resupply
```

`base set [radius]` uses **your** feet, with a default radius of 16 (4–32). `chest add [rule]` and `chest remove` use **your** selected supported chest or barrel; copper variants are supported. Rules: any, ores, building, junk, supplies, disabled. Omitting the rule preserves an existing container's rule. Removals match location instead of each bot's storage ID. Both accept explicit `<dimension> <x> <y> <z>` coordinates; `base set` optionally takes a radius afterward. `base status/scan/clear/resupply` and `chest list [page]` also work. Clearing a base keeps its registered chests; remove those individually.

Bot responses appear in your chat. Only fresh online autonomous bots are selected, and offline commands are not queued for later. Bots reject edits during an active supply trip. Loaded containers are checked immediately; unloaded locations are verified when a bot arrives. These commands configure the bots without enabling automation on your character. Update **both** the issuing player client and receiving bots to 0.1.0.9 or newer.
