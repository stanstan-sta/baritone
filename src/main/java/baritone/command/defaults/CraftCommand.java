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

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.task.ITaskPlan;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code #craft}
 *
 * <p>Paths to the nearest cached crafting table and right-clicks it to
 * open the 3×3 crafting grid.  Uses the same block-cache lookup as
 * {@code #task interact crafting_table}.
 *
 * <h3>Outcomes logged to chat</h3>
 * <p>The underlying task plan logs progress and completion via the
 * {@code [Baritone]} wire format.
 *
 * <h3>Examples</h3>
 * <pre>
 *   #craft      – find nearest crafting table, walk to it, and open it
 * </pre>
 */
public class CraftCommand extends Command {

    public CraftCommand(IBaritone baritone) {
        super(baritone, "craft");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);

        boolean queueActive = baritone.getTaskPlanProcess().pendingCount() > 0;
        ITaskPlan plan;
        if (queueActive) {
            baritone.getTaskPlanProcess().createInteractPlan("crafting_table", 4);
            logDirect("Queued craft plan behind existing tasks. Use #task queue to see pending.");
            return;
        } else {
            plan = baritone.getTaskPlanProcess()
                    .runInteractPlanByBlockName("crafting_table", 4);
        }

        if (plan == null) {
            logDirect("Could not find any cached crafting tables nearby.");
            return;
        }

        logDirect("Pathing to nearest crafting table… Use #task status to monitor.");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Path to and open the nearest crafting table";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The craft command tells Baritone to find the nearest crafting table",
                "and path to it, then right-click to open the 3×3 crafting grid.",
                "",
                "Uses the block-cache index for fast lookup (same as #task interact crafting_table).",
                "",
                "To cancel: use #task cancel",
                "",
                "Usage:",
                "> #craft"
        );
    }
}