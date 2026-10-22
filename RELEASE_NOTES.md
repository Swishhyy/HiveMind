**HiveMind 0.1.0.3** — map orders and a quiet player sharing mode for **Minecraft 26.3 / Fabric**.

- Right-click the atlas to choose an online bot in that dimension and send **Go here** or **Explore here** with a 32–2048 block radius. Height-aware picking follows the displayed terrain. Existing work is paused, and exploration uses elytra when available or walks a finite survey route.
- Use **#client player** on your own Minecraft instance. It shares your location, loaded terrain, visual sightings and short-lived ESP player detections with connected bots. Your character stays under manual control with no HiveMind world overlays, automatic combat, movement, inventory actions, respawning or reconnecting.
- Player clients are labeled on the map and fleet list, excluded from bot command selectors and protected from broadcast commands and network policies. Only a local **#client bot** enables automation again; previous work stays parked until resumed.
- ESP contacts expire after six seconds and act as search hints. They do not become historical sightings or base evidence, and they never allow bots to attack through walls.

Use the same Fabric mod JAR for both roles. After **#client player**, copy the connection command from controller **Settings** and run it in that client. Your existing network sharing settings still apply.

Update **all clients and the standalone controller** to this version together: live contact sharing requires network protocol 6. Install the mod with **#update install**, then close and relaunch Minecraft. Replace the standalone controller application with this release while keeping its existing data/configuration directory, restart it, and refresh the webpage. The client updater does not replace the standalone controller.
