package com.bonfire.landsbridge.service;

import com.bonfire.landsbridge.config.ServerGuardSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.logging.Logger;

public final class ServerGuard {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final ServerGuardSettings settings;
    private boolean warnedMissing;

    public ServerGuard(JavaPlugin plugin, Logger logger, ServerGuardSettings settings) {
        this.plugin = plugin;
        this.logger = logger;
        this.settings = settings;
    }

    public boolean canExecute() {
        if (!settings.enabled()) {
            return true;
        }

        Path path = plugin.getDataFolder().toPath().resolve(settings.landsServerNameFile()).normalize();
        if (!path.toFile().isFile()) {
            if (!warnedMissing) {
                logger.warning("Server guard file not found: " + path);
                warnedMissing = true;
            }
            return !settings.requireMaster();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
        return yaml.getBoolean("master", false) || !settings.requireMaster();
    }
}
