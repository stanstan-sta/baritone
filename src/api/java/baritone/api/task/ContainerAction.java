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

package baritone.api.task;

import net.minecraft.world.item.Item;

/**
 * Represents an action to be performed on a container (e.g., Chest, Furnace).
 */
public class ContainerAction {

    public enum Type {
        WITHDRAW,
        DEPOSIT,
        DUMP_ALL
    }

    private final Type type;
    private final Item targetItem;
    private final int count;

    public ContainerAction(Type type, Item targetItem, int count) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (type != Type.DUMP_ALL && targetItem == null) {
            throw new IllegalArgumentException("targetItem must not be null for " + type);
        }
        this.type = type;
        this.targetItem = targetItem;
        this.count = count;
    }

    public static ContainerAction withdraw(Item targetItem, int count) {
        return new ContainerAction(Type.WITHDRAW, targetItem, count);
    }

    public static ContainerAction deposit(Item targetItem, int count) {
        return new ContainerAction(Type.DEPOSIT, targetItem, count);
    }

    public static ContainerAction dumpAll() {
        return new ContainerAction(Type.DUMP_ALL, null, -1);
    }

    public Type getType() {
        return type;
    }

    public Item getTargetItem() {
        return targetItem;
    }

    public int getCount() {
        return count;
    }

    public boolean movesAllMatchingItems() {
        return count <= 0;
    }
}
