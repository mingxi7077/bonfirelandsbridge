package com.bonfire.landsbridge.model;

import java.util.UUID;

public record RentalQueryEntry(
        UUID tenantId,
        String tenantName,
        String landName,
        String areaName,
        String areaId,
        int rentMinutes,
        int baseMaxMinutes,
        boolean baseMaxKnown,
        int currentMaxMinutes,
        int rentedMinutes,
        long passedSeconds,
        int remainingMinutes,
        boolean preciseRemaining,
        boolean snapshotAvailable
) {
}
