package com.bonfire.landsbridge.service;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class BaseMaxRegistry {

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration yaml;

    public BaseMaxRegistry(JavaPlugin plugin, String fileName) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), fileName);
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    public synchronized int getOrStore(String areaId, int fallbackMax) {
        String path = "areas." + areaId;
        if (!yaml.contains(path)) {
            yaml.set(path, fallbackMax);
            save();
        }
        return yaml.getInt(path, fallbackMax);
    }

    public synchronized Integer peek(String areaId) {
        String path = "areas." + areaId;
        return yaml.contains(path) ? yaml.getInt(path) : null;
    }

    private void save() {
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save base max registry: " + exception.getMessage());
        }
    }
}
