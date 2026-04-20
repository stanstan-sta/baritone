/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.process;

import baritone.api.task.TaskOutcome;
import net.minecraft.core.BlockPos;

/**
 * Process that locates an unoccupied bed, navigates to it, and initiates
 * sleep.  Returns structured outcomes suitable for LLM / bridge callers.
 *
 * <h3>Possible outcomes (see {@link #lastOutcome()})</h3>
 * <ul>
 *   <li>{@code SUCCEEDED} – player entered the sleeping state</li>
 *   <li>{@code NOT_FOUND} – no bed block found within the scan radius</li>
 *   <li>{@code UNREACHABLE} – bed found but no walkable path exists</li>
 *   <li>{@code NOT_NIGHT} – it is currently daytime; sleep is not allowed</li>
 *   <li>{@code OCCUPIED} – the closest reachable bed is occupied by another
 *       player</li>
 *   <li>{@code INTERACTION_FAILED} – reached the bed but right-click had no
 *       visible effect within the timeout window</li>
 *   <li>{@code PLAYER_DIED} – player died while the process was running</li>
 *   <li>{@code TIMEOUT} – interaction retry limit was exhausted</li>
 *   <li>{@code CANCELLED} – the caller explicitly cancelled the process</li>
 * </ul>
 *
 * <p>This process is death-aware: on {@code onPlayerDeath()} the outcome is
 * immediately set to {@code PLAYER_DIED} and pathing / input state is cleaned
 * up.
 */
public interface ISleepInBedProcess extends IBaritoneProcess {

    /**
     * Scan for any accessible bed within the default search range and attempt
     * to sleep in it.
     *
     * <p>If multiple beds are found the closest one is preferred.
     */
    void sleepInBed();

    /**
     * Attempt to sleep in the bed at a specific, already-known position.
     *
     * <p>The night-time check and occupied check are still performed; only the
     * world-scan step is skipped.
     *
     * @param bedPos exact position of one part (head or foot) of the target
     *               bed; must not be {@code null}
     */
    void sleepInBed(BlockPos bedPos);

    /**
     * Returns the outcome of the most recent sleep attempt.
     *
     * @return the outcome, or {@code null} if no attempt has been started yet
     */
    TaskOutcome lastOutcome();

    /**
     * Returns {@code true} once the current sleep attempt has reached a
     * terminal state (success, failure, cancellation, or timeout).
     */
    boolean isFinished();
}
