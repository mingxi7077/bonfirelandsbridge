package com.bonfire.landsbridge.service;

import com.bonfire.landsbridge.model.BridgeComputation;

public final class DynamicCapCalculator {

    private DynamicCapCalculator() {
    }

    public static BridgeComputation compute(int baseMaxMinutes, int rentMinutes, long passedSeconds) {
        int passedMinutes = (int) Math.max(0L, passedSeconds / 60L);
        int safeRentMinutes = Math.max(1, rentMinutes);
        int completedPeriods = passedMinutes / safeRentMinutes;
        int dynamicMaxMinutes = baseMaxMinutes + (completedPeriods * safeRentMinutes);
        return new BridgeComputation(baseMaxMinutes, safeRentMinutes, passedMinutes, completedPeriods, dynamicMaxMinutes);
    }
}
