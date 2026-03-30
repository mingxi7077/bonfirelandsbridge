package com.bonfire.landsbridge.config;

import org.bukkit.configuration.file.FileConfiguration;

public record RuntimeSettings(
        boolean interceptRentalBlocks,
        boolean saveAndPublishToRedis,
        boolean denyWhenSnapshotMissing,
        boolean repairTenantOnRenewal,
        boolean repairTenantBeforeTrustCommand,
        String auditFileSuffix,
        String messagePrefix
) {

    public static RuntimeSettings from(FileConfiguration config) {
        return new RuntimeSettings(
                config.getBoolean("runtime.intercept-rental-blocks", true),
                config.getBoolean("runtime.save-and-publish-to-redis", true),
                config.getBoolean("runtime.deny-when-snapshot-missing", true),
                config.getBoolean("runtime.repair-tenant-on-renewal", true),
                config.getBoolean("runtime.repair-tenant-before-trust-command", true),
                config.getString("runtime.audit-file-suffix", "runtime"),
                config.getString("runtime.message-prefix", "&a[\u623f\u4ea7]&r ")
        );
    }
}