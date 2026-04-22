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
 *
 * <h3>Subcommands</h3>
 * <dl>
 *   <dt>{@code status}</dt>
 *   <dd>Print the status of the currently running plan and each of its steps.
 *       Machine-readable wire strings (e.g. {@code "running"}, {@code "succeeded"})
 *       are shown for each step so LLM bridge consumers can parse the output.</dd>
 *
 *   <dt>{@code cancel}</dt>
 *   <dd>Cancel the currently running plan immediately.  The plan outcome is set
 *       to {@code cancelled}.</dd>
 *
 *   <dt>{@code sleep}</dt>
 *   <dd>Start a pre-built "sleep in nearest bed" multi-step plan:
 *       scan → path → interact → verify.  Equivalent to {@code #sleep} but
 *       executed through the TaskPlanProcess framework so each step is
 *       individually tracked and can be inspected via {@code #task status}.</dd>
 *
 *   <dt>{@code interact <x> <y> <z>}</dt>
 *   <dd>Start a pre-built "interact with block" multi-step plan:
 *       path → interact.  Equivalent to {@code #interact x y z} but executed
 *       through the TaskPlanProcess so each step is tracked.</dd>
 * </dl>
 *
 * <h3>Examples</h3>
 * <pre>
 *   #task sleep               – run the sleep plan
 *   #task interact 100 64 200 – run the interact plan for that block
 *   #task status              – print current plan + step statuses
 *   #task cancel              – abort the active plan
 * </pre>
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
            default:
                throw new CommandInvalidTypeException(args.getConsumed().peekLast(),
                        "status | cancel | sleep | interact | chest");
        }
    }

    // ─── Subcommand handlers ──────────────────────────────────────────────────

    private void executeStatus(IArgConsumer args) throws CommandException {
        args.requireMax(0);
        ITaskPlan plan = baritone.getTaskPlanProcess().currentPlan();
        if (plan == null) {
            logDirect("No task plan is currently running.");
            return;
        }

        // Header line: plan label + aggregate status + outcome (if finished)
        String planLine = String.format("Plan '%s'  status=%s",
                plan.label(),
                plan.status().name().toLowerCase());
        if (plan.outcome() != null) {
            planLine += "  outcome=" + plan.outcome().toWireString();
        }
        logDirect(planLine);

        // Per-step detail
        List<ITaskStep> steps = plan.steps();
        for (int i = 0; i < steps.size(); i++) {
            ITaskStep step = steps.get(i);
            String marker = (i == plan.currentStepIndex()) ? "► " : "  ";
            String stepLine = String.format("%s[%d] %s  %s",
                    marker, i + 1,
                    step.description(),
                    step.status().name().toLowerCase());
            if (step.outcome() != null) {
                stepLine += "  → " + step.outcome().toWireString();
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

    // ─── ICommand ────────────────────────────────────────────────────────────

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

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (!args.hasAny()) return Stream.empty();
        if (!args.has(2)) {
            // First token: offer subcommands
            String prefix;
            try {
                prefix = args.peekString().toLowerCase();
            } catch (baritone.api.command.exception.CommandNotEnoughArgumentsException e) {
                return Stream.empty();
            }
            return Stream.of("status", "cancel", "sleep", "interact", "chest")
                    .filter(s -> s.startsWith(prefix));
        }
        // Second token onward: if subcommand is "interact" offer coordinate hints
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
                "  status               – print current plan and per-step statuses",
                "  cancel               – abort the active plan (outcome: cancelled)",
                "  sleep                – run the built-in sleep plan (4 steps)",
                "  interact <x> <y> <z> – run the built-in interact plan for a block",
                "",
                "Output format for 'status':",
                "  Plan '<label>'  status=running",
                "  ► [1] Scan for bed   running",
                "    [2] Path to bed    pending",
                "    [3] Interact       pending",
                "    [4] Verify sleep   pending",
                "",
                "Usage:",
                "> #task sleep",
                "> #task interact 100 64 200",
                "> #task interact ~ ~1 ~",
                "> #task status",
                "> #task cancel",
                "",
                "See also: #interact (quick single-step interact), #sleep (quick sleep)"
        );
    }
}
