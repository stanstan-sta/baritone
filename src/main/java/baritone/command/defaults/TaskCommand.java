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
import baritone.api.command.datatypes.BlockById;
import baritone.api.command.datatypes.ItemById;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.task.ContainerAction;
import baritone.api.task.ITaskPlan;
import baritone.api.task.ITaskStep;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code #task <subcommand> [args]}
 *
 * <p>Controls and inspects multi-step task plans executed by
 * {@link baritone.api.process.ITaskPlanProcess}.
 */
public class TaskCommand extends Command {

    public TaskCommand(IBaritone baritone) {
        super(baritone, "task");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        String sub = args.getString().toLowerCase();

        switch (sub) {
            case "status":
                executeStatus(args);
                break;
            case "cancel":
                executeCancel(args);
                break;
            case "sleep":
                executeSleep(args);
                break;
            case "interact":
                executeInteract(args);
                break;
            case "chest":
                executeChest(args);
                break;
            case "smelt":
                executeSmelt(args);
                break;
            case "enqueue":
                executeEnqueue(args);
                break;
            case "queue":
                executeQueueStatus(args);
                break;
            case "clear":
                executeClearQueue(args);
                break;
            default:
                throw new CommandInvalidTypeException(args.getConsumed().peekLast(),
                        "status | cancel | sleep | interact | chest | smelt | enqueue | queue | clear");
        }
    }

    // --- Subcommand handlers --------------------------------------------------

    private void executeStatus(IArgConsumer args) throws CommandException {
        args.requireMax(0);
        ITaskPlan plan = baritone.getTaskPlanProcess().currentPlan();
        if (plan == null) {
            logDirect("No task plan is currently running.");
            return;
        }

        String planLine = String.format("Plan '%s'  status=%s",
                plan.label(),
                plan.status().name().toLowerCase());
        if (plan.outcome() != null) {
            planLine += "  outcome=" + plan.outcome().toWireString();
        }
        logDirect(planLine);

        List<ITaskStep> steps = plan.steps();
        for (int i = 0; i < steps.size(); i++) {
            ITaskStep step = steps.get(i);
            String marker = (i == plan.currentStepIndex()) ? "\u25ba " : "  ";
            String stepLine = String.format("%s[%d] %s  %s",
                    marker, i + 1,
                    step.description(),
                    step.status().name().toLowerCase());
            if (step.outcome() != null) {
                stepLine += "  \u2192 " + step.outcome().toWireString();
            }
            logDirect(stepLine);
        }
    }

    private void executeCancel(IArgConsumer args) throws CommandException {
        args.requireMax(0);
        ITaskPlan plan = baritone.getTaskPlanProcess().currentPlan();
        if (plan == null) {
            throw new CommandInvalidStateException("No task plan is currently running");
        }
        baritone.getTaskPlanProcess().cancelPlan();
        logDirect("Task plan '" + plan.label() + "' cancelled.");
    }

    private void executeSleep(IArgConsumer args) throws CommandException {
        args.requireMax(0);
        ITaskPlan plan = baritone.getTaskPlanProcess().runSleepPlan();
        logDirect("Started sleep plan '" + plan.label() + "' ("
                + plan.steps().size() + " steps). Use #task status to monitor.");
    }

    private void executeInteract(IArgConsumer args) throws CommandException {
        args.requireMin(1);

        // Block-name form: #task interact <block>
        if (!args.has(3)) {
            args.requireMax(1);
            String blockArg = args.peekString();
            net.minecraft.world.level.block.Block block = args.getDatatypeFor(BlockById.INSTANCE);
            String registryName = BuiltInRegistries.BLOCK.getKey(block).getPath();
            ITaskPlan plan = baritone.getTaskPlanProcess().runInteractPlanByBlockName(registryName, 4);
            if (plan == null) {
                logDirect("Could not find any cached positions for " + blockArg);
                return;
            }
            logDirect(String.format("Started interact plan '%s' for %s (%d steps). "
                            + "Use #task status to monitor.",
                    plan.label(), blockArg, plan.steps().size()));
            return;
        }

        // Coordinate form: #task interact <x> <y> <z>
        args.requireExactly(3);
        BetterBlockPos origin = ctx.playerFeet();
        BlockPos target = CommandCoordParser.parseXYZ(args,
                origin.getX(), origin.getY(), origin.getZ());

        ITaskPlan plan = baritone.getTaskPlanProcess().runInteractPlan(target);
        logDirect(String.format("Started interact plan '%s' for block %d %d %d (%d steps). "
                        + "Use #task status to monitor.",
                plan.label(), target.getX(), target.getY(), target.getZ(),
                plan.steps().size()));
    }

    // --- Chest subcommand ----------------------------------------------------

    private void executeChest(IArgConsumer args) throws CommandException {
        args.requireMin(5);
        args.requireMax(6);

        BetterBlockPos origin = ctx.playerFeet();
        BlockPos target = CommandCoordParser.parseXYZ(args,
                origin.getX(), origin.getY(), origin.getZ());

        String mode = args.getString().toLowerCase();
        Item item = args.getDatatypeFor(ItemById.INSTANCE);
        int count = -1;
        if (args.hasAny()) {
            String countToken = args.peekString().toLowerCase();
            if ("all".equals(countToken) || "max".equals(countToken)) {
                args.getString();
            } else {
                count = args.getAs(Integer.class);
            }
        }
        args.requireMax(0);

        ContainerAction action;
        switch (mode) {
            case "withdraw":
                action = ContainerAction.withdraw(item, count);
                break;
            case "deposit":
                action = ContainerAction.deposit(item, count);
                break;
            default:
                throw new CommandInvalidTypeException(args.consumed(),
                        "withdraw | deposit");
        }

        ITaskPlan plan = baritone.getTaskPlanProcess().runContainerPlan(target, action);
        logDirect(String.format("Started chest plan '%s' for block %d %d %d (%s %s%s). "
                        + "Use #task status to monitor.",
                plan.label(),
                target.getX(), target.getY(), target.getZ(),
                mode,
                BuiltInRegistries.ITEM.getKey(item),
                action.movesAllMatchingItems() ? "" : " x" + action.getCount()));
    }

    // --- Smelt subcommand ----------------------------------------------------

    private void executeSmelt(IArgConsumer args) throws CommandException {
        args.requireMin(1);

        // Check for smelt-all form: #task smelt all [furnace_type]
        String first = args.peekString().toLowerCase();
        if ("all".equals(first)) {
            args.getString(); // consume "all"
            String furnaceType = "furnace";
            if (args.hasAny()) {
                String candidate = args.peekString().toLowerCase();
                if (candidate.equals("furnace") || candidate.equals("blast_furnace") || candidate.equals("smoker")) {
                    furnaceType = args.getString();
                }
            }
            args.requireMax(0);
            ITaskPlan plan = baritone.getTaskPlanProcess()
                    .runSmeltAllItems(furnaceType, 4);
            if (plan == null) {
                logDirect("Could not find a " + furnaceType + " nearby or no smeltable items in inventory.");
                return;
            }
            logDirect(String.format("Started smelt-all plan on %s. Use #task status to monitor.", furnaceType));
            return;
        }

        // Parse: #task smelt <item> [count|all] [furnace_type]
        Item item = args.getDatatypeFor(ItemById.INSTANCE);
        int count = -1;
        String furnaceType = "furnace";

        if (args.hasAny()) {
            String next = args.peekString().toLowerCase();
            try {
                count = Integer.parseInt(next);
                args.getString();
            } catch (NumberFormatException e) {
                if ("all".equals(next) || "max".equals(next)) {
                    args.getString();
                } else {
                    // Treat as furnace type
                }
            }
        }

        if (args.hasAny()) {
            String candidate = args.peekString().toLowerCase();
            if (candidate.equals("furnace") || candidate.equals("blast_furnace") || candidate.equals("smoker")) {
                furnaceType = args.getString();
            }
        }
        args.requireMax(0);

        ITaskPlan plan = baritone.getTaskPlanProcess()
                .runSmeltPlan(item, count, furnaceType, 4);
        if (plan == null) {
            logDirect("Could not find a " + furnaceType + " nearby.");
            return;
        }
        logDirect(String.format("Started smelt plan for %s (%s). Use #task status to monitor.",
                BuiltInRegistries.ITEM.getKey(item),
                count > 0 ? "x" + count : "all"));
    }

    // --- Enqueue subcommand --------------------------------------------------

    private void executeEnqueue(IArgConsumer args) throws CommandException {
        args.requireMin(1);

        // Block-name form: #task enqueue <block>
        if (!args.has(3)) {
            args.requireMax(1);
            String blockArg = args.peekString();
            net.minecraft.world.level.block.Block block = args.getDatatypeFor(BlockById.INSTANCE);
            String registryName = BuiltInRegistries.BLOCK.getKey(block).getPath();
            baritone.getTaskPlanProcess().createInteractPlan(registryName, 4);
            logDirect("Queued interact plan for " + blockArg + ". Use #task queue to see pending.");
            return;
        }

        // Coordinate form: #task enqueue <x> <y> <z>
        args.requireExactly(3);
        BetterBlockPos origin = ctx.playerFeet();
        BlockPos target = CommandCoordParser.parseXYZ(args,
                origin.getX(), origin.getY(), origin.getZ());

        baritone.getTaskPlanProcess().createInteractPlan(target);
        logDirect(String.format("Queued interact plan for %d %d %d. Use #task queue to see pending.",
                target.getX(), target.getY(), target.getZ()));
    }

    private void executeQueueStatus(IArgConsumer args) throws CommandException {
        args.requireMax(0);
        int pending = baritone.getTaskPlanProcess().queueSize();
        int total = baritone.getTaskPlanProcess().pendingCount();
        logDirect(String.format("Task queue: %d pending, %d total (including active).",
                pending, total));
    }

    private void executeClearQueue(IArgConsumer args) throws CommandException {
        args.requireMax(0);
        baritone.getTaskPlanProcess().clearQueue();
        logDirect("Task queue cleared.");
    }

    // --- Tab completion ------------------------------------------------------

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (!args.hasAny()) return Stream.empty();
        if (!args.has(2)) {
            String prefix;
            try {
                prefix = args.peekString().toLowerCase();
            } catch (baritone.api.command.exception.CommandNotEnoughArgumentsException e) {
                return Stream.empty();
            }
            return Stream.of("status", "cancel", "sleep", "interact", "chest", "smelt")
                    .filter(s -> s.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Manage multi-step task plans";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The task command controls multi-step task plans that allow LLM agents",
                "to orchestrate compound actions with per-step status tracking.",
                "",
                "Each plan has an aggregate status (pending/running/succeeded/failed/cancelled)",
                "and each step within it has its own status and outcome code.",
                "",
                "Death policy: if the player dies while a plan is running, the plan is",
                "immediately aborted with outcome 'player_died' and all state is cleared.",
                "",
                "Subcommands:",
                "  status               - print current plan and per-step statuses",
                "  cancel               - abort the active plan (outcome: cancelled)",
                "  sleep                - run the built-in sleep plan (4 steps)",
                "  interact <x> <y> <z> - run the built-in interact plan for a block",
                "",
                "Usage:",
                "> #task sleep",
                "> #task interact 100 64 200",
                "> #task smelt iron_ore all",
                "> #task smelt all",
                "> #task status",
                "> #task cancel"
        );
    }
}