package com.bonfire.landsbridge.listener;

import com.bonfire.landsbridge.BonfireLandsBridge;
import me.angeschossen.lands.api.events.land.block.LandBlockInteractEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class RentalRenewalListener implements Listener {

    private final BonfireLandsBridge plugin;

    public RentalRenewalListener(BonfireLandsBridge plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRentalInteract(LandBlockInteractEvent event) {
        plugin.bridgeService().handleRentalBlockInteract(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTrustCommand(PlayerCommandPreprocessEvent event) {
        if (plugin.bridgeService().handlePotentialTrustCommand(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }
}