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

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.BlockById;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.BetterBlockPos;
import baritone.cache.CachedChunk;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static baritone.api.command.IBaritoneChatControl.FORCE_COMMAND_PREFIX;

public class FindCommand extends Command {

    public FindCommand(IBaritone baritone) {
        super(baritone, "find");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        List<Block> toFind = new ArrayList<>();
        while (args.hasAny()) {
            toFind.add(args.getDatatypeFor(BlockById.INSTANCE));
        }
        Set<Block> blocksToKeepTrackOf = CachedChunk.getBlocksToKeepTrackOf();
        BetterBlockPos origin = ctx.playerFeet();
        
        // Warn about blocks that won't be tracked in future sessions
        List<Block> untracked = toFind.stream()
                .filter(b -> !blocksToKeepTrackOf.contains(b))
                .toList();
        if (!untracked.isEmpty()) {
            logDirect("Note: some blocks are not in the persistent cache set. " +
                    "Use #repack to index them for this session.");
        }
        
        // Try cache-first; on empty result, re-pack loaded chunks and retry
        Component[] components = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            components = toFind.stream()
                    .flatMap(block ->
                            ctx.worldData().getCachedWorld().getLocationsOf(
                                    BuiltInRegistries.BLOCK.getKey(block).getPath(),
                                    Integer.MAX_VALUE,
                                    origin.x,
                                    origin.z,
                                    4
                            ).stream()
                    )
                    .map(BetterBlockPos::new)
                    .map(this::positionToComponent)
                    .toArray(Component[]::new);
            if (components.length > 0) {
                break;
            }
            // Fallback: force-scan loaded chunks
            BaritoneAPI.getProvider().getWorldScanner().repack(ctx);
        }
        
        if (components.length > 0) {
            Arrays.asList(components).forEach(this::logDirect);
        } else {
            logDirect("No positions known, are you sure the blocks are in loaded chunks?");
        }
    }

    private Component positionToComponent(BetterBlockPos pos) {
        String positionText = String.format("%s %s %s", pos.x, pos.y, pos.z);
        String command = String.format("%sgoal %s", FORCE_COMMAND_PREFIX, positionText);
        MutableComponent baseComponent = Component.literal(pos.toString());
        MutableComponent hoverComponent = Component.literal("Click to set goal to this position");
        baseComponent.setStyle(baseComponent.getStyle()
                .withColor(ChatFormatting.GRAY)
                .withInsertion(positionText)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hoverComponent)));
        return baseComponent;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return new TabCompleteHelper()
                .append(
                        CachedChunk.getBlocksToKeepTrackOf().stream()
                                .map(BuiltInRegistries.BLOCK::getKey)
                                .map(Object::toString)
                )
                .filterPrefixNamespaced(args.getString())
                .sortAlphabetically()
                .stream();
    }

    @Override
    public String getShortDesc() {
        return "Find positions of a certain block";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The find command searches through Baritone's cache and attempts to find the location of the block.",
                "Tab completion will suggest only cached blocks and uncached blocks can not be found.",
                "",
                "Usage:",
                "> find <block> [...] - Try finding the listed blocks"
        );
    }
}
