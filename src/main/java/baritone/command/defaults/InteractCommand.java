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
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code #interact <x> <y> <z>}
 *
 * <p>Tells Baritone to navigate to the block at the given absolute or
 * relative coordinates and perform a right-click (use) interaction on it.
 * Supports {@code ~}-relative coordinates just like vanilla commands.
 *
 * <h3>Outcomes logged to chat</h3>
 * <ul>
 *   <li>{@code succeeded} – a container/UI opened as a result of the click</li>
 *   <li>{@code unreachable} – no walkable path exists to the block</li>
 *   <li>{@code interaction_failed} – reached the block but right-click had
 *       no effect within the timeout window</li>
 *   <li>{@code timeout} – interaction retry limit exhausted</li>
 *   <li>{@code player_died} – player died during the operation</li>
 *   <li>{@code cancelled} – {@code #cancel} was issued</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <pre>
 *   #interact 100 64 200      – interact with the block at (100, 64, 200)
 *   #interact ~ ~1 ~          – interact with the block one above your feet
 * </pre>
 */
public class InteractCommand extends Command {

    public InteractCommand(IBaritone baritone) {
        super(baritone, "interact");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireExactly(3);

        BetterBlockPos origin = ctx.playerFeet();

        // Parse each coordinate, respecting ~ notation
        double x = parseRelative(args, origin.getX());
        double y = parseRelative(args, origin.getY());
        double z = parseRelative(args, origin.getZ());

        BlockPos target = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));

        logDirect(String.format("Interacting with block at %s %s %s",
                target.getX(), target.getY(), target.getZ()));
        baritone.getInteractBlockProcess().interactWithBlock(target);
    }

    /**
     * Reads the next argument as either a plain integer or a {@code ~}-relative
     * offset from {@code origin}.
     */
    private double parseRelative(IArgConsumer args, int origin) throws CommandException {
        String raw = args.getString();
        if (raw.startsWith("~")) {
            try {
                double offset = raw.length() > 1 ? Double.parseDouble(raw.substring(1)) : 0;
                return origin + offset;
            } catch (NumberFormatException e) {
                throw new CommandInvalidTypeException(args.getConsumed().peekLast(), "~offset");
            }
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new CommandInvalidTypeException(args.getConsumed().peekLast(), "number or ~offset");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.of("~", "~ ~", "~ ~ ~");
    }

    @Override
    public String getShortDesc() {
        return "Right-click (use) a block at given coordinates";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The interact command tells Baritone to walk to the specified block",
                "and perform a right-click (use/open) interaction on it.",
                "",
                "This works for any interactable block: chests, crafting tables,",
                "furnaces, doors, buttons, beds, etc.",
                "",
                "Supports ~ relative coordinates just like vanilla Minecraft commands.",
                "",
                "Outcome is printed to chat when the action finishes:",
                "  succeeded, unreachable, interaction_failed, timeout, player_died, cancelled",
                "",
                "Usage:",
                "> #interact <x> <y> <z>  – absolute coordinates",
                "> #interact ~ ~1 ~        – relative: one block above your feet",
                "",
                "See also: #sleep (dedicated bed interaction), #task (multi-step plans)"
        );
    }
}
