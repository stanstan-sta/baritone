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

package baritone.task;

import baritone.api.task.ITaskStep;
import baritone.api.task.StepStatus;
import baritone.api.task.TaskOutcome;

/**
 * Mutable concrete implementation of {@link ITaskStep}.
 *
 * <p>Instances are created and managed exclusively by {@link TaskPlanImpl}
 * and {@link baritone.process.TaskPlanProcess}; callers receive them as the
 * read-only {@link ITaskStep} interface.
 */
public final class TaskStepImpl implements ITaskStep {

    private final String description;
    private volatile StepStatus status;
    private volatile TaskOutcome outcome;

    public TaskStepImpl(String description) {
        this.description = description;
        this.status = StepStatus.PENDING;
        this.outcome = null;
    }

    // ─── ITaskStep (read API) ─────────────────────────────────────────────────

    @Override
    public String description() {
        return description;
    }

    @Override
    public StepStatus status() {
        return status;
    }

    @Override
    public TaskOutcome outcome() {
        return outcome;
    }

    // ─── Mutators (package-private; used by TaskPlanProcess) ─────────────────

    public void setRunning() {
        this.status = StepStatus.RUNNING;
        this.outcome = null;
    }

    public void succeed() {
        this.status = StepStatus.SUCCEEDED;
        this.outcome = TaskOutcome.SUCCEEDED;
    }

    public void fail(TaskOutcome reason) {
        this.status = StepStatus.FAILED;
        this.outcome = reason;
    }

    public void cancel() {
        this.status = StepStatus.CANCELLED;
        this.outcome = TaskOutcome.CANCELLED;
    }

    @Override
    public String toString() {
        return "TaskStep{" + description + ", " + status
                + (outcome != null ? ", " + outcome : "") + "}";
    }
}
