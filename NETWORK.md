# Client network and local atlas — Fabric 26.3

One Minecraft client hosts a small relay. Up to 16 other clients connect to it, share visual player reports and visited chunks, and keep a local history. The local webpage also controls commands, trust and settings for one bot or the group. The Minecraft server does not need a plugin. This is part of the Fabric 26.3 mod.

Each client generates its own persistent network key on its first launch, before joining a world. Existing configured keys are preserved. You no longer need to generate a key before hosting. **Update every connected client to this build:** control messages use protocol version 2.

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

Coverage records where bots walked. It is not a rendered terrain map, a scan of every loaded chunk, or knowledge of unexplored terrain. A player marker is a dated observation, not a guarantee the player is still there. Frequent areas are observations, not proof of base ownership.

The webpage has four tabs:

- **Atlas** retains the exploration map, player reports and frequent sighting areas.
- **Commands** selects one bot or all connected bots, shows live bot health/food/task status, and offers Stop, Pause, Resume, Deposit and Status buttons. Task forms support mining, stalking, named hunts and escorts. A command line supports the normal trusted bot-command set. Action responses show each bot's acceptance or error; an accepted task is not a claim that the task has finished.
- **Trust** adds or revokes whisper controllers by online name or exact UUID. It shows the actual trusted players on the selected bots. A global choice also applies to future bots; individual choices can differ.
- **Settings** edits 79 typed movement/mining/survival/stalking/combat/companion/sharing settings. Choose All bots for a saved group default, or select one bot for an individual override. Mixed values are shown clearly. Applying a setting/player to All bots replaces individual overrides for that same value. Unsaved field edits survive automatic refresh.

Group defaults and individual overrides live on the host and sync to newly connected bots. Settings/trust choices can be saved for an offline bot; quick commands are only sent to online bots and are never saved for later replay. Per-bot responses distinguish queued requests, applied changes and failures. Managed web settings are persisted per server and restored when that client returns; leaving the server restores its ordinary runtime settings. In-game commands and Mods > Baritone > Configure remain available.

The page binds only to `127.0.0.1`, requires its separate random access token for reads and writes, and blocks unrelated browser origins/hostnames. Only authenticated form requests enter a bounded client-thread queue; HTTP/socket workers never modify Minecraft state. Clients sharing the host's network key belong to the same control group; keep that key with your bot clients. The Trust tab manages human whisper controllers separately from network membership. The page contains no external scripts, fonts, trackers or hosted service. `#network web off` stops it. With the peer network off, the webpage can control its own local bot.

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

These options are also in **Mods > Baritone > Configure** and `#set`:

| Setting | Default | Effect |
| --- | --- | --- |
| `networkShareSightings` | `true` | Share this bot's new and retained visual player reports |
| `networkShareExploration` | `true` | Share this bot's positions and visited chunks |
| `networkSightingIntervalMS` | `5000` | Stationary observation refresh, bounded 2–60 seconds |
| `networkSightingTTLMS` | `120000` | Live search-mark lifetime, bounded 10 seconds–10 minutes |
| `networkRenderSightings` | `false` | Optional cyan in-world boxes at recent shared positions in the current dimension |

Connection modes, ports, keys, scope and viewer enablement are local per-server configuration managed by the commands above. Sharing toggles affect this bot's outgoing observations; an active relay still forwards its peers' reports, and the local viewer can still record local observations.
