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
 * Process that navigates to a specific block position and performs a
 * right-click (use) interaction on it.
 *
 * <p>Outcome codes returned by {@link #lastOutcome()}:
 * <ul>
 *   <li>{@code SUCCEEDED} – interaction was accepted by the game</li>
 *   <li>{@code UNREACHABLE} – no path could be calculated to the block</li>
 *   <li>{@code INTERACTION_FAILED} – reached the block but right-click
 *       produced no response within the timeout window</li>
 *   <li>{@code PLAYER_DIED} – player died during the operation</li>
 *   <li>{@code CANCELLED} – cancelled by the caller</li>
 *   <li>{@code TIMEOUT} – interaction retry limit reached</li>
 * </ul>
 *
 * <p>This process is death-aware: if {@code onPlayerDeath()} fires while the
 * process is running, the outcome is set to {@code PLAYER_DIED} and all
 * pathing/input state is cleaned up immediately.
 */
public interface IInteractBlockProcess extends IBaritoneProcess {

    /**
     * Begin the interaction flow: navigate to {@code target} and right-click it.
     *
     * <p>Calling this while a previous interaction is still in progress will
     * cancel the current one and start fresh.
     *
     * @param target the block position to interact with; must not be {@code null}
     */
    void interactWithBlock(BlockPos target);

    /**
     * Returns the outcome of the most recent interaction attempt.
     *
     * @return the outcome, or {@code null} if no interaction has been started
     *         yet (i.e. {@link #isActive()} is {@code false} and the process
     *         was never triggered)
     */
    TaskOutcome lastOutcome();

    /**
     * Returns {@code true} once the current interaction has reached a terminal
     * state (success, failure, cancellation, or timeout).
     *
     * <p>Note that {@link #isActive()} returns {@code false} at this point too,
     * but {@link #lastOutcome()} will still hold the final outcome until the
     * next {@link #interactWithBlock} call.
     */
    boolean isFinished();
}
