# Reliability and coordination implementation

Target: Fabric 26.3, all 33 approved additions. Bot, player and controller keep independent component versions.

The client owns immediate movement, combat and inventory actions. The controller assigns durable fleet intent and reservations. No saved inventory click or historical observation is replayed as an action.

- [x] 1. Shared action ownership
- [x] 2. Interruption and resume checkpoints
- [x] 3. Stable decision thresholds
- [x] 4. Progress-based changing retries
- [x] 5. Specific failure detection
- [x] 6. Verified completion
- [x] 7. Recoverable inventory transactions
- [x] 8. Shulker manifests
- [x] 9. Shared stock reservations
- [x] 10. Base health and alternatives
- [x] 11. Supply delivery jobs
- [x] 12. Maintenance and production
- [x] 13. Trip resource budgets
- [x] 14. Shared flight plans
- [x] 15. Server-condition adaptation
- [x] 16. Remember launch and landing sites
- [x] 17. Portal reliability
- [x] 18. Death loop protection
- [x] 19. Versioned acknowledged commands
- [x] 20. Controller outage policy
- [x] 21. Capability-aware assignments
- [x] 22. Observation confidence and age
- [x] 23. Map behavior regions
- [x] 24. Manual player-client contract
- [x] 25. Frozen-client watchdog
- [x] 26. Persistent fleet pause and recall
- [x] 27. Gradual updates with rollback
- [x] 28. Component compatibility
- [x] 29. Recoverable state snapshots
- [x] 30. Runtime performance budgets
- [x] 31. Detailed explanations
- [x] 32. Repeatable failure scenarios
- [x] 33. Unattended tests and metrics

Validation must include real client compilation, headless policies, controller integration, network permissions, session recovery, and packaging. Live gameplay/overnight testing requires Minecraft instances and must be reported separately.

Implementation: one action arbiter, durable intent/interruption and handoff journals, verified transfers/completion, refill bands and flight commitments, alternate bases and supply delivery, bounded maintenance/production, trip budgets and site memory, ordered receipts and policy revisions, protocol 7/8 negotiation, fleet hold/recall, manual-player guarantees, local watchdog, canary updates and verified rollback, map regions, bounded scans, diagnostics, regression scenarios and monitoring.

Validation: Minecraft 26.3 API compilation; policy/network/controller/packaging/update checks; real encrypted old/new interoperability; deterministic simulated eight-hour policy soak (576,000 ticks, 5,939 combat preemptions, eight checkpoint reloads); authenticated monitor and secret-exclusion checks. CI runs the complete Fabric build and Windows package. The simulation is not a live overnight test. `scripts/soak-monitor.py` collects real deployment observations when clients are available.
