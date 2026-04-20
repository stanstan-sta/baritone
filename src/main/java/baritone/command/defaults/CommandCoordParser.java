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

import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidTypeException;
import net.minecraft.core.BlockPos;

/**
 * Shared helper for parsing integer block coordinates from Baritone command
 * arguments.  Supports {@code ~}-relative notation (e.g. {@code ~}, {@code ~3},
 * {@code ~-1}) using the player's current block position as the origin.
 *
 * <p>Used by {@link InteractCommand}, {@link SleepCommand}, and
 * {@link TaskCommand} to avoid code duplication.
 */
final class CommandCoordParser {

    private CommandCoordParser() {}

    /**
     * Reads the next argument from {@code args} and interprets it as an
     * absolute integer coordinate or a {@code ~}-relative offset from
     * {@code origin}.
     *
     * <p>Examples:
     * <pre>
     *   "100"   → 100
     *   "~"     → origin
     *   "~3"    → origin + 3
     *   "~-2"   → origin - 2
     * </pre>
     *
     * @param args   the argument consumer; consumes exactly one token
     * @param origin the player's coordinate along this axis, used for {@code ~}
     * @return the resolved integer coordinate
     * @throws CommandException if the token cannot be parsed
     */
    static int parse(IArgConsumer args, int origin) throws CommandException {
        String raw = args.getString();
        if (raw.startsWith("~")) {
            try {
                int offset = raw.length() > 1 ? Integer.parseInt(raw.substring(1)) : 0;
                return origin + offset;
            } catch (NumberFormatException e) {
                throw new CommandInvalidTypeException(
                        args.getConsumed().peekLast(), "~offset");
            }
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new CommandInvalidTypeException(
                    args.getConsumed().peekLast(), "integer or ~offset");
        }
    }

    /**
     * Convenience method: reads the next three arguments as X, Y, Z integer
     * coordinates and returns a {@link BlockPos}.
     *
     * @param args    the argument consumer; consumes exactly three tokens
     * @param originX player's X
     * @param originY player's Y
     * @param originZ player's Z
     * @return the resolved {@link BlockPos}
     * @throws CommandException if any coordinate token cannot be parsed
     */
    static BlockPos parseXYZ(IArgConsumer args, int originX, int originY, int originZ)
            throws CommandException {
        int x = parse(args, originX);
        int y = parse(args, originY);
        int z = parse(args, originZ);
        return new BlockPos(x, y, z);
    }
}
