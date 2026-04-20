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
 * Life-cycle state of a single {@link ITaskStep} within a {@link ITaskPlan}.
 *
 * <p>Valid transitions:
 * <pre>
 *   PENDING → RUNNING → SUCCEEDED
 *                     → FAILED
 *             RUNNING → CANCELLED  (external cancel or death)
 *   PENDING           → CANCELLED  (plan cancelled before step started)
 * </pre>
 */
public enum StepStatus {

    /** The step has not yet been started. */
    PENDING,

    /** The step is currently executing. */
    RUNNING,

    /** The step completed successfully. */
    SUCCEEDED,

    /** The step completed with a failure outcome. */
    FAILED,

    /** The step was cancelled before or during execution. */
    CANCELLED
}
