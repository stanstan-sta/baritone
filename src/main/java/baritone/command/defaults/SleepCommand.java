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
import baritone.api.command.exception.CommandInvalidTypeException;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code #sleep [<x> <y> <z>]}
 *
 * <p>Instructs Baritone to find an unoccupied bed, navigate to it, and
 * initiate the sleeping sequence.  Optionally a specific bed position can be
 * supplied to skip the world scan.
 *
 * <h3>Outcomes logged to chat</h3>
 * <ul>
 *   <li>{@code succeeded} – player entered the sleeping state</li>
 *   <li>{@code not_found} – no bed found within scan range (~64 blocks)</li>
 *   <li>{@code unreachable} – bed found but no walkable path exists</li>
 *   <li>{@code not_night} – it is currently daytime; sleep is not allowed</li>
 *   <li>{@code occupied} – the nearest bed is occupied by another player</li>
 *   <li>{@code interaction_failed} – reached bed but right-click had no effect</li>
 *   <li>{@code timeout} – retry limit exhausted</li>
 *   <li>{@code player_died} – player died during the operation</li>
 *   <li>{@code cancelled} – {@code #cancel} was issued</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <pre>
 *   #sleep               – scan for nearest bed and sleep in it
 *   #sleep 100 64 200    – sleep in the bed at (100, 64, 200)
 * </pre>
 */
public class SleepCommand extends Command {

    public SleepCommand(IBaritone baritone) {
        super(baritone, "sleep");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (args.hasAny()) {
            // Specific bed coordinates supplied
            args.requireExactly(3);
            int x = parseCoord(args, ctx.playerFeet().getX());
            int y = parseCoord(args, ctx.playerFeet().getY());
            int z = parseCoord(args, ctx.playerFeet().getZ());
            BlockPos bedPos = new BlockPos(x, y, z);
            logDirect(String.format("Sleeping in bed at %d %d %d", x, y, z));
            baritone.getSleepInBedProcess().sleepInBed(bedPos);
        } else {
            // Auto-scan for nearest bed
            logDirect("Scanning for nearest bed to sleep in…");
            baritone.getSleepInBedProcess().sleepInBed();
        }
    }

    /** Reads the next argument as an integer, supporting {@code ~} notation. */
    private int parseCoord(IArgConsumer args, int origin) throws CommandException {
        String raw = args.getString();
        if (raw.startsWith("~")) {
            try {
                int offset = raw.length() > 1 ? Integer.parseInt(raw.substring(1)) : 0;
                return origin + offset;
            } catch (NumberFormatException e) {
                throw new CommandInvalidTypeException(args.getConsumed().peekLast(), "~offset");
            }
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new CommandInvalidTypeException(args.getConsumed().peekLast(), "integer or ~offset");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (!args.hasAny()) return Stream.empty();
        // Only offer tab-complete hints for the coordinate form
        return Stream.of("~", "~ ~", "~ ~ ~");
    }

    @Override
    public String getShortDesc() {
        return "Find and sleep in the nearest bed";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The sleep command instructs Baritone to locate an unoccupied bed",
                "within scan range (~64 blocks), navigate to it, and sleep.",
                "",
                "You can optionally specify exact bed coordinates to skip the scan.",
                "",
                "Sleep is only possible at night (day-time tick ≥ 12542) or during",
                "a thunderstorm.  The process will abort with outcome 'not_night'",
                "if called during the day.",
                "",
                "Outcome is printed to chat when the action finishes:",
                "  succeeded, not_found, unreachable, not_night, occupied,",
                "  interaction_failed, timeout, player_died, cancelled",
                "",
                "Usage:",
                "> #sleep                – auto-scan for nearest bed",
                "> #sleep <x> <y> <z>   – sleep in the bed at these coordinates",
                "",
                "See also: #interact (generic block interaction), #task (multi-step plans)"
        );
    }
}
