# Client network and local atlas — Fabric 26.3

One Minecraft client hosts a small relay. Up to 16 other clients connect to it, share visual player reports and visited chunks, and keep a local history. The local webpage also controls commands, trust and settings for one bot or the group. The Minecraft server does not need a plugin. This is part of the Fabric 26.3 mod.

Each client generates its own persistent network key on its first launch, before joining a world. Existing configured keys are preserved. You no longer need to generate a key before hosting. **Update every connected client to this build:** control messages use protocol version 4.

## Connect your bots

On the client that will host:

```text
#network host
#network web
```

Use `#network key` to show the automatically generated host key. Click the key itself or **[Copy key]** to copy it, or copy it from the webpage's Settings tab. Other bot clients can join in one command:

```text
#network join 192.168.1.50 <host-key>
```

Replace the example IP with the host computer's LAN/VPN address. Use `127.0.0.1` for clients on the same computer. Each Minecraft client needs its own instance folder. The relay uses TCP **28766** by default; custom-port syntax is `#network join <host> <port> <host-key>`. Existing `#network key <key>` followed by `#network join <host> [port]` still works. For connections over the Internet the host must be reachable through its firewall/router or a VPN.

All peers must have the same key and be playing on the same Minecraft server. Server-address matching ignores case and an explicit default `:25565` port. If bots use different aliases or LAN/public addresses for the same Minecraft server, set the same unique label on each:

```text
#network scope my-survival-server
```

`#network scope auto` restores address-based matching. Use distinct labels for different Minecraft servers. The host client must stay in a world for its relay to run. Clients retry connections with backoff and re-share their own saved observations after a host restart. Network mode/endpoints persist for the current server; `#network off` disables automatic reconnection.

## Open the local webpage

`#network web` prints a clickable localhost address. Click it in chat to open the atlas in your browser, or click **[Copy link]** to copy the complete address, including its random access token. Open it on the computer running that client. The displayed address is kept short; both actions include the full access token. Key/link messages always appear in chat so their actions remain available even with toast logging enabled. The default web port is **28767**; `#network web 28768` uses another port, useful when multiple clients run on one computer. The token changes when the page server restarts; run the command again for the current address.

The page includes:

- A pannable, zoomable map of the chunks bots actually visited, with separate Overworld/Nether/End or custom-dimension views.
- Recent bot positions and each player's last reported position, with source bot, age and coordinates.
- Frequent sighting areas grouped into 64 × 64 block regions, with return visits, observed time and reporting bots. Simultaneous reports and replay do not count as extra visits.
- Player/source search, sighting-age filters, layer toggles, clickable reports and markers, fit/zoom controls, and pausable automatic updates.

The visited-chunk layer records where bots walked. The terrain layer separately renders block colors and surface heights from already-loaded chunks, with top-down and isometric views, height shading, pan/zoom and markers. It never loads/generates terrain to fill the map. Old coverage has no terrain pixels until a client samples those chunks again. A player marker is a dated observation, not a guarantee the player is still there. Frequent areas are observations, not proof of base ownership.

The webpage has four tabs:

- **Atlas** retains the exploration map, player reports and frequent sighting areas.
- **Commands** selects one bot or all connected bots, shows live bot health/food/task status, and offers Stop, Pause, Resume, Deposit and Status buttons. Task forms support mining, stalking, named hunts and escorts. A command line supports the normal trusted bot-command set. Action responses show each bot's acceptance or error; an accepted task is not a claim that the task has finished.
- **Trust** adds or revokes whisper controllers by online name or exact UUID. It shows the actual trusted players on the selected bots. A global choice also applies to future bots; individual choices can differ.
- **Settings** edits 93 typed movement/mining/survival/stalking/combat/companion/sharing settings. Choose All bots for a saved group default, or select one bot for an individual override. Mixed values are shown clearly. Applying a setting/player to All bots replaces individual overrides for that same value. Unsaved field edits survive automatic refresh.

Group defaults and individual overrides live on the host and sync to newly connected bots. Settings/trust choices can be saved for an offline bot; quick commands are only sent to online bots and are never saved for later replay. Per-bot responses distinguish queued requests, applied changes and failures. Managed web settings are persisted per server and restored when that client returns; leaving the server restores its ordinary runtime settings. In-game commands and Mods > HiveMind > Configure remain available.

The page binds only to `127.0.0.1`, requires its separate random access token for reads and writes, and blocks unrelated browser origins/hostnames. Only authenticated form requests enter a bounded client-thread queue; HTTP/socket workers never modify Minecraft state. Clients sharing the host's network key belong to the same control group; keep that key with your bot clients. The Trust tab manages human whisper controllers separately from network membership. The page contains no external scripts, fonts, trackers or hosted service. `#network web off` stops it. With the peer network off, the webpage can control its own local bot.

## Teamwork and idle scouting

Connected clients automatically recognize authenticated group members as teammates, separately from human whisper trust. They do not randomly select teammates for stalking, accept teammate hunt targets, retaliate against teammate hits, or count teammates as extra opponents/observers. Nearby damage to an ally can trigger the configured flee/defend response. Group membership never grants human whisper permissions.

Bots exchange fresh structured task intent; only the host issues team plans. Multiple miners reserve separate ore veins (six-block separation), keep claims while travelling, and use different exploration headings. A contested claim waits rather than canceling or blacklisting the ore. Explicit release is immediate; disconnected claims expire with a clock-skew grace period. A freshly started relay waits 14 seconds before issuing claims that could overlap its previous session. No task/claim history is replayed.

Hunters sharing a target choose one explosive attacker; support bots stay outside its blast radius. With melee, one engaged bot has the close attack lane and others hold a nearby support distance, taking over when that bot withdraws. Sweeping attacks and strikes through teammates are avoided. Detonation still checks every bystander. Watchers yield crowded stations deterministically and prefer separate viewpoints without continuously orbiting.

`botAutoRoam=true` is the default. With no foreground task, queued job or open screen, the bot scouts unexplored directions, avoids other scouts' destinations, and records the same terrain/sightings as other work. Care and conflict handling retain priority. Roaming does not break or place blocks; planned bucket falls remain available. `#roam on`, `#roam off` and `#roam status` are also available in trusted messages and web commands. `#stop` parks roaming persistently for that Minecraft server; `#resume` or `#roam on` releases it. With automatic respawning enabled, death does not park otherwise-enabled roaming; a manual park remains in effect. Pause continues to pause everything.

While connected to a world, switching to another window does not automatically open Minecraft's pause menu. This keeps roaming, tracking, flight and queued commands running in the background without changing the saved `pauseOnLostFocus` option. Escape still opens the usual pause menu, and `#pause` / `#stop` still hold or cancel bot work. Inventory, container, settings and death screens retain their existing behavior.

Focus-loss gameplay check: start roaming or a named tracking order, switch to another application for at least 30 seconds, and confirm movement/status updates continue. Repeat during a supplied elytra flight and in a singleplayer world. Then check that Escape still pauses singleplayer, an open inventory still holds idle roaming, `#pause` holds work until `#resume`, and `#stop` stays stopped while unfocused.

Scouts mark visible containers, beds and workstations as **Possible base**, including coordinates, source and evidence. These may be generated structures; ownership is not inferred. Markers appear on the terrain map and in Bases & built sites. `#stalk base <player>` confirmation also creates a confirmed map marker. Weak automatic evidence cannot overwrite a manual confirmation.

Terrain/site history is bounded to 4,096 terrain chunks, 512 base/site markers and 512 waypoints/logout/death marks for up to 30 days, in `bot-network-terrain.properties`. Tiles are 16 × 16 block colors/heights, not a texture-pack renderer or complete server-side Dynmap. Nether samples below the roof show a local cavern surface. Clients share terrain through the same authenticated relay and replay it to late joins. Terrain API reads require the webpage token and matching origin/hostname. Mapping runs in small batches on the client thread; persistence and network/HTTP work run off-thread.

## How observations work

When networking or the viewer is enabled, a client samples once a second. It records other living, non-spectator players only while it has an actual clear visual sight line; invisible players are skipped. This includes bystanders seen during stalking, mining or other work. Stationary reports refresh every five seconds by default; material movement may update after two seconds. At most 32 player reports are made per sample. The bot's actual position records visited chunks.

Observation messages carry the reporting UUID/name, observed player UUID/name, dimension, coordinates and original observation time. The relay preserves this provenance, deduplicates replay and sends retained exploration history to new peers. Clients upload their own observations. The transport authenticates both ends using the shared key and encrypts bounded messages. Frames from another connection, replayed/tampered data, invalid coordinates, oversized input and a client's attempt to impersonate another reporting UUID are rejected.

Live bot states, control requests and acknowledgements travel separately from exploration history. A client submits a request to the host, which validates the command or typed setting before dispatching it. Only the authenticated host can dispatch commands or policies. Requests expire, and IDs prevent duplicate command execution within a live session. One-shot commands and policy-application messages are excluded from history replay. Reconnecting sends fresh saved policy state, without replaying old commands. Existing authenticated-whisper control stays available.

Recent peer sightings expire after `networkSightingTTLMS=120000` by default. If a stalking target disappears, a bot can check a newer peer report in its current dimension before its saved places/local search. Fresh reports of the same stationary position do not keep restarting the route. Reports do not interrupt an established watch position. A bot still has to travel there and visually reacquire the player. Peer observations do not add visits, dwell time or confirmed bases to the separate local player-memory file.

The atlas saves asynchronously approximately every 30 seconds and when leaving a server. It retains up to 30 days, **8,192 visited chunks**, **4,096 visual observations**, and **256 recent bot identities**; older entries are removed when those bounds are reached. Replay is idempotent across restarts. History is saved under the server/save's Baritone directory in `bot-network-atlas.properties`, alongside `bot-network.properties` connection settings. The host's group rules use `bot-network-controls.properties`; each client's managed settings use `bot-web-settings.properties` and its existing `bot-control.properties` trust list. The automatically generated client key is stored once in the instance's `baritone/bot-client.properties`. No network is enabled until configured.

## Commands and config

`#net` is an alias for `#network`. These commands are local, including access to the shared key and page address.

| Command | Effect |
| --- | --- |
| `#network key [key\|new]` | Show/copy the automatically generated key, set a copied key, or rotate it with `new` |
| `#network host [port]` | Host the relay; default 28766 |
| `#network join <host> [port] [host-key]` / `connect` | Connect in one command and automatically reconnect |
| `#network status` / `peers` | Inspect runtime state or direct authenticated connections |
| `#network sightings [player]` / `marks` | List recent peer marks with their sources and ages |
| `#network web [port\|off]` / `map` | Print/start the localhost page or stop it |
| `#network off` / `disconnect` | Disable networking for this server while keeping history |
| `#network scope <label\|auto>` | Match Minecraft-server aliases or restore address matching |
| `#network bind <address>` | Set the relay bind address; default `0.0.0.0` |
| `#network reload` | Reload local connection/page configuration |
| `#network clear` | Clear this client's atlas and marks; peers retain their histories and can share them again |

These options are also in **Mods > HiveMind > Configure** and `#set`:

| Setting | Default | Effect |
| --- | --- | --- |
| `networkShareSightings` | `true` | Share this bot's new and retained visual player reports |
| `networkShareExploration` | `true` | Share this bot's positions and visited chunks |
| `networkSightingIntervalMS` | `5000` | Stationary observation refresh, bounded 2–60 seconds |
| `networkSightingTTLMS` | `120000` | Live search-mark lifetime, bounded 10 seconds–10 minutes |
| `networkRenderSightings` | `false` | Optional cyan in-world boxes at recent shared positions in the current dimension |

`botAutoRoam` can also be changed in the Settings tab or Mod Menu config. Connection modes, ports, keys, scope and viewer enablement are local per-server configuration managed by the commands above. Sharing toggles affect this bot's outgoing observations; an active relay still forwards its peers' reports, and the local viewer can still record local observations.

## Autonomous-client orders, flight and reporting

This fork runs its own controller and does not require Meteor, Impact or another utility client. `botAutoRespawn=true` requests a normal respawn after `botRespawnDelayMS=2000` while a death screen is present. Requests are paced with backoff while the death screen remains; manual pause prevents them. It does not automate joining a server or passing a login/queue screen.

`#kill <player> [auto|melee|crystal|anchor]` and named `#stalk <player>` orders are stored per server in `bot-tracking-order.properties` when `botPersistentTracking=true`. A player who has never been observed can be named while offline: the order waits for a real online profile before binding their UUID. Known offline targets wait near an observed logout area or saved place when available. No global player coordinates are available. Online searches and live combat attempts may back off, but their saved order remains. An observed target death completes a hunt; disappearance or logout never counts as a kill.

Saved orders can resume after respawn, disconnect/reconnect or client restart, once a living client has loaded the same server and has suitable health and food. An unarmed hunt can observe, but the ambush still requires usable combat supplies. Other foreground work retains priority. Missing care/supplies are checked again every 30 seconds while an order is pending. Revoking a whisper issuer's trust prevents their saved order from restarting. `#kill stop`, `#stalk stop`, `#stop` and `#forcecancel` remove the order; `#pause` holds it and `#resume` releases it. Starting replacement work removes the previous tracking order. Death does not automatically recover dropped equipment or provide a replacement combat kit; existing registered food resupply still applies. Mining queues keep their existing explicit-resume behavior.

`botUseElytra=true` connects native flight navigation to roaming, player searches, long approaches to visible hunt targets, `#goto`/path goals and following. Default departure distance is `botFlightMinimumDistance=128`; existing explicitly saved thresholds remain in effect. Roaming uses continuous survey legs; `botFlightRoamDistance=768` is the threshold for a longer commute back to a distant next survey point. Normal departures need usable wings (at least 32 durability, or more if the native minimum is higher) and ordinary rockets: the larger of `botFlightRocketReserve=8` and `elytraMinFireworksBeforeLanding`, plus two for departure. A carried elytra can be equipped through normal inventory swaps; the previous chest armor is restored after landing if its stored slot still matches. No item is left on the cursor by this exchange.

`botAerialSearch=true` searches for an online but unloaded target by surveying terrain after checking fresh sightings and saved places. Roaming uses the same expanding square pattern. Adjacent flight strips default to `botFlightSurveySpacing=128`, with a desired `botFlightSurveyHeight=48` above the locally known surface. The native navigator still avoids obstacles and controls flight pitch; entity scanning works independently of the camera direction. Unsupported flight or exhausted supplies continue the expanding route on foot. Spacing and height are configurable globally or per bot on the web settings page and through the mod config.

Survey checkpoints in `bot-surveys.properties` preserve origin, heading and progress per server, dimension, target and travel mode. Restarting or finishing a search pass no longer starts the same 512-block search over again. New distant visual evidence can redirect a target search to a new anchor. Survey legs prefer unmapped terrain, while retaining mapped candidates when necessary to leave a known area. This marks no chunks as visited until the bot actually travels there or receives terrain data. Terrain sampling now processes up to four loaded tiles per 100 ms within a four-millisecond budget checked between tiles, filling the sides of flight paths more quickly. It never requests unloaded chunks.

`botEntityAwareness=true` provides ESP-style acquisition for nearby players and mobs from entity data already loaded by the client. It refreshes at most ten times per second and retains up to 256 nearest contacts within `botEntityAwarenessRange=512` (64–512 blocks). Actual server tracking distance can be shorter. Stalk/search and elytra pursuit can use a contact behind cover; a missing, dead or removed entity cannot stay a live contact. Contacts are transient and cleared on world changes. Turning this setting off makes player acquisition visual-only. Entity awareness does not reveal unloaded players, bypass attack reach or line of sight, or predict their base. Occluded contacts are explicitly reported as entity contacts; visual sightings, base evidence, logout evidence and shared map player reports keep their existing sight-line requirements. This is bot awareness, with no new screen or ESP rendering overlay.

Autonomous takeoff prepares its flight path before jumping, can walk to a nearby clear launch column, and can deploy while already falling. A failed takeoff rejects that launch spot briefly and retries after three seconds while walking, rather than imposing a two-minute flight lockout. Unsupported native platforms, inaccessible launch spots and missing supplies use walking. Temporary care/task interruptions request a landing instead of abruptly deleting airborne navigation. Manual pause/stop still overrides flight. Nearby mobs alone do not force an airborne hunt to land through the pursuit logic; normal resource safety landing remains in effect.

`botElytraCombatEscape=true` prioritizes flight from unwanted player conflict and a hunt's withdrawal. `botFlightEscapeDistance=2048` sets the escape leg (512–8192 blocks); another leg is planned if an observed pursuer remains nearby. Escape can use wings down to 10 durability (still above a higher native minimum), and uses a final reserve of two rockets instead of the ordinary travel reserve. It still needs two departure rockets above that reserve. It does not stop to trade melee hits while it can launch, and can continue ground retreat while the native escape path is being prepared. Empty supplies or blocked takeoff fall back to ground retreat.

`botElytraPursuit=true` lets an ordered hunt chase a flying opponent from a current entity contact, including inside the old 128-block landing radius and when the opponent is mainly above the bot. Pursuit updates the destination with a short bounded movement lead while retaining the solver/rocket boost state. It follows only current loaded entity contacts or their last position for up to eight seconds; losing contact returns to ordinary search. A grounded opponent is approached more closely before landing. Normal non-hunting stalking keeps its stand-off behavior. Flight time does not consume the suspended ground-combat approach timeout. Needed combat supplies displaced by rocket use are restored to the hotbar before the next approach. The existing low-level elytra settings remain available; this uses normal gliding and rockets and requires no world seed.

`botFightHostileMobs=true` lets an armed bot clear ordinary hostile or attacking mobs with a carried sword/axe, selecting stronger weapons and respecting normal reach, line of sight and attack cooldowns. Unarmed, low-health or overwhelmed bots retreat; dangerous mobs such as wardens/creepers and player conflict retain retreat priority. Passive animals and unprovoked neutral mobs are not selected by this behavior. Player hunting and optional escort defense use the same weapon selection, with axes preferred against raised shields.

Explosive combat advances through already-confirmed preparation steps in the same tick, then sends at most one placement/use/attack in a client tick. It still waits for server-confirmed blocks and observed crystal entities. A confirmed obsidian base can be reused immediately if its current reach, clearance and blast estimates remain valid. `killExplosiveDelayTicks=0` is the new default; values above one intentionally pace actual actions. Nearby block updates prompt a faster recheck when combat temporarily falls back to melee. Friendly spacing, self-damage estimates and anchor dimension checks still apply.

Gameplay checks: search with a supplied elytra for an online target outside loaded range, confirm successive survey legs expand past 512 blocks, restart the client and confirm route progress continues, then test an occluded loaded target with entity awareness on/off and check that only actual sightings enter visual history. Try flat-ground and nearby-clear-column takeoff, a long `#goto`, flight to a moving target, an opponent taking off again during landing, low-rocket escape, pursuit losing sight, carried-wing armor restoration, armed/unarmed mob encounters, and crystal placement after a server-confirmed obsidian block under latency. Actual in-game timing and flight performance still require Minecraft testing.

When an active target leaves the online roster after a firsthand sighting within two minutes, the map gains a **Logout** mark. It records the last observed position and observation time separately from the logout-detection time. Leaving render distance alone does not create a logout mark. This is evidence of an apparent logout near a last seen location, not a verified exact logout block. Bots also record their own death locations. These marks share and replay through the authenticated relay, separately from sighting/base evidence.

Create independent waypoints with `#mark add <name> [x y z]`, or danger marks with `#mark danger <name> [x y z]`. `#mark list` lists IDs; `#mark remove <UUID>` removes a mark on the bot that created it. The Atlas tab has a waypoint form, a separate Waypoints & logouts list/layer, and copy/remove actions on selected marks. The form uses one online bot in the currently displayed dimension. Deletions retain bounded tombstones so ordinary stale replay cannot restore a removed mark. All marker history expires after 30 days.

`botPeriodicReports=true` sends short private status reports every `botReportIntervalMS=300000` (five minutes). Reports include the task/wait reason, dimension, bot coordinates, health and food. `botReportAllTrusted=true` selects all currently online trusted controllers; set it false to report only to a saved tracking order's issuer. Reports are paced behind direct command replies, expire after three minutes, and recheck recipient trust and online status at send time. They never use public chat. These settings appear in the Mod Menu configuration and global/per-bot web settings.


## Reactive combat, portable supplies and bucket clutches

`botKillAura=true` adds close-range automatic aim/attacks against the current engaged hunt target or the opponent already selected for active mob/defense combat. It does not turn a quiet stalk into a hunt or select unrelated nearby players. The extra attack check runs after the normal movement update has sent its rotation, uses a charged sword/axe swing with ordinary three-block reach and line of sight, and shares one attack budget with crystal breaks. Connected bots and trusted players retain their target exclusions. Disabling it leaves the existing command-driven combat in place.

`botAutoCrystal=true` adds reactive crystal handling to an engaged crystal/auto hunt. It can break an already-observed useful crystal instead of waiting to build another, and rechecks pending placement/charge/break stages after the current aim update. It retains server block/entity confirmation, blast exposure, ally spacing and dimension checks. One actual explosive action per client tick remains the limit, including the reactive pass. `#kill <player> auto` selects supported carried crystal/anchor supplies with melee fallback; `#kill <player> crystal` explicitly requests crystals.

`botAutoTotem=true` equips a carried totem into the offhand across autonomous work and replaces a consumed one on the next eligible inventory tick. Inventory swaps use an empty cursor and the ordinary player inventory; a manual screen, pause/stop or open container holds them. During a hunt, `killUseTotems=false` still opts out. Offhand shield use is released before replacement. Carried shulkers can supply spare totems when the bot has a safe place to unpack, rather than trying to open a box inside the fight.

`botUseShulkers=true` lets the bot read a carried shulker's item contents and use it for food, spare totems, rockets/wings, a water bucket, mining tools/building blocks and the current hunt's weapon/crystal/anchor loadout. Item-fetch jobs also try carried boxes before registered storage. All shulker colors are supported. It requires `allowInventory`, `allowPlace` and `allowBreak`. The bot waits for stable ground away from immediate combat, places a box with lid clearance, waits for placement and contents confirmation, withdraws useful stacks, closes it, mines that exact box, and waits for inventory pickup before resuming. Inventory room for the recovered box is reserved; supplies are not dumped and unrelated placed containers remain protected. A mining-zone limit does not block recovery of the bot's own placed box; explicitly protected areas remain protected.

The outstanding box location and confirmed ownership are stored per server in `bot-portable-box.properties`. A nearby confirmed box can be recovered after an interruption/restart; an ambiguous unconfirmed placement is reported for manual recovery. A protected, missing, obstructed or unreachable box reports coordinates and backs off while retaining its recovery record. The bot will not place another box over an outstanding one. Stop/pause still stops work; it does not erase the recovery location. Nested boxes, ender-chest retrieval and opening a box during active PvP are not included.

Planned bucket falls now use the shared clutch controller. `botEmergencyBucketClutch=true` also arms it for unexpected falling while the bot is working. It estimates imminent landing from velocity and loaded solid terrain, equips a carried water bucket, aims early and attempts normal bucket use on every reachable tick. The direct use call avoids waiting for the ordinary repeated-right-click delay or an old crosshair result, including before movement crosses the remaining placement window. Its water is reclaimed only after the fall has been caught or the bot has landed; natural sources are left alone. It skips Nether water clutches, gliding, riding, unloaded/unsuitable landing surfaces and manual screens. Server latency, blocked placement and diagonal high-speed falls still need live testing.

Gameplay checks: pop successive offhand totems while roaming and during a hunt; verify pause and container holds; compare reactive melee/crystal aim with the toggles off/on; restock from a colored shulker with a full hotbar, near-full inventory and a partial merge; cancel after placement and resume recovery; test denied placement/breaking and disconnect/reconnect without removing unrelated boxes. Test planned and unexpected falls at low and high velocity with the water bucket both on the hotbar and in the main inventory, then verify pickup happens after the catch and Nether/gliding exclusions remain effective.
