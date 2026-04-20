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

package baritone.api.task;

import java.util.List;

/**
 * An ordered sequence of {@link ITaskStep}s managed by
 * {@link baritone.api.process.ITaskPlanProcess}.
 *
 * <p>The plan tracks its own aggregate status: it is {@link StepStatus#RUNNING}
 * as long as steps are executing, transitions to {@link StepStatus#SUCCEEDED}
 * when all steps succeed, and to {@link StepStatus#FAILED} if any step fails
 * (or the player dies, etc.).
 *
 * <p>A plan object is single-use; it cannot be restarted once it has reached
 * a terminal state.
 */
public interface ITaskPlan {

    /**
     * A short human-readable label for this plan, used in logs and HUD output.
     */
    String label();

    /**
     * Ordered list of all steps in this plan.
     *
     * @return immutable view; never {@code null}
     */
    List<ITaskStep> steps();

    /**
     * Zero-based index of the step that is currently running, or {@code -1}
     * if the plan has not started or has already finished.
     */
    int currentStepIndex();

    /**
     * Aggregate status of the plan, derived from its step states.
     *
     * @return never {@code null}
     */
    StepStatus status();

    /**
     * Aggregate outcome of the plan.
     *
     * <p>Only meaningful (non-{@code null}) when {@link #status()} is
     * {@link StepStatus#SUCCEEDED} or {@link StepStatus#FAILED}.
     *
     * @return the outcome, or {@code null} while the plan is pending/running
     */
    TaskOutcome outcome();
}
