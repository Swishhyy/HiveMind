**HiveMind 0.1.0.2** — persistent world maps, stronger ESP, outward exploration and crystal fixes for **Minecraft 26.3 / Fabric**.

- Forward single-block server updates to the action listeners, independently of optional world-cache repacking. Obsidian placements, anchor placements/charges and shulker placements no longer wait for a confirmation that only the batch-update path delivered.
- Aim at visible parts of a crystal within normal attack reach. A lower edge blocked by obsidian or a shoreline ledge no longer hides the whole crystal. The actual sent aim is checked before attacking.
- Detect rejected item interactions and re-evaluate instead of waiting through a false confirmation timeout. Remember a confirmed obsidian base for reuse when the attack needs another attempt.
- Make idle scouts leave the starting area and follow widening outbound sweeps. `botFlightRoamDistance` sets the initial aerial departure distance (768 blocks by default); later sweeps keep advancing. Route progress and headings survive restart, and supplied aerial scouts continue through waypoints without a landing between legs.
- Sample a wider area of loaded terrain with a bounded tick budget, including biome grass, foliage and water tints.
- Keep isometric terrain at its observed height across zoom levels. Fill cliff faces across chunk boundaries and retain terrain detail in a shared cache, reducing seams, missing tiles and floating markers during zoom changes.
- Retain explored terrain in compressed region archives without the old 4,096-chunk eviction or 30-day terrain expiry. Keep a lightweight whole-map overview even when detail is not cached. Existing terrain data migrates on save.
- Add configurable terrain view range (`botTerrainViewRange`, default 128 blocks) and map range rings. Previously mapped terrain stays visible outside those rings.
- Prioritize players over mobs in a larger, per-tick awareness scan. Add clear, interpolated player ESP outlines through terrain, with configurable hostile-mob outlines and a 64–2048-block awareness setting. Actual detection remains limited to entities supplied by the server.
- Includes the elytra takeoff fixes from 0.1.0.1.

Update clients with `#update install`, then close and relaunch Minecraft. For the atlas changes on a standalone controller, replace its application with the **0.1.0.2** download, keep its existing data/configuration directory, restart it and refresh the webpage. The network protocol remains compatible.

Automated regression checks cover crystal visibility/reach, outbound route expansion and persistence, terrain height continuity, chunk-edge cliffs, persistence beyond 4,096 chunks, old-map retention, compressed region reloads, and player-first awareness in crowded areas. Live combat and flight still need checking on your server.
