**HiveMind 0.1.0.4** — autonomous reliability and team operations for **Minecraft 26.3 / Fabric**.

- **Task explanations:** controller cards show the current task, blocker, next action, flight decision and recovery count.
- **Stuck recovery:** stalled or looping navigation escalates from replanning to a short detour and a temporary walking fallback. Failed takeoffs and unconfirmed explosive placements are shared as temporary warnings; path searches receive an immutable cost snapshot to prefer other routes. Intentional stationary watching and open menus do not trigger recovery.
- **Persistent map queues:** append go, explore and check tasks from the right-click menu; view routes and search radii, reorder or remove entries, pause/resume/clear the queue, and append a storage return. Checks wait ten seconds; storage returns deposit before advancing. Queues hold at dimension mismatches and preserve remaining work across restarts.
- **Team search sectors:** bots searching for the same target divide survey directions into non-overlapping sectors. Nearby combat support uses separate interception positions rather than piling into the same approach.
- **Expedition supplies:** before distant scouting, roaming or flight, bots check food, building blocks, rockets, totems and worn armor durability. They fetch from carried shulkers or registered stock, return to the departure point and resume. Healthy spare armor can replace nearly broken matching armor. Missing stock has a retry cooldown; it never creates items or allows unavailable flight supplies to be ignored.
- **Clear report evidence:** the atlas distinguishes recent/old visual sightings from six-second ESP hints, with source and last-seen time. ESP hints never become historical sighting hotspots or base evidence.
- **Player assistance:** `#meet`, `#backup`, and `#bring rockets [quantity]` select the nearest healthy idle bot in your dimension. Bring accepts other item IDs; deliveries retain handoff confirmation. Backup meets and escorts using the configured defense policy. Busy bots and manual player clients are excluded.
- **Connection/performance:** per-client RTT, last-packet age, estimated traffic, queued frames and stale telemetry. Commands retain priority over terrain, stationary clients scan less often, unchanged terrain is not retransmitted, and congested connections pause terrain sampling.

New settings: `botStuckRecovery`, `botExpeditionSupplies`, `botExpeditionRockets` (32), `botExpeditionTotems` (1). These are available through existing configuration commands and controller settings.

Use `#scout list/pause/resume/clear`, `#scout remove <id>`, and `#scout move <id> <position>` for queue management. `#scout add go/explore/check ...` appends work; `#scout add return` appends a storage visit.

Update **the controller and all clients together** to 0.1.0.4 (network protocol 7), then refresh the dashboard. Keep the controller data directory. The client updater installs the mod; the standalone controller application still needs replacing separately. Player mode continues to suppress in-game overlays and reject bot automation.
