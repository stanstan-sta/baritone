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

package baritone.process;

import baritone.Baritone;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.ITaskPlanProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.task.ITaskPlan;
import baritone.api.task.StepStatus;
import baritone.api.task.TaskOutcome;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.task.TaskPlanImpl;
import baritone.task.TaskStepImpl;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Executes multi-step {@link ITaskPlan}s with explicit step-state tracking
 * and death-safe interruption.
 *
 * <h2>Built-in plan types</h2>
 * <dl>
 *   <dt>Interact plan ({@link #runInteractPlan})</dt>
 *   <dd>Steps: PATH_TO_BLOCK → INTERACT_WITH_BLOCK</dd>
 *   <dt>Sleep plan ({@link #runSleepPlan})</dt>
 *   <dd>Steps: SCAN_FOR_BED → PATH_TO_BED → INTERACT_WITH_BED → VERIFY_SLEEP</dd>
 * </dl>
 *
 * <h2>Death policy</h2>
 * On {@code onPlayerDeath()} the plan is aborted: the running step is failed
 * with {@link TaskOutcome#PLAYER_DIED}, all pending steps are cancelled, and
 * the plan's outcome is set to {@code PLAYER_DIED}.
 */
public final class TaskPlanProcess extends BaritoneProcessHelper
        implements ITaskPlanProcess, AbstractGameEventListener {

    // ── Interact-plan step tags ──────────────────────────────────────────────
    private static final String STEP_PATH   = "Path to block";
    private static final String STEP_INTERACT = "Interact with block";

    // ── Sleep-plan step tags ─────────────────────────────────────────────────
    private static final String STEP_SCAN   = "Scan for bed";
    private static final String STEP_BED_PATH = "Path to bed";
    private static final String STEP_BED_INTERACT = "Interact with bed";
    private static final String STEP_BED_VERIFY = "Verify sleep";

    // ── Timeouts / retry limits ──────────────────────────────────────────────
    private static final int MAX_CALC_FAILURES  = 3;
    private static final int INTERACT_TIMEOUT   = 40;
    private static final int SLEEP_VERIFY_TICKS = 60;
    private static final int BED_SCAN_RADIUS    = 64;
    private static final long NIGHT_START_TICK  = 12542L;

    /** All 16 bed block variants. */
    private static final List<net.minecraft.world.level.block.Block> BED_BLOCKS = Arrays.asList(
            Blocks.WHITE_BED, Blocks.ORANGE_BED, Blocks.MAGENTA_BED,
            Blocks.LIGHT_BLUE_BED, Blocks.YELLOW_BED, Blocks.LIME_BED,
            Blocks.PINK_BED, Blocks.GRAY_BED, Blocks.LIGHT_GRAY_BED,
            Blocks.CYAN_BED, Blocks.PURPLE_BED, Blocks.BLUE_BED,
            Blocks.BROWN_BED, Blocks.GREEN_BED, Blocks.RED_BED,
            Blocks.BLACK_BED
    );

    // ── Runtime state ────────────────────────────────────────────────────────

    private TaskPlanImpl plan;
    private int stepIdx;

    // Shared per-step state (reused across plan types)
    private BlockPos targetPos;
    private List<BlockPos> bedCandidates;
    private int interactTick;
    private int calcFailCount;
    private int verifyTick;

    public TaskPlanProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    // ─── ITaskPlanProcess ─────────────────────────────────────────────────────

    @Override
    public void runPlan(ITaskPlan plan) {
        if (plan == null) throw new IllegalArgumentException("plan must not be null");
        cancelPlan(); // cancel any existing plan
        this.plan = (TaskPlanImpl) plan;
        this.stepIdx = 0;
        advanceToStep(0);
    }

    @Override
    public ITaskPlan runSleepPlan() {
        TaskPlanImpl p = new TaskPlanImpl("sleep_in_bed");
        p.addStep(STEP_SCAN);
        p.addStep(STEP_BED_PATH);
        p.addStep(STEP_BED_INTERACT);
        p.addStep(STEP_BED_VERIFY);
        runPlan(p);
        return p;
    }

    @Override
    public ITaskPlan runInteractPlan(BlockPos target) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        TaskPlanImpl p = new TaskPlanImpl("interact_block");
        p.addStep(STEP_PATH);
        p.addStep(STEP_INTERACT);
        this.targetPos = target;
        runPlan(p);
        return p;
    }

    @Override
    public ITaskPlan currentPlan() {
        return plan;
    }

    @Override
    public void cancelPlan() {
        if (plan == null) return;
        abortPlan(TaskOutcome.CANCELLED);
    }

    // ─── IBaritoneProcess ─────────────────────────────────────────────────────

    @Override
    public boolean isActive() {
        return plan != null && plan.status() == StepStatus.RUNNING;
    }

    @Override
    public double priority() {
        // Higher than default so the task plan is preferred over standalone processes
        return DEFAULT_PRIORITY + 0.5;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (plan == null || !isActive()) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (stepIdx >= plan.mutableSteps().size()) {
            finishPlan(TaskOutcome.SUCCEEDED);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        TaskStepImpl currentStep = plan.mutableSteps().get(stepIdx);
        String tag = currentStep.description();

        PathingCommand cmd = dispatchStep(tag, calcFailed, isSafeToCancel);

        // After dispatching, check if the current step just finished
        if (currentStep.status() == StepStatus.SUCCEEDED) {
            stepIdx++;
            if (stepIdx >= plan.mutableSteps().size()) {
                finishPlan(TaskOutcome.SUCCEEDED);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            advanceToStep(stepIdx);
        } else if (currentStep.status() == StepStatus.FAILED) {
            abortPlan(currentStep.outcome() != null
                    ? currentStep.outcome() : TaskOutcome.INTERACTION_FAILED);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        return cmd;
    }

    @Override
    public void onLostControl() {
        if (plan != null && plan.status() == StepStatus.RUNNING) {
            abortPlan(TaskOutcome.CANCELLED);
        } else {
            clearState();
        }
    }

    @Override
    public String displayName0() {
        if (plan == null) return "TaskPlan (idle)";
        int total = plan.mutableSteps().size();
        return "TaskPlan[" + plan.label() + "] step " + (stepIdx + 1) + "/" + total;
    }

    // ─── AbstractGameEventListener ────────────────────────────────────────────

    @Override
    public void onPlayerDeath() {
        if (isActive()) {
            logDirect("TaskPlan: player died – aborting plan '" + plan.label() + "'");
            abortPlan(TaskOutcome.PLAYER_DIED);
        }
    }

    // ─── Step dispatch ────────────────────────────────────────────────────────

    /**
     * Routes a tick to the appropriate step handler based on the step
     * description tag.
     */
    private PathingCommand dispatchStep(String tag, boolean calcFailed,
                                        boolean isSafeToCancel) {
        switch (tag) {
            // ── Interact plan ─────────────────────────────────────────────────
            case STEP_PATH:
                return tickPathToBlock(calcFailed);
            case STEP_INTERACT:
                return tickInteractWithBlock();

            // ── Sleep plan ────────────────────────────────────────────────────
            case STEP_SCAN:
                return tickScanForBed();
            case STEP_BED_PATH:
                return tickPathToBed(calcFailed);
            case STEP_BED_INTERACT:
                return tickInteractWithBed();
            case STEP_BED_VERIFY:
                return tickVerifySleep();

            default:
                logDirect("TaskPlan: unknown step tag '" + tag + "', skipping");
                succeedStep();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
    }

    // ── Interact-plan step handlers ──────────────────────────────────────────

    private PathingCommand tickPathToBlock(boolean calcFailed) {
        if (targetPos == null) {
            failStep(TaskOutcome.NOT_FOUND);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (calcFailed) {
            calcFailCount++;
            if (calcFailCount >= MAX_CALC_FAILURES) {
                logDirect("TaskPlan: PATH_TO_BLOCK unreachable after " + calcFailCount + " failures");
                failStep(TaskOutcome.UNREACHABLE);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }
        Optional<Rotation> reachable = RotationUtils.reachable(ctx, targetPos,
                ctx.playerController().getBlockReachDistance());
        if (reachable.isPresent()) {
            logDirect("TaskPlan: in range of " + targetPos);
            succeedStep();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalGetToBlock(targetPos),
                PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand tickInteractWithBlock() {
        if (targetPos == null) {
            failStep(TaskOutcome.NOT_FOUND);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        Optional<Rotation> reachable = RotationUtils.reachable(ctx, targetPos,
                ctx.playerController().getBlockReachDistance());
        if (!reachable.isPresent()) {
            // Fell out of range – re-path
            logDirect("TaskPlan: drifted out of range during INTERACT, re-pathing");
            failStep(TaskOutcome.UNREACHABLE);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        baritone.getLookBehavior().updateTarget(reachable.get(), true);

        if (interactTick++ >= INTERACT_TIMEOUT) {
            logDirect("TaskPlan: INTERACT_WITH_BLOCK timed out");
            failStep(TaskOutcome.TIMEOUT);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (ctx.isLookingAt(targetPos)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
                logDirect("TaskPlan: block interaction succeeded");
                baritone.getInputOverrideHandler().clearAllKeys();
                succeedStep();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // ── Sleep-plan step handlers ─────────────────────────────────────────────

    private PathingCommand tickScanForBed() {
        if (!isNightOrThunder()) {
            logDirect("TaskPlan: SCAN_FOR_BED – not night");
            failStep(TaskOutcome.NOT_NIGHT);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        bedCandidates = findNearbyBeds();
        if (bedCandidates.isEmpty()) {
            logDirect("TaskPlan: SCAN_FOR_BED – no beds found");
            failStep(TaskOutcome.NOT_FOUND);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        BetterBlockPos pf = ctx.playerFeet();
        bedCandidates.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b)));
        targetPos = bedCandidates.get(0);
        logDirect("TaskPlan: found " + bedCandidates.size() + " bed(s), nearest at " + targetPos);
        succeedStep();
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickPathToBed(boolean calcFailed) {
        if (targetPos == null) {
            failStep(TaskOutcome.NOT_FOUND);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (calcFailed) {
            calcFailCount++;
            // Try the next bed candidate
            if (bedCandidates != null) {
                bedCandidates.remove(targetPos);
                if (!bedCandidates.isEmpty()) {
                    BetterBlockPos pf = ctx.playerFeet();
                    bedCandidates.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b)));
                    targetPos = bedCandidates.get(0);
                    calcFailCount = 0;
                    logDirect("TaskPlan: PATH_TO_BED trying next candidate " + targetPos);
                    return new PathingCommand(buildBedGoal(),
                            PathingCommandType.REVALIDATE_GOAL_AND_PATH);
                }
            }
            if (calcFailCount >= MAX_CALC_FAILURES) {
                logDirect("TaskPlan: PATH_TO_BED all candidates unreachable");
                failStep(TaskOutcome.UNREACHABLE);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }

        Optional<Rotation> reachable = RotationUtils.reachable(ctx, targetPos,
                ctx.playerController().getBlockReachDistance());
        if (reachable.isPresent()) {
            // Check occupied before transitioning to interact
            BlockState state = ctx.world().getBlockState(targetPos);
            if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.OCCUPIED)) {
                logDirect("TaskPlan: bed at " + targetPos + " is occupied, trying next");
                if (bedCandidates != null) {
                    bedCandidates.remove(targetPos);
                    if (!bedCandidates.isEmpty()) {
                        BetterBlockPos pf = ctx.playerFeet();
                        bedCandidates.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b)));
                        targetPos = bedCandidates.get(0);
                        calcFailCount = 0;
                        return new PathingCommand(buildBedGoal(),
                                PathingCommandType.REVALIDATE_GOAL_AND_PATH);
                    }
                }
                failStep(TaskOutcome.OCCUPIED);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            interactTick = 0;
            baritone.getPathingBehavior().cancelEverything();
            logDirect("TaskPlan: reached bed at " + targetPos);
            succeedStep();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(buildBedGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand tickInteractWithBed() {
        if (!isNightOrThunder()) {
            logDirect("TaskPlan: INTERACT_WITH_BED – no longer night");
            failStep(TaskOutcome.NOT_NIGHT);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (targetPos == null) {
            failStep(TaskOutcome.NOT_FOUND);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        Optional<Rotation> reachable = RotationUtils.reachable(ctx, targetPos,
                ctx.playerController().getBlockReachDistance());
        if (!reachable.isPresent()) {
            logDirect("TaskPlan: drifted away from bed during interaction");
            failStep(TaskOutcome.UNREACHABLE);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        baritone.getLookBehavior().updateTarget(reachable.get(), true);

        if (interactTick++ >= INTERACT_TIMEOUT) {
            logDirect("TaskPlan: INTERACT_WITH_BED timed out");
            failStep(TaskOutcome.TIMEOUT);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (ctx.isLookingAt(targetPos)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            if (ctx.player().isSleeping()) {
                baritone.getInputOverrideHandler().clearAllKeys();
                verifyTick = 0;
                succeedStep();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickVerifySleep() {
        if (ctx.player().isSleeping()) {
            if (verifyTick++ >= 5) {
                logDirect("TaskPlan: sleep confirmed");
                succeedStep();
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (verifyTick++ >= SLEEP_VERIFY_TICKS) {
            logDirect("TaskPlan: VERIFY_SLEEP timed out – not sleeping");
            failStep(TaskOutcome.INTERACTION_FAILED);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // ─── Plan/step lifecycle helpers ─────────────────────────────────────────

    private void advanceToStep(int idx) {
        interactTick = 0;
        calcFailCount = 0;
        verifyTick = 0;
        plan.markRunning(idx);
        plan.mutableSteps().get(idx).setRunning();
        logDirect("TaskPlan[" + plan.label() + "]: starting step "
                + (idx + 1) + "/" + plan.mutableSteps().size()
                + " – " + plan.mutableSteps().get(idx).description());
    }

    private void succeedStep() {
        if (plan == null || stepIdx >= plan.mutableSteps().size()) return;
        plan.mutableSteps().get(stepIdx).succeed();
    }

    private void failStep(TaskOutcome reason) {
        if (plan == null || stepIdx >= plan.mutableSteps().size()) return;
        plan.mutableSteps().get(stepIdx).fail(reason);
    }

    private void finishPlan(TaskOutcome outcome) {
        logDirect("TaskPlan[" + plan.label() + "]: finished – " + outcome);
        plan.markSucceeded();
        clearState();
    }

    private void abortPlan(TaskOutcome reason) {
        if (plan == null) return;
        // Fail the currently running step
        if (stepIdx < plan.mutableSteps().size()) {
            TaskStepImpl current = plan.mutableSteps().get(stepIdx);
            if (current.status() == StepStatus.RUNNING) {
                current.fail(reason);
            }
        }
        // Cancel all remaining pending steps
        for (int i = stepIdx + 1; i < plan.mutableSteps().size(); i++) {
            plan.mutableSteps().get(i).cancel();
        }
        plan.markFailed(reason);
        logDirect("TaskPlan[" + plan.label() + "]: aborted – " + reason);
        clearState();
    }

    private void clearState() {
        plan = null;
        stepIdx = 0;
        targetPos = null;
        bedCandidates = null;
        interactTick = 0;
        calcFailCount = 0;
        verifyTick = 0;
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    // ─── World helpers ────────────────────────────────────────────────────────

    private List<BlockPos> findNearbyBeds() {
        List<BlockPos> result = new ArrayList<>();
        BetterBlockPos pf = ctx.playerFeet();
        int minY = ctx.world().getMinBuildHeight();
        int maxY = ctx.world().getMaxBuildHeight();
        for (int x = pf.x - BED_SCAN_RADIUS; x <= pf.x + BED_SCAN_RADIUS; x++) {
            for (int z = pf.z - BED_SCAN_RADIUS; z <= pf.z + BED_SCAN_RADIUS; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = ctx.world().getBlockState(pos);
                    if (BED_BLOCKS.contains(state.getBlock())
                            && state.getValue(BedBlock.PART) == BedPart.FOOT) {
                        result.add(pos);
                    }
                }
            }
        }
        return result;
    }

    private boolean isNightOrThunder() {
        long dayTime = ctx.world().getDayTime() % 24000L;
        return dayTime >= NIGHT_START_TICK || ctx.world().isThundering();
    }

    private baritone.api.pathing.goals.Goal buildBedGoal() {
        if (bedCandidates != null && bedCandidates.size() > 1) {
            return new GoalComposite(bedCandidates.stream()
                    .map(GoalGetToBlock::new)
                    .toArray(baritone.api.pathing.goals.Goal[]::new));
        }
        return new GoalGetToBlock(targetPos);
    }
}
