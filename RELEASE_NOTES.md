**HiveMind 0.1.0.8** — direct hunts and tracking priority after respawn for **Minecraft 26.3 / Fabric**.

- **#kill** immediately pursues a live loaded target, including ESP entity contacts behind cover. It no longer waits for a viewing distance, quiet period, the target looking away, a rear approach or an invisibility potion. Actual melee hits and explosive placements retain their reach, line-of-sight and teammate checks.
- Fresh local contacts interrupt search retry delays. Shared location hints guide hunts toward the reported position instead of stopping in a stalking ring. Stalking can approach an occluded contact's viewing range before choosing a concealed vantage; its stationary watching and gaze-scatter behavior remain separate.
- Saved hunt/watch orders reserve the bot during respawn and retry delays. Background roaming cannot launch a different exploration route before the saved order resumes. Inventory assessment and necessary care still finish first.
- Hunts no longer pause movement merely because the teamwork plan is unavailable, and melee hunters can close in together subject to friendly-fire checks. Explosive support spacing remains.
- Landing searches seed the player air space even when a destination is inside terrain, recognize snowy mountain ground and leaf canopies, retain bounded scan progress, and back off exhausted scans. Landing messages are throttled; real ground contact releases flight without waiting for an exact waypoint.
- Obsolete killWatchMinimumMS/killWatchMaximumMS settings are retained only for compatibility and no longer affect hunts.

Update with **#update install** and restart Minecraft. The standard **hivemind-0.1.0.8.jar** filename/update channel and separate locked **hivemind-player-0.1.0.8.jar** player channel remain compatible. Protocol remains 7. Automated checks and release packaging are verified; live multiplayer pathfinding still needs gameplay testing.
