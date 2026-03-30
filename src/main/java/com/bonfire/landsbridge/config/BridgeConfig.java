package com.bonfire.landsbridge.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public record BridgeConfig(
        boolean enabled,
        boolean dryRun,
        long intervalSeconds,
        String providerType,
        int maxUpdatesPerCycle,
        int maxWriteLandsPerCycle,
        boolean auditEnabled,
        String auditFilePrefix,
        String registryFileName,
        List<String> landWhitelist,
        DatabaseSettings databaseSettings,
        ServerGuardSettings serverGuardSettings,
        RuntimeSettings runtimeSettings
) {

    public static BridgeConfig from(FileConfiguration config) {
        return new BridgeConfig(
                config.getBoolean("enabled", false),
                config.getBoolean("dry-run", true),
                config.getLong("interval-seconds", 300L),
                config.getString("provider.type", "mysql"),
                config.getInt("limits.max-updates-per-cycle", 100),
                config.getInt("limits.max-write-lands-per-cycle", 10),
                config.getBoolean("audit.enabled", true),
                config.getString("audit.file-prefix", "bridge-audit"),
                config.getString("registry.file", "base-max-registry.yml"),
                config.getStringList("lands.whitelist"),
                DatabaseSettings.from(config),
                ServerGuardSettings.from(config),
                RuntimeSettings.from(config)
        );
    }
}
