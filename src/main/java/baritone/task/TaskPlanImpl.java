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

import baritone.api.task.ITaskPlan;
import baritone.api.task.ITaskStep;
import baritone.api.task.StepStatus;
import baritone.api.task.TaskOutcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable concrete implementation of {@link ITaskPlan}.
 *
 * <p>Instances are created and managed exclusively by
 * {@link baritone.process.TaskPlanProcess}; callers receive them as the
 * read-only {@link ITaskPlan} interface.
 */
public final class TaskPlanImpl implements ITaskPlan {

    private final String label;
    private final List<TaskStepImpl> mutableSteps;
    private final List<ITaskStep> readOnlySteps;

    private volatile int currentStepIndex = -1;
    private volatile StepStatus status = StepStatus.PENDING;
    private volatile TaskOutcome outcome = null;

    public TaskPlanImpl(String label) {
        this.label = label;
        this.mutableSteps = new ArrayList<>();
        this.readOnlySteps = Collections.unmodifiableList(
                new ArrayList<>(mutableSteps) // updated below
        );
    }

    // ─── Builder API (used by TaskPlanProcess factories) ─────────────────────

    /**
     * Appends a step and returns the new step instance for reference.
     * Must only be called before the plan starts executing.
     */
    public TaskStepImpl addStep(String description) {
        TaskStepImpl step = new TaskStepImpl(description);
        mutableSteps.add(step);
        return step;
    }

    /** Returns the mutable step list (for use by TaskPlanProcess). */
    public List<TaskStepImpl> mutableSteps() {
        return mutableSteps;
    }

    // ─── Plan-level state mutators (used by TaskPlanProcess) ─────────────────

    public void markRunning(int stepIndex) {
        this.currentStepIndex = stepIndex;
        this.status = StepStatus.RUNNING;
    }

    public void markSucceeded() {
        this.currentStepIndex = -1;
        this.status = StepStatus.SUCCEEDED;
        this.outcome = TaskOutcome.SUCCEEDED;
    }

    public void markFailed(TaskOutcome reason) {
        this.currentStepIndex = -1;
        this.status = StepStatus.FAILED;
        this.outcome = reason;
    }

    public void markCancelled() {
        this.currentStepIndex = -1;
        this.status = StepStatus.CANCELLED;
        this.outcome = TaskOutcome.CANCELLED;
    }

    // ─── ITaskPlan (read API) ─────────────────────────────────────────────────

    @Override
    public String label() {
        return label;
    }

    @Override
    public List<ITaskStep> steps() {
        // return a snapshot of the mutable list wrapped as ITaskStep
        return Collections.unmodifiableList(new ArrayList<>(mutableSteps));
    }

    @Override
    public int currentStepIndex() {
        return currentStepIndex;
    }

    @Override
    public StepStatus status() {
        return status;
    }

    @Override
    public TaskOutcome outcome() {
        return outcome;
    }

    @Override
    public String toString() {
        return "TaskPlan{" + label + ", " + status
                + (outcome != null ? ", " + outcome : "") + "}";
    }
}
