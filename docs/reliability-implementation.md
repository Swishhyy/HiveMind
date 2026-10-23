# Reliability and coordination implementation

Target: Fabric 26.3, all 33 approved additions. Bot, player and controller keep independent component versions.

The client owns immediate movement, combat and inventory actions. The controller assigns durable fleet intent and reservations. No saved inventory click or historical observation is replayed as an action.

- [ ] 1. Shared action ownership
- [ ] 2. Interruption and resume checkpoints
- [ ] 3. Stable decision thresholds
- [ ] 4. Progress-based changing retries
- [ ] 5. Specific failure detection
- [ ] 6. Verified completion
- [ ] 7. Recoverable inventory transactions
- [ ] 8. Shulker manifests
- [ ] 9. Shared stock reservations
- [ ] 10. Base health and alternatives
- [ ] 11. Supply delivery jobs
- [ ] 12. Maintenance and production
- [ ] 13. Trip resource budgets
- [ ] 14. Shared flight plans
- [ ] 15. Server-condition adaptation
- [ ] 16. Remember launch and landing sites
- [ ] 17. Portal reliability
- [ ] 18. Death loop protection
- [ ] 19. Versioned acknowledged commands
- [ ] 20. Controller outage policy
- [ ] 21. Capability-aware assignments
- [ ] 22. Observation confidence and age
- [ ] 23. Map behavior regions
- [ ] 24. Manual player-client contract
- [ ] 25. Frozen-client watchdog
- [ ] 26. Persistent fleet pause and recall
- [ ] 27. Gradual updates with rollback
- [ ] 28. Component compatibility
- [ ] 29. Recoverable state snapshots
- [ ] 30. Runtime performance budgets
- [ ] 31. Detailed explanations
- [ ] 32. Repeatable failure scenarios
- [ ] 33. Unattended tests and metrics

Validation must include real client compilation, headless policies, controller integration, network permissions, session recovery, and packaging. Live gameplay/overnight testing requires Minecraft instances and must be reported separately.
