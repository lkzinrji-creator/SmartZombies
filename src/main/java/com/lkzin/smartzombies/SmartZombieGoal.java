package com.lkzin.smartzombies;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class SmartZombieGoal extends Goal {
    private final Zombie zombie;
    private Player target;
    private int actionCooldown;
    private int scanCooldown;
    private int bridgeCooldown;

    private static final double MAX_THINK_DISTANCE = 48.0D;
    private static final double BREAK_DISTANCE = 3.2D;

    public SmartZombieGoal(Zombie zombie) {
        this.zombie = zombie;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(zombie.level() instanceof ServerLevel)) return false;
        target = findTarget();
        return target != null && target.isAlive() &&
                zombie.distanceToSqr(target) <= MAX_THINK_DISTANCE * MAX_THINK_DISTANCE;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive()
                && zombie.distanceToSqr(target) <= 72 * 72;
    }

    @Override
    public void stop() {
        target = null;
        actionCooldown = 0;
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) return;

        zombie.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (--scanCooldown <= 0) {
            scanCooldown = 10;
            collectNearbyItems();
            chooseUsefulTool();
        }

        if (--actionCooldown <= 0) {
            actionCooldown = 8;

            if (tryOpenPath()) return;
            if (tryBuildBridge()) return;
            if (tryBuildStairs()) return;
        }

        // Keep ordinary navigation active when no construction action is needed.
        zombie.getNavigation().moveTo(target, 1.12D);
    }

    private Player findTarget() {
        List<Player> players = zombie.level().getEntitiesOfClass(
                Player.class,
                new AABB(zombie.blockPosition()).inflate(MAX_THINK_DISTANCE),
                p -> p.isAlive() && !p.isSpectator()
        );
        return players.stream()
                .min(Comparator.comparingDouble(zombie::distanceToSqr))
                .orElse(null);
    }

    private void collectNearbyItems() {
        AABB box = zombie.getBoundingBox().inflate(6.0D);
        List<ItemEntity> items = zombie.level().getEntitiesOfClass(
                ItemEntity.class, box, e -> !e.getItem().isEmpty());

        ItemEntity best = items.stream()
                .filter(e -> isUseful(e.getItem()))
                .min(Comparator.comparingDouble(zombie::distanceToSqr))
                .orElse(null);

        if (best != null && zombie.distanceToSqr(best) > 2.2D) {
            zombie.getNavigation().moveTo(best, 1.18D);
            zombie.getLookControl().setLookAt(best, 30, 30);
        }

        // Zombies can pick up loot through vanilla Mob mechanics.
        // Giving them the pickup ability is done when they spawn.
    }

    private boolean isUseful(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof PickaxeItem
                || item instanceof AxeItem
                || item instanceof ShovelItem
                || item.isEdible()
                || Block.byItem(item) != Blocks.AIR;
    }

    private void chooseUsefulTool() {
        if (!(zombie.level() instanceof ServerLevel level)) return;

        // Prefer tools from nearby dropped items and visually equip them.
        List<ItemEntity> items = level.getEntitiesOfClass(
                ItemEntity.class,
                zombie.getBoundingBox().inflate(4),
                e -> isTool(e.getItem())
        );

        ItemEntity best = items.stream()
                .min(Comparator.comparingDouble(zombie::distanceToSqr))
                .orElse(null);

        if (best == null) return;

        ItemStack stack = best.getItem();
        if (zombie.getMainHandItem().isEmpty()) {
            zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                    stack.copyWithCount(1));
            stack.shrink(1);
        }
    }

    private boolean isTool(ItemStack stack) {
        return stack.getItem() instanceof DiggerItem;
    }

    private boolean tryOpenPath() {
        BlockPos base = zombie.blockPosition();
        Direction dir = directionToTarget();

        // Check the two blocks at head/body height in front.
        for (int y = 0; y <= 1; y++) {
            BlockPos pos = base.relative(dir).above(y);
            BlockState state = zombie.level().getBlockState(pos);

            if (!state.isAir() && isBreakableForZombie(state)) {
                if (zombie.distanceToSqr(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5)
                        <= BREAK_DISTANCE * BREAK_DISTANCE) {
                    breakBlock(pos, state);
                    return true;
                }
                zombie.getNavigation().moveTo(pos.getX()+0.5, pos.getY(), pos.getZ()+0.5, 1.1D);
                return true;
            }
        }
        return false;
    }

    private boolean tryBuildBridge() {
        if (++bridgeCooldown < 6) return false;
        bridgeCooldown = 0;

        BlockPos base = zombie.blockPosition();
        Direction dir = directionToTarget();
        BlockPos ahead = base.relative(dir);

        // Detect a short gap one block down. Place a solid block under the route.
        if (zombie.level().isEmptyBlock(ahead)
                && zombie.level().isEmptyBlock(ahead.below())
                && hasBuildingMaterial()) {

            BlockPos place = ahead.below();
            if (zombie.level().isEmptyBlock(place) && place.getY() > zombie.level().getMinBuildHeight()) {
                placeBlock(place);
                return true;
            }
        }

        return false;
    }

    private boolean tryBuildStairs() {
        BlockPos base = zombie.blockPosition();
        int dy = target.blockPosition().getY() - base.getY();
        if (Math.abs(dy) < 2 || !hasBuildingMaterial()) return false;

        Direction dir = directionToTarget();
        if (dy > 0) {
            BlockPos step = base.relative(dir);
            if (zombie.level().isEmptyBlock(step) && zombie.level().isEmptyBlock(step.above())) {
                placeBlock(step);
                return true;
            }
        } else {
            BlockPos below = base.below();
            if (zombie.level().isEmptyBlock(below)) {
                placeBlock(below);
                return true;
            }
        }
        return false;
    }

    private Direction directionToTarget() {
        double dx = target.getX() - zombie.getX();
        double dz = target.getZ() - zombie.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean isBreakableForZombie(BlockState state) {
        if (state.isAir()) return false;
        if (state.getDestroySpeed(zombie.level(), zombie.blockPosition()) < 0) return false;
        return state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.OAK_LOG)
                || state.is(Blocks.SPRUCE_LOG)
                || state.is(Blocks.BIRCH_LOG)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.STONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.OAK_PLANKS)
                || state.is(Blocks.SPRUCE_PLANKS);
    }

    private void breakBlock(BlockPos pos, BlockState state) {
        if (!(zombie.level() instanceof ServerLevel level)) return;

        // Simulated tool use: the equipped tool affects the visual behavior,
        // while the server performs a safe, bounded block break.
        if (zombie.getMainHandItem().isEmpty()) {
            zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                    new ItemStack(Items.WOODEN_PICKAXE));
        }

        level.destroyBlock(pos, true, zombie);
    }

    private boolean hasBuildingMaterial() {
        // Use an item currently equipped in either hand, or nearby dropped blocks.
        if (isPlaceable(zombie.getOffhandItem()) || isPlaceable(zombie.getMainHandItem())) return true;

        if (zombie.level() instanceof ServerLevel level) {
            return level.getEntitiesOfClass(
                    ItemEntity.class,
                    zombie.getBoundingBox().inflate(5),
                    e -> isPlaceable(e.getItem())
            ).stream().findAny().isPresent();
        }
        return false;
    }

    private boolean isPlaceable(ItemStack stack) {
        return !stack.isEmpty() && Block.byItem(stack.getItem()) != Blocks.AIR;
    }

    private ItemStack findBuildingStack() {
        if (isPlaceable(zombie.getOffhandItem())) return zombie.getOffhandItem();
        if (isPlaceable(zombie.getMainHandItem())) return zombie.getMainHandItem();

        if (zombie.level() instanceof ServerLevel level) {
            ItemEntity item = level.getEntitiesOfClass(
                    ItemEntity.class,
                    zombie.getBoundingBox().inflate(5),
                    e -> isPlaceable(e.getItem())
            ).stream().findFirst().orElse(null);
            if (item != null) {
                zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,
                        item.getItem().copyWithCount(1));
                item.getItem().shrink(1);
                return zombie.getOffhandItem();
            }
        }
        return ItemStack.EMPTY;
    }

    private void placeBlock(BlockPos pos) {
        ItemStack stack = findBuildingStack();
        if (!isPlaceable(stack)) return;

        Block block = Block.byItem(stack.getItem());
        if (block == Blocks.AIR) return;

        BlockState state = block.defaultBlockState();
        if (!zombie.level().getBlockState(pos).canBeReplaced()) return;

        // Only place blocks adjacent to a solid face to prevent free-floating spam.
        boolean supported = false;
        for (Direction d : Direction.values()) {
            if (!zombie.level().getBlockState(pos.relative(d)).isAir()) {
                supported = true;
                break;
            }
        }
        if (!supported) return;

        zombie.level().setBlock(pos, state, 3);
        stack.shrink(1);
        if (stack.isEmpty()) {
            zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }
    }
}