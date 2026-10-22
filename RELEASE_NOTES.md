**HiveMind 0.1.0.1** — elytra takeoff fix for **Minecraft 26.3 / Fabric**.

- Keep the launch phase active as the bot rises and falls. A jump changing the bot's Y position no longer sends it walking back to the original launch block before it can activate its elytra.
- Use a normal jump, release, then a fresh jump press during descent to deploy the wings. Prepare the flight path before jumping and cancel the ground path before setting launch input.
- Prefer a simulated, collision-checked rocket boost during initial glide activation, rather than accepting a short unboosted path that drops the bot back onto the ground.
- Require sustained gliding with actual travel or climb before marking takeoff successful. Brief wing deployments and repeated jumps no longer disable the failed-launch timeout.
- Select launch sites using standing dimensions, open overhead clearance and a clear departure lane. Avoid launching beneath Overworld tree canopies; search farther for open ground.
- Recover from a stalled takeoff, stop boosting into the obstruction, and avoid that nearby launch area for a minute. Ground contact after a completed flight starts a fresh departure near the bot instead of returning to its old launch point.

Update with `#update install`, or use **Updates → Download and install** in the controller. Close and relaunch Minecraft after downloading. The existing controller remains compatible; replacing it is optional for this client fix.

[Setup guide](https://github.com/Swishhyy/HiveMind/blob/main/CONTROLLER.md). Automated takeoff regression checks cover jump/release/glide handoff, airborne launch latching, real flight progress, failed-glide recovery and landing far from the launch point. Live gameplay still needs verification on your server.
