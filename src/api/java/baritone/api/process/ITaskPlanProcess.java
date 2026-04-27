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
import baritone.api.task.ContainerAction;
import net.minecraft.core.BlockPos;

import java.util.List;

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
     * Convenience factory: build and immediately run a container manipulation plan.
     *
     * @param target the container to interact with; must not be {@code null}
     * @param action the action to perform on the container; must not be {@code null}
     * @return the plan that was queued; never {@code null}
     */
    ITaskPlan runContainerPlan(BlockPos target, ContainerAction action);

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

    // ─── Queue API ─────────────────────────────────────────────────────────

    /**
     * Append a plan to the FIFO queue. If no plan is currently executing,
     * the plan begins immediately; otherwise it waits until all
     * previously queued plans complete.
     *
     * <p>Unlike {@link #runPlan(ITaskPlan)}, this does <b>not</b> cancel
     * the currently running plan.
     *
     * @param plan the plan to queue; must not be {@code null}
     */
    void enqueuePlan(ITaskPlan plan);

    /**
     * Append every plan in the list to the queue in order.
     * Semantics are identical to calling {@link #enqueuePlan(ITaskPlan)}
     * for each element.
     *
     * @param plans the plans to queue; must not be {@code null}
     */
    void enqueuePlans(List<ITaskPlan> plans);

    /**
     * Remove all pending (not-yet-started) plans from the queue.
     * The currently executing plan, if any, is <b>not</b> cancelled.
     */
    void clearQueue();

    /**
     * Number of plans waiting in the queue (excludes the running plan).
     *
     * @return non-negative count
     */
    int queueSize();

    /**
     * Total number of plans including the currently executing plan.
     *
     * @return {@code queueSize() + (isActive() ? 1 : 0)}
     */
    int pendingCount();

    /**
     * Find the nearest cached position of the given block name and run an
     * interact plan on it. If no cached position is found the plan is not
     * started and {@code null} is returned.
     *
     * @param blockName the block registry name (e.g. {@code "minecraft:chest"})
     * @param maxSearchRadius maximum region search radius from the player
     * @return the plan that was queued, or {@code null} if no block was found
     */
    ITaskPlan runInteractPlanByBlockName(String blockName, int maxSearchRadius);

    /**
     * Creates (but does not start) an interact plan and queues it via
     * {@link #enqueuePlan(ITaskPlan)}.  If no plan is currently running
     * the plan starts immediately.
     *
     * @param target the precise block position
     */
    void createInteractPlan(BlockPos target);

    /**
     * Creates (but does not start) an interact plan and queues it via
     * {@link #enqueuePlan(ITaskPlan)}.  If no plan is currently running
     * the plan starts immediately.
     *
     * @param blockName the registry path of the block (e.g. {@code "crafting_table"})
     * @param maxSearchRadius radius in regions to search for cached positions
     */
    void createInteractPlan(String blockName, int maxSearchRadius);
}
