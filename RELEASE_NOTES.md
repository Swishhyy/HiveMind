**HiveMind 0.1.1.1 — keep Minecraft running during combat logout**

- Manual and automatic combat logout now use the same full world cleanup and menu transition as Minecraft 26.3's Disconnect button.
- Execute logout at the end of the tick so in-world logic finishes with its player/world intact. A queued logout is discarded if its connection has been replaced.
- Reconnect from the multiplayer server list after the configured wait, while retaining the independent controller connection. An indefinite manual logout continues to wait until reconnect is requested.

Bot and player clients advance to **0.1.1.1**. The controller remains **0.1.1.0** and reuses its existing release files and update manifest. Install the client update and restart Minecraft once to apply the fix.

Validation covers the actual session behavior with a headless client fixture: logout waits until the end of the tick, returns to the menu, respects its delay, reconnects from the server list, and leaves a replacement connection alone. CI also builds against Minecraft 26.3 and runs controller/policy/Gradle checks. A live Minecraft window is not available in the build environment.
