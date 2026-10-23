**HiveMind bundle 0.1.0.11** — filled-shulker stock at bot bases.

- **Bot/player clients 0.1.0.10** can now fetch a useful filled shulker from registered storage, confirm the withdrawal, unpack needed expedition supplies at a quiet grounded spot, and recover the box before continuing. Recovered boxes stay in inventory with any remaining contents.
- Uses carried shulkers and loose chest supplies first. Colored/named boxes need no naming convention; their actual contents determine usefulness. Normal/trapped/copper chests and barrels retain their existing registration and interaction paths.
- Requires two free main-inventory slots and shulker/inventory/placement/breaking permissions. Failures report blocked placement, missing inventory room, no confirmed supply progress or outstanding recovery. Stops additional withdrawals if a placed box still needs recovery; no items are discarded. Each trip takes at most eight storage shulkers.
- Prevents background portable resupply from racing an active chest transaction. Reserve targets and interrupted tasks remain intact. Use `#bots all base resupply` after registering supply chests.
- **Controller stays 0.1.0.10**, with its exact existing JAR, portable Windows archive and update manifest reused. No controller download/restart is needed. Protocol remains 7; existing bot/player update URLs and filename conventions remain unchanged.

Automated inventory-policy/recovery tests and Fabric checks cover this release. Live multiplayer chest placement and server permissions still need an in-game test.
