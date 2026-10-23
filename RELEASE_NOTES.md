**HiveMind bundle 0.1.0.13 — clients/controller 0.1.0.12**

- Breaking, placing, inventory movement and shulker use are built-in bot capabilities with no disable switches. Remove the old toggles from chat settings, config forms and dashboard controls, along with the obsolete break-exception list.
- Remove the matching gates across mining, pathing, building, storage-shulker acquisition/recovery, equipment swaps, totems, elytra supplies, bucket clutches and mining waste cleanup. Bots can use these capabilities as their tasks need them.
- Ignore old local/profile switches, migrate saved global and individual controller policies, clean client web-settings files, and tolerate retired fields from older policy senders. Other settings and trust persist; newly submitted retired switches are rejected.

Update/restart the controller and clients for the settings catalogue and saved-policy migration. Bot, player and controller components are version 0.1.0.12; the release bundle is 0.1.0.13. Updater channels and filenames keep their existing conventions. Contextual terrain/container protections, safe shulker placement/recovery, manual player role and ordinary task controls are preserved.

Validation: automated policy, controller, Fabric and Windows builds; public release hashes and updater-channel checks. Live Minecraft gameplay still needs testing.
