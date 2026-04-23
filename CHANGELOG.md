# Changelog

## 2026-04-22

- Improved `#task chest deposit/withdraw` behavior for container item transfers.
- Added explicit support for `all` / `max` to mean "move every matching item".
- Enhanced task transfer slot selection to choose better item stacks and handle duplicate matching stacks.
- Changed requested-count behavior so missing quantity now fails cleanly instead of silently stopping.
- Added pathing-specific camera smoothing to reduce harsh look changes during movement.
