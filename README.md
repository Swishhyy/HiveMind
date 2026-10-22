# HiveMind

Autonomous companion bots for **Fabric on Minecraft 26.3**, built on the Baritone pathfinder.

HiveMind adds trusted whisper control, persistent mining job queues,
sculk-aware routes and quiet escapes, food/health/tool checks and automatic resupply, labeled storage chests with item rules,
construction protection, optional waste cleanup, and Overworld–Nether travel for ancient debris.
Conflict behavior defaults to retreat; optional defensive retaliation, stealth stalking and a named-player hunt mode (melee, crystal or anchor) are available.
The optional [client network and localhost atlas](NETWORK.md) shares sightings/exploration between bots and provides browser commands, trust management, and global or individual settings. Keys are generated on first client launch. Connected bots recognize allies, coordinate hunt roles and mining veins, and scout while idle. The map renders sampled terrain in top-down/isometric views with base markers.

The autonomous controller also supports saved offline-player watch/hunt orders, automatic respawning, elytra scouting/search travel, shared logout/death/waypoint marks and periodic private status reports. See [NETWORK.md](NETWORK.md#autonomous-client-orders-flight-and-reporting).

## Install

Requires Minecraft 26.3, Fabric Loader 0.19.5 or newer, and Java 25. [Mod Menu](https://modrinth.com/mod/modmenu) 21.0.0 is optional and adds a settings screen under **Mods > HiveMind > Configure**.

Download `hivemind-0.1.0.2.jar` from [Releases](https://github.com/Swishhyy/HiveMind/releases/latest) and put it in your `mods` folder. Remove previous development mod jars before this first release. Packages, mod ID and configs now use `hivemind`; old configs are not migrated.

For an independent controller, download and extract the entire Windows controller zip and run `HiveMindController.exe`, or run `java -jar hivemind-controller.jar` on Java 17+. See [CONTROLLER.md](CONTROLLER.md) for remote hosting, session/reconnect controls, optional combat logging and automatic updates.

To build it yourself, run `./gradlew build`; the jar is written to `fabric/build/libs/`.

## Documentation

- [MINING.md](MINING.md): setup, commands, settings, limits and gameplay checks for mining, logistics, companions, stalking and combat.
- [NETWORK.md](NETWORK.md): the bot network, localhost atlas, autonomous orders, flight and reporting.
- [USAGE.md](USAGE.md): HiveMind's own commands (`#goto`, `#mine`, `#follow`, `#build`, `#farm`, `#explore`, `#set` and more), which all still work.

## Credits and license

HiveMind is a fork of [Baritone](https://github.com/cabaletta/baritone) by leijurv, Brady and its contributors, and is distributed under the same [LGPL-3.0](LICENSE) license.

