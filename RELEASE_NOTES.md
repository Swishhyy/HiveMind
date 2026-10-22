**HiveMind 0.1.0.6** — keep-inventory-aware death records for **Minecraft 26.3 / Fabric**.

- Death records start pending, rather than assuming the entire inventory dropped. After respawn, auto mode waits for the server's full player inventory and a stable settling period, then compares main inventory, armor, equipped elytra and offhand counts with the pre-death snapshot.
- Fully retained inventories skip gear retrieval. Partial-keep plugins record only missing item counts. Empty pre-death inventories do not count as evidence of keep inventory. Each new death is reassessed, so a changed gamerule or per-world plugin can change the result.
- Pending observations and outcomes persist per server/save. Unknown remote gamerules are never presented as confirmed. Roaming, tracking, queued jobs and automatic restocking wait for assessment; combat defense remains available.
- **#recover status** and controller cards show the observed inventory outcome. Existing **#recover now** retrieves recorded missing items; it refuses to start while respawn inventory is unconfirmed.
- **botDeathInventoryMode** defaults to **auto**. Set **keep** or **drop** in controller Companions settings (global or individual), or **#set botDeathInventoryMode keep/drop/auto**, for unusual servers that do not send normal inventory synchronization. Overrides are explicit operator choices, not learned gamerules.
- Manual player clients do not record or automate recovery. Cross-dimension companion travel waits for an active portal process instead of repeatedly starting it.

Update clients with **#update install**, then restart Minecraft. Replace the standalone controller while preserving its data directory and refresh the webpage. Protocol remains 7. Automated policy, Fabric build and packaging checks cover this release; live server behavior still needs gameplay testing.
