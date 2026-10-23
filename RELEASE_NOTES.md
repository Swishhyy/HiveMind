**HiveMind 0.1.1.0 — Fabric 26.3 bot, player and standalone controller**

- Bot storage observations now record server-confirmed chest and private ender stock, including items visible inside filled shulkers. The dashboard displays recent stock, age, and stale state; observations never authorize a transfer without opening storage again.
- Four configurable supply kits cover roaming, mining, Nether trips and hunts. Use `#kit list`, `#kit set <profile> <item> <count>`, `#kit reset <profile>` and `#kit now`.
- `#why` and the Commands page show a bounded, persistent trail of tasks, blockers, navigation recovery, portal trips and supply failures. The controller retains the last operations report when a client goes offline.
- Observed Nether portal entrance/exit pairs are saved per world. Known destinations select a recorded route; `#portal status` lists recent links and `#portal clear` removes links in that dimension.
- The controller JAR has a separate local `--supervise <profile.properties>` mode for one foreground Minecraft process per bot. Nonzero exits retry with capped backoff, while intentional zero exits stop. See [CONTROLLER.md](CONTROLLER.md#local-game-supervisor).

All three components advance to 0.1.1.0. Existing bot and player JAR filenames and updater channels remain unchanged. This bundle changes the bot/client network operations payload; update the controller and clients together.

Validation: Java 17 policy/controller checks and release packaging locally; Fabric Java 25 and Windows builds through CI. Real server inventory, portal and crash recovery behavior still require gameplay testing.
