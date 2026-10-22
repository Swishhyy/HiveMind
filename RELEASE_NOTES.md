**HiveMind 0.1.0.2** — crystal combat, outward exploration and atlas fixes for **Minecraft 26.3 / Fabric**.

- Forward single-block server updates to the action listeners, independently of optional world-cache repacking. Obsidian placements, anchor placements/charges and shulker placements no longer wait for a confirmation that only the batch-update path delivered.
- Aim at visible parts of a crystal within normal attack reach. A lower edge blocked by obsidian or a shoreline ledge no longer hides the whole crystal. The actual sent aim is checked before attacking.
- Detect rejected item interactions and re-evaluate instead of waiting through a false confirmation timeout. Remember a confirmed obsidian base for reuse when the attack needs another attempt.
- Make idle scouts leave the starting area and follow widening outbound sweeps. `botFlightRoamDistance` sets the initial aerial departure distance (768 blocks by default); later sweeps keep advancing. Route progress and headings survive restart, and supplied aerial scouts continue through waypoints without a landing between legs.
- Sample a wider area of loaded terrain with a bounded tick budget, including biome grass, foliage and water tints.
- Keep isometric terrain at its observed height across zoom levels. Fill cliff faces across chunk boundaries and retain terrain detail in a shared cache, reducing seams, missing tiles and floating markers during zoom changes.
- Includes the elytra takeoff fixes from 0.1.0.1.

Update clients with `#update install`, then close and relaunch Minecraft. For the atlas changes on a standalone controller, replace its application with the **0.1.0.2** download, keep its existing data/configuration directory, restart it and refresh the webpage. The network protocol remains compatible.

Automated regression checks cover crystal visibility/reach, outbound route expansion and persistence, terrain height continuity and chunk-edge cliffs. Live combat and flight still need checking on your server.
