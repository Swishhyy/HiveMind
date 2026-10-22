# HiveMind standalone controller

The controller runs without Minecraft and owns the network relay, coordination, maps, saved global/individual settings and trust, plus combat-log/reconnect decisions. Each client executes movement and combat every game tick. The dashboard and other bots keep running if one bot disconnects or crashes.

## Start

Windows: download `hivemind-controller-0.1.0.0-windows-x64.zip`, extract the **whole folder**, and run `HiveMindController.exe`. Its Java runtime is included; keep the neighboring folders beside the executable. The console prints a clickable dashboard address containing its access token. Open it, go to **Settings**, and copy the **Bot connection command** into each client's chat once while joined to the intended server.

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
