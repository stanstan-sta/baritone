# Changelog

## 2026-04-27 — Multi-Queue Rewrite, Smelting, Craft Command & Three-Tier Block Finder

### New Commands
- **`#craft`** — Finds the nearest crafting table via the block cache, paths to it, and opens the 3×3 crafting grid. Supports queued mode.
- **`#task smelt <item> [count|all] [furnace|blast_furnace|smoker]`** — Auto-finds nearest furnace, paths to it, loads fuel (coal from inventory) and input, monitors smelting progress, collects output, and loops until the target count is reached.
- **`#task chest <x> <y> <z> withdraw|deposit <item> [count|all]`** — Direct-position container transfers.
- **`#task enqueue <block>`** / **`#task queue`** / **`#task clear`** — FIFO multi-task queue with inspection and cancel.

### Three-Tier Block Finder (`findNearestBlock`)
All auto-find methods (`runInteractPlanByBlockName`, `runContainerPlanByBlockName`, `runSmeltPlan`) now use a three-tier lookup:
1. **Cache lookup** (`getLocationsOf`) — instant for previously scanned blocks.
2. **Repack loaded chunks + retry** — triggers `WorldScanner.repack()` then retries cache.
3. **Direct world scan** (`scanChunkRadius`) — same mechanism as `#mine`, works when no cached data exists.

### Multi-Queue Architecture Fix
Per-plan execution context (`targetPos`, `containerAction`, `bedCandidates`, smelt state) has been moved from `TaskPlanProcess` instance fields into `TaskPlanImpl` itself. Each queued plan carries its own isolated context, so queuing Plan A (interact with chest at pos1) followed by Plan B (deposit items at pos2) no longer corrupts Plan A's state.

### Smelt Workflow (8-Step Plan)
```
FIND_FURNACE → PATH → INTERACT → AWAIT_FURNACE_MENU → LOAD_FURNACE → MONITOR_SMELT → COLLECT_OUTPUT → CLOSE_CONTAINER
```
- Smart fuel detection: scans player inventory for coal, loads one piece per batch.
- Exact-count smelting with automatic batch loop-back (skip fuel reload on repeat).
- 60-second monitoring timeout per batch.

### Wire-Format Logging
Plan completion and failure emit structured log lines:
- `[Baritone] Task complete: <label>`
- `[Baritone] Task failed: <label> - <outcome>`
Designed for LLM bridge consumers to parse task outcomes.

### Build
- Full Gradle `fabric:build` verified: **BUILD SUCCESSFUL** (Java 21, Fabric 1.21.11, ProGuard optimization).

### Files Changed
| File | Change |
|------|--------|
| `ITaskPlanProcess.java` | +69 lines — added `runSmeltPlan`, `createSmeltPlan`, `runContainerPlanByBlockName`, `createContainerPlanByBlockName`, `createSleepPlan` |
| `TaskPlanProcess.java` | ~1047 lines refactored — three-tier finder, smelt plan factory + 5 tick handlers, per-plan state migration, queue auto-advance |
| `TaskPlanImpl.java` | +21 lines — added per-plan context fields (`targetPos`, `containerAction`, `bedCandidates`, smelt state) |
| `TaskCommand.java` | +43 lines — added `executeSmelt()`, `executeChest()`, `executeEnqueue()`, queue inspection subcommands |
| `CraftCommand.java` | +10 lines — new command registration, queue-aware mode |

## 2026-04-22

- Improved `#task chest deposit/withdraw` behavior for container item transfers.
- Added explicit support for `all` / `max` to mean "move every matching item".
- Enhanced task transfer slot selection to choose better item stacks and handle duplicate matching stacks.
- Changed requested-count behavior so missing quantity now fails cleanly instead of silently stopping.
- Added pathing-specific camera smoothing to reduce harsh look changes during movement.
