package com.bonfire.landsbridge.model;

import java.util.UUID;

public record RenewalResult(
        String landName,
        String areaName,
        UUID playerId,
        String playerName,
        int baseMaxMinutes,
        int rentMinutes,
        int rentedMinutesBefore,
        int rentedMinutesAfter,
        long passedSeconds,
        int remainingBeforeMinutes,
        int remainingAfterMinutes,
        double cost,
        boolean success,
        String decision,
        String note
) {
}
