package com.bonfire.landsbridge;

import com.bonfire.landsbridge.command.BridgeCommand;
import com.bonfire.landsbridge.config.BridgeConfig;
import com.bonfire.landsbridge.listener.RentalRenewalListener;
import com.bonfire.landsbridge.service.BaseMaxRegistry;
import com.bonfire.landsbridge.service.BridgeService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class BonfireLandsBridge extends JavaPlugin {

    private BridgeConfig bridgeConfig;
    private BaseMaxRegistry baseMaxRegistry;
    private BridgeService bridgeService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadBridge();

        PluginCommand command = getCommand("bonfirelandsbridge");
        if (command != null) {
            BridgeCommand executor = new BridgeCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new RentalRenewalListener(this), this);

        if (!pluginManager.isPluginEnabled("Lands")) {
            getLogger().warning("bonfirelandsbridge \u4f9d\u8d56 Lands \u63d2\u4ef6\uff0c\u8bf7\u5148\u786e\u8ba4 Lands \u5df2\u542f\u7528\u3002");
        }

        if (!bridgeConfig.enabled()) {
            getLogger().info("bonfirelandsbridge \u5df2\u5728 config.yml \u4e2d\u88ab\u7981\u7528\u3002");
            return;
        }

        if (bridgeConfig.dryRun()) {
            getLogger().info("bonfirelandsbridge v0.3.0 \u5f53\u524d\u5904\u4e8e\u9884\u6f14\u6a21\u5f0f\uff0c\u6e38\u620f\u5185\u79df\u8d41\u724c\u4ecd\u4f7f\u7528 Lands \u539f\u751f\u903b\u8f91\u3002");
            return;
        }

        getLogger().info("bonfirelandsbridge v0.3.0 \u8fd0\u884c\u65f6\u6a21\u5f0f\u5df2\u5c31\u7eea\u3002 provider=" + bridgeService.providerName()
                + ", runtimeReady=" + bridgeService.isRuntimeReady()
                + ", economy=" + bridgeService.hasEconomy()
                + ", snapshotRepo=" + bridgeService.hasSnapshotRepository());
    }

    @Override
    public void onDisable() {
        if (bridgeService != null) {
            bridgeService.close();
        }
    }

    public void reloadBridge() {
        if (bridgeService != null) {
            bridgeService.close();
        }

        reloadConfig();
        this.bridgeConfig = BridgeConfig.from(getConfig());
        this.baseMaxRegistry = new BaseMaxRegistry(this, bridgeConfig.registryFileName());
        this.bridgeService = new BridgeService(this, bridgeConfig, baseMaxRegistry);
    }

    public BridgeConfig bridgeConfig() {
        return bridgeConfig;
    }

    public BridgeService bridgeService() {
        return bridgeService;
    }
}