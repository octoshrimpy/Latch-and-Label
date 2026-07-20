package com.latchandlabel.client.config;

import com.latchandlabel.client.sort.SortMethod;

/** Runtime settings for brush group sorting. */
public final class SortSettings {
    private static SortMethod sortMethod = SortMethod.REGISTRY_ID;
    private static boolean dropOverflowAtFeet = false;

    private SortSettings() {
    }

    public static SortMethod sortMethod() {
        return sortMethod;
    }

    public static void setSortMethod(SortMethod method) {
        sortMethod = method == null ? SortMethod.REGISTRY_ID : method;
    }

    /**
     * When the player inventory can't ferry everything in one trip: true throws overflow at the
     * player's feet (picked back up as space frees, fewer chest visits), false schedules extra
     * ferry trips. Ground drops can despawn or be stolen, so this is opt-in.
     */
    public static boolean dropOverflowAtFeet() {
        return dropOverflowAtFeet;
    }

    public static void setDropOverflowAtFeet(boolean value) {
        dropOverflowAtFeet = value;
    }
}
