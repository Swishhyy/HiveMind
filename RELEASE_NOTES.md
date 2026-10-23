**HiveMind 0.1.1.2 — stable roam flights and carried supplies first**

- Missing spare armor/wings and ordinary kit top-ups no longer order a landing immediately after takeoff. Carried refuel landings use nearly depleted loose consumables; the next departure waits for a critical carried refill.
- Count useful items inside carried shulkers when choosing supplies. Use those boxes first, including when they cover only part of the request, before deciding whether external storage is needed.
- Wait for a quiet grounded placement/opening opportunity instead of treating a delayed box-open attempt as absent stock. Preserve box recovery and stop on an unconfirmed/no-progress refill.
- Roaming does not return solely for optional armor or an extra backup wing. Automatic mining/combat reserve bands and a ten-minute unchanged-shortage cooldown reduce repeated storage trips. `#kit now` still requests the full configured loadout.

Bot and player clients advance to **0.1.1.2**. The controller remains **0.1.1.0** and reuses its existing release files and update manifest. Install the client update and restart Minecraft once to apply the fixes.

Validation covers reserve planning through launch consumption, helpful and partial carried boxes, optional gear, genuine shortages and retry timing. CI also builds against Minecraft 26.3 and runs the controller, policy and Gradle checks. Live flight and server inventory interactions still require gameplay verification.
