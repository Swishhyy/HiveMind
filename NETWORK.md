# Client network and local atlas — Fabric 26.3

One Minecraft client hosts a small relay. Up to 16 other clients connect to it, share visual player reports and visited chunks, and keep a local history. The Minecraft server does not need a plugin. This is part of the Fabric 26.3 mod.

## Connect your bots

On the client that will host:

```text
#network key
#network host
#network web
```

The first command generates and displays a random key. Click the key itself or **[Copy key]** in chat to copy just the key to your clipboard. Set it on your other bot clients:

```text
#network key <the-key-from-the-host>
#network join 192.168.1.50
```

Replace the example IP with the host computer's LAN/VPN address. Use `127.0.0.1` for clients on the same computer. Each Minecraft client needs its own instance folder. The relay uses TCP **28766** by default; a different port can be supplied to both `host` and `join`. For connections over the Internet the host must be reachable through its firewall/router or a VPN.

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

The page is read-only; use the normal in-game commands to control bots. It binds only to `127.0.0.1`, requires its own page token for data, and blocks unrelated browser origins/hostnames. It contains no external scripts, fonts, trackers or hosted service. `#network web off` stops it. A local viewer can run even while the peer network is off.

## How observations work

When networking or the viewer is enabled, a client samples once a second. It records other living, non-spectator players only while it has an actual clear visual sight line; invisible players are skipped. This includes bystanders seen during stalking, mining or other work. Stationary reports refresh every five seconds by default; material movement may update after two seconds. At most 32 player reports are made per sample. The bot's actual position records visited chunks.

Messages carry the reporting UUID/name, observed player UUID/name, dimension, coordinates and original observation time. The relay preserves this provenance, deduplicates replay and sends retained history to new peers. Clients upload only their own observations. The transport authenticates both ends using the shared key and encrypts its bounded messages. Frames from another connection, replayed/tampered data, invalid coordinates, oversized input and a client's attempt to impersonate another reporting UUID are rejected. This transport has no bot-command messages; existing trusted-whisper control remains separate.

Recent peer sightings expire after `networkSightingTTLMS=120000` by default. If a stalking target disappears, a bot can check a newer peer report in its current dimension before its saved places/local search. Fresh reports of the same stationary position do not keep restarting the route. Reports do not interrupt an established watch position. A bot still has to travel there and visually reacquire the player. Peer observations do not add visits, dwell time or confirmed bases to the separate local player-memory file.

The atlas saves asynchronously approximately every 30 seconds and when leaving a server. It retains up to 30 days, **8,192 visited chunks**, **4,096 visual observations**, and **256 recent bot identities**; older entries are removed when those bounds are reached. Replay is idempotent across restarts. History is saved under the server/save's Baritone directory in `bot-network-atlas.properties`, alongside local `bot-network.properties` connection settings. No network is enabled until configured.

## Commands and config

`#net` is an alias for `#network`. These commands are local, including access to the shared key and page address.

| Command | Effect |
| --- | --- |
| `#network key [key\|new]` | Generate/show the local key, set a copied key, or rotate it with `new` |
| `#network host [port]` | Host the relay; default 28766 |
| `#network join <host> [port]` / `connect` | Connect and automatically reconnect |
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
