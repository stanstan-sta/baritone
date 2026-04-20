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
 * Machine-readable result codes for task and step outcomes.
 *
 * <p>These stable string values are intended for consumption by upstream LLM
 * bridge callers that need deterministic, parseable outcome signals to decide
 * on retry or recovery strategies.
 *
 * <p>The {@link #name()} of each constant (lower-cased) is the canonical
 * string sent over the wire (e.g. {@code "succeeded"}, {@code "not_found"}).
 */
public enum TaskOutcome {

    /** Task or step completed successfully. */
    SUCCEEDED,

    /** Target block or entity could not be found in the world scan range. */
    NOT_FOUND,

    /**
     * A path to the target exists but could not be computed (all routes
     * blocked or calc limit exceeded).
     */
    UNREACHABLE,

    /**
     * The player reached the target block but the right-click interaction
     * produced no result within the timeout window.
     */
    INTERACTION_FAILED,

    /**
     * Sleep was attempted but it is not currently night-time (or a
     * thunder-storm) in the current dimension.
     */
    NOT_NIGHT,

    /**
     * The target bed block was found and reached, but it is currently
     * occupied by another player.
     */
    OCCUPIED,

    /** The player died while the task was executing. */
    PLAYER_DIED,

    /** The task was explicitly cancelled by the caller. */
    CANCELLED,

    /**
     * The task exceeded its configured time-out or maximum retry count
     * without completing.
     */
    TIMEOUT;

    /**
     * Returns the lower-cased name suitable for JSON/bridge serialisation,
     * e.g. {@code "not_found"}.
     */
    public String toWireString() {
        return name().toLowerCase();
    }
}
