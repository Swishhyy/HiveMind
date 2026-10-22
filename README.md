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

1. Download `hivemind-<version>.jar` from the [latest release](https://github.com/Swishhyy/HiveMind/releases/latest) and put it in the bot client's `mods` folder.
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
#network web               opens the local map and dashboard in your browser
#stop                      stops everything
```

From your main account, whisper commands to the bot, for example `/msg BotName #mine 32 netherite`. Only private messages from trusted UUIDs are accepted, and trust and storage settings can only be changed locally on the bot.

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
./gradlew build                                      # Fabric mod: fabric/build/libs/hivemind-<version>.jar
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
