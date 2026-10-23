# HiveMind standalone controller

The controller runs without Minecraft and owns the network relay, coordination, maps, saved global/individual settings and trust, plus combat-log/reconnect decisions. Each client executes movement and combat every game tick. The dashboard and other bots keep running if one bot disconnects or crashes.

## Start

Windows: download `hivemind-controller-0.1.0.10-windows-x64.zip`, extract the **whole folder**, and run `HiveMindController.exe`. Its Java runtime is included; keep the neighboring folders beside the executable. The console prints a clickable dashboard address containing its access token. Open it, go to **Settings**, and copy the **Bot connection command** into each client's chat once while joined to the intended server.

Linux, macOS or a server with Java 17+: `java -jar hivemind-controller.jar`. The same dashboard and connection command work there. Minecraft clients still require Java 25 and Fabric 26.3.

By default the dashboard listens on `127.0.0.1:28767`, and the encrypted bot relay on TCP `28766`. The first start creates a persistent identity, network key and web token. Data is stored in `hivemind-controller-data` in the working directory. Stop with Ctrl+C; maps and policies are saved during shutdown and every 30 seconds.

## Remote controller

Use the controller machine's IP or host name as `--advertise`, so its copied connection command points bots to that machine:

```sh
java -jar hivemind-controller.jar --advertise 100.64.0.10 --web-bind 0.0.0.0 --public-url http://100.64.0.10:28767 --scope anarchy
```

For an HTTPS reverse proxy, set `--public-url https://your-controller.example` and forward to the configured web port, preserving its Host header. The page allows only the configured host/origin and requires its bearer token for data and actions. A private network such as Tailscale also works. Run one controller and data directory per Minecraft server, with a separate `--scope` label. All clients must copy the matching connection command.

Other flags: `--data <directory>`, `--bind <relay-address>`, `--port <relay-port>`, `--web-bind <address>`, `--web-port <port>`. `--help` lists them. Settings/Trust pages manage bot behavior globally or individually; connection flags are startup options.

## Sessions and combat logging

The **Sessions** page can reconnect a bot, log it out for 60 seconds, or leave it offline until requested. The control connection remains up at Minecraft's menu and reconnects to the controller on client restart using its saved link. A client must have joined the server and connected to this controller once to establish that link.

- `#session logout` — stay offline until requested.
- `#session logout 60` — log out temporarily and reconnect after the wait.
- `#session reconnect` — connect to the last multiplayer server.
- `#session status` — inspect the session.

Under **Settings → Sessions**, enable `botCombatLog`, choose `botCombatLogHealth` (default 8 health points), `botCombatLogWaitMS` (default 60000), `botAutoReconnect`, and `botReconnectDelayMS`. Combat logging is off until enabled. The controller requires recent damage, a nearby untrusted non-teammate player, and health at/below the threshold. Reconnect attempts back off to five minutes rather than flooding a server. Manual logout overrides automatic reconnect. A running queued mining job is remembered when requesting logout and resumes five seconds after rejoining, if the same job still heads its queue and autonomous work is not manually paused.

Combat logging sends a real disconnect; a server may keep an avatar present or reject subsequent joins. It cannot undo server-side combat tags. Reconnecting uses the client's already authenticated Minecraft account. It does not sign into Microsoft accounts or launch a closed/crashed Minecraft JVM. Keep clients running, or use your existing launcher/process supervisor to restart crashed game instances. Saved named tracking orders remain in the client; map history, policies and reconnect decisions remain in the controller. Raw combat clicks and live entity references stay in Minecraft.

## Automatic updates

The standalone controller **0.1.0.10 and newer checks its own update channel on every launch, before opening the relay/dashboard**. A newer controller is downloaded and verified by SHA-256, size, version and controller identity. A separate helper waits for the current process to exit, backs up the old JAR, installs the replacement and starts it with the same working directory and command-line options. This works with both the JAR and the portable Windows executable; the Windows runtime includes the helper's Java launcher. Keep the installation folder writable. Saved maps, keys, settings and trust are untouched.

If a check/download fails or the internet is unavailable, the installed controller starts. Failed replacement retains the installed version and retries on a later launch rather than looping. The previous JAR is kept beside the installation in `.hivemind-controller-updates/previous.jar`. Startup updates replace the controller application JAR; a future Java runtime/launcher change requires replacing the portable folder.

**One-time upgrade:** older controllers do not have this updater. Download/extract 0.1.0.10 once, retaining your existing data folder and startup options; subsequent controller updates happen on launch.

Controller, bot and player versions are independent. See each release’s component list for its individual versions. An unchanged component retains the exact published file, checksum and update manifest, so a bot-only release does not download/restart the controller. Shared runtime/network changes may require multiple components to update. Existing bot/player filenames and update-channel URLs remain unchanged.


Each instance asks once on its first server join whether to enable automatic downloads from **Swishhyy/HiveMind GitHub Releases**, for Fabric 26.3 only. You can change it per client or across connected clients on **Updates**, or use:

```text
#update status
#update check
#update install
#update auto on
#update auto off
```

Updates verify size, SHA-256, mod identity, Minecraft version and build commit. A separate helper waits for that game process to exit, backs up the existing mod in `hivemind/updates/backups`, then replaces it for the next launch. It never replaces the running jar or forces Minecraft to close. The standalone controller is a separate download; replacing its files requires stopping it. Its durable data directory can be reused.

## Building and checks

`./gradlew build` builds the Fabric mod and embeds its updater helper. `python3 scripts/build-distributions.py controller` builds the independent Java 17 controller. `bash scripts/check-controller.sh` exercises two actual network clients, authenticated HTTP commands, durable policies/history, offline control, combat-log decisions, backoff and the updater installer. `bash scripts/check-mining-policies.sh` runs the autonomous behavior policy checks.

GitHub Actions builds the Windows executable with a bundled runtime. A successful main-branch build publishes a new four-part version from `gradle.properties`, together with its public update manifest and checksums. Existing releases are left unchanged. Bump `mod_version` to publish the next version.

### Player sharing and map orders

Install `hivemind-player-<version>.jar` on your own client, then use the connection command from the controller's **Settings** page. The player edition starts in manual sharing mode and cannot switch to bot mode, even with an existing bot configuration. Your position and observations feed the shared map and the bots; HiveMind overlays and automation stay off on your screen. Nearby loaded players can be detected through ESP without displaying it to you. Those contacts expire after six seconds and are search hints, not recorded visual sightings or base evidence. Nothing scans entities that the server has not sent to your client.

The controller labels player clients separately and does not send them bot work, settings, trust policies or automatic session actions. The player JAR refuses `#client bot`. Install the standard `hivemind-<version>.jar` on bot accounts; it defaults to bot mode and retains existing local role choices during upgrades.

Right-click terrain on the atlas, select an online bot in that dimension and choose **Go here** or **Explore here**. Explore accepts a radius of 32–2048 blocks and surveys a finite grid by elytra or on foot. Commands are pinned to the clicked dimension, and will be rejected if the bot changes dimension before executing. Unmapped clicks are labeled approximate. Keyboard: focus the map and press Shift+F10 to command its center; Escape closes the menu.

Manual equivalents: `#scout go minecraft:overworld 100 -200`, `#scout explore minecraft:overworld 100 -200 256`, and `#scout status` / `#scout stop`.

### Reliable operations and itineraries (0.1.0.9)

The controller's Commands page explains each client's **Task**, **Blocker**, **Next** action and flight decision. It also shows round-trip latency, estimated bytes sent/received, queued frames and recovery count. Stale reports are labeled. Traffic totals refer to the current connection, not lifetime usage.

On the map, right-click and enable **Append to queue** to chain go/explore/check tasks. **Check here** visits a point and watches for ten seconds. Select one bot in **Map task queue** to reorder/remove entries, pause/resume/clear them, or append a **storage return** which deposits before advancing. Remaining tasks survive restart; an interrupted active survey restarts its current task, rather than pretending it was completed. Dimension mismatches hold the queue until resolved. Routes and search radii can be toggled in the map legend.

Commands: `#scout add explore minecraft:overworld 100 -200 256`, `#scout add check minecraft:overworld 150 -250`, `#scout add return`, `#scout list`, `#scout pause`, `#scout resume`, `#scout remove <id>`, `#scout move <id> <position>`, `#scout clear`. A new direct go/explore/check command replaces the itinerary; appending preserves it. Other manual work parks an active itinerary instead of silently discarding it.

Before long-distance travel, expedition checks use carried shulkers and registered storage for food, building blocks, rocket reserves, totems and replacements for nearly broken worn armor. Healthy matching spare armor is equipped through normal inventory actions. Item components and enchantments are retained. Storage shortages show a reason and a retry cooldown while the bot continues with available supplies; ordinary survival and flight reserve checks still apply. Configure `botExpeditionSupplies`, `botExpeditionRockets` (32), `botExpeditionTotems` (1), and `botStuckRecovery` through Settings or `#set`. Only actual accessible stock is used.

Player-mode clients can request `#meet`, `#backup`, or `#bring rockets 64` (`#bring <item> [quantity]` supports other items). Selection requires a healthy idle bot in the same dimension; a bot that becomes busy before the request arrives rejects it. Meet goes to your requested location. Backup meets and escorts under existing defense settings. Deliveries require the existing handoff confirmation. Your character remains manual and its overlays stay off.

Bots searching for the same target partition survey directions among live team members, share firsthand sightings and short-lived ESP hints, and spread support positions toward likely escape paths. ESP hints appear separately on the map and expire after six seconds; they do not create base evidence or historical sighting hotspots. Terrain scanning slows while stationary, unchanged tiles are suppressed, and network congestion defers terrain sampling while control messages keep priority.

Upgrade controller and clients together: this version uses network protocol 7. Keep existing controller data when replacing the standalone application.

### Long-range loadouts and bot bases

The default reserve bands are **2–4 stacks of rockets**, **1–2 stacks of allowed food**, **two usable elytras total**, and **3–4 totems**. Equipped usable wings and the offhand totem count toward those totals. Refills begin below the lower bound, rather than after every item consumed. Whole-stack transfers aim near the upper bound without requiring a cursor-full partial stack operation. Loadout settings expose the rocket/food upper targets, wing count and totem target.

Carry separate shulkers full of totems, food, rockets and wings. When safe and grounded, the bot opens a useful box, restocks, breaks and recovers that same box, then selects another category's box as needed. A five-second quiet window prevents repeated placements around approaching threats. One inventory slot is reserved for box recovery. Existing placement/break/inventory permissions still apply. If supplies are available in carried boxes during flight, the bot can deliberately land away from nearby untrusted players to refill; emergency escape takes priority. Ordinary deposits retain portable boxes and loose reserve items. Explicit `deposit all` retains its existing override behavior.

At your supply area run `#botbase set` (alias `#bot base set`). It saves the anchor and discovers supported loaded containers in a default 16-block radius; use `#botbase set 24` for a wider scan (maximum 32). Newly registered containers become supplies storage with base labels and priority 50. Existing rules remain intact. Copper variants, normal/trapped chests and barrels use the existing deposit adapter.

Use `#botbase resupply` to request a full reserve refill without replacing current work. The bot tries carried boxes and registered stock, or travels to a configured base to discover its containers. It returns to the interrupted location and resumes its task. Registered Nether portals are required for cross-dimension trips; arbitrary unloaded chests are never scanned remotely. Stock shortages and insufficient inventory room are reported rather than assumed successful.

Use `#botbase status`, `#botbase scan`, or `#botbase clear`. Clearing the anchor leaves registered chests intact; use `#deposit remove <id>` for containers you no longer want used. To configure one shared location for several bots, select **All bots** in the controller and send `#botbase set minecraft:overworld 100 64 -200 16`, substituting your coordinates. Bot base commands affect autonomous clients; player-mode clients remain manual.

Death recovery compares inventory after respawn rather than assuming all items dropped. `#recover status` shows pending, retained, lost, partial or empty observations; `#recover now` targets missing items only. Auto requires the server's full player inventory sync and a settling period. Set `botDeathInventoryMode` to `auto` (default), `keep`, or `drop` under controller **Companions**, or through `#set`, for unusual server plugins. Observations are per server/save and reassessed after each death; they do not claim access to a remote gamerule.

Separate client editions: the standard bot filename remains `hivemind-<version>.jar`, with the existing `hivemind-26.3-update.properties` update URL. The locked manual player edition is `hivemind-player-<version>.jar`, using `hivemind-player-26.3-update.properties`. Both support the same first-join automatic-update consent and `#update` controls. Downloads and install plans verify the edition as well as the checksum; updates replace the installed JAR in place and preserve its filename. Old standard JARs continue updating through the existing channel, including saved manual-role configurations. To adopt the locked player package, manually replace the old mod once, then its own updater handles later releases. Install only one edition per instance. The controller is still a separate application.

### Configure bots from the player client

Connect to the same controller/network, then use these on your manual player account. Replace `all` with a bot name or its UUID from `#bots list` to target one bot.

```text
#bots list
#bots all base set
#bots SeizureMike base status
#bots all chest add supplies
#bots all chest list
#bots all chest remove
#bots SeizureMike base resupply
```

`base set [radius]` uses **your** feet, with a default radius of 16 (4–32). `chest add [rule]` and `chest remove` use **your** selected supported chest or barrel; copper variants are supported. Rules: any, ores, building, junk, supplies, disabled. Omitting the rule preserves an existing container's rule. Removals match location instead of each bot's storage ID. Both accept explicit `<dimension> <x> <y> <z>` coordinates; `base set` optionally takes a radius afterward. `base status/scan/clear/resupply` and `chest list [page]` also work. Clearing a base keeps its registered chests; remove those individually.

Bot responses appear in your chat. Only fresh online autonomous bots are selected, and offline commands are not queued for later. Bots reject edits during an active supply trip. Loaded containers are checked immediately; unloaded locations are verified when a bot arrives. These commands configure the bots without enabling automation on your character. Update **both** the issuing player client and receiving bots to 0.1.0.9 or newer.

### Supply chests containing filled shulkers

Bot clients 0.1.0.10 and newer can refill expedition reserves from filled shulkers inside registered storage. From the connected player client, stand at your base and use `#bots all base set 24`. Look at each supply chest/barrel and use `#bots all chest add supplies`, then request `#bots all base resupply`. The bot first uses useful carried boxes and loose stock. When required supplies remain inside a stored shulker, it takes one useful box, waits for a safe grounded spot, places/opens it, takes the needed supplies, and breaks/recovers that box before checking stock again. The recovered box stays in the bot's inventory as portable stock, including remaining contents.

This works with colored/named boxes containing compatible rockets, food, usable elytras and totems. No box naming scheme is required. Leave clear floor/lid space around storage and at least two free main-inventory slots on the bot. Breaking, placing, inventory movement and shulker use are always available to bots. Chest transfers must be confirmed before unpacking starts. A failed unpack, outstanding recovery or lack of inventory room stops additional box withdrawals and reports why. A trip is bounded to eight storage-box withdrawals. Existing reserve targets and interrupted work are preserved.

Controller 0.1.0.10 is reused unchanged for this client update. Bot/player channels and filenames remain compatible; update the clients with `#update install` and restart Minecraft. Live placement/permissions still depend on the server.

### Armor, ender reserves and emergency restocking

Clients 0.1.0.11 use the same boxed-stock route for emergency food/tool resupply and expedition loadouts. Starving bots can take food shulkers from registered chests; failed automatic care retries after 30 seconds when `botAutoResupply` remains enabled. Clients 0.1.0.12 remove the old breaking, placing, inventory and shulker switches entirely; saved disabled values are ignored automatically.

`botAutoArmor` selects usable carried vanilla armor by material, protection enchantments and durability, skips binding-cursed candidates, and preserves displaced pieces. `botRestockArmor` adds one usable piece per armor slot to expedition supplies, counting worn pieces once. Missing or worn-out pieces can come from loose storage or filled shulkers. With `botUseElytra` enabled, safe grounded bots proactively equip usable carried wings even before they have rockets; committed ground hunts prefer a carried chestplate. Actual takeoff still requires usable wings, rockets and launch space. Inventory transactions and flight prevent competing gear swaps.

`botUseEnderChests` enables each bot's **own private ender inventory** as a supply source. Existing placed ender chests within eight blocks are discovered when resupply begins; base scans and `#bots all chest add supplies` also register them. Bots only open server-loaded, reachable access points and inspect the contents sent to their own account. Ender chests can supply armor, ordinary supplies and useful filled shulkers. They are withdrawal-only, including when `deposit all` is requested; no shared map or controller inventory is treated as another account's ender stock. This feature uses placed ender chests; it does not place or mine access blocks.

Storage positions are checked after arrival with a brief loading grace period. If the location still contains another block, the message names the dimension, coordinates and observed block instead of dismissing distant storage as missing. Check `#bots all chest list` against those coordinates if necessary.

Use **Survival** settings for the armor/ender options. Breaking, placing, inventory movement and shulker use have no disable switches. Controller and both client editions are 0.1.0.12 in bundle 0.1.0.13; update the controller too because its settings catalogue and saved-policy migration have changed. Restart the standalone controller to install its update, and use `#update install` plus a Minecraft restart for clients. Player clients remain manual.

Old capability switches are removed from chat settings, config forms and the dashboard. Existing global and individual controller policies migrate automatically while preserving other settings and trust. Bots still choose when an action is useful, wait for suitable shulker placement and recover their own boxes before borrowing another. Manual player clients remain manual.
