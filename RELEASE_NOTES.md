**HiveMind 0.1.2.0 — coordinated recovery and long-running fleets**

- One action owner coordinates movement, looking, hands, inventory and item use. Emergency survival/combat can preempt storage and equipment work without competing clicks.
- Keep the original task through combat, supply trips, portals, disconnects and restarts. Confirm inventory and task results from actual server observations. Unconfirmed delivery drops are retained for inspection instead of being sent twice.
- Count known carried shulker contents, safely inspect unknown boxes, reserve shared storage, rank healthy bases, request spare supplies from idle teammates, and perform bounded maintenance using registered stations and carried ingredients.
- Budget longer trips, share committed flight phases across tasks, keep launch/landing/portal failure memory, adapt waits to observed server latency, and regroup after repeated deaths.
- Ordered, durable command acknowledgements distinguish accepted, started, blocked, completed and cancelled work. Fleet pause and `#recall` survive reconnects; recall returns to a known base and holds the original job until `#resume`.
- Add protected, dangerous, exploration, building and storage regions from commands or the map. Distinguish fresh ESP hints, firsthand observations and historical sightings. Bound terrain/entity work per tick.
- A local supervisor detects frozen bot ticks as well as crashes. Staged fleet updates grant one restart at a time, verify a healthy canary, and pause on failures. Updated clients and the controller retain verified previous builds and hold failed releases after rollback.
- Protocol 8 negotiates with protocol 7 clients/controllers. New coordination features activate between capable peers; player clients remain manual. Bot filenames and existing component update channels stay compatible.
- `#doctor status/export/retry` explains current recovery and exports an allowlisted support bundle. A read-only soak monitor records fleet metrics without network keys or launcher information.

Bot, player and controller each advance to **0.1.2.0** because all three components changed. Their versions remain independent for future releases. Older clients need one normal restart to load the new supervisor/update capabilities. Player clients continue installing after manual closure.

Validation includes policy/controller/network/packaging/updater regressions, old/new encrypted interoperability, Minecraft 26.3 compilation, and a **simulated** eight-hour policy soak (576,000 ticks, 5,939 combat preemptions, eight checkpoint reloads). Live flight, inventory permissions and overnight server operation still require gameplay testing; the included monitor supports that verification.
