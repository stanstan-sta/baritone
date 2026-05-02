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
import baritone.api.BaritoneAPI;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.ITaskPlanProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.task.ContainerAction;
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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    // ── Container-plan step tags ─────────────────────────────────────────────
    private static final String STEP_AWAIT_CONTAINER = "Wait for container";
    private static final String STEP_TRANSFER_ITEMS = "Transfer items";
    private static final String STEP_CLOSE_CONTAINER = "Close container";

    // ── Timeouts / retry limits ──────────────────────────────────────────────
    private static final int MAX_CALC_FAILURES  = 3;
    private static final int INTERACT_TIMEOUT   = 40;
    private static final int SLEEP_VERIFY_TICKS = 60;
    private static final int BED_SCAN_RADIUS    = 64;
    private static final long NIGHT_START_TICK  = 12542L;

    // ── Smelt-plan step tags ──────────────────────────────────────────────────
    private static final String STEP_FIND_FURNACE = "Find furnace";
    private static final String STEP_AWAIT_FURNACE_MENU = "Wait for furnace";
    private static final String STEP_LOAD_FURNACE   = "Load furnace";
    private static final String STEP_MONITOR_SMELT  = "Monitor smelting";
    private static final String STEP_COLLECT_OUTPUT  = "Collect output";

    private static final int SMELT_TIMEOUT_TICKS = 1200;
    private static final int SMELT_ROTATION_TICKS = 10;
    private static final int LOAD_PHASE_FUEL = 0;
    private static final int LOAD_PHASE_INPUT = 1;

    private static final List<net.minecraft.world.level.block.Block> BED_BLOCKS = Arrays.asList(
            Blocks.WHITE_BED, Blocks.ORANGE_BED, Blocks.MAGENTA_BED,
            Blocks.LIGHT_BLUE_BED, Blocks.YELLOW_BED, Blocks.LIME_BED,
            Blocks.PINK_BED, Blocks.GRAY_BED, Blocks.LIGHT_GRAY_BED,
            Blocks.CYAN_BED, Blocks.PURPLE_BED, Blocks.BLUE_BED,
            Blocks.BROWN_BED, Blocks.GREEN_BED, Blocks.RED_BED,
            Blocks.BLACK_BED
    );

    // ── Transfer finite-state-machine phases ──────────────────────────────────
    private static final int TRANSFER_PHASE_NONE = 0;
    private static final int TRANSFER_PHASE_PULL_EXACT = 1;
    private static final int TRANSFER_PHASE_PLACE_TEMP = 2;
    private static final int TRANSFER_PHASE_RETURN_EXCESS = 3;
    private static final int TRANSFER_PHASE_DEPOSIT_REMAINING = 4;

    // ── Process-level state (queue + transient per-tick counters) ─────────────
    private final Deque<TaskPlanImpl> planQueue = new ArrayDeque<>();
    private TaskPlanImpl plan;
    private int stepIdx;

    // Transient, reset between steps — only valid while a plan is running.
    private int interactTick;
    private int calcFailCount;
    private int verifyTick;
    private int transferRemaining;
    private int transferPhase;
    private int transferSourceSlot;
    private int transferTempSlot;
    private int transferGrabbed;
    private int transferToReturn;

    public TaskPlanProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    // ─── ITaskPlanProcess ─────────────────────────────────────────────────────

    @Override
    public void runPlan(ITaskPlan plan) {
        if (plan == null) throw new IllegalArgumentException("plan must not be null");
        cancelPlan();
        this.plan = (TaskPlanImpl) plan;
        this.stepIdx = 0;
        advanceToStep(0);
    }

    // ── Factory: run variants ─────────────────────────────────────────────────

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
        p.targetPos = target;
        runPlan(p);
        return p;
    }

    @Override
    public ITaskPlan runContainerPlan(BlockPos target, ContainerAction action) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        if (action == null) throw new IllegalArgumentException("action must not be null");
        TaskPlanImpl p = new TaskPlanImpl("container_interact");
        p.addStep(STEP_PATH);
        p.addStep(STEP_INTERACT);
        p.addStep(STEP_AWAIT_CONTAINER);
        p.addStep(STEP_TRANSFER_ITEMS);
        p.addStep(STEP_CLOSE_CONTAINER);
        p.targetPos = target;
        p.containerAction = action;
        runPlan(p);
        return p;
    }

    @Override
    public ITaskPlan runInteractPlanByBlockName(String blockName, int maxSearchRadius) {
        if (blockName == null || blockName.isEmpty())
            throw new IllegalArgumentException("blockName must not be null or empty");
        BlockPos target = findPositionForBlock(blockName, maxSearchRadius);
        if (target == null) {
            logDirect("TaskPlan[" + blockName + "]: no cached positions found");
            return null;
        }
        return runInteractPlan(target);
    }

    @Override
    public ITaskPlan runContainerPlanByBlockName(String blockName, ContainerAction action, int maxSearchRadius) {
        net.minecraft.world.level.block.Block block = blockFromName(blockName);
        BlockPos target = findNearestBlock(blockName, block, maxSearchRadius);
        if (target == null) {
            logDirect("TaskPlan[" + blockName + "]: no positions found");
            return null;
        }
        return runContainerPlan(target, action);
    }

    @Override
    public ITaskPlan runSmeltPlan(net.minecraft.world.item.Item item, int count, String furnaceBlockName, int maxSearchRadius) {
        TaskPlanImpl p = buildSmeltPlan(item, count, furnaceBlockName, maxSearchRadius);
        runPlan(p);
        return p;
    }

    // ── Factory: create / enqueue variants ────────────────────────────────────

    @Override
    public void createInteractPlan(BlockPos target) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        TaskPlanImpl p = new TaskPlanImpl("interact_block");
        p.addStep(STEP_PATH);
        p.addStep(STEP_INTERACT);
        p.targetPos = target;
        enqueuePlan(p);
    }

    @Override
    public void createInteractPlan(String blockName, int maxSearchRadius) {
        BlockPos target = findPositionForBlock(blockName, maxSearchRadius);
        if (target == null) {
            logDirect("TaskPlan[" + blockName + "]: no cached positions found");
            return;
        }
        createInteractPlan(target);
    }

    @Override
    public void createContainerPlan(BlockPos target, ContainerAction action) {
        TaskPlanImpl p = new TaskPlanImpl("container_interact");
        p.addStep(STEP_PATH);
        p.addStep(STEP_INTERACT);
        p.addStep(STEP_AWAIT_CONTAINER);
        p.addStep(STEP_TRANSFER_ITEMS);
        p.addStep(STEP_CLOSE_CONTAINER);
        p.targetPos = target;
        p.containerAction = action;
        enqueuePlan(p);
    }

    @Override
    public void createContainerPlanByBlockName(String blockName, ContainerAction action, int maxSearchRadius) {
        net.minecraft.world.level.block.Block block = blockFromName(blockName);
        BlockPos target = findNearestBlock(blockName, block, maxSearchRadius);
        if (target == null) {
            logDirect("TaskPlan[" + blockName + "]: no positions found");
            return;
        }
        createContainerPlan(target, action);
    }

    @Override
    public void createSleepPlan() {
        TaskPlanImpl p = new TaskPlanImpl("sleep_in_bed");
        p.addStep(STEP_SCAN);
        p.addStep(STEP_BED_PATH);
        p.addStep(STEP_BED_INTERACT);
        p.addStep(STEP_BED_VERIFY);
        enqueuePlan(p);
    }

    @Override
    public void createSmeltPlan(net.minecraft.world.item.Item item, int count, String furnaceBlockName, int maxSearchRadius) {
        TaskPlanImpl p = buildSmeltPlan(item, count, furnaceBlockName, maxSearchRadius);
        enqueuePlan(p);
    }

    private TaskPlanImpl buildSmeltPlan(net.minecraft.world.item.Item item, int count, String furnaceName, int maxSearchRadius) {
        TaskPlanImpl p = new TaskPlanImpl("smelt_items");
        p.addStep(STEP_FIND_FURNACE);
        p.addStep(STEP_PATH);
        p.addStep(STEP_INTERACT);
        p.addStep(STEP_AWAIT_FURNACE_MENU);
        p.addStep(STEP_LOAD_FURNACE);
        p.addStep(STEP_MONITOR_SMELT);
        p.addStep(STEP_COLLECT_OUTPUT);
        p.addStep(STEP_CLOSE_CONTAINER);
        p.smeltItem = item;
        p.smeltItems = resolveSmeltInputs(item);
        p.smeltTargetCount = count;
        p.smeltFurnaceName = furnaceName;
        p.smeltMaxSearchRadius = Math.max(0, maxSearchRadius);
        p.smeltedSoFar = 0;
        p.smeltMonitorTick = 0;
        p.smeltLoadPhase = LOAD_PHASE_FUEL;
        p.smeltNoWorkVisits = 0;
        p.smeltDidWorkAtCurrentFurnace = false;
        return p;
    }

    // ─── Introspection ────────────────────────────────────────────────────────

    @Override
    public ITaskPlan currentPlan() { return plan; }

    @Override
    public void cancelPlan() {
        if (plan != null) abortPlan(TaskOutcome.CANCELLED);
        planQueue.clear();
        clearState();
    }

    @Override
    public void enqueuePlan(ITaskPlan p) {
        if (p == null) throw new IllegalArgumentException("plan must not be null");
        TaskPlanImpl impl = (TaskPlanImpl) p;
        if (plan == null) {
            this.plan = impl;
            this.stepIdx = 0;
            advanceToStep(0);
        } else {
            planQueue.addLast(impl);
            logDirect("TaskPlan: queued '" + impl.label() + "' (position " + planQueue.size() + " in queue)");
        }
    }

    @Override
    public void enqueuePlans(List<ITaskPlan> plans) {
        for (ITaskPlan p : plans) enqueuePlan(p);
    }

    @Override
    public void clearQueue() {
        if (planQueue.isEmpty()) return;
        int removed = planQueue.size();
        planQueue.clear();
        logDirect("TaskPlan: cleared " + removed + " queued plan(s)");
    }

    @Override public int queueSize() { return planQueue.size(); }

    @Override
    public int pendingCount() {
        return (plan != null && plan.status() == StepStatus.RUNNING ? 1 : 0) + planQueue.size();
    }

    @Override public boolean isActive() {
        return plan != null && plan.status() == StepStatus.RUNNING;
    }

    @Override public double priority() { return DEFAULT_PRIORITY + 0.5; }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (plan == null || !isActive())
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        if (stepIdx >= plan.mutableSteps().size()) {
            finishPlan(TaskOutcome.SUCCEEDED);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        TaskStepImpl currentStep = plan.mutableSteps().get(stepIdx);
        PathingCommand cmd = dispatchStep(currentStep.description(), calcFailed, isSafeToCancel);
        if (currentStep.status() == StepStatus.SUCCEEDED) {
            stepIdx++;
            if (stepIdx >= plan.mutableSteps().size()) {
                finishPlan(TaskOutcome.SUCCEEDED);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            advanceToStep(stepIdx);
        } else if (currentStep.status() == StepStatus.FAILED) {
            abortPlan(currentStep.outcome() != null ? currentStep.outcome() : TaskOutcome.INTERACTION_FAILED);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return cmd;
    }

    @Override
    public void onLostControl() {
        if (plan != null && plan.status() == StepStatus.RUNNING) abortPlan(TaskOutcome.CANCELLED);
        else clearState();
    }

    @Override
    public String displayName0() {
        if (plan == null) return "TaskPlan (idle)";
        return "TaskPlan[" + plan.label() + "] step " + (stepIdx + 1) + "/" + plan.mutableSteps().size();
    }

    @Override
    public void onPlayerDeath() {
        if (isActive()) {
            logDirect("TaskPlan: player died – aborting plan '" + plan.label() + "'");
            abortPlan(TaskOutcome.PLAYER_DIED);
        }
    }

    // ─── Step dispatch ────────────────────────────────────────────────────────

    private PathingCommand dispatchStep(String tag, boolean calcFailed, boolean isSafeToCancel) {
        switch (tag) {
            case STEP_PATH:            return tickPathToBlock(calcFailed);
            case STEP_INTERACT:        return tickInteractWithBlock(calcFailed);
            case STEP_AWAIT_CONTAINER: return tickAwaitContainer();
            case STEP_TRANSFER_ITEMS:  return tickTransferItems();
            case STEP_CLOSE_CONTAINER: return tickCloseContainer();
            case STEP_SCAN:            return tickScanForBed();
            case STEP_BED_PATH:        return tickPathToBed(calcFailed);
            case STEP_BED_INTERACT:    return tickInteractWithBed();
            case STEP_BED_VERIFY:      return tickVerifySleep();
            case STEP_FIND_FURNACE:    return tickFindFurnace();
            case STEP_AWAIT_FURNACE_MENU: return tickAwaitFurnaceMenu();
            case STEP_LOAD_FURNACE:    return tickLoadFurnace();
            case STEP_MONITOR_SMELT:   return tickMonitorSmelt();
            case STEP_COLLECT_OUTPUT:   return tickCollectOutput();
            default:
                logDirect("TaskPlan: unknown step tag '" + tag + "', skipping");
                succeedStep();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
    }

    // ── Interact-plan step handlers (use plan.targetPos) ──────────────────────

    private PathingCommand tickPathToBlock(boolean calcFailed) {
        BlockPos tp = plan.targetPos;
        if (tp == null) { failStep(TaskOutcome.NOT_FOUND); return cancelPath(); }
        if (calcFailed) {
            if (++calcFailCount >= MAX_CALC_FAILURES) {
                failStep(TaskOutcome.UNREACHABLE); return cancelPath();
            }
        }
        if (RotationUtils.reachable(ctx, tp, reachDist()).isPresent()) {
            succeedStep();
            return pause();
        }
        return new PathingCommand(new GoalGetToBlock(tp), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand tickInteractWithBlock(boolean calcFailed) {
        BlockPos tp = plan.targetPos;
        if (tp == null) { failStep(TaskOutcome.NOT_FOUND); return cancelPath(); }
        if (calcFailed) {
            if (++calcFailCount >= MAX_CALC_FAILURES) {
                failStep(TaskOutcome.UNREACHABLE); return cancelPath();
            }
        }
        Optional<Rotation> rot = RotationUtils.reachable(ctx, tp, reachDist());
        if (!rot.isPresent()) {
            interactTick = 0;
            return new PathingCommand(new GoalGetToBlock(tp), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        baritone.getLookBehavior().updateTarget(rot.get(), true);
        if (interactTick == 0 || interactTick % 4 == 0) {
            sendUsePacket(tp);
            ctx.player().swing(InteractionHand.MAIN_HAND);
        }
        if (++interactTick >= INTERACT_TIMEOUT) {
            failStep(TaskOutcome.TIMEOUT); return cancelPath();
        }
        if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
            baritone.getInputOverrideHandler().clearAllKeys();
            succeedStep();
            return cancelPath();
        }
        if (isContainerOrSmeltPlan()) return pause();
        if (interactTick >= 4) {
            baritone.getInputOverrideHandler().clearAllKeys();
            succeedStep();
            return cancelPath();
        }
        return pause();
    }

    // ── Container-plan step handlers (use plan.containerAction) ───────────────

    private PathingCommand tickAwaitContainer() {
        if (!(ctx.player().containerMenu instanceof InventoryMenu)) { succeedStep(); return pause(); }
        if (++interactTick >= INTERACT_TIMEOUT) { failStep(TaskOutcome.TIMEOUT); return cancelPath(); }
        return pause();
    }

    private PathingCommand tickTransferItems() {
        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (menu instanceof InventoryMenu) { failStep(TaskOutcome.INTERACTION_FAILED); return cancelPath(); }
        ContainerAction action = plan.containerAction;
        if (action == null) { succeedStep(); return pause(); }
        if (!menu.getCarried().isEmpty()) { failStep(TaskOutcome.INTERACTION_FAILED); return cancelPath(); }
        int src = findTransferSourceSlot(menu, action);
        if (src < 0) {
            if (action.movesAllMatchingItems()) { succeedStep(); }
            else { failStep(TaskOutcome.NOT_FOUND); }
            return pause();
        }
        int cnt = menu.getSlot(src).getItem().getCount();
        if (transferRemaining > 0 && cnt > transferRemaining) {
            int empty = findFirstEmptyOppositeSlot(menu, action);
            if (empty < 0) { failStep(TaskOutcome.INTERACTION_FAILED); return cancelPath(); }
            transferPhase = TRANSFER_PHASE_PLACE_TEMP;
            transferSourceSlot = src;
            transferTempSlot = empty;
            transferGrabbed = cnt;
            transferToReturn = cnt - transferRemaining;
            ctx.playerController().windowClick(menu.containerId, src, 0, ClickType.PICKUP, ctx.player());
            return pause();
        }
        if (transferPhase == TRANSFER_PHASE_PLACE_TEMP) {
            if (menu.getCarried().isEmpty()) return pause();
            ctx.playerController().windowClick(menu.containerId, transferTempSlot, 0, ClickType.PICKUP, ctx.player());
            transferPhase = TRANSFER_PHASE_RETURN_EXCESS;
            return pause();
        }
        if (transferPhase == TRANSFER_PHASE_RETURN_EXCESS) {
            if (menu.getCarried().isEmpty()) {
                ctx.playerController().windowClick(menu.containerId, transferTempSlot, 0, ClickType.PICKUP, ctx.player());
                return pause();
            }
            if (transferToReturn > 0) {
                ctx.playerController().windowClick(menu.containerId, transferSourceSlot, 1, ClickType.PICKUP, ctx.player());
                transferToReturn--;
                return pause();
            }
            ctx.playerController().windowClick(menu.containerId, transferTempSlot, 0, ClickType.PICKUP, ctx.player());
            transferPhase = TRANSFER_PHASE_DEPOSIT_REMAINING;
            return pause();
        }
        if (transferPhase == TRANSFER_PHASE_DEPOSIT_REMAINING) {
            if (!menu.getCarried().isEmpty()) return pause();
            transferPhase = TRANSFER_PHASE_NONE;
            transferSourceSlot = transferTempSlot = -1;
            transferGrabbed = transferToReturn = transferRemaining = 0;
            succeedStep();
            return pause();
        }
        ItemStack before = menu.getSlot(src).getItem().copy();
        ctx.playerController().windowClick(menu.containerId, src, 0, ClickType.QUICK_MOVE, ctx.player());
        ItemStack after = menu.getSlot(src).getItem();
        boolean moved = after.isEmpty() || after.getItem() != before.getItem() || after.getCount() != before.getCount();
        if (!moved) { failStep(TaskOutcome.INTERACTION_FAILED); return cancelPath(); }
        if (transferRemaining > 0) transferRemaining -= before.getCount();
        if (transferRemaining <= 0 && !action.movesAllMatchingItems()) succeedStep();
        return pause();
    }

    private PathingCommand tickCloseContainer() {
        if (!(ctx.player().containerMenu instanceof InventoryMenu)) ctx.player().closeContainer();
        succeedStep();
        return pause();
    }

    // ── Sleep-plan step handlers (use plan.targetPos / plan.bedCandidates) ────

    private PathingCommand tickScanForBed() {
        if (!isNightOrThunder()) { failStep(TaskOutcome.NOT_NIGHT); return cancelPath(); }
        plan.bedCandidates = findNearbyBeds();
        if (plan.bedCandidates.isEmpty()) { failStep(TaskOutcome.NOT_FOUND); return cancelPath(); }
        BetterBlockPos pf = ctx.playerFeet();
        plan.bedCandidates.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b)));
        plan.targetPos = plan.bedCandidates.get(0);
        succeedStep();
        return pause();
    }

    private PathingCommand tickPathToBed(boolean calcFailed) {
        BlockPos tp = plan.targetPos;
        if (tp == null) { failStep(TaskOutcome.NOT_FOUND); return cancelPath(); }
        if (calcFailed) {
            calcFailCount++;
            if (plan.bedCandidates != null) {
                plan.bedCandidates.remove(tp);
                if (!plan.bedCandidates.isEmpty()) {
                    plan.targetPos = plan.bedCandidates.get(0);
                    calcFailCount = 0;
                    return new PathingCommand(buildBedGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
                }
            }
            if (calcFailCount >= MAX_CALC_FAILURES) { failStep(TaskOutcome.UNREACHABLE); return cancelPath(); }
        }
        Optional<Rotation> rot = RotationUtils.reachable(ctx, tp, reachDist());
        if (rot.isPresent()) {
            BlockState state = ctx.world().getBlockState(tp);
            if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.OCCUPIED)) {
                if (plan.bedCandidates != null) {
                    plan.bedCandidates.remove(tp);
                    if (!plan.bedCandidates.isEmpty()) {
                        plan.targetPos = plan.bedCandidates.get(0);
                        calcFailCount = 0;
                        return new PathingCommand(buildBedGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
                    }
                }
                failStep(TaskOutcome.OCCUPIED); return cancelPath();
            }
            interactTick = 0;
            baritone.getPathingBehavior().cancelEverything();
            succeedStep();
            return pause();
        }
        return new PathingCommand(buildBedGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand tickInteractWithBed() {
        if (!isNightOrThunder()) { failStep(TaskOutcome.NOT_NIGHT); return cancelPath(); }
        BlockPos tp = plan.targetPos;
        if (tp == null) { failStep(TaskOutcome.NOT_FOUND); return cancelPath(); }
        Optional<Rotation> rot = RotationUtils.reachable(ctx, tp, reachDist());
        if (!rot.isPresent()) return new PathingCommand(buildBedGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        baritone.getLookBehavior().updateTarget(rot.get(), true);
        if (++interactTick >= INTERACT_TIMEOUT) { failStep(TaskOutcome.TIMEOUT); return cancelPath(); }
        if (ctx.isLookingAt(tp)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            if (ctx.player().isSleeping()) {
                baritone.getInputOverrideHandler().clearAllKeys();
                verifyTick = 0;
                succeedStep();
                return pause();
            }
        }
        return pause();
    }

    private PathingCommand tickVerifySleep() {
        if (ctx.player().isSleeping()) {
            if (++verifyTick >= 5) succeedStep();
            return pause();
        }
        if (++verifyTick >= SLEEP_VERIFY_TICKS) { failStep(TaskOutcome.INTERACTION_FAILED); return cancelPath(); }
        return pause();
    }

    // ── Smelt-plan step handlers (use plan.smeltItem and friends) ─────────────

    private PathingCommand tickFindFurnace() {
        if (plan.smeltFurnaceName == null || plan.smeltFurnaceName.isEmpty()) plan.smeltFurnaceName = "furnace";
        net.minecraft.world.level.block.Block fb = blockFromName(plan.smeltFurnaceName);
        BlockPos target = findNearestBlock(plan.smeltFurnaceName, fb, plan.smeltMaxSearchRadius);
        if (target == null) { failStep(TaskOutcome.NOT_FOUND); return cancelPath(); }
        plan.smeltFurnaces = findConnectedFurnaces(target, fb);
        if (plan.smeltFurnaces.isEmpty()) {
            plan.smeltFurnaces = List.of(target);
        }
        plan.smeltFurnaceIndex = 0;
        plan.targetPos = plan.smeltFurnaces.get(0);
        plan.smeltNoWorkVisits = 0;
        plan.smeltDidWorkAtCurrentFurnace = false;
        succeedStep();
        return pause();
    }

    private PathingCommand tickAwaitFurnaceMenu() {
        if (!(ctx.player().containerMenu instanceof InventoryMenu)) { succeedStep(); return pause(); }
        if (++interactTick >= INTERACT_TIMEOUT) { failStep(TaskOutcome.TIMEOUT); return cancelPath(); }
        return pause();
    }

    private PathingCommand tickLoadFurnace() {
        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (menu instanceof InventoryMenu) { failStep(TaskOutcome.INTERACTION_FAILED); return cancelPath(); }
        if (plan.smeltItem == null) { succeedStep(); return pause(); }
        int fuelSlot = 1, inputSlot = 0, playerInvStart = firstPlayerInventorySlot(menu);
        if (plan.smeltLoadPhase == LOAD_PHASE_FUEL) {
            boolean hasInputReady = hasSmeltInputInInventory(menu) || isSmeltInputSlot(menu.getSlot(inputSlot));
            if (hasInputReady && !hasUsableFuel(menu.getSlot(fuelSlot))) {
                int fuelSrc = findFuelSlot(menu, playerInvStart);
                if (fuelSrc < 0) { failStep(TaskOutcome.INTERACTION_FAILED); return cancelPath(); }
                ctx.playerController().windowClick(menu.containerId, fuelSrc, 0, ClickType.QUICK_MOVE, ctx.player());
                plan.smeltDidWorkAtCurrentFurnace = true;
                return pause();
            }
            plan.smeltLoadPhase = LOAD_PHASE_INPUT;
            return pause();
        }
        int inputInInv = countSmeltInputInInventory(menu);
        if (inputInInv <= 0) { succeedStep(); return pause(); }
        if (!menu.getSlot(inputSlot).hasItem() || isSmeltInputSlot(menu.getSlot(inputSlot))) {
            for (int i = playerInvStart; i < menu.slots.size(); i++)
                if (menu.getSlot(i).hasItem() && isSmeltInput(menu.getSlot(i).getItem().getItem())) {
                    ctx.playerController().windowClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());
                    plan.smeltMonitorTick = 0;
                    plan.smeltDidWorkAtCurrentFurnace = true;
                    succeedStep();
                    return pause();
                }
        }
        plan.smeltLoadPhase = LOAD_PHASE_FUEL;
        plan.smeltMonitorTick = 0;
        succeedStep();
        return pause();
    }

    private PathingCommand tickMonitorSmelt() {
        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (menu instanceof InventoryMenu) { failStep(TaskOutcome.INTERACTION_FAILED); return cancelPath(); }
        if (menu.getSlot(2).hasItem()) { succeedStep(); return pause(); }
        if (!hasSmeltInputInInventory(menu) && !isSmeltInputSlot(menu.getSlot(0))) {
            succeedStep();
            return pause();
        }
        plan.smeltMonitorTick++;
        if (isMultiFurnaceSmelt() && plan.smeltMonitorTick >= SMELT_ROTATION_TICKS) {
            succeedStep();
            return pause();
        }
        if (plan.smeltMonitorTick >= SMELT_TIMEOUT_TICKS) { failStep(TaskOutcome.TIMEOUT); return cancelPath(); }
        return pause();
    }

    private PathingCommand tickCollectOutput() {
        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (menu instanceof InventoryMenu) { failStep(TaskOutcome.INTERACTION_FAILED); return cancelPath(); }
        if (menu.getSlot(2).hasItem()) {
            int collected = menu.getSlot(2).getItem().getCount();
            ctx.playerController().windowClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, ctx.player());
            plan.smeltedSoFar += collected;
            plan.smeltDidWorkAtCurrentFurnace = true;
            plan.smeltNoWorkVisits = 0;
            if (plan.smeltTargetCount > 0 && plan.smeltedSoFar >= plan.smeltTargetCount) { succeedStep(); return pause(); }
        }
        if (isMultiFurnaceSmelt()) {
            boolean hasInventoryInput = hasSmeltInputInInventory(menu);
            boolean currentFurnaceActive = isSmeltInputSlot(menu.getSlot(0)) || menu.getSlot(2).hasItem();
            boolean shouldKeepCycling = hasInventoryInput || currentFurnaceActive || plan.smeltDidWorkAtCurrentFurnace;

            if (shouldKeepCycling) {
                plan.smeltNoWorkVisits = 0;
                plan.smeltDidWorkAtCurrentFurnace = false;
                advanceSmeltFurnaceTarget();
                plan.smeltLoadPhase = LOAD_PHASE_FUEL;
                plan.smeltMonitorTick = 0;
                ctx.player().closeContainer();
                stepIdx = 0; // next tick resumes at PATH to reach the next furnace target
            } else if (++plan.smeltNoWorkVisits < plan.smeltFurnaces.size()) {
                advanceSmeltFurnaceTarget();
                plan.smeltLoadPhase = LOAD_PHASE_FUEL;
                plan.smeltMonitorTick = 0;
                ctx.player().closeContainer();
                stepIdx = 0;
            }
        } else if (hasSmeltInputInInventory(menu)) {
            plan.smeltLoadPhase = LOAD_PHASE_FUEL;
            plan.smeltMonitorTick = 0;
            stepIdx = 3; // next tick resumes at LOAD_FURNACE while the same furnace menu stays open
        }
        succeedStep();
        return pause();
    }

    private boolean isSmeltInput(net.minecraft.world.item.Item item) {
        return plan != null && plan.smeltItems != null && plan.smeltItems.contains(item);
    }

    private boolean isSmeltInputSlot(Slot slot) {
        return slot != null && slot.hasItem() && isSmeltInput(slot.getItem().getItem());
    }

    private boolean hasUsableFuel(Slot slot) {
        return slot != null && slot.hasItem() && isKnownFurnaceFuel(slot.getItem());
    }

    private int findFuelSlot(AbstractContainerMenu menu, int playerInvStart) {
        for (int i = playerInvStart; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.hasItem() && isKnownFurnaceFuel(slot.getItem())) {
                return i;
            }
        }
        return -1;
    }

    private boolean isKnownFurnaceFuel(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        net.minecraft.world.item.Item item = stack.getItem();
        return item == Items.COAL
                || item == Items.CHARCOAL
                || item == Items.COAL_BLOCK
                || item == Items.BLAZE_ROD
                || item == Items.DRIED_KELP_BLOCK
                || item == Items.BAMBOO
                || item == Items.STICK;
    }

    private boolean hasSmeltInputInInventory(AbstractContainerMenu menu) {
        return countSmeltInputInInventory(menu) > 0;
    }

    private int countSmeltInputInInventory(AbstractContainerMenu menu) {
        int inputInInv = 0;
        int playerInvStart = firstPlayerInventorySlot(menu);
        for (int i = playerInvStart; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).hasItem() && isSmeltInput(menu.getSlot(i).getItem().getItem())) {
                inputInInv += menu.getSlot(i).getItem().getCount();
            }
        }
        return inputInInv;
    }

    private boolean isMultiFurnaceSmelt() {
        return plan != null && plan.smeltFurnaces != null && plan.smeltFurnaces.size() > 1;
    }

    private List<net.minecraft.world.item.Item> resolveSmeltInputs(net.minecraft.world.item.Item requestedItem) {
        if (!(requestedItem instanceof BlockItem requestedBlockItem)) {
            return List.of(requestedItem);
        }

        if (requestedBlockItem.getBlock().defaultBlockState().is(BlockTags.IRON_ORES)) {
            return collectBlockTagItems(BlockTags.IRON_ORES, requestedItem);
        }
        if (requestedBlockItem.getBlock().defaultBlockState().is(BlockTags.COPPER_ORES)) {
            return collectBlockTagItems(BlockTags.COPPER_ORES, requestedItem);
        }
        if (requestedBlockItem.getBlock().defaultBlockState().is(BlockTags.GOLD_ORES)) {
            return collectBlockTagItems(BlockTags.GOLD_ORES, requestedItem);
        }
        if (requestedBlockItem.getBlock().defaultBlockState().is(BlockTags.COAL_ORES)) {
            return collectBlockTagItems(BlockTags.COAL_ORES, requestedItem);
        }
        if (requestedBlockItem.getBlock().defaultBlockState().is(BlockTags.DIAMOND_ORES)) {
            return collectBlockTagItems(BlockTags.DIAMOND_ORES, requestedItem);
        }
        if (requestedBlockItem.getBlock().defaultBlockState().is(BlockTags.EMERALD_ORES)) {
            return collectBlockTagItems(BlockTags.EMERALD_ORES, requestedItem);
        }
        if (requestedBlockItem.getBlock().defaultBlockState().is(BlockTags.LAPIS_ORES)) {
            return collectBlockTagItems(BlockTags.LAPIS_ORES, requestedItem);
        }
        if (requestedBlockItem.getBlock().defaultBlockState().is(BlockTags.REDSTONE_ORES)) {
            return collectBlockTagItems(BlockTags.REDSTONE_ORES, requestedItem);
        }

        return List.of(requestedItem);
    }

    private List<net.minecraft.world.item.Item> collectBlockTagItems(net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tag,
                                                                     net.minecraft.world.item.Item fallbackItem) {
        LinkedHashSet<net.minecraft.world.item.Item> items = new LinkedHashSet<>();
        BuiltInRegistries.ITEM.stream()
                .filter(BlockItem.class::isInstance)
                .map(BlockItem.class::cast)
                .filter(blockItem -> blockItem.getBlock().defaultBlockState().is(tag))
                .map(net.minecraft.world.item.Item.class::cast)
                .forEach(items::add);
        if (items.isEmpty()) {
            items.add(fallbackItem);
        }
        return List.copyOf(items);
    }

    private boolean advanceSmeltFurnaceTarget() {
        if (plan == null || plan.smeltFurnaces == null || plan.smeltFurnaces.isEmpty()) {
            return false;
        }
        plan.smeltFurnaceIndex = (plan.smeltFurnaceIndex + 1) % plan.smeltFurnaces.size();
        plan.targetPos = plan.smeltFurnaces.get(plan.smeltFurnaceIndex);
        return true;
    }

    private List<BlockPos> findConnectedFurnaces(BlockPos start, net.minecraft.world.level.block.Block block) {
        if (start == null || block == null) {
            return List.of();
        }

        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        ArrayList<BlockPos> found = new ArrayList<>();
        frontier.add(start);
        visited.add(start);

        while (!frontier.isEmpty()) {
            BlockPos pos = frontier.removeFirst();
            if (!ctx.world().getBlockState(pos).is(block)) {
                continue;
            }
            found.add(pos);
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (visited.add(next) && ctx.world().getBlockState(next).is(block)) {
                    frontier.addLast(next);
                }
            }
        }

        if (found.isEmpty()) {
            return List.of(start);
        }

        BetterBlockPos pf = ctx.playerFeet();
        found.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b)));
        return List.copyOf(found);
    }

    // ─── Plan/step lifecycle helpers ─────────────────────────────────────────

    private void advanceToStep(int idx) {
        interactTick = calcFailCount = verifyTick = 0;
        ContainerAction action = plan.containerAction;
        transferRemaining = action == null ? 0 : (action.movesAllMatchingItems() ? -1 : action.getCount());
        transferPhase = TRANSFER_PHASE_NONE;
        transferSourceSlot = transferTempSlot = -1;
        transferGrabbed = transferToReturn = 0;
        plan.markRunning(idx);
        plan.mutableSteps().get(idx).setRunning();
    }

    private void clearState() {
        plan = null;
        stepIdx = 0;
        interactTick = calcFailCount = verifyTick = 0;
        transferRemaining = 0;
        transferPhase = TRANSFER_PHASE_NONE;
        transferSourceSlot = transferTempSlot = -1;
        transferGrabbed = transferToReturn = 0;
    }
    private void succeedStep() {
        if (plan == null || stepIdx >= plan.mutableSteps().size()) return;
        plan.mutableSteps().get(stepIdx).succeed();
    }
    private void failStep(TaskOutcome r) {
        if (plan == null || stepIdx >= plan.mutableSteps().size()) return;
        plan.mutableSteps().get(stepIdx).fail(r);
    }
    private void finishPlan(TaskOutcome o) {
        String label = plan.label();
        plan.markSucceeded();
        clearState();
        logDirect("[Baritone] Task complete: " + label);
        if (!planQueue.isEmpty()) {
            this.plan = planQueue.pollFirst();
            this.stepIdx = 0;
            advanceToStep(0);
        } else {
            logDirect("[Baritone] All queued tasks complete");
        }
    }
    private void abortPlan(TaskOutcome r) {
        if (plan == null) return;
        String label = plan.label();
        if (stepIdx < plan.mutableSteps().size()) {
            TaskStepImpl cur = plan.mutableSteps().get(stepIdx);
            if (cur.status() == StepStatus.RUNNING) cur.fail(r);
        }
        for (int i = stepIdx + 1; i < plan.mutableSteps().size(); i++) plan.mutableSteps().get(i).cancel();
        plan.markFailed(r);
        logDirect("[Baritone] Task failed: " + label + " - " + r);
        clearState();
        planQueue.clear();
    }

    // ─── Block-finding utilities ──────────────────────────────────────────────

    private BlockPos findPositionForBlock(String blockName, int maxSearchRadius) {
        var cachedWorld = baritone.getWorldProvider().getCurrentWorld().getCachedWorld();
        if (cachedWorld == null) { logDirect("TaskPlan: no cached world"); return null; }
        BetterBlockPos pf = ctx.playerFeet();
        ArrayList<BlockPos> positions = cachedWorld.getLocationsOf(blockName, Integer.MAX_VALUE, pf.x, pf.z, maxSearchRadius);
        if (positions.isEmpty()) {
            BaritoneAPI.getProvider().getWorldScanner().repack(ctx);
            positions = cachedWorld.getLocationsOf(blockName, Integer.MAX_VALUE, pf.x, pf.z, maxSearchRadius);
        }
        if (positions.isEmpty()) return null;
        positions.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b)));
        return positions.get(0);
    }

    private static net.minecraft.world.level.block.Block blockFromName(String name) {
        for (net.minecraft.world.level.block.Block b : BuiltInRegistries.BLOCK)
            if (BuiltInRegistries.BLOCK.getKey(b).getPath().equals(name)) return b;
        return null;
    }

    private BlockPos findNearestBlock(String blockName, net.minecraft.world.level.block.Block block, int maxSearchRadius) {
        var world = baritone.getWorldProvider().getCurrentWorld();
        var cachedWorld = world.getCachedWorld();
        BetterBlockPos pf = ctx.playerFeet();
        if (cachedWorld != null) {
            ArrayList<BlockPos> positions = cachedWorld.getLocationsOf(blockName, Integer.MAX_VALUE, pf.x, pf.z, maxSearchRadius);
            if (!positions.isEmpty()) { positions.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b))); return positions.get(0); }
        }
        BaritoneAPI.getProvider().getWorldScanner().repack(ctx);
        if (cachedWorld != null) {
            ArrayList<BlockPos> positions = cachedWorld.getLocationsOf(blockName, Integer.MAX_VALUE, pf.x, pf.z, maxSearchRadius);
            if (!positions.isEmpty()) { positions.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b))); return positions.get(0); }
        }
        if (block != null) {
            java.util.List<net.minecraft.world.level.block.Block> target = java.util.Collections.singletonList(block);
            java.util.List<BlockPos> scanned = BaritoneAPI.getProvider().getWorldScanner()
                    .scanChunkRadius(ctx, target, Math.max(0, maxSearchRadius * 16), 0, 256);
            if (scanned != null && !scanned.isEmpty()) { scanned.sort((a, b) -> Double.compare(pf.distSqr(a), pf.distSqr(b))); return scanned.get(0); }
        }
        return null;
    }

    // ─── World helpers ────────────────────────────────────────────────────────

    private List<BlockPos> findNearbyBeds() {
        List<BlockPos> r = new ArrayList<>();
        BetterBlockPos pf = ctx.playerFeet();
        int minY = Math.max(ctx.world().getMinY(), pf.y - BED_SCAN_RADIUS);
        int maxY = Math.min(ctx.world().getMaxY(), pf.y + BED_SCAN_RADIUS + 1);
        for (int x = pf.x - BED_SCAN_RADIUS; x <= pf.x + BED_SCAN_RADIUS; x++)
            for (int z = pf.z - BED_SCAN_RADIUS; z <= pf.z + BED_SCAN_RADIUS; z++)
                for (int y = minY; y < maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = ctx.world().getBlockState(pos);
                    if (BED_BLOCKS.contains(state.getBlock()) && state.getValue(BedBlock.PART) == BedPart.FOOT) r.add(pos);
                }
        return r;
    }
    private boolean isNightOrThunder() {
        long dayTime = ctx.world().getDayTime() % 24000L;
        return dayTime >= NIGHT_START_TICK || ctx.world().isThundering();
    }
    private baritone.api.pathing.goals.Goal buildBedGoal() {
        if (plan.bedCandidates != null && plan.bedCandidates.size() > 1)
            return new GoalComposite(plan.bedCandidates.stream().map(GoalGetToBlock::new).toArray(baritone.api.pathing.goals.Goal[]::new));
        return new GoalGetToBlock(plan.targetPos);
    }
    private double reachDist() { return Math.max(0.0, ctx.playerController().getBlockReachDistance() - 0.1); }
    private boolean isContainerOrSmeltPlan() {
        return plan != null && ("container_interact".equals(plan.label()) || "smelt_items".equals(plan.label()));
    }
    private void sendUsePacket(BlockPos pos) {
        Vec3 hit = Vec3.atCenterOf(pos);
        Vec3 eye = ctx.player().getEyePosition();
        Direction face = nearestFace(eye.x - hit.x, eye.y - hit.y, eye.z - hit.z);
        ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
    }

    // ─── Slot helpers ─────────────────────────────────────────────────────────
    private int findTransferSourceSlot(AbstractContainerMenu menu, ContainerAction action) {
        if (action == null) return -1;
        if (action.getType() == ContainerAction.Type.DUMP_ALL) return findFirstMovablePlayerSlot(menu, null);
        int pi = firstPlayerInventorySlot(menu);
        switch (action.getType()) {
            case WITHDRAW: return findBestMatchingSlot(menu, 0, pi, action.getTargetItem());
            case DEPOSIT:  return findBestMatchingSlot(menu, pi, menu.slots.size(), action.getTargetItem());
            default: return -1;
        }
    }
    private int findBestMatchingSlot(AbstractContainerMenu menu, int start, int end, net.minecraft.world.item.Item item) {
        int bestBelowRemainingSlot = -1, bestBelowRemainingCount = -1, bestOverflowSlot = -1, bestOverflowCount = Integer.MAX_VALUE;
        for (int i = start; i < end; i++) {
            Slot slot = menu.getSlot(i);
            if (!slot.hasItem() || slot.getItem().getItem() != item) continue;
            int c = slot.getItem().getCount();
            if (transferRemaining > 0) {
                if (c == transferRemaining) return i;
                if (c > transferRemaining && c < bestOverflowCount) { bestOverflowCount = c; bestOverflowSlot = i; }
                else if (c < transferRemaining && c > bestBelowRemainingCount) { bestBelowRemainingCount = c; bestBelowRemainingSlot = i; }
            } else return i;
        }
        if (transferRemaining > 0) return bestOverflowSlot >= 0 ? bestOverflowSlot : bestBelowRemainingSlot;
        return -1;
    }
    private int findFirstMovablePlayerSlot(AbstractContainerMenu menu, net.minecraft.world.item.Item item) {
        int pi = firstPlayerInventorySlot(menu);
        for (int i = pi; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.hasItem() && (item == null || s.getItem().getItem() == item)) return i;
        }
        return -1;
    }
    private int findFirstEmptyOppositeSlot(AbstractContainerMenu menu, ContainerAction action) {
        if (action == null) return -1;
        int pi = firstPlayerInventorySlot(menu);
        if (action.getType() == ContainerAction.Type.WITHDRAW) {
            for (int i = pi; i < menu.slots.size(); i++) if (!menu.getSlot(i).hasItem()) return i;
        } else {
            for (int i = 0; i < pi; i++) if (!menu.getSlot(i).hasItem()) return i;
        }
        return -1;
    }
    private int firstPlayerInventorySlot(AbstractContainerMenu menu) { return Math.max(0, menu.slots.size() - 36); }
    private Direction nearestFace(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
    private PathingCommand pause() { return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE); }
    private PathingCommand cancelPath() { return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL); }
}
