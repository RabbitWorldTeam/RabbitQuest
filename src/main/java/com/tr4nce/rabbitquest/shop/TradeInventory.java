package com.tr4nce.rabbitquest.shop;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

public final class TradeInventory {
    private TradeInventory() {}

    public record Cost(ItemStack sample, int count, boolean exact) {}

    public enum Failure {
        INVALID_QUANTITY,
        MISSING_COST,
        INVENTORY_FULL
    }

    public record Result(Failure failure, List<ItemStack> slots, List<ItemStack> removed, List<ItemStack> granted) {
        public boolean success() {
            return failure == null;
        }
    }

    /**
     * Plans a trade using the player's main inventory. Applies no mutations to input stacks.
     * On success, the returned slots can replace the inputs in the same order.
     */
    public static Result plan(List<ItemStack> slots, List<Cost> costs, List<ItemStack> rewards, int quantity) {
        if (quantity < 1
            || costs.stream().anyMatch(cost -> cost.sample().isEmpty() || !canMultiply(cost.count(), quantity))
            || rewards.stream().anyMatch(reward -> reward.isEmpty() || !canMultiply(reward.getCount(), quantity))) {
            return failed(Failure.INVALID_QUANTITY);
        }

        // Exact costs have fewer eligible slots. Reserve them before consuming generic costs of the same item.
        var debits = costs.stream()
            .sorted(Comparator.comparing(Cost::exact).reversed())
            .map(cost -> new Cost(cost.sample(), cost.count() * quantity, cost.exact()))
            .toList();
        Result result = tryPlan(slots, debits, rewards, quantity);
        if (result.failure() == Failure.INVENTORY_FULL && debits.stream().anyMatch(cost -> !cost.exact())) {
            return tryPlan(slots, chooseDebits(slots, debits, rewards, quantity), rewards, quantity);
        }
        return result;
    }

    private static Result tryPlan(List<ItemStack> slots, List<Cost> debits, List<ItemStack> rewards, int quantity) {
        var next = new ArrayList<>(slots.stream().map(ItemStack::copy).toList());
        var removed = new ArrayList<ItemStack>();
        for (Cost cost : debits) {
            if (!remove(next, cost, cost.count(), removed)) {
                return failed(Failure.MISSING_COST);
            }
        }

        var granted = new ArrayList<ItemStack>();
        for (ItemStack reward : rewards) {
            if (!insert(next, reward, reward.getCount() * quantity, granted)) {
                return failed(Failure.INVENTORY_FULL);
            }
        }

        return new Result(null, List.copyOf(next), List.copyOf(removed), List.copyOf(granted));
    }

    private static List<Cost> chooseDebits(List<ItemStack> slots, List<Cost> costs, List<ItemStack> rewards, int quantity) {
        var groups = new LinkedHashMap<Item, List<Variant>>();
        for (ItemStack stack : slots) {
            if (stack.isEmpty()) {
                continue;
            }
            var variants = groups.computeIfAbsent(stack.getItem(), item -> new ArrayList<>());
            Variant variant = findVariant(variants, stack);
            if (variant == null) {
                variant = new Variant(stack);
                variants.add(variant);
            }
            variant.counts.add(stack.getCount());
            variant.total += stack.getCount();
        }

        var generic = new LinkedHashMap<Item, Integer>();
        for (Cost cost : costs) {
            Item item = cost.sample().getItem();
            if (cost.exact()) {
                findVariant(groups.get(item), cost.sample()).exactDebit += cost.count();
            } else {
                generic.merge(item, cost.count(), Integer::sum);
            }
        }
        for (ItemStack reward : rewards) {
            var variants = groups.get(reward.getItem());
            Variant variant = variants == null ? null : findVariant(variants, reward);
            if (variant != null) {
                variant.rewardCount += (long) reward.getCount() * quantity;
            }
        }

        List<Cost> debits = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<Variant> variants = entry.getValue();
            int genericCount = generic.getOrDefault(entry.getKey(), 0);
            if (genericCount != 0 && variants.size() == 1) {
                variants.getFirst().exactDebit += genericCount;
            } else if (genericCount != 0) {
                distributeGeneric(variants, genericCount);
            }
            for (Variant variant : variants) {
                if (variant.exactDebit > 0) {
                    debits.add(new Cost(variant.sample, variant.exactDebit, true));
                }
            }
        }
        return debits;
    }

    private static Variant findVariant(List<Variant> variants, ItemStack stack) {
        return variants.stream().filter(variant -> ItemStack.isSameItemSameComponents(variant.sample, stack))
            .findFirst().orElse(null);
    }

    // Different item IDs cannot share a debit or a reward stack. For each item,
    // find the split of its generic debit that uses the fewest slots after rewards.
    private static void distributeGeneric(List<Variant> variants, int count) {
        int[][] chosen = new int[variants.size()][count + 1];
        long[] best = new long[count + 1];
        Arrays.fill(best, Long.MAX_VALUE);
        best[0] = 0;
        for (int index = 0; index < variants.size(); index++) {
            Variant variant = variants.get(index);
            variant.counts.sort(Comparator.naturalOrder());
            int limit = Math.min(count, variant.total - variant.exactDebit);
            long[] occupied = new long[limit + 1];
            for (int debit = 0; debit <= limit; debit++) {
                occupied[debit] = variant.occupiedAfterReward(variant.exactDebit + debit);
            }

            long[] next = new long[count + 1];
            Arrays.fill(next, Long.MAX_VALUE);
            for (int spent = 0; spent <= count; spent++) {
                if (best[spent] == Long.MAX_VALUE) {
                    continue;
                }
                for (int debit = 0; debit <= limit && spent + debit <= count; debit++) {
                    long candidate = best[spent] + occupied[debit];
                    if (candidate < next[spent + debit]) {
                        next[spent + debit] = candidate;
                        chosen[index][spent + debit] = debit;
                    }
                }
            }
            best = next;
        }
        for (int index = variants.size() - 1; index >= 0; index--) {
            int debit = chosen[index][count];
            variants.get(index).exactDebit += debit;
            count -= debit;
        }
    }

    private static final class Variant {
        private final ItemStack sample;
        private final List<Integer> counts = new ArrayList<>();
        private int total;
        private int exactDebit;
        private long rewardCount;

        private Variant(ItemStack sample) {
            this.sample = sample;
        }

        private long occupiedAfterReward(int debit) {
            int capacity = sample.getMaxStackSize();
            int occupied = 0;
            long unplaced = rewardCount;
            for (int count : counts) {
                int removed = Math.min(count, debit);
                debit -= removed;
                int remaining = count - removed;
                if (remaining > 0) {
                    occupied++;
                    unplaced -= Math.max(0, capacity - remaining);
                }
            }
            return occupied + Math.ceilDiv(Math.max(0, unplaced), capacity);
        }
    }

    private static boolean canMultiply(int count, int quantity) {
        return count > 0 && count <= Integer.MAX_VALUE / quantity;
    }

    private static Result failed(Failure failure) {
        return new Result(failure, List.of(), List.of(), List.of());
    }

    private static boolean remove(List<ItemStack> slots, Cost cost, int remaining, List<ItemStack> removed) {
        // Consuming smaller stacks first can free capacity even when all slots are occupied.
        var matches = slots.stream()
            .filter(stack -> !stack.isEmpty() && (cost.exact()
                ? ItemStack.isSameItemSameComponents(stack, cost.sample())
                : ItemStack.isSameItem(stack, cost.sample())))
            .sorted(Comparator.comparingInt(ItemStack::getCount))
            .toList();
        for (ItemStack stack : matches) {
            ItemStack debit = stack.split(remaining);
            removed.add(debit);
            remaining -= debit.getCount();
            if (remaining == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean insert(List<ItemStack> slots, ItemStack reward, int remaining, List<ItemStack> granted) {
        for (ItemStack stack : slots) {
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, reward)) {
                continue;
            }
            int amount = Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
            if (amount > 0) {
                stack.grow(amount);
                granted.add(reward.copyWithCount(amount));
                remaining -= amount;
                if (remaining == 0) {
                    return true;
                }
            }
        }

        for (int i = 0; i < slots.size(); i++) {
            if (!slots.get(i).isEmpty()) {
                continue;
            }
            int amount = Math.min(remaining, reward.getMaxStackSize());
            ItemStack inserted = reward.copyWithCount(amount);
            slots.set(i, inserted);
            granted.add(inserted.copy());
            remaining -= amount;
            if (remaining == 0) {
                return true;
            }
        }
        return false;
    }
}
