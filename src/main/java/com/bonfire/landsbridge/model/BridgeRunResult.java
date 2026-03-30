package com.bonfire.landsbridge.model;

public record BridgeRunResult(
        String provider,
        String mode,
        boolean dryRun,
        int scannedLands,
        int scannedAreas,
        int changedAreas,
        int writtenLands,
        int conflicts,
        long durationMs,
        String note
) {

    public static BridgeRunResult empty(String provider, String mode, boolean dryRun, String note) {
        return new BridgeRunResult(provider, mode, dryRun, 0, 0, 0, 0, 0, 0L, note);
    }
}
