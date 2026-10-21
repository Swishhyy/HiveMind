# HiveMind

Autonomous companion bots for **Fabric on Minecraft 26.3**, built on the [Baritone](https://github.com/cabaletta/baritone) pathfinder.

HiveMind adds trusted whisper control, persistent mining job queues,
sculk-aware routes and quiet escapes, food/health/tool checks and automatic resupply, labeled storage chests with item rules,
construction protection, optional waste cleanup, and Overworld–Nether travel for ancient debris.
Conflict behavior defaults to retreat; optional defensive retaliation, stealth stalking and a named-player hunt mode (melee, crystal or anchor) are available.
The optional [client network and localhost atlas](NETWORK.md) shares sightings/exploration between bots and provides browser commands, trust management, and global or individual settings. Keys are generated on first client launch. Connected bots recognize allies, coordinate hunt roles and mining veins, and scout while idle. The map renders sampled terrain in top-down/isometric views with base markers.

The autonomous controller also supports saved offline-player watch/hunt orders, automatic respawning, elytra scouting/search travel, shared logout/death/waypoint marks and periodic private status reports. See [NETWORK.md](NETWORK.md#autonomous-client-orders-flight-and-reporting).

## Install

Requires Minecraft 26.3, Fabric Loader 0.19.5 or newer, and Java 25. [Mod Menu](https://modrinth.com/mod/modmenu) 21.0.0 is optional and adds a settings screen under **Mods > HiveMind > Configure**.

Download the jar from the latest successful [build run](https://github.com/Swishhyy/baritone-but-better/actions/workflows/gradle_build.yml) (artifact **HiveMind**) and put `hivemind-<version>.jar` in your `mods` folder.

To build it yourself, run `./gradlew build`; the jar is written to `fabric/build/libs/`.

## Documentation

- [MINING.md](MINING.md): setup, commands, settings, limits and gameplay checks for mining, logistics, companions, stalking and combat.
- [NETWORK.md](NETWORK.md): the bot network, localhost atlas, autonomous orders, flight and reporting.
- [USAGE.md](USAGE.md): Baritone's own commands (`#goto`, `#mine`, `#follow`, `#build`, `#farm`, `#explore`, `#set` and more), which all still work.

## Credits and license

HiveMind is a fork of [Baritone](https://github.com/cabaletta/baritone) by leijurv, Brady and its contributors, and is distributed under the same [LGPL-3.0](LICENSE) license.
