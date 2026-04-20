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

/**
 * A single discrete step within a {@link ITaskPlan}.
 *
 * <p>Implementations are provided by the task framework and are not meant to
 * be created by callers directly; use the factory methods on
 * {@link baritone.api.process.ITaskPlanProcess} instead.
 */
public interface ITaskStep {

    /**
     * Human-readable description of what this step does.
     * Suitable for HUD display or logging.
     */
    String description();

    /**
     * Current execution state of this step.
     *
     * @return never {@code null}
     */
    StepStatus status();

    /**
     * Outcome of this step.
     *
     * <p>Only meaningful (non-{@code null}) when {@link #status()} is
     * {@link StepStatus#SUCCEEDED} or {@link StepStatus#FAILED}.
     *
     * @return the outcome, or {@code null} if the step has not yet finished
     */
    TaskOutcome outcome();
}
