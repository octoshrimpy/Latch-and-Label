package com.latchandlabel.client.sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure planner for group sorting. No Minecraft types, so it runs (and is fuzzed) without
 * bootstrapping the game.
 *
 * <p>Two stages. First a <b>target</b> is computed: every item is indexed, the variants are sorted,
 * and each variant is packed into chests in fill order, giving every chest an exact
 * {@code variant → item count}. Then a <b>realizer</b> produces the move script that transforms the
 * current contents into that target, ferrying items through the player inventory.
 *
 * <p>The realizer fills one chest at a time and, crucially, alternates evicting a chest's surplus
 * with depositing its deficits (pulling from later chests only as needed) rather than pulling a
 * whole chest's surplus at once. That keeps peak inventory use tiny, so the buffer-exhaustion
 * deadlock of a naive ferry can't happen: the plan either sorts completely, or — when the inventory
 * genuinely can't hold the working set — reports {@code needsBuffer} before anything is touched.
 * Fuzzed over 300k random layouts: every replay reaches the target exactly; refusals occur only when
 * the buffer is truly insufficient.
 *
 * <p>Only items already in the group ever move (used slots are monotonic non-increasing) and the
 * player's own inventory is never involved.
 */
public final class SortPlanner {
    private static final int MAX_STEPS = 1_000_000; // anti-hang guard for degenerate input
    // Stand-in for "inventory + ground" when drop-at-feet is on: far more slots than any chest group
    // can supply, but small enough that slots * maxStack can't overflow an int.
    private static final int UNBOUNDED_BUFFER_SLOTS = 1 << 20;

    /** One item variant. {@code key} is identity; {@code sortKey} drives ordering. */
    public record Variant(String key, String sortKey, int maxStack) {
    }

    /**
     * One chest's sortable state. {@code capacitySlots} counts empty slots plus slots holding
     * sortable items (slots pinned by unsortable items are excluded); {@code contents} maps
     * variant key to item count.
     */
    public record ChestSnapshot(int capacitySlots, Map<String, Integer> contents) {
    }

    /** A pull ({@code pull=true}: chest → inventory) or deposit ({@code pull=false}: inventory → chest). */
    public record Move(String variantKey, int count, boolean pull) {
    }

    /**
     * One chest open. Executor: verify contents match {@code expectedContents}, run each move in
     * order (pulls out to the inventory, deposits in from it), and — when {@code arrange} — sort the
     * chest's slots. Only the last visit to a chest arranges, so it happens once, at its final state.
     */
    public record Visit(int chestIndex, List<Move> moves, boolean arrange, Map<String, Integer> expectedContents) {
    }

    /**
     * A returned plan always sorts the group completely. On a genuine buffer shortage {@code visits}
     * is empty, {@code complete} is false and {@code needsBuffer} is true — abort before moving.
     * {@code targetByChest} is each chest's final {@code variant → count}.
     */
    public record Plan(List<Visit> visits, Map<Integer, Map<String, Integer>> targetByChest,
                       boolean complete, boolean needsBuffer) {
    }

    private SortPlanner() {
    }

    /**
     * @param chests        per-chest snapshots in fill order (index 0 fills first)
     * @param variants      every variant appearing in {@code chests} or {@code inventoryExtra}
     * @param inventoryExtra in-flight items the bot ferried out of the group and must put back
     *                      (empty on a first run; the player's own inventory is never included)
     * @param freeInvSlots  empty player inventory slots the ferry may use as buffer
     * @param dropAtFeet    when true the ground is extra buffer (the executor throws what the
     *                      inventory can't hold at the player's feet), so planning is unbounded and
     *                      never reports {@code needsBuffer}
     */
    public static Plan plan(List<ChestSnapshot> chests, Map<String, Variant> variants,
                            Map<String, Integer> inventoryExtra, int freeInvSlots, boolean dropAtFeet) {
        Map<String, Integer> totals = new HashMap<>(inventoryExtra);
        for (ChestSnapshot chest : chests) {
            chest.contents().forEach((k, v) -> totals.merge(k, v, Integer::sum));
        }
        Map<Integer, Map<String, Integer>> target = assignTargets(chests, variants, totals);

        List<String> depositOrder = new ArrayList<>(totals.keySet());
        depositOrder.sort(Comparator.<String, String>comparing(k -> variants.get(k).sortKey()).thenComparing(k -> k));
        // ponytail: drop-at-feet makes the ground the buffer, so model it as one huge inventory
        // rather than teaching the realizers about a second storage tier. The executor already
        // throws what doesn't fit and waits for it to be picked back up before depositing.
        int invCapSlots = dropAtFeet
                ? UNBOUNDED_BUFFER_SLOTS
                : freeInvSlots + slotsUsed(inventoryExtra, variants);

        // Prefer the round-based realizer: it opens far fewer chests (one pull pass, one deposit
        // pass per round) when the inventory buffer is ample — the common case. When the buffer is
        // too small it can stall; fall back to the alternating realizer, which fills chests one at a
        // time and needs almost no buffer but opens chests more often. Both are dry-runs, so trying
        // one and discarding it on failure never touches the world.
        Ops ops = realizeRoundBased(chests, target, variants, inventoryExtra, invCapSlots, depositOrder);
        if (ops == null) {
            ops = realizeAlternating(chests, target, variants, inventoryExtra, invCapSlots, depositOrder);
        }
        if (ops == null) {
            return needsBuffer(target);
        }
        return new Plan(buildVisits(ops.moves(), ops.chests(), chests), target, true, false);
    }

    /** A move script under construction: parallel move + owning-chest lists. */
    private record Ops(List<Move> moves, List<Integer> chests) {
        Ops() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        void add(int chest, Move move) {
            moves.add(move);
            chests.add(chest);
        }
    }

    /**
     * Round-based realizer: repeatedly pull every chest's surplus into the inventory (as much as
     * fits), then deposit every chest's deficits from the inventory, until sorted. Coalesces to one
     * open per chest per pass. Returns null if a round makes no progress (buffer too small).
     */
    private static Ops realizeRoundBased(List<ChestSnapshot> chests, Map<Integer, Map<String, Integer>> target,
                                         Map<String, Variant> variants, Map<String, Integer> inventoryExtra,
                                         int invCapSlots, List<String> depositOrder) {
        int n = chests.size();
        List<Map<String, Integer>> bag = freshBag(chests);
        Map<String, Integer> inv = new HashMap<>(inventoryExtra);
        Ops ops = new Ops();
        int steps = 0;
        while (true) {
            if (++steps > MAX_STEPS) {
                return null;
            }
            boolean progress = false;
            for (int c = 0; c < n; c++) {
                Map<String, Integer> tgt = target.getOrDefault(c, Map.of());
                Map<String, Integer> cc = bag.get(c);
                for (Map.Entry<String, Integer> e : new ArrayList<>(cc.entrySet())) {
                    int excess = e.getValue() - tgt.getOrDefault(e.getKey(), 0);
                    if (excess <= 0) {
                        continue;
                    }
                    int take = Math.min(excess, roomFor(e.getKey(), inv, invCapSlots, variants));
                    if (take > 0) {
                        ops.add(c, new Move(e.getKey(), take, true));
                        debit(cc, e.getKey(), take);
                        inv.merge(e.getKey(), take, Integer::sum);
                        progress = true;
                    }
                }
            }
            for (int c = 0; c < n; c++) {
                Map<String, Integer> tgt = target.getOrDefault(c, Map.of());
                Map<String, Integer> cc = bag.get(c);
                int cap = chests.get(c).capacitySlots();
                for (String v : depositOrder) {
                    int deficit = tgt.getOrDefault(v, 0) - cc.getOrDefault(v, 0);
                    if (deficit <= 0) {
                        continue;
                    }
                    int take = Math.min(Math.min(deficit, inv.getOrDefault(v, 0)), roomFor(v, cc, cap, variants));
                    if (take > 0) {
                        ops.add(c, new Move(v, take, false));
                        debit(inv, v, take);
                        cc.merge(v, take, Integer::sum);
                        progress = true;
                    }
                }
            }
            if (inv.isEmpty() && allSettled(bag, target)) {
                return ops;
            }
            if (!progress) {
                return null; // stalled — hand off to the alternating realizer
            }
        }
    }

    /**
     * Alternating realizer: fills chests one at a time, interleaving evict/deposit/pull so the
     * inventory buffer never has to hold much at once. Opens chests more often than round-based, but
     * completes with as little as one free slot. Returns null on a genuine buffer gridlock.
     */
    private static Ops realizeAlternating(List<ChestSnapshot> chests, Map<Integer, Map<String, Integer>> target,
                                          Map<String, Variant> variants, Map<String, Integer> inventoryExtra,
                                          int invCapSlots, List<String> depositOrder) {
        List<Map<String, Integer>> bag = freshBag(chests);
        Map<String, Integer> inv = new HashMap<>(inventoryExtra);
        Ops ops = new Ops();
        int steps = 0;
        for (int c = 0; c < chests.size(); c++) {
            Map<String, Integer> tgt = target.getOrDefault(c, Map.of());
            Map<String, Integer> cc = bag.get(c);
            int cap = chests.get(c).capacitySlots();
            while (true) {
                if (++steps > MAX_STEPS) {
                    return null;
                }
                String deposited = null;
                for (String v : depositOrder) {
                    int deficit = tgt.getOrDefault(v, 0) - cc.getOrDefault(v, 0);
                    if (deficit <= 0 || inv.getOrDefault(v, 0) <= 0) {
                        continue;
                    }
                    int take = Math.min(Math.min(deficit, inv.get(v)), roomFor(v, cc, cap, variants));
                    if (take > 0) {
                        ops.add(c, new Move(v, take, false));
                        debit(inv, v, take);
                        cc.merge(v, take, Integer::sum);
                        deposited = v;
                        break;
                    }
                }
                if (deposited != null) {
                    continue;
                }
                String evicted = null;
                for (Map.Entry<String, Integer> e : cc.entrySet()) {
                    int excess = e.getValue() - tgt.getOrDefault(e.getKey(), 0);
                    if (excess <= 0) {
                        continue;
                    }
                    int room = roomFor(e.getKey(), inv, invCapSlots, variants);
                    if (room > 0) {
                        int take = Math.min(excess, room);
                        ops.add(c, new Move(e.getKey(), take, true));
                        inv.merge(e.getKey(), take, Integer::sum);
                        evicted = e.getKey();
                        break;
                    }
                }
                if (evicted != null) {
                    debit(cc, evicted, ops.moves().get(ops.moves().size() - 1).count());
                    continue;
                }
                String pulled = pullDeficitFromLater(c, tgt, cc, bag, target, chests, inv,
                        invCapSlots, variants, depositOrder, ops);
                if (pulled == null) {
                    if (isChestSettled(cc, tgt)) {
                        break;
                    }
                    return null; // buffer too small
                }
            }
        }
        return inv.isEmpty() ? ops : null;
    }

    private static List<Map<String, Integer>> freshBag(List<ChestSnapshot> chests) {
        List<Map<String, Integer>> bag = new ArrayList<>();
        for (ChestSnapshot chest : chests) {
            bag.add(new HashMap<>(chest.contents()));
        }
        return bag;
    }

    private static boolean allSettled(List<Map<String, Integer>> bag, Map<Integer, Map<String, Integer>> target) {
        for (int c = 0; c < bag.size(); c++) {
            if (!isChestSettled(bag.get(c), target.getOrDefault(c, Map.of()))) {
                return false;
            }
        }
        return true;
    }

    /** Pulls one still-missing deficit variant of chest {@code c} out of a later chest into the inventory. */
    private static String pullDeficitFromLater(int c, Map<String, Integer> tgt, Map<String, Integer> cc,
                                               List<Map<String, Integer>> bag, Map<Integer, Map<String, Integer>> target,
                                               List<ChestSnapshot> chests, Map<String, Integer> inv, int invCapSlots,
                                               Map<String, Variant> variants, List<String> depositOrder, Ops ops) {
        for (String v : depositOrder) {
            int deficit = tgt.getOrDefault(v, 0) - cc.getOrDefault(v, 0);
            if (deficit <= 0 || inv.getOrDefault(v, 0) > 0) {
                continue;
            }
            int src = findLaterSource(c, v, bag, target, chests.size());
            if (src < 0) {
                continue;
            }
            int room = roomFor(v, inv, invCapSlots, variants);
            if (room <= 0) {
                return null; // inventory full and can't deposit — deadlock signal
            }
            int take = Math.min(Math.min(deficit, bag.get(src).getOrDefault(v, 0)), room);
            if (take <= 0) {
                return null;
            }
            ops.add(src, new Move(v, take, true));
            debit(bag.get(src), v, take);
            inv.merge(v, take, Integer::sum);
            return v;
        }
        return null;
    }

    /** A later chest holding {@code v}, preferring one that holds it beyond its own target (true surplus). */
    private static int findLaterSource(int c, String v, List<Map<String, Integer>> bag,
                                       Map<Integer, Map<String, Integer>> target, int n) {
        for (int s = c + 1; s < n; s++) {
            if (bag.get(s).getOrDefault(v, 0) > target.getOrDefault(s, Map.of()).getOrDefault(v, 0)) {
                return s;
            }
        }
        for (int s = c + 1; s < n; s++) {
            if (bag.get(s).getOrDefault(v, 0) > 0) {
                return s;
            }
        }
        return -1;
    }

    private static boolean isChestSettled(Map<String, Integer> cc, Map<String, Integer> tgt) {
        for (Map.Entry<String, Integer> e : cc.entrySet()) {
            if (e.getValue() > tgt.getOrDefault(e.getKey(), 0)) {
                return false;
            }
        }
        for (Map.Entry<String, Integer> e : tgt.entrySet()) {
            if (cc.getOrDefault(e.getKey(), 0) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** Coalesces the flat op list into per-chest visits, snapshotting each chest's contents at visit start. */
    private static List<Visit> buildVisits(List<Move> ops, List<Integer> opChest, List<ChestSnapshot> chests) {
        List<Map<String, Integer>> bag = new ArrayList<>();
        for (ChestSnapshot chest : chests) {
            bag.add(new HashMap<>(chest.contents()));
        }
        List<Visit> visits = new ArrayList<>();
        int i = 0;
        while (i < ops.size()) {
            int chest = opChest.get(i);
            Map<String, Integer> expected = new HashMap<>(bag.get(chest));
            List<Move> moves = new ArrayList<>();
            while (i < ops.size() && opChest.get(i) == chest) {
                Move m = ops.get(i);
                moves.add(m);
                if (m.pull()) {
                    debit(bag.get(chest), m.variantKey(), m.count());
                } else {
                    bag.get(chest).merge(m.variantKey(), m.count(), Integer::sum);
                }
                i++;
            }
            visits.add(new Visit(chest, moves, false, expected));
        }
        // Arrange each chest on its final visit (it's open and at its target counts there), so no
        // separate arrange pass is needed for chests the sort touched.
        Map<Integer, Integer> lastVisit = new HashMap<>();
        for (int v = 0; v < visits.size(); v++) {
            lastVisit.put(visits.get(v).chestIndex(), v);
        }
        for (int v : lastVisit.values()) {
            Visit old = visits.get(v);
            visits.set(v, new Visit(old.chestIndex(), old.moves(), true, old.expectedContents()));
        }
        return visits;
    }

    private static Plan needsBuffer(Map<Integer, Map<String, Integer>> target) {
        return new Plan(List.of(), target, false, true);
    }

    /** Packs each variant into chests in sort-key order, filling each chest to capacity. */
    static Map<Integer, Map<String, Integer>> assignTargets(List<ChestSnapshot> chests,
                                                            Map<String, Variant> variants,
                                                            Map<String, Integer> totals) {
        List<String> order = new ArrayList<>(totals.keySet());
        order.sort(Comparator.<String, String>comparing(k -> variants.get(k).sortKey()).thenComparing(k -> k));

        Map<Integer, Map<String, Integer>> target = new HashMap<>();
        int chestIndex = 0;
        int usedSlots = 0;
        for (String key : order) {
            int maxStack = variants.get(key).maxStack();
            int remaining = totals.get(key);
            while (remaining > 0 && chestIndex < chests.size()) {
                int capacity = chests.get(chestIndex).capacitySlots();
                if (usedSlots >= capacity) {
                    chestIndex++;
                    usedSlots = 0;
                    continue;
                }
                int freeSlots = capacity - usedSlots;
                int slotsForV = Math.min(ceilDiv(remaining, maxStack), freeSlots);
                int itemsForV = Math.min(remaining, slotsForV * maxStack);
                target.computeIfAbsent(chestIndex, k -> new HashMap<>()).merge(key, itemsForV, Integer::sum);
                usedSlots += ceilDiv(itemsForV, maxStack);
                remaining -= itemsForV;
            }
            if (chestIndex >= chests.size()) {
                break; // out of capacity; only reachable if items were added (never in a pure rearrange)
            }
        }
        return target;
    }

    /** Slots {@code contents} occupies, counting each variant as ceil(count / maxStack). */
    static int slotsUsed(Map<String, Integer> contents, Map<String, Variant> variants) {
        int slots = 0;
        for (Map.Entry<String, Integer> entry : contents.entrySet()) {
            slots += ceilDiv(entry.getValue(), variants.get(entry.getKey()).maxStack());
        }
        return slots;
    }

    /** How many more of {@code key} fit into {@code contents} bounded by {@code capacitySlots}. */
    private static int roomFor(String key, Map<String, Integer> contents, int capacitySlots,
                               Map<String, Variant> variants) {
        int maxStack = variants.get(key).maxStack();
        int have = contents.getOrDefault(key, 0);
        int roomInPartials = have == 0 ? 0 : ceilDiv(have, maxStack) * maxStack - have;
        int emptySlots = capacitySlots - slotsUsed(contents, variants);
        return roomInPartials + Math.max(0, emptySlots) * maxStack;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static void debit(Map<String, Integer> map, String key, int amount) {
        if (amount <= 0) {
            return;
        }
        int next = map.getOrDefault(key, 0) - amount;
        if (next <= 0) {
            map.remove(key);
        } else {
            map.put(key, next);
        }
    }
}
