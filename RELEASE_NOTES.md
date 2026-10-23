**HiveMind bundle 0.1.0.12 — clients/controller 0.1.0.11**

- Emergency hunger/tool resupply now uses category-aware loose and boxed stock, fixing the older loose-only path shown as Survival WAIT. Automatic care retries after 30 seconds when enabled. Incomplete withdrawals report failure rather than success.
- Armor reserves fill missing/worn vanilla helmet, chestplate, leggings and boots from ordinary storage or shulkers. Safe inventory automation equips carried armor using material, protection and durability ranking, skips binding-cursed candidates, and preserves displaced gear. Grounded noncombat bots proactively equip carried usable wings without requiring rockets first; flight requirements remain enforced.
- Placed ender chests are private withdrawal-only access points. Bots discover nearby loaded ones or use registered/base access points, opening their own account's ender inventory for supplies, armor and useful shulkers. No automatic ender deposits, access-block placement or mining.
- Dashboard now exposes **allowInventory** and the new **botAutoArmor**, **botRestockArmor**, **botUseEnderChests** toggles. Shulker errors name the exact disabled settings, and loadout status includes inventory automation and armor counts.
- Storage block validation waits until arrival plus a brief loading grace period; actual mismatches report the location and observed block. Existing registrations are not silently deleted.

Update/restart the controller and clients for the shared settings catalogue. All components changed and are versioned 0.1.0.11; updater URLs and bot filenames remain stable, protocol stays 7. Automated policy, controller and Fabric checks cover the release; live multiplayer gear swaps, storage permissions and placement still need gameplay testing.
