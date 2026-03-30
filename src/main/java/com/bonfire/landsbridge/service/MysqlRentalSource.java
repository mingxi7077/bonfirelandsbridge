package com.bonfire.landsbridge.service;

import com.bonfire.landsbridge.config.BridgeConfig;
import com.bonfire.landsbridge.config.DatabaseSettings;
import com.bonfire.landsbridge.model.BridgeComputation;
import com.bonfire.landsbridge.model.BridgeRunResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class MysqlRentalSource implements RentalSource {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final BridgeConfig config;
    private final BaseMaxRegistry baseMaxRegistry;
    private final AuditLogService auditLogService;
    private final ServerGuard serverGuard;
    private final HikariDataSource dataSource;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public MysqlRentalSource(JavaPlugin plugin,
                             Logger logger,
                             BridgeConfig config,
                             BaseMaxRegistry baseMaxRegistry,
                             AuditLogService auditLogService) {
        this.plugin = plugin;
        this.logger = logger;
        this.config = config;
        this.baseMaxRegistry = baseMaxRegistry;
        this.auditLogService = auditLogService;
        this.serverGuard = new ServerGuard(plugin, logger, config.serverGuardSettings());
        this.dataSource = createDataSource(config.databaseSettings());
    }

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public BridgeRunResult runCycle() {
        return execute("cycle", null);
    }

    @Override
    public BridgeRunResult restore(String landFilter) {
        return execute("restore", normalizeLandFilter(landFilter));
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private BridgeRunResult execute(String mode, String landFilter) {
        long startedAt = System.currentTimeMillis();
        if (!serverGuard.canExecute()) {
            return BridgeRunResult.empty(name(), mode, config.dryRun(), "server-guard skipped execution");
        }

        int scannedLands = 0;
        int scannedAreas = 0;
        int changedAreas = 0;
        int writtenLands = 0;
        int conflicts = 0;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepareSelect(connection, landFilter);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                String landId = resultSet.getString(1);
                String landName = resultSet.getString(2);
                String originalAreas = resultSet.getString(3);
                if (originalAreas == null || originalAreas.isBlank()) {
                    continue;
                }

                scannedLands++;
                JsonArray areasArray;
                try {
                    JsonElement parsed = JsonParser.parseString(originalAreas);
                    if (!parsed.isJsonArray()) {
                        continue;
                    }
                    areasArray = parsed.getAsJsonArray();
                } catch (Exception exception) {
                    logger.warning("Failed to parse areas json for land " + landName + ": " + exception.getMessage());
                    continue;
                }

                boolean rowChanged = false;
                for (JsonElement element : areasArray) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject areaObject = element.getAsJsonObject();
                    JsonObject rentalObject = getObject(areaObject, "rental");
                    if (rentalObject == null || !rentalObject.has("max") || !rentalObject.has("rent")) {
                        continue;
                    }

                    scannedAreas++;
                    String areaId = getString(areaObject, "ulid", "unknown-area");
                    String areaName = getString(areaObject, "name", areaId);
                    int currentMax = getInt(rentalObject, "max", 0);
                    int rentMinutes = Math.max(1, getInt(rentalObject, "rent", 1));
                    int baseMax = baseMaxRegistry.getOrStore(areaId, currentMax);
                    boolean activeRental = rentalObject.has("rented") && getInt(rentalObject, "rented", 0) > 0;
                    long passedSeconds = getLong(rentalObject, "passed", 0L);
                    int desiredMax;

                    if ("restore".equals(mode) || !activeRental) {
                        desiredMax = baseMax;
                    } else {
                        BridgeComputation computation = DynamicCapCalculator.compute(baseMax, rentMinutes, passedSeconds);
                        desiredMax = computation.dynamicMaxMinutes();
                    }

                    if (desiredMax == currentMax) {
                        continue;
                    }

                    if (changedAreas >= config.maxUpdatesPerCycle()) {
                        break;
                    }

                    changedAreas++;
                    auditLogService.append(LocalDateTime.now()
                            + "," + (config.dryRun() ? "dry-run" : mode)
                            + "," + csv(landName)
                            + "," + csv(areaName)
                            + "," + baseMax
                            + "," + currentMax
                            + "," + desiredMax
                            + "," + passedSeconds);

                    if (!config.dryRun()) {
                        rentalObject.addProperty("max", desiredMax);
                        rowChanged = true;
                    }
                }

                if (config.dryRun() || !rowChanged) {
                    if (changedAreas >= config.maxUpdatesPerCycle()) {
                        break;
                    }
                    continue;
                }

                if (writtenLands >= config.maxWriteLandsPerCycle()) {
                    break;
                }

                String updatedAreas = gson.toJson(areasArray);
                if (tryOptimisticUpdate(connection, landId, originalAreas, updatedAreas)) {
                    writtenLands++;
                } else {
                    conflicts++;
                    logger.warning("Optimistic update conflict for land " + landName + " (" + landId + ")");
                }

                if (changedAreas >= config.maxUpdatesPerCycle()) {
                    break;
                }
            }
        } catch (SQLException exception) {
            return new BridgeRunResult(name(), mode, config.dryRun(), scannedLands, scannedAreas, changedAreas, writtenLands, conflicts,
                    System.currentTimeMillis() - startedAt, exception.getMessage());
        }

        return new BridgeRunResult(name(), mode, config.dryRun(), scannedLands, scannedAreas, changedAreas, writtenLands, conflicts,
                System.currentTimeMillis() - startedAt, "ok");
    }

    private PreparedStatement prepareSelect(Connection connection, String landFilter) throws SQLException {
        String prefix = config.databaseSettings().tablePrefix();
        List<String> filters = new ArrayList<>();
        List<String> parameters = new ArrayList<>();

        if (!config.landWhitelist().isEmpty()) {
            filters.add("l.name IN (" + placeholders(config.landWhitelist().size()) + ")");
            parameters.addAll(config.landWhitelist());
        }

        if (landFilter != null) {
            filters.add("l.name = ?");
            parameters.add(landFilter);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT c.land, l.name, c.areas FROM ")
                .append(prefix).append("lands_claims c JOIN ")
                .append(prefix).append("lands l ON l.ulid = c.land");

        if (!filters.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", filters));
        }
        sql.append(" ORDER BY l.name ASC");

        PreparedStatement statement = connection.prepareStatement(sql.toString());
        statement.setFetchSize(32);
        for (int i = 0; i < parameters.size(); i++) {
            statement.setString(i + 1, parameters.get(i));
        }
        return statement;
    }

    private boolean tryOptimisticUpdate(Connection connection, String landId, String originalAreas, String updatedAreas) throws SQLException {
        String sql = "UPDATE " + config.databaseSettings().tablePrefix() + "lands_claims SET areas = ? WHERE land = ? AND areas = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, updatedAreas);
            statement.setString(2, landId);
            statement.setString(3, originalAreas);
            return statement.executeUpdate() == 1;
        }
    }

    private HikariDataSource createDataSource(DatabaseSettings settings) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("bonfirelandsbridge");
        hikariConfig.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.name()
                + "?useSSL=false&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048");
        hikariConfig.setUsername(settings.user());
        hikariConfig.setPassword(settings.password());
        hikariConfig.setMaximumPoolSize(Math.max(1, settings.poolSize()));
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(settings.connectionTimeoutMs());
        hikariConfig.setInitializationFailTimeout(-1L);
        hikariConfig.setAutoCommit(true);
        return new HikariDataSource(hikariConfig);
    }

    private JsonObject getObject(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private String getString(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private int getInt(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long getLong(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private String normalizeLandFilter(String landFilter) {
        if (landFilter == null || landFilter.isBlank() || landFilter.equalsIgnoreCase("all")) {
            return null;
        }
        return landFilter;
    }

    private String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
