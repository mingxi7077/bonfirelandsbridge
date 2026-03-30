package com.bonfire.landsbridge.model;

public record BridgeComputation(int baseMaxMinutes, int rentMinutes, int passedMinutes, int completedPeriods, int dynamicMaxMinutes) {
}
