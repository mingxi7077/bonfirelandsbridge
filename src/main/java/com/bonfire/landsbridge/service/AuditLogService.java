package com.bonfire.landsbridge.service;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

public final class AuditLogService {

    private static final String LEGACY_HEADER = "time,mode,land,area,base_max,current_max,new_max,passed_seconds";

    private final JavaPlugin plugin;
    private final boolean enabled;
    private final String filePrefix;
    private final String header;

    public AuditLogService(JavaPlugin plugin, boolean enabled, String filePrefix) {
        this(plugin, enabled, filePrefix, LEGACY_HEADER);
    }

    public AuditLogService(JavaPlugin plugin, boolean enabled, String filePrefix, String header) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.filePrefix = filePrefix;
        this.header = header;
    }

    public void append(String line) {
        if (!enabled) {
            return;
        }

        try {
            Path directory = plugin.getDataFolder().toPath();
            Files.createDirectories(directory);
            Path file = directory.resolve(filePrefix + "_" + LocalDate.now() + ".csv");
            if (Files.notExists(file)) {
                Files.writeString(file, header + System.lineSeparator(), StandardCharsets.UTF_8);
            }
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to write audit log: " + exception.getMessage());
        }
    }
}
