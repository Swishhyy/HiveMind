HiveMind's first release: **0.1.0.0**, for **Minecraft 26.3 / Fabric**.

- Renamed the mod ID, Java packages/API, commands, configs, assets and build artifacts to HiveMind. Existing Baritone configs are not migrated. Remove the old Baritone/HiveMind development jar before installing this first release; keep one HiveMind mod jar in the instance's mods directory.
- Standalone controller with shared map, commands, trust, global/individual settings, persistent bot history and coordinated tasks. The dashboard and bot network survive an individual client disconnect/crash.
- Session controls and optional controller-directed combat logging, timed relogging and reconnect backoff. A running client remains remotely controllable on the game menu.
- Per-client first-join update prompt, manual/automatic updates and dashboard controls. Verified updates install after Minecraft closes, with a backup, for the next launch.
- Autonomous mining/logistics, shared exploration and sightings, persistent tracking orders, elytra search/escape, auto respawn, reactive melee/crystal combat, auto totems, portable shulker supplies and improved bucket clutch handling.

**Downloads:** `hivemind-0.1.0.0.jar` is the Fabric client mod. Windows users can extract the entire controller zip and run `HiveMindController.exe`; it includes Java. Other systems can run `java -jar hivemind-controller.jar` with Java 17+. The Minecraft client requires Java 25. Full setup: [controller guide](https://github.com/Swishhyy/HiveMind/blob/main/CONTROLLER.md).

The controller does not log into Microsoft accounts or start a closed Minecraft process. Clients execute real game actions, so movement/combat and server-specific disconnect behavior still require live gameplay verification.

Based on Baritone, retaining its LGPL-3.0 license and upstream contributor credits.
