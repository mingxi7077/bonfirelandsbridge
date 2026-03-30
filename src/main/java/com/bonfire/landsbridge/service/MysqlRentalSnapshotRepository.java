package com.bonfire.landsbridge.service;

import com.bonfire.landsbridge.config.DatabaseSettings;
import com.bonfire.landsbridge.model.RentalSnapshot;
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
import java.util.Optional;
import java.util.logging.Logger;

public final class MysqlRentalSnapshotRepository implements RentalSnapshotRepository {

    private final Logger logger;
    private final String tablePrefix;
    private final HikariDataSource dataSource;

    public MysqlRentalSnapshotRepository(JavaPlugin plugin, Logger logger, DatabaseSettings settings) {
        this.logger = logger;
        this.tablePrefix = settings.tablePrefix();
        this.dataSource = createDataSource(settings);
        plugin.getLogger().info("bonfirelandsbridge runtime snapshot repository ready");
    }

    @Override
    public Optional<RentalSnapshot> findSnapshot(String landId, String areaId) {
        String sql = "SELECT areas FROM " + tablePrefix + "lands_claims WHERE land = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, landId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                String areas = resultSet.getString(1);
                if (areas == null || areas.isBlank()) {
                    return Optional.empty();
                }

                JsonElement parsed = JsonParser.parseString(areas);
                if (!parsed.isJsonArray()) {
                    return Optional.empty();
                }

                JsonArray array = parsed.getAsJsonArray();
                for (JsonElement element : array) {
                    if (!element.isJsonObject()) {
                        continue;
                    }

                    JsonObject areaObject = element.getAsJsonObject();
                    if (!areaId.equals(getString(areaObject, "ulid", ""))) {
                        continue;
                    }

                    JsonObject rentalObject = getObject(areaObject, "rental");
                    if (rentalObject == null) {
                        return Optional.empty();
                    }

                    int currentMax = getInt(rentalObject, "max", 0);
                    int rentMinutes = Math.max(1, getInt(rentalObject, "rent", 1));
                    int rentedMinutes = Math.max(0, getInt(rentalObject, "rented", 0));
                    long passedSeconds = Math.max(0L, getLong(rentalObject, "passed", 0L));
                    boolean activeRental = rentedMinutes > 0;

                    return Optional.of(new RentalSnapshot(
                            landId,
                            "",
                            getString(areaObject, "name", areaId),
                            areaId,
                            currentMax,
                            currentMax,
                            rentMinutes,
                            rentedMinutes,
                            passedSeconds,
                            activeRental
                    ));
                }
            }
        } catch (SQLException exception) {
            logger.warning("Failed to query rental snapshot for area " + areaId + ": " + exception.getMessage());
        } catch (Exception exception) {
            logger.warning("Failed to parse rental snapshot for area " + areaId + ": " + exception.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public String name() {
        return "mysql-runtime";
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private HikariDataSource createDataSource(DatabaseSettings settings) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("bonfirelandsbridge-runtime");
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
}
