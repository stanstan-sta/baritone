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

import baritone.api.task.ITaskPlan;
import baritone.api.task.TaskOutcome;
import net.minecraft.core.BlockPos;

/**
 * Executes multi-step {@link ITaskPlan}s, enabling LLM agents to orchestrate
 * compound actions (e.g. scan → path → interact → verify) with explicit,
 * per-step state tracking and deterministic completion codes.
 *
 * <h3>Death policy</h3>
 * If the player dies while a plan is running the plan is immediately aborted,
 * the current step is marked {@code FAILED} with outcome
 * {@link TaskOutcome#PLAYER_DIED}, the overall plan outcome is set to
 * {@code PLAYER_DIED}, and all pathing / input state is cleared.
 *
 * <h3>Cancellation</h3>
 * Calling {@link #cancelPlan()} at any time will stop the active plan,
 * marking remaining steps as {@code CANCELLED} and the overall outcome as
 * {@link TaskOutcome#CANCELLED}.
 */
public interface ITaskPlanProcess extends IBaritoneProcess {

    /**
     * Execute a pre-built {@link ITaskPlan}.
     *
     * <p>If another plan is currently running it is cancelled first.
     *
     * @param plan the plan to execute; must not be {@code null}
     */
    void runPlan(ITaskPlan plan);

    /**
     * Convenience factory: build and immediately run a "sleep in the nearest
     * reachable bed" plan.
     *
     * <p>The returned plan object can be inspected at any time for step-level
     * status and the final outcome.
     *
     * @return the plan that was queued; never {@code null}
     */
    ITaskPlan runSleepPlan();

    /**
     * Convenience factory: build and immediately run an "interact with the
     * block at {@code target}" plan.
     *
     * @param target the block to interact with; must not be {@code null}
     * @return the plan that was queued; never {@code null}
     */
    ITaskPlan runInteractPlan(BlockPos target);

    /**
     * Returns the plan that is currently executing, or {@code null} if this
     * process is idle.
     */
    ITaskPlan currentPlan();

    /**
     * Cancel any active plan, marking it with
     * {@link TaskOutcome#CANCELLED} and clearing all pathing state.
     *
     * <p>No-op if no plan is running.
     */
    void cancelPlan();
}
