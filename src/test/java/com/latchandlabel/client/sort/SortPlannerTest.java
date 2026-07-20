package com.latchandlabel.client.sort;

import com.latchandlabel.client.sort.SortPlanner.ChestSnapshot;
import com.latchandlabel.client.sort.SortPlanner.Move;
import com.latchandlabel.client.sort.SortPlanner.Plan;
import com.latchandlabel.client.sort.SortPlanner.Variant;
import com.latchandlabel.client.sort.SortPlanner.Visit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SortPlannerTest {
    private static final Map<String, Variant> VARIANTS = Map.of(
            "a", new Variant("a", "a", 64),
            "b", new Variant("b", "b", 64),
            "c", new Variant("c", "c", 64),
            "pearl", new Variant("pearl", "pearl", 16)
    );

    @Test
    void scrambledTwoChestsSortExactly() {
        List<ChestSnapshot> chests = List.of(
                new ChestSnapshot(27, Map.of("b", 64)),
                new ChestSnapshot(27, Map.of("a", 64)));
        Plan plan = SortPlanner.plan(chests, VARIANTS, Map.of(), 27, false);
        assertTrue(plan.complete());
        assertReplaySortsToTarget(chests, plan, 27);
    }

    @Test
    void assignmentFollowsSortKeyOrderAndCapacity() {
        Plan plan = SortPlanner.plan(
                List.of(
                        new ChestSnapshot(27, Map.of("a", 30 * 64)),
                        new ChestSnapshot(27, Map.of("b", 64, "c", 64))),
                VARIANTS, Map.of(), 27, false);
        assertEquals(Set.of("a"), plan.targetByChest().get(0).keySet());
        assertTrue(plan.targetByChest().get(1).keySet().containsAll(Set.of("a", "b", "c")));
    }

    @Test
    void lowStackSizeVariantsReserveEnoughSlots() {
        List<ChestSnapshot> chests = List.of(
                new ChestSnapshot(2, Map.of("a", 128)),
                new ChestSnapshot(27, Map.of("pearl", 64)));
        Plan plan = SortPlanner.plan(chests, VARIANTS, Map.of(), 27, false);
        assertTrue(plan.complete());
        assertEquals(Set.of("a"), plan.targetByChest().get(0).keySet());
        assertEquals(Set.of("pearl"), plan.targetByChest().get(1).keySet());
        assertReplaySortsToTarget(chests, plan, 27);
    }

    @Test
    void zeroBufferGridlockRefusesUpFront() {
        List<ChestSnapshot> full = List.of(fullChest(), fullChest(), fullChest());
        Plan plan = SortPlanner.plan(full, VARIANTS, Map.of(), 0, false);
        assertFalse(plan.complete());
        assertTrue(plan.needsBuffer());
        assertTrue(plan.visits().isEmpty());
    }

    @Test
    void dropAtFeetSortsTheSameGridlockWithNoBuffer() {
        List<ChestSnapshot> full = List.of(fullChest(), fullChest(), fullChest());
        Plan plan = SortPlanner.plan(full, VARIANTS, Map.of(), 0, true);
        assertFalse(plan.needsBuffer());
        assertTrue(plan.complete());
    }

    @Test
    void oneFreeSlotBreaksAFullChestRotation() {
        List<ChestSnapshot> full = List.of(fullChest(), fullChest(), fullChest());
        Plan plan = SortPlanner.plan(full, VARIANTS, Map.of(), 27, false);
        assertTrue(plan.complete());
        assertReplaySortsToTarget(full, plan, 27);
    }

    @Test
    void ferriedItemsAreDepositedBackIn() {
        List<ChestSnapshot> chests = List.of(new ChestSnapshot(27, Map.of("a", 64)));
        Plan plan = SortPlanner.plan(chests, VARIANTS, Map.of("a", 32), 27, false);
        assertTrue(plan.complete());
        // The 32 in-flight "a" must be deposited back into the (only) chest.
        int deposited = plan.visits().stream()
                .flatMap(v -> v.moves().stream())
                .filter(m -> !m.pull() && m.variantKey().equals("a"))
                .mapToInt(Move::count).sum();
        assertEquals(32, deposited);
    }

    /**
     * The core guarantee: over thousands of random layouts, whenever the planner returns a plan
     * (doesn't report needsBuffer), replaying its move script through an independent bag model
     * drains the inventory and lands every chest exactly on its target.
     */
    @Test
    void fuzzEveryReturnedPlanSortsExactly() {
        Random r = new Random(1234);
        String[] keys = {"a", "b", "c", "pearl"};
        for (int t = 0; t < 5000; t++) {
            int n = 2 + r.nextInt(4);
            List<ChestSnapshot> chests = new ArrayList<>();
            List<Map<String, Integer>> start = new ArrayList<>();
            int[] cap = new int[n];
            for (int c = 0; c < n; c++) {
                cap[c] = 2 + r.nextInt(10);
                Map<String, Integer> m = new HashMap<>();
                int fill = r.nextInt(cap[c] + 1);
                for (int s = 0; s < fill; s++) {
                    String v = keys[r.nextInt(keys.length)];
                    m.merge(v, 1 + r.nextInt(VARIANTS.get(v).maxStack()), Integer::sum);
                }
                while (slots(m) > cap[c]) {
                    String any = m.keySet().iterator().next();
                    debit(m, any, 1);
                }
                chests.add(new ChestSnapshot(cap[c], new HashMap<>(m)));
                start.add(m);
            }
            int freeInv = 1 + r.nextInt(24);
            Plan plan = SortPlanner.plan(chests, VARIANTS, Map.of(), freeInv, false);
            if (plan.needsBuffer()) {
                continue; // legitimately refused; big-buffer coverage is exercised elsewhere
            }
            replayAndAssert(start, cap, plan, freeInv, t);
        }
    }

    // ---- helpers ----

    private static void assertReplaySortsToTarget(List<ChestSnapshot> chests, Plan plan, int freeInv) {
        List<Map<String, Integer>> start = new ArrayList<>();
        int[] cap = new int[chests.size()];
        for (int c = 0; c < chests.size(); c++) {
            start.add(new HashMap<>(chests.get(c).contents()));
            cap[c] = chests.get(c).capacitySlots();
        }
        replayAndAssert(start, cap, plan, freeInv, -1);
    }

    private static void replayAndAssert(List<Map<String, Integer>> start, int[] cap, Plan plan, int freeInv, int trial) {
        List<Map<String, Integer>> bag = new ArrayList<>();
        for (Map<String, Integer> m : start) {
            bag.add(new HashMap<>(m));
        }
        Map<String, Integer> inv = new HashMap<>();
        for (Visit visit : plan.visits()) {
            assertTrue(bag.get(visit.chestIndex()).equals(visit.expectedContents()),
                    "verify mismatch at trial " + trial);
            for (Move m : visit.moves()) {
                if (m.pull()) {
                    assertTrue(bag.get(visit.chestIndex()).getOrDefault(m.variantKey(), 0) >= m.count(),
                            "pull exceeds chest, trial " + trial);
                    debit(bag.get(visit.chestIndex()), m.variantKey(), m.count());
                    inv.merge(m.variantKey(), m.count(), Integer::sum);
                    assertTrue(slots(inv) <= freeInv, "buffer overflow, trial " + trial);
                } else {
                    assertTrue(inv.getOrDefault(m.variantKey(), 0) >= m.count(),
                            "deposit exceeds inventory, trial " + trial);
                    debit(inv, m.variantKey(), m.count());
                    bag.get(visit.chestIndex()).merge(m.variantKey(), m.count(), Integer::sum);
                    assertTrue(slots(bag.get(visit.chestIndex())) <= cap[visit.chestIndex()],
                            "chest overflow, trial " + trial);
                }
            }
        }
        assertTrue(inv.isEmpty(), "inventory not drained, trial " + trial + ": " + inv);
        for (int c = 0; c < start.size(); c++) {
            assertEquals(plan.targetByChest().getOrDefault(c, Map.of()), bag.get(c),
                    "chest " + c + " not at target, trial " + trial);
        }
    }

    private static int slots(Map<String, Integer> bag) {
        int s = 0;
        for (Map.Entry<String, Integer> e : bag.entrySet()) {
            int max = VARIANTS.get(e.getKey()).maxStack();
            s += (e.getValue() + max - 1) / max;
        }
        return s;
    }

    private static void debit(Map<String, Integer> m, String k, int a) {
        int n = m.getOrDefault(k, 0) - a;
        if (n <= 0) {
            m.remove(k);
        } else {
            m.put(k, n);
        }
    }

    private static ChestSnapshot fullChest() {
        Map<String, Integer> c = new HashMap<>();
        c.put("a", 9 * 64);
        c.put("b", 9 * 64);
        c.put("c", 9 * 64);
        return new ChestSnapshot(27, c);
    }
}
