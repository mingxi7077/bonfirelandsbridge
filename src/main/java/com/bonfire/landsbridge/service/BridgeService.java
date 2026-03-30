/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.milkbowl.vault.economy.Economy
 *  net.milkbowl.vault.economy.EconomyResponse
 *  org.bukkit.Bukkit
 *  org.bukkit.ChatColor
 *  org.bukkit.OfflinePlayer
 *  org.bukkit.entity.Player
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.RegisteredServiceProvider
 *  org.bukkit.plugin.java.JavaPlugin
 */
package com.bonfire.landsbridge.service;

import com.bonfire.landsbridge.config.BridgeConfig;
import com.bonfire.landsbridge.model.BridgeComputation;
import com.bonfire.landsbridge.model.BridgeRunResult;
import com.bonfire.landsbridge.model.RenewalResult;
import com.bonfire.landsbridge.model.RentalQueryEntry;
import com.bonfire.landsbridge.model.RentalSnapshot;
import com.bonfire.landsbridge.service.AuditLogService;
import com.bonfire.landsbridge.service.BaseMaxRegistry;
import com.bonfire.landsbridge.service.DynamicCapCalculator;
import com.bonfire.landsbridge.service.MysqlRentalSnapshotRepository;
import com.bonfire.landsbridge.service.RentalSnapshotRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.events.land.block.LandBlockInteractEvent;
import me.angeschossen.lands.api.flags.types.RoleFlag;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.land.LandArea;
import me.angeschossen.lands.api.land.block.LandBlock;
import me.angeschossen.lands.api.land.block.LandBlockType;
import me.angeschossen.lands.api.land.rental.offer.base.RentalOfferBase;
import me.angeschossen.lands.api.land.rental.offer.types.RentedState;
import me.angeschossen.lands.api.player.LandPlayer;
import me.angeschossen.lands.api.role.Role;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class BridgeService {
    private static final String RUNTIME_AUDIT_HEADER = "time,mode,land,area,player,base_max,rent_minutes,rented_before,rented_after,passed_seconds,remaining_before,remaining_after,cost,decision,note";
    private static final String TENANT_ROLE_ID = "tenant";
    private static final RoleFlag PLAYER_TRUST_FLAG = RoleFlag.of("player_trust");
    private final JavaPlugin plugin;
    private final Logger logger;
    private final BridgeConfig config;
    private final BaseMaxRegistry baseMaxRegistry;
    private final AuditLogService auditLogService;
    private final LandsIntegration landsIntegration;
    private final RentalSnapshotRepository snapshotRepository;
    private final Economy economy;
    private final Set<String> inFlightAreas = ConcurrentHashMap.newKeySet();
    private final AtomicLong attemptedRenewals = new AtomicLong();
    private final AtomicLong successfulRenewals = new AtomicLong();
    private final AtomicLong deniedRenewals = new AtomicLong();
    private final AtomicLong failedRenewals = new AtomicLong();
    private final AtomicReference<BridgeRunResult> lastResult = new AtomicReference();
    private final AtomicReference<RenewalResult> lastRenewal = new AtomicReference();

    public BridgeService(JavaPlugin plugin, BridgeConfig config, BaseMaxRegistry baseMaxRegistry) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.baseMaxRegistry = baseMaxRegistry;
        this.auditLogService = new AuditLogService(plugin, config.auditEnabled(), config.auditFilePrefix() + "-" + config.runtimeSettings().auditFileSuffix(), RUNTIME_AUDIT_HEADER);
        this.landsIntegration = this.createLandsIntegration();
        this.snapshotRepository = this.createSnapshotRepository();
        this.economy = this.resolveEconomy();
    }

    public String providerName() {
        return this.snapshotRepository == null ? "runtime-disabled" : this.snapshotRepository.name();
    }

    public void handleRentalBlockInteract(LandBlockInteractEvent event) {
        Player player;
        if (!this.config.enabled() || this.config.dryRun() || !this.config.runtimeSettings().interceptRentalBlocks()) {
            return;
        }
        if (this.snapshotRepository == null || this.economy == null) {
            return;
        }
        if (event.getLandBlock().getLandBlockType() != LandBlockType.RENTAL) {
            return;
        }
        LandPlayer landPlayer = event.getLandPlayer();
        Player player2 = player = landPlayer == null ? null : landPlayer.getPlayer();
        if (landPlayer == null || player == null) {
            return;
        }
        Land land = event.getLand();
        if (!this.isWhitelisted(land)) {
            return;
        }
        Area area = this.resolveArea(land, event.getLandBlock());
        if (area == null) {
            return;
        }
        RentalOfferBase offer = area.getRentalOffer();
        if (!(offer instanceof RentedState)) {
            return;
        }
        UUID tenant = area.getTenant();
        if (tenant == null || !tenant.equals(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        this.queueRenewal(area, player);
    }

    public boolean handlePotentialTrustCommand(Player player, String rawMessage) {
        if (!this.config.enabled() || this.config.dryRun() || !this.config.runtimeSettings().repairTenantBeforeTrustCommand()) {
            return false;
        }
        ResidentCommand residentCommand = this.parseResidentCommand(rawMessage);
        if (player == null || !player.isOnline() || this.landsIntegration == null || residentCommand == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        TrustAreaResolution resolution = this.resolveTrustAreaForResident(playerId, player.getLocation());
        if (resolution.resolvedArea() == null) {
            if (resolution.firstTenantArea() == null) {
                return false;
            }
            if (this.playerOwnsAnyLand(playerId)) {
                return false;
            }

            this.logger.info("Blocked bridged " + residentCommand.action().commandName() + " because resident is outside resolved rental area. resident=" + player.getName()
                    + ", currentArea=" + this.describeArea(resolution.currentArea())
                    + ", firstTenantArea=" + this.describeArea(resolution.firstTenantArea())
                    + ", detectionMode=" + resolution.detectionMode());
            this.sendResidentLocationGuidance(player, resolution, residentCommand.action());
            return true;
        }

        if (!"direct".equals(resolution.detectionMode())) {
            this.logger.info("Resolved tenant " + residentCommand.action().commandName() + " area by fallback. resident=" + player.getName()
                    + ", detectionMode=" + resolution.detectionMode()
                    + ", currentArea=" + this.describeArea(resolution.currentArea())
                    + ", resolvedArea=" + this.describeArea(resolution.resolvedArea()));
        }

        TenantSelfHealOutcome repairOutcome = this.repairTenantIdentity(resolution.resolvedArea(), playerId);
        if (!repairOutcome.applicable()) {
            return false;
        }
        if (!repairOutcome.success()) {
            this.logger.warning("Tenant self-heal failed before bridged /lands " + residentCommand.action().commandName() + " for resident " + player.getName() + " in area " + repairOutcome.areaName() + ": " + repairOutcome.note());
            if (residentCommand.action() == ResidentCommandAction.TRUST) {
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "当前租户身份自修复失败，本次给予其他居民信任未执行，请联系管理员处理。");
            } else {
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "当前租户身份自修复失败，本次移除居民信任未执行，请联系管理员处理。");
            }
            return true;
        }

        return residentCommand.action() == ResidentCommandAction.TRUST
                ? this.handleBridgedTrustGrant(player, residentCommand, resolution, repairOutcome)
                : this.handleBridgedTrustRevoke(player, residentCommand, resolution, repairOutcome);
    }

    public BridgeRunResult runCycle() {

        BridgeRunResult result = BridgeRunResult.empty(this.providerName(), "cycle", this.config.dryRun(), "\u8fd0\u884c\u65f6\u6a21\u5f0f\u5df2\u79fb\u9664\u6b64\u529f\u80fd");
        this.lastResult.set(result);
        return result;
    }

    public BridgeRunResult restore(String landFilter) {
        BridgeRunResult result = BridgeRunResult.empty(this.providerName(), "restore", this.config.dryRun(), "\u8fd0\u884c\u65f6\u6a21\u5f0f\u4e0d\u518d\u63d0\u4f9b\u65e7\u7248 restore \u529f\u80fd");
        this.lastResult.set(result);
        return result;
    }

    public BridgeComputation computeDynamicCap(int baseMaxMinutes, int rentMinutes, long passedSeconds) {
        return DynamicCapCalculator.compute(baseMaxMinutes, rentMinutes, passedSeconds);
    }

    public BridgeRunResult lastResult() {
        return this.lastResult.get();
    }

    public boolean isRunning() {
        return !this.inFlightAreas.isEmpty();
    }

    public boolean isRuntimeReady() {
        return this.config.enabled() && !this.config.dryRun() && this.landsIntegration != null && this.snapshotRepository != null && this.economy != null;
    }

    public boolean hasEconomy() {
        return this.economy != null;
    }

    public boolean hasSnapshotRepository() {
        return this.snapshotRepository != null;
    }

    public CompletableFuture<List<RentalQueryEntry>> queryRentalsForResident(UUID residentId) {
        if (residentId == null || this.landsIntegration == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<RentalAreaDescriptor> descriptors = new ArrayList<>();
        for (Area area : this.findTenantAreas(residentId)) {
            RentalAreaDescriptor descriptor = this.describeRentalArea(area);
            if (descriptor != null) {
                descriptors.add(descriptor);
            }
        }
        return this.queryRentalDescriptors(descriptors);
    }

    public CompletableFuture<List<RentalQueryEntry>> queryAllActiveRentals() {
        if (this.landsIntegration == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return this.queryRentalDescriptors(this.collectAllActiveRentalAreas());
    }

    public String formatDurationForDisplay(int minutes) {
        return minutes < 0 ? "\u65e0\u6cd5\u7cbe\u786e\u8ba1\u7b97" : this.formatDurationMinutes(minutes);
    }

    public long attemptedRenewals() {
        return this.attemptedRenewals.get();
    }

    public long successfulRenewals() {
        return this.successfulRenewals.get();
    }

    public long deniedRenewals() {
        return this.deniedRenewals.get();
    }

    public long failedRenewals() {
        return this.failedRenewals.get();
    }

    public int inFlightCount() {
        return this.inFlightAreas.size();
    }

    public RenewalResult lastRenewal() {
        return this.lastRenewal.get();
    }

    public void close() {
        if (this.snapshotRepository != null) {
            try {
                this.snapshotRepository.close();
            }
            catch (Exception exception) {
                this.logger.warning("Failed to close snapshot repository: " + exception.getMessage());
            }
        }
    }

    private void queueRenewal(Area area, Player player) {
        String lockKey = area.getULID().toString();
        if (!this.inFlightAreas.add(lockKey)) {
            this.deniedRenewals.incrementAndGet();
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.YELLOW) + "\u5f53\u524d\u533a\u57df\u6b63\u5728\u5904\u7406\u7eed\u79df\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002");
            return;
        }
        this.attemptedRenewals.incrementAndGet();
        String landId = area.getLand().getULID().toString();
        String areaId = area.getULID().toString();
        UUID playerId = player.getUniqueId();
        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin)this.plugin, () -> {
            Optional<RentalSnapshot> snapshot = this.snapshotRepository.findSnapshot(landId, areaId);
            this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> this.finalizeRenewal(area, playerId, snapshot));
        });
    }

    private void finalizeRenewal(Area area, UUID playerId, Optional<RentalSnapshot> snapshotOptional) {
        TenantSelfHealOutcome repairOutcome;
        Player player = Bukkit.getPlayer((UUID)playerId);
        if (player == null || !player.isOnline()) {
            this.failedRenewals.incrementAndGet();
            this.releaseLock(area);
            return;
        }
        if (snapshotOptional.isEmpty()) {
            this.failedRenewals.incrementAndGet();
            RenewalResult result = new RenewalResult(area.getLand().getName(), area.getName(), playerId, player.getName(), 0, 0, 0, 0, 0L, 0, 0, 0.0, false, "SNAPSHOT_MISSING", "\u8fd0\u884c\u65f6\u79df\u8d41\u5feb\u7167\u7f3a\u5931");
            this.lastRenewal.set(result);
            this.audit(result);
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "\u7f3a\u5c11\u8be5\u533a\u57df\u7684\u79df\u8d41\u5feb\u7167\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002\u533a\u57df=" + area.getName());
            this.releaseLock(area);
            return;
        }
        RentalOfferBase currentOffer = area.getRentalOffer();
        if (!(currentOffer instanceof RentedState)) {
            this.failedRenewals.incrementAndGet();
            RenewalResult result = new RenewalResult(area.getLand().getName(), area.getName(), playerId, player.getName(), 0, 0, 0, 0, 0L, 0, 0, 0.0, false, "STATE_CHANGED", "\u5f53\u524d\u533a\u57df\u5df2\u4e0d\u5904\u4e8e\u79df\u8d41\u72b6\u6001");
            this.lastRenewal.set(result);
            this.audit(result);
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "\u8be5\u533a\u57df\u5f53\u524d\u5df2\u4e0d\u5904\u4e8e\u79df\u8d41\u72b6\u6001\uff0c\u8bf7\u91cd\u65b0\u786e\u8ba4\u3002");
            this.releaseLock(area);
            return;
        }
        RentedState rentedState = (RentedState)currentOffer;
        UUID tenant = area.getTenant();
        if (tenant == null || !tenant.equals(playerId)) {
            this.failedRenewals.incrementAndGet();
            RenewalResult result = new RenewalResult(area.getLand().getName(), area.getName(), playerId, player.getName(), 0, 0, 0, 0, 0L, 0, 0, 0.0, false, "TENANT_CHANGED", "\u5f53\u524d\u5c45\u6c11\u5df2\u4e0d\u662f\u79df\u6237");
            this.lastRenewal.set(result);
            this.audit(result);
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "\u5f53\u524d\u5c45\u6c11\u5df2\u7ecf\u4e0d\u662f\u8be5\u533a\u57df\u7684\u79df\u6237\uff0c\u65e0\u6cd5\u7ee7\u7eed\u7eed\u79df\u3002");
            this.releaseLock(area);
            return;
        }
        RentalSnapshot snapshot = snapshotOptional.get();
        int rentMinutes = Math.max(1, rentedState.getMinutes());
        int baseMaxMinutes = this.baseMaxRegistry.getOrStore(area.getULID().toString(), snapshot.baseMaxMinutes());
        int rentedBefore = Math.max(snapshot.rentedMinutes(), rentedState.getRentedMinutes());
        int passedMinutes = (int)Math.max(0L, snapshot.passedSeconds() / 60L);
        int remainingBefore = Math.max(0, rentedBefore - passedMinutes);
        int remainingAfter = remainingBefore + rentMinutes;
        double cost = currentOffer.getCost();
        if (remainingAfter > baseMaxMinutes) {
            this.deniedRenewals.incrementAndGet();
            RenewalResult result = new RenewalResult(area.getLand().getName(), area.getName(), playerId, player.getName(), baseMaxMinutes, rentMinutes, rentedBefore, rentedBefore, snapshot.passedSeconds(), remainingBefore, remainingBefore, cost, false, "LIMIT_REACHED", "\u5269\u4f59\u65f6\u95f4\u4e0a\u9650\u5df2\u8fbe\u5230");
            this.lastRenewal.set(result);
            this.audit(result);
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "\u5f53\u524d\u5269\u4f59\u65f6\u95f4\u4e3a " + this.formatDurationMinutes(remainingBefore) + String.valueOf(ChatColor.RED) + "\uff0c\u82e5\u518d\u7eed\u79df\u4e00\u6b21\u5c06\u8d85\u8fc7\u4e0a\u9650 " + this.formatDurationMinutes(baseMaxMinutes) + "\u3002");
            this.releaseLock(area);
            return;
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer((UUID)playerId);
        if (!this.economy.has(offlinePlayer, cost)) {
            this.deniedRenewals.incrementAndGet();
            RenewalResult result = new RenewalResult(area.getLand().getName(), area.getName(), playerId, player.getName(), baseMaxMinutes, rentMinutes, rentedBefore, rentedBefore, snapshot.passedSeconds(), remainingBefore, remainingBefore, cost, false, "INSUFFICIENT_FUNDS", "\u7ecf\u6d4e\u4f59\u989d\u4e0d\u8db3");
            this.lastRenewal.set(result);
            this.audit(result);
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "\u4f59\u989d\u4e0d\u8db3\uff0c\u672c\u6b21\u7eed\u79df\u9700\u8981 " + String.valueOf(ChatColor.GOLD) + this.formatCost(cost));
            this.releaseLock(area);
            return;
        }
        EconomyResponse withdrawResponse = this.economy.withdrawPlayer(offlinePlayer, cost);
        if (withdrawResponse == null || !withdrawResponse.transactionSuccess()) {
            this.failedRenewals.incrementAndGet();
            RenewalResult result = new RenewalResult(area.getLand().getName(), area.getName(), playerId, player.getName(), baseMaxMinutes, rentMinutes, rentedBefore, rentedBefore, snapshot.passedSeconds(), remainingBefore, remainingBefore, cost, false, "WITHDRAW_FAILED", withdrawResponse == null ? "\u7ecf\u6d4e\u6263\u8d39\u8fd4\u56de\u7a7a\u7ed3\u679c" : withdrawResponse.errorMessage);
            this.lastRenewal.set(result);
            this.audit(result);
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "\u6263\u8d39\u5931\u8d25\uff0c\u672c\u6b21\u7eed\u79df\u672a\u5b8c\u6210\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002");
            this.releaseLock(area);
            return;
        }
        rentedState.modifyRentedMinutes(rentMinutes);
        TenantSelfHealOutcome tenantSelfHealOutcome = repairOutcome = this.config.runtimeSettings().repairTenantOnRenewal() ? this.repairTenantIdentity(area, playerId) : TenantSelfHealOutcome.notApplicable(area, "tenant self-heal disabled");
        if (repairOutcome.applicable() && !repairOutcome.success()) {
            this.logger.warning("Tenant self-heal check during renewal failed for resident " + player.getName() + " in area " + repairOutcome.areaName() + ": " + repairOutcome.note());
        }
        int rentedAfter = rentedState.getRentedMinutes();
        int remainingAfterReal = Math.max(0, rentedAfter - passedMinutes);
        RenewalResult result = new RenewalResult(area.getLand().getName(), area.getName(), playerId, player.getName(), baseMaxMinutes, rentMinutes, rentedBefore, rentedAfter, snapshot.passedSeconds(), remainingBefore, remainingAfterReal, cost, true, "SUCCESS", this.buildRenewalSuccessNote(repairOutcome));
        this.persist(area.getLand()).whenComplete((ignored, throwable) -> this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
            if (throwable != null) {
                this.rollbackRenewal(area, rentedState, rentMinutes, offlinePlayer, cost, player, result, (Throwable)throwable);
                this.releaseLock(area);
                return;
            }
            this.successfulRenewals.incrementAndGet();
            this.lastRenewal.set(result);
            this.audit(result);
            if (player.isOnline()) {
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.GREEN) + "\u5df2\u6210\u529f\u4e3a\u533a\u57df " + String.valueOf(ChatColor.GOLD) + area.getName() + String.valueOf(ChatColor.GREEN) + " \u7eed\u79df " + String.valueOf(ChatColor.YELLOW) + this.formatDurationMinutes(rentMinutes) + String.valueOf(ChatColor.GREEN) + "\uff0c\u5f53\u524d\u5269\u4f59\u65f6\u95f4 " + String.valueOf(ChatColor.YELLOW) + this.formatDurationMinutes(remainingAfterReal));
            }
            this.releaseLock(area);
        }));
    }

    private void rollbackRenewal(Area area, RentedState rentedState, int rentMinutes, OfflinePlayer offlinePlayer, double cost, Player player, RenewalResult result, Throwable throwable) {
        rentedState.modifyRentedMinutes(-rentMinutes);
        try {
            this.persist(area.getLand());
        }
        catch (Exception exception) {
            // empty catch block
        }
        EconomyResponse refundResponse = this.economy.depositPlayer(offlinePlayer, cost);
        this.lastRenewal.set(new RenewalResult(result.landName(), result.areaName(), result.playerId(), result.playerName(), result.baseMaxMinutes(), result.rentMinutes(), result.rentedMinutesBefore(), result.rentedMinutesBefore(), result.passedSeconds(), result.remainingBeforeMinutes(), result.remainingBeforeMinutes(), result.cost(), false, "SAVE_FAILED", throwable.getMessage()));
        this.audit(this.lastRenewal.get());
        this.logger.warning("Failed to save runtime renewal for area " + result.areaName() + ": " + throwable.getMessage());
        if (refundResponse == null || !refundResponse.transactionSuccess()) {
            this.logger.warning("Failed to refund renewal cost for area " + result.areaName());
        }
        if (player.isOnline()) {
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "\u7eed\u79df\u7ed3\u679c\u4fdd\u5b58\u5931\u8d25\uff0c\u5df2\u5c1d\u8bd5\u56de\u6eda\u65f6\u95f4\u4e0e\u9000\u6b3e\u3002");
        }
    }

    private TenantSelfHealOutcome repairCurrentTenantIdentity(Player player) {
        Area area = this.landsIntegration.getArea(player.getLocation());
        return this.repairTenantIdentity(area, player.getUniqueId());
    }

    private TenantSelfHealOutcome repairTenantIdentity(Area area, UUID playerId) {
        boolean finalHasTrustFlag;
        boolean needsTenantRole;
        Role currentRole;
        if (area == null) {
            return TenantSelfHealOutcome.notApplicable(null, "no area at current location");
        }
        Land land = area.getLand();
        if (!this.isWhitelisted(land)) {
            return TenantSelfHealOutcome.notApplicable(area, "land not whitelisted");
        }
        RentalOfferBase rentalOffer = area.getRentalOffer();
        if (!(rentalOffer instanceof RentedState)) {
            return TenantSelfHealOutcome.notApplicable(area, "area is not rented");
        }
        UUID tenant = area.getTenant();
        if (tenant == null || !tenant.equals(playerId)) {
            return TenantSelfHealOutcome.notApplicable(area, "player is not tenant");
        }
        boolean changed = false;
        StringBuilder note = new StringBuilder();
        if (!area.isTrusted(playerId)) {
            area.trustPlayer(playerId);
            if (!area.isTrusted(playerId)) {
                return TenantSelfHealOutcome.failure(area, "failed to restore tenant trusted state");
            }
            changed = true;
            note.append("trusted");
        }
        boolean currentRoleHasTrust = (currentRole = area.getRole(playerId)) != null && currentRole.hasFlag(PLAYER_TRUST_FLAG);
        boolean bl = needsTenantRole = currentRole == null || currentRole.isVisitorRole() || !currentRoleHasTrust;
        if (needsTenantRole) {
            Role tenantRole = area.getRole(TENANT_ROLE_ID);
            if (tenantRole == null) {
                return TenantSelfHealOutcome.failure(area, "tenant role missing in area");
            }
            if (currentRole == null || !currentRole.equals(tenantRole)) {
                area.setRole(playerId, tenantRole);
                changed = true;
                if (!note.isEmpty()) {
                    note.append(';');
                }
                note.append("role=tenant");
            }
        }
        Role finalRole = area.getRole(playerId);
        boolean finalTrusted = area.isTrusted(playerId);
        boolean bl2 = finalHasTrustFlag = finalRole != null && finalRole.hasFlag(PLAYER_TRUST_FLAG);
        if (!finalTrusted) {
            return TenantSelfHealOutcome.failure(area, "tenant still not trusted after self-heal");
        }
        if (!finalHasTrustFlag) {
            return TenantSelfHealOutcome.failure(area, "tenant role still missing player_trust");
        }
        if (!changed) {
            return TenantSelfHealOutcome.success(area, false, "already healthy");
        }
        return TenantSelfHealOutcome.success(area, true, note.toString());
    }

    private String buildRenewalSuccessNote(TenantSelfHealOutcome repairOutcome) {
        if (!repairOutcome.applicable()) {
            return "\u901a\u8fc7\u8fd0\u884c\u65f6\u6865\u63a5\u5b8c\u6210\u7eed\u79df";
        }
        if (!repairOutcome.success()) {
            return "\u901a\u8fc7\u8fd0\u884c\u65f6\u6865\u63a5\u5b8c\u6210\u7eed\u79df\uff1b\u79df\u6237\u8eab\u4efd\u81ea\u4fee\u590d\u544a\u8b66=" + repairOutcome.note();
        }
        if (!repairOutcome.changed()) {
            return "\u901a\u8fc7\u8fd0\u884c\u65f6\u6865\u63a5\u5b8c\u6210\u7eed\u79df\uff1b\u79df\u6237\u8eab\u4efd\u5df2\u68c0\u67e5";
        }
        return "\u901a\u8fc7\u8fd0\u884c\u65f6\u6865\u63a5\u5b8c\u6210\u7eed\u79df\uff1b\u79df\u6237\u8eab\u4efd\u81ea\u4fee\u590d=" + repairOutcome.note();
    }

    private void schedulePersist(Land land, String source, String areaName) {
        this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> this.persist(land).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                this.logger.warning("Failed to persist tenant self-heal for source " + source + " in area " + areaName + ": " + throwable.getMessage());
            }
        }), 1L);
    }

    private CompletableFuture<?> persist(Land land) {
        if (this.config.runtimeSettings().saveAndPublishToRedis()) {
            return land.saveAndPublishToRedis();
        }
        return land.save();
    }

    private CompletableFuture<List<RentalQueryEntry>> queryRentalDescriptors(List<RentalAreaDescriptor> descriptors) {
        if (descriptors.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            List<RentalQueryEntry> entries = new ArrayList<>();
            for (RentalAreaDescriptor descriptor : descriptors) {
                entries.add(this.buildRentalQueryEntry(descriptor));
            }
            entries.sort((left, right) -> {
                int tenantCompare = left.tenantName().compareToIgnoreCase(right.tenantName());
                if (tenantCompare != 0) {
                    return tenantCompare;
                }
                int landCompare = left.landName().compareToIgnoreCase(right.landName());
                if (landCompare != 0) {
                    return landCompare;
                }
                return left.areaName().compareToIgnoreCase(right.areaName());
            });
            return entries;
        });
    }

    private List<RentalAreaDescriptor> collectAllActiveRentalAreas() {
        List<RentalAreaDescriptor> descriptors = new ArrayList<>();
        for (Object landObject : this.landsIntegration.getLands()) {
            if (!(landObject instanceof Land land) || !this.isWhitelisted(land)) {
                continue;
            }
            for (Object areaObject : land.getAllAreas()) {
                if (!(areaObject instanceof Area area)) {
                    continue;
                }
                RentalAreaDescriptor descriptor = this.describeRentalArea(area);
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            }
        }
        return descriptors;
    }

    private RentalAreaDescriptor describeRentalArea(Area area) {
        if (area == null) {
            return null;
        }
        RentalOfferBase rentalOffer = area.getRentalOffer();
        if (!(rentalOffer instanceof RentedState rentedState)) {
            return null;
        }
        UUID tenantId = area.getTenant();
        if (tenantId == null) {
            return null;
        }
        OfflinePlayer tenant = Bukkit.getOfflinePlayer(tenantId);
        String tenantName = this.safeResidentName(tenant, tenantId.toString());
        Integer baseMax = this.baseMaxRegistry.peek(area.getULID().toString());
        return new RentalAreaDescriptor(
                area.getLand().getULID().toString(),
                area.getLand().getName(),
                area.getULID().toString(),
                area.getName(),
                tenantId,
                tenantName,
                Math.max(1, rentedState.getMinutes()),
                Math.max(0, rentedState.getMaxMinutes()),
                baseMax == null ? 0 : Math.max(0, baseMax),
                baseMax != null,
                Math.max(0, rentedState.getRentedMinutes())
        );
    }

    private RentalQueryEntry buildRentalQueryEntry(RentalAreaDescriptor descriptor) {
        Optional<RentalSnapshot> snapshot = this.snapshotRepository == null
                ? Optional.empty()
                : this.snapshotRepository.findSnapshot(descriptor.landId(), descriptor.areaId());
        int rentedMinutes = descriptor.rentedMinutes();
        long passedSeconds = 0L;
        int remainingMinutes = -1;
        boolean preciseRemaining = false;
        if (snapshot.isPresent()) {
            RentalSnapshot rentalSnapshot = snapshot.get();
            passedSeconds = Math.max(0L, rentalSnapshot.passedSeconds());
            int passedMinutes = (int) Math.max(0L, passedSeconds / 60L);
            int rentedReference = Math.max(rentedMinutes, rentalSnapshot.rentedMinutes());
            remainingMinutes = Math.max(0, rentedReference - passedMinutes);
            preciseRemaining = true;
        }
        int baseMaxMinutes = descriptor.baseMaxKnown() ? descriptor.baseMaxMinutes() : descriptor.currentMaxMinutes();
        return new RentalQueryEntry(
                descriptor.tenantId(),
                descriptor.tenantName(),
                descriptor.landName(),
                descriptor.areaName(),
                descriptor.areaId(),
                descriptor.rentMinutes(),
                baseMaxMinutes,
                descriptor.baseMaxKnown(),
                descriptor.currentMaxMinutes(),
                rentedMinutes,
                passedSeconds,
                remainingMinutes,
                preciseRemaining,
                snapshot.isPresent()
        );
    }

    private Area resolveArea(Land land, LandBlock landBlock) {
        String blockId = landBlock.getId();
        for (Object object : land.getAllAreas()) {
            Area area;
            RentalOfferBase offer;
            if (!(object instanceof Area) || (offer = (area = (Area)object).getRentalOffer()) == null || offer.getBlock() == null || !blockId.equals(offer.getBlock().getId())) continue;
            return area;
        }
        return null;
    }

    private TrustAreaResolution resolveTrustAreaForResident(UUID playerId, Location location) {
        Area currentArea = this.landsIntegration.getArea(location);
        List<Area> tenantAreas = this.findTenantAreas(playerId);
        Area firstTenantArea = tenantAreas.isEmpty() ? null : tenantAreas.getFirst();
        if (this.isTenantArea(currentArea, playerId)) {
            return new TrustAreaResolution(currentArea, currentArea, firstTenantArea, tenantAreas.size(), "direct");
        }

        Area exactMatch = this.findTenantAreaByLocation(tenantAreas, location, true);
        if (exactMatch != null) {
            return new TrustAreaResolution(currentArea, exactMatch, firstTenantArea, tenantAreas.size(), "exact-scan");
        }

        Area horizontalMatch = this.findTenantAreaByLocation(tenantAreas, location, false);
        if (horizontalMatch != null) {
            return new TrustAreaResolution(currentArea, horizontalMatch, firstTenantArea, tenantAreas.size(), "horizontal-scan");
        }

        return new TrustAreaResolution(currentArea, null, firstTenantArea, tenantAreas.size(), "none");
    }

    private Area findTenantAreaByLocation(List<Area> tenantAreas, Location location, boolean exactY) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        Area matchedArea = null;
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        for (Area area : tenantAreas) {
            if (!(area instanceof LandArea landArea) || landArea.getWorld() == null || !landArea.getWorld().equals(location.getWorld())) {
                continue;
            }

            boolean contains = exactY
                    ? landArea.contains(x, y, z)
                    : landArea.getBoundingBox() != null
                    ? landArea.getBoundingBox().contains(x, z)
                    : landArea.containsChunk(x >> 4, z >> 4, true);
            if (!contains) {
                continue;
            }

            if (matchedArea != null && !matchedArea.getULID().equals(area.getULID())) {
                return null;
            }
            matchedArea = area;
        }
        return matchedArea;
    }

    private boolean handleBridgedTrustGrant(Player player, ResidentCommand residentCommand, TrustAreaResolution resolution, TenantSelfHealOutcome repairOutcome) {
        Area area = resolution.resolvedArea();
        OfflinePlayer target = this.resolveTrustTarget(residentCommand.targetName());
        if (target == null || target.getUniqueId() == null) {
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "未找到这个居民，请确认居民名是否正确，并且该居民至少进服过一次。");
            return true;
        }

        UUID targetId = target.getUniqueId();
        String residentName = this.safeResidentName(target, residentCommand.targetName());
        if (targetId.equals(player.getUniqueId())) {
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.YELLOW) + "你不需要对自己使用这个命令。");
            return true;
        }

        boolean alreadyTrusted = area.isTrusted(targetId);
        boolean trustChanged = false;
        if (!alreadyTrusted) {
            boolean trusted = area.trustPlayer(targetId);
            if (!trusted || !area.isTrusted(targetId)) {
                this.logger.warning("Bridged trust grant failed before save. actor=" + player.getName()
                        + ", target=" + residentName
                        + ", area=" + area.getName()
                        + ", mode=" + resolution.detectionMode());
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "未能给予该居民信任，可能是该居民已被封禁，或该区域状态异常。");
                return true;
            }
            trustChanged = true;
        }

        if (!trustChanged && !repairOutcome.changed()) {
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.YELLOW) + "居民 " + String.valueOf(ChatColor.GOLD) + residentName + String.valueOf(ChatColor.YELLOW) + " 已经在区域 " + String.valueOf(ChatColor.GOLD) + area.getName() + String.valueOf(ChatColor.YELLOW) + " 的信任列表中。");
            if (!"direct".equals(resolution.detectionMode())) {
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.GRAY) + "本次已自动按你的租赁区域 " + area.getName() + " 处理，已绕过当前位置识别偏差。");
            }
            return true;
        }

        boolean finalTrustChanged = trustChanged;
        this.persist(area.getLand()).whenComplete((ignored, throwable) -> this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
            if (throwable != null) {
                if (finalTrustChanged) {
                    try {
                        area.untrustPlayer(targetId);
                        this.persist(area.getLand());
                    }
                    catch (Exception ignoredException) {
                    }
                }
                this.logger.warning("Failed to save bridged trust grant. actor=" + player.getName()
                        + ", target=" + residentName
                        + ", area=" + area.getName()
                        + ", mode=" + resolution.detectionMode()
                        + ", error=" + throwable.getMessage());
                if (player.isOnline()) {
                    player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "给予居民信任时保存失败，已尝试回滚，请稍后重试并联系管理员。");
                }
                return;
            }

            if (!player.isOnline()) {
                return;
            }

            if (finalTrustChanged) {
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.GREEN) + "已将居民 " + String.valueOf(ChatColor.GOLD) + residentName + String.valueOf(ChatColor.GREEN) + " 加入区域 " + String.valueOf(ChatColor.GOLD) + area.getName() + String.valueOf(ChatColor.GREEN) + " 的信任列表。");
            } else {
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.YELLOW) + "居民 " + String.valueOf(ChatColor.GOLD) + residentName + String.valueOf(ChatColor.YELLOW) + " 已经在区域 " + String.valueOf(ChatColor.GOLD) + area.getName() + String.valueOf(ChatColor.YELLOW) + " 的信任列表中，已同步保存当前租户身份状态。");
            }
            if (!"direct".equals(resolution.detectionMode())) {
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.GRAY) + "本次已自动按你的租赁区域 " + area.getName() + " 处理居民信任，已绕过当前位置识别偏差。");
            }
        }));
        return true;
    }

    private boolean handleBridgedTrustRevoke(Player player, ResidentCommand residentCommand, TrustAreaResolution resolution, TenantSelfHealOutcome repairOutcome) {
        Area area = resolution.resolvedArea();
        OfflinePlayer target = this.resolveTrustTarget(residentCommand.targetName());
        if (target == null || target.getUniqueId() == null) {
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "未找到这个居民，请确认居民名是否正确，并且该居民至少进服过一次。");
            return true;
        }

        UUID targetId = target.getUniqueId();
        String residentName = this.safeResidentName(target, residentCommand.targetName());
        if (targetId.equals(player.getUniqueId())) {
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.YELLOW) + "你不需要对自己使用这个命令。");
            return true;
        }

        boolean currentlyTrusted = area.isTrusted(targetId);
        boolean revokeChanged = false;
        if (currentlyTrusted) {
            boolean untrusted = area.untrustPlayer(targetId);
            if (!untrusted || area.isTrusted(targetId)) {
                this.logger.warning("Bridged trust revoke failed before save. actor=" + player.getName()
                        + ", target=" + residentName
                        + ", area=" + area.getName()
                        + ", mode=" + resolution.detectionMode());
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "未能移除该居民的信任，可能是区域状态异常，请稍后重试。");
                return true;
            }
            revokeChanged = true;
        }

        if (!revokeChanged && !repairOutcome.changed()) {
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.YELLOW) + "居民 " + String.valueOf(ChatColor.GOLD) + residentName + String.valueOf(ChatColor.YELLOW) + " 当前不在区域 " + String.valueOf(ChatColor.GOLD) + area.getName() + String.valueOf(ChatColor.YELLOW) + " 的信任列表中。");
            if (!"direct".equals(resolution.detectionMode())) {
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.GRAY) + "本次已自动按你的租赁区域 " + area.getName() + " 检查，已绕过当前位置识别偏差。");
            }
            return true;
        }

        boolean finalRevokeChanged = revokeChanged;
        this.persist(area.getLand()).whenComplete((ignored, throwable) -> this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
            if (throwable != null) {
                if (finalRevokeChanged) {
                    try {
                        area.trustPlayer(targetId);
                        this.persist(area.getLand());
                    }
                    catch (Exception ignoredException) {
                    }
                }
                this.logger.warning("Failed to save bridged trust revoke. actor=" + player.getName()
                        + ", target=" + residentName
                        + ", area=" + area.getName()
                        + ", mode=" + resolution.detectionMode()
                        + ", error=" + throwable.getMessage());
                if (player.isOnline()) {
                    player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.RED) + "移除居民信任时保存失败，已尝试回滚，请稍后重试并联系管理员。");
                }
                return;
            }

            if (!player.isOnline()) {
                return;
            }

            if (finalRevokeChanged) {
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.GREEN) + "已将居民 " + String.valueOf(ChatColor.GOLD) + residentName + String.valueOf(ChatColor.GREEN) + " 从区域 " + String.valueOf(ChatColor.GOLD) + area.getName() + String.valueOf(ChatColor.GREEN) + " 的信任列表中移除。");
            } else {
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.YELLOW) + "居民 " + String.valueOf(ChatColor.GOLD) + residentName + String.valueOf(ChatColor.YELLOW) + " 当前不在区域 " + String.valueOf(ChatColor.GOLD) + area.getName() + String.valueOf(ChatColor.YELLOW) + " 的信任列表中，已同步保存当前租户身份状态。");
            }
            if (!"direct".equals(resolution.detectionMode())) {
                player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.GRAY) + "本次已自动按你的租赁区域 " + area.getName() + " 处理居民信任，已绕过当前位置识别偏差。");
            }
        }));
        return true;
    }

    private void sendResidentLocationGuidance(Player player, TrustAreaResolution resolution, ResidentCommandAction action) {
        String actionText = action == ResidentCommandAction.TRUST ? "给予其他居民信任" : "移除其他居民的信任";
        String commandText = action == ResidentCommandAction.TRUST ? "/lands trust <居民名>" : "/lands untrust <居民名>";
        player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.YELLOW) + "当前未识别到你正站在自己的租赁区域内，暂时不能" + actionText + "。");
        player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.GRAY) + "当前识别区域: " + this.describeArea(resolution.currentArea()));
        if (resolution.firstTenantArea() != null) {
            player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.GRAY) + "你名下租赁区域: " + this.describeArea(resolution.firstTenantArea()));
        }
        player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.GRAY) + "请尽量站在房屋中央或租赁牌附近，再使用 " + commandText + "。");
        player.sendMessage(this.color(this.config.runtimeSettings().messagePrefix()) + String.valueOf(ChatColor.GRAY) + "如果你确认自己就在房内，请联系管理员检查该房屋的租赁区域边界。");
    }

    private OfflinePlayer resolveTrustTarget(String targetName) {
        Player onlineExact = Bukkit.getPlayerExact(targetName);
        if (onlineExact != null) {
            return onlineExact;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(targetName)) {
                return online;
            }
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
        if (offline.isOnline() || offline.hasPlayedBefore()) {
            return offline;
        }
        return null;
    }

    private String safeResidentName(OfflinePlayer target, String fallbackName) {
        String name = target.getName();
        return name == null || name.isBlank() ? fallbackName : name;
    }

    private String describeArea(Area area) {
        if (area == null) {
            return "\u672a\u8bc6\u522b";
        }
        return area.getLand().getName() + "/" + area.getName();
    }

    private ResidentCommand parseResidentCommand(String rawMessage) {
        ResidentCommandAction action = this.resolveResidentCommandAction(rawMessage);
        if (action == null) {
            return null;
        }

        String[] parts = rawMessage.trim().split("\s+");
        if (parts.length < 3) {
            return null;
        }

        String targetName = parts[2].trim();
        if (targetName.isEmpty()) {
            return null;
        }
        return new ResidentCommand(action, targetName);
    }

    private List<Area> findTenantAreas(UUID playerId) {
        List<Area> tenantAreas = new ArrayList<>();
        if (this.landsIntegration == null) {
            return tenantAreas;
        }

        for (Object landObject : this.landsIntegration.getLands()) {
            if (!(landObject instanceof Land land) || !this.isWhitelisted(land)) {
                continue;
            }
            for (Object areaObject : land.getAllAreas()) {
                if (areaObject instanceof Area area && this.isTenantArea(area, playerId)) {
                    tenantAreas.add(area);
                }
            }
        }
        return tenantAreas;
    }

    private Area findFirstTenantArea(UUID playerId) {
        List<Area> tenantAreas = this.findTenantAreas(playerId);
        return tenantAreas.isEmpty() ? null : tenantAreas.getFirst();
    }

    private boolean playerOwnsAnyLand(UUID playerId) {
        if (this.landsIntegration == null) {
            return false;
        }
        for (Object landObject : this.landsIntegration.getLands()) {
            if (!(landObject instanceof Land land) || !playerId.equals(land.getOwnerUID())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean isTenantArea(Area area, UUID playerId) {
        if (area == null || playerId == null) {
            return false;
        }
        RentalOfferBase rentalOffer = area.getRentalOffer();
        if (!(rentalOffer instanceof RentedState)) {
            return false;
        }
        UUID tenant = area.getTenant();
        return tenant != null && tenant.equals(playerId);
    }

    private boolean isWhitelisted(Land land) {

        return this.config.landWhitelist().isEmpty() || this.config.landWhitelist().contains(land.getName());
    }

    private ResidentCommandAction resolveResidentCommandAction(String rawMessage) {
        String command;
        if (rawMessage == null) {
            return null;
        }
        String trimmed = rawMessage.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split("\s+");
        if (parts.length < 3) {
            return null;
        }
        String string = command = parts[0].startsWith("/") ? parts[0].substring(1) : parts[0];
        if (!command.equalsIgnoreCase("lands") && !command.equalsIgnoreCase("land")) {
            return null;
        }
        if (parts[1].equalsIgnoreCase("trust")) {
            return ResidentCommandAction.TRUST;
        }
        if (parts[1].equalsIgnoreCase("untrust")) {
            return ResidentCommandAction.UNTRUST;
        }
        return null;
    }

    private LandsIntegration createLandsIntegration() {
        try {
            return LandsIntegration.of((Plugin)this.plugin);
        }
        catch (Exception exception) {
            this.logger.warning("bonfirelandsbridge runtime mode could not create Lands integration: " + exception.getMessage());
            return null;
        }
    }

    private Economy resolveEconomy() {
        RegisteredServiceProvider registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            this.logger.warning("bonfirelandsbridge runtime mode could not find Vault economy provider");
            return null;
        }
        return (Economy)registration.getProvider();
    }

    private RentalSnapshotRepository createSnapshotRepository() {
        if (!this.config.databaseSettings().enabled()) {
            this.logger.warning("bonfirelandsbridge runtime mode needs database.enabled=true");
            return null;
        }
        return new MysqlRentalSnapshotRepository(this.plugin, this.logger, this.config.databaseSettings());
    }

    private void releaseLock(Area area) {
        this.inFlightAreas.remove(area.getULID().toString());
    }

    private void audit(RenewalResult result) {
        this.auditLogService.append(String.valueOf(LocalDateTime.now()) + ",runtime," + this.csv(result.landName()) + "," + this.csv(result.areaName()) + "," + this.csv(result.playerName()) + "," + result.baseMaxMinutes() + "," + result.rentMinutes() + "," + result.rentedMinutesBefore() + "," + result.rentedMinutesAfter() + "," + result.passedSeconds() + "," + result.remainingBeforeMinutes() + "," + result.remainingAfterMinutes() + "," + this.formatCost(result.cost()) + "," + result.decision() + "," + this.csv(result.note()));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes((char)'&', (String)(text == null ? "" : text));
    }

    private String formatCost(double cost) {
        return String.format(Locale.US, "%.2f", cost);
    }

    private String formatDurationMinutes(int minutes) {
        int safeMinutes = Math.max(0, minutes);
        int days = safeMinutes / 1440;
        int hours = safeMinutes % 1440 / 60;
        int mins = safeMinutes % 60;
        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append("\u5929");
        }
        if (hours > 0) {
            builder.append(hours).append("\u5c0f\u65f6");
        }
        if (mins > 0 || builder.isEmpty()) {
            builder.append(mins).append("\u5206\u949f");
        }
        return builder.toString();
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private record RentalAreaDescriptor(
            String landId,
            String landName,
            String areaId,
            String areaName,
            UUID tenantId,
            String tenantName,
            int rentMinutes,
            int currentMaxMinutes,
            int baseMaxMinutes,
            boolean baseMaxKnown,
            int rentedMinutes) {
    }

    private record ResidentCommand(ResidentCommandAction action, String targetName) {
    }

    private enum ResidentCommandAction {
        TRUST,
        UNTRUST;

        private String commandName() {
            return this == TRUST ? "trust" : "untrust";
        }
    }

    private record TrustAreaResolution(Area currentArea, Area resolvedArea, Area firstTenantArea, int tenantAreaCount, String detectionMode) {
    }

    private record TenantSelfHealOutcome(Area area, boolean applicable, boolean success, boolean changed, String note) {
        private static TenantSelfHealOutcome notApplicable(Area area, String note) {
            return new TenantSelfHealOutcome(area, false, false, false, note);
        }

        private static TenantSelfHealOutcome success(Area area, boolean changed, String note) {
            return new TenantSelfHealOutcome(area, true, true, changed, note);
        }

        private static TenantSelfHealOutcome failure(Area area, String note) {
            return new TenantSelfHealOutcome(area, true, false, false, note);
        }

        private String landName() {
            return this.area == null ? "-" : this.area.getLand().getName();
        }

        private String areaName() {
            return this.area == null ? "-" : this.area.getName();
        }
    }
}

