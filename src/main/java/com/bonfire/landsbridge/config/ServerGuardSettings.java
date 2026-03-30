package com.bonfire.landsbridge.config;

import org.bukkit.configuration.file.FileConfiguration;

public record ServerGuardSettings(boolean enabled, boolean requireMaster, String landsServerNameFile) {

    public static ServerGuardSettings from(FileConfiguration config) {
        return new ServerGuardSettings(
                config.getBoolean("server-guard.enabled", false),
                config.getBoolean("server-guard.require-master", true),
                config.getString("server-guard.lands-server-name-file", "../Lands/server-name.yml")
        );
    }
}
