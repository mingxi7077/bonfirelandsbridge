package com.bonfire.landsbridge.model;

public record RentalSnapshot(
        String landId,
        String landName,
        String areaName,
        String areaId,
        int baseMaxMinutes,
        int currentMaxMinutes,
        int rentMinutes,
        int rentedMinutes,
        long passedSeconds,
        boolean activeRental
) {
}
