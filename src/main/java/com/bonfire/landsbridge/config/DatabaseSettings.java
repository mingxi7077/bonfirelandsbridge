package com.bonfire.landsbridge.config;

import org.bukkit.configuration.file.FileConfiguration;

public record DatabaseSettings(
        boolean enabled,
        String host,
        int port,
        String name,
        String user,
        String password,
        String tablePrefix,
        int poolSize,
        long connectionTimeoutMs
) {

    public static DatabaseSettings from(FileConfiguration config) {
        return new DatabaseSettings(
                config.getBoolean("database.enabled", false),
                config.getString("database.host", "127.0.0.1"),
                config.getInt("database.port", 3306),
                config.getString("database.name", "lands1w2"),
                config.getString("database.user", "root"),
                config.getString("database.password", "change-me"),
                config.getString("database.table-prefix", "lands1w2"),
                config.getInt("database.pool-size", 2),
                config.getLong("database.connection-timeout-ms", 5000L)
        );
    }
}
