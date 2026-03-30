package com.bonfire.landsbridge.command;

import com.bonfire.landsbridge.BonfireLandsBridge;
import com.bonfire.landsbridge.model.BridgeComputation;
import com.bonfire.landsbridge.model.BridgeRunResult;
import com.bonfire.landsbridge.model.RenewalResult;
import com.bonfire.landsbridge.model.RentalQueryEntry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class BridgeCommand implements CommandExecutor, TabCompleter {

    private static final int RENT_LIST_PAGE_SIZE = 8;

    private final BonfireLandsBridge plugin;

    public BridgeCommand(BonfireLandsBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender.hasPermission("bonfire.landsbridge.admin")) {
                return this.handleStatus(sender);
            }
            this.sendHelp(sender, label);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        switch (subCommand) {
            case "myrent":
                return this.handleMyRent(sender, args);
            case "rentinfo":
                return this.handleRentInfo(sender, args);
            case "rentlist":
                return this.handleRentList(sender, args);
            case "status":
                return this.handleStatusCommand(sender);
            case "reload":
                return this.handleReload(sender);
            case "calc":
                return this.handleCalc(sender, label, args);
            case "runonce":
            case "restore":
                return this.handleRunOrRestore(sender, args);
            case "help":
                this.sendHelp(sender, label);
                return true;
            default:
                sender.sendMessage(this.prefix() + ChatColor.RED + "\u672a\u77e5\u5b50\u547d\u4ee4\uff0c\u8bf7\u4f7f\u7528 /" + label + " help \u67e5\u770b\u7528\u6cd5\u3002");
                return true;
        }
    }

    private boolean handleMyRent(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bonfire.landsbridge.query")) {
            sender.sendMessage(this.prefix() + ChatColor.RED + "\u4f60\u6ca1\u6709\u67e5\u8be2\u81ea\u5df1\u623f\u5c4b\u4fe1\u606f\u7684\u6743\u9650\u3002");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.prefix() + ChatColor.RED + "\u8fd9\u4e2a\u547d\u4ee4\u53ea\u80fd\u7531\u6e38\u620f\u5185\u5c45\u6c11\u4f7f\u7528\u3002");
            return true;
        }

        boolean detail = false;
        if (args.length >= 2) {
            if (!args[1].equalsIgnoreCase("detail")) {
                sender.sendMessage(this.prefix() + ChatColor.RED + "\u7528\u6cd5: /blb myrent [detail]");
                return true;
            }
            detail = true;
        }

        player.sendMessage(this.prefix() + ChatColor.YELLOW + "\u6b63\u5728\u67e5\u8be2\u4f60\u5f53\u524d\u79df\u8d41\u7684\u623f\u5c4b...");
        boolean detailView = detail;
        this.attachRentalQuery(plugin.bridgeService().queryRentalsForResident(player.getUniqueId()), sender,
                entries -> this.sendTargetRentals(player, this.safePlayerName(player), entries, detailView, false));
        return true;
    }

    private boolean handleRentInfo(CommandSender sender, String[] args) {
        if (!this.ensureAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(this.prefix() + ChatColor.RED + "\u7528\u6cd5: /blb rentinfo <\u73a9\u5bb6\u540d>");
            return true;
        }

        OfflinePlayer target = this.resolveKnownPlayer(args[1]);
        if (target == null || target.getUniqueId() == null) {
            sender.sendMessage(this.prefix() + ChatColor.RED + "\u672a\u627e\u5230\u8fd9\u4e2a\u73a9\u5bb6\uff0c\u8bf7\u786e\u8ba4\u540d\u5b57\u662f\u5426\u6b63\u786e\uff0c\u5e76\u4e14\u8be5\u73a9\u5bb6\u81f3\u5c11\u8fdb\u670d\u8fc7\u4e00\u6b21\u3002");
            return true;
        }

        String targetName = this.safePlayerName(target);
        sender.sendMessage(this.prefix() + ChatColor.YELLOW + "\u6b63\u5728\u67e5\u8be2\u5c45\u6c11 " + ChatColor.GOLD + targetName + ChatColor.YELLOW + " \u7684\u79df\u8d41\u4fe1\u606f...");
        this.attachRentalQuery(plugin.bridgeService().queryRentalsForResident(target.getUniqueId()), sender, entries -> {
            this.sendTargetRentals(sender, targetName, entries);
        });
        return true;
    }

    private boolean handleRentList(CommandSender sender, String[] args) {
        if (!this.ensureAdmin(sender)) {
            return true;
        }

        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException exception) {
                sender.sendMessage(this.prefix() + ChatColor.RED + "\u9875\u7801\u5fc5\u987b\u662f\u6570\u5b57\u3002");
                return true;
            }
        }

        int requestedPage = page;
        sender.sendMessage(this.prefix() + ChatColor.YELLOW + "\u6b63\u5728\u6574\u7406\u5168\u670d\u5f53\u524d\u79df\u8d41\u5217\u8868...");
        this.attachRentalQuery(plugin.bridgeService().queryAllActiveRentals(), sender, entries -> {
            this.sendPagedRentalList(sender, entries, requestedPage);
        });
        return true;
    }

    private boolean handleStatusCommand(CommandSender sender) {
        if (!this.ensureAdmin(sender)) {
            return true;
        }
        return this.handleStatus(sender);
    }

    private boolean handleStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "[BLB] " + ChatColor.YELLOW + "\u542f\u7528=" + plugin.bridgeConfig().enabled()
                + ChatColor.GRAY + "\uff0c\u9884\u6f14=" + plugin.bridgeConfig().dryRun()
                + ChatColor.GRAY + "\uff0c\u63d0\u4f9b\u5668=" + plugin.bridgeService().providerName()
                + ChatColor.GRAY + "\uff0c\u8fd0\u884c\u5c31\u7eea=" + plugin.bridgeService().isRuntimeReady()
                + ChatColor.GRAY + "\uff0c\u7ecf\u6d4e=" + plugin.bridgeService().hasEconomy()
                + ChatColor.GRAY + "\uff0c\u5feb\u7167\u4ed3\u5e93=" + plugin.bridgeService().hasSnapshotRepository()
                + ChatColor.GRAY + "\uff0c\u5904\u7406\u4e2d=" + plugin.bridgeService().inFlightCount());
        sender.sendMessage(ChatColor.GOLD + "[BLB] " + ChatColor.YELLOW
                + "\u5c1d\u8bd5\u7eed\u79df=" + plugin.bridgeService().attemptedRenewals()
                + ChatColor.GRAY + "\uff0c\u6210\u529f=" + plugin.bridgeService().successfulRenewals()
                + ChatColor.GRAY + "\uff0c\u62d2\u7edd=" + plugin.bridgeService().deniedRenewals()
                + ChatColor.GRAY + "\uff0c\u5931\u8d25=" + plugin.bridgeService().failedRenewals());

        RenewalResult lastRenewal = plugin.bridgeService().lastRenewal();
        if (lastRenewal != null) {
            sender.sendMessage(ChatColor.GOLD + "[BLB] " + ChatColor.YELLOW + lastRenewal.decision()
                    + ChatColor.GRAY + " \u9886\u5730=" + lastRenewal.landName()
                    + ChatColor.GRAY + "\uff0c\u533a\u57df=" + lastRenewal.areaName()
                    + ChatColor.GRAY + "\uff0c\u5c45\u6c11=" + lastRenewal.playerName()
                    + ChatColor.GRAY + "\uff0c\u7eed\u79df\u524d\u5269\u4f59=" + lastRenewal.remainingBeforeMinutes()
                    + ChatColor.GRAY + "\uff0c\u7eed\u79df\u540e\u5269\u4f59=" + lastRenewal.remainingAfterMinutes()
                    + ChatColor.GRAY + "\uff0c\u8d39\u7528=" + lastRenewal.cost()
                    + ChatColor.GRAY + "\uff0c\u5907\u6ce8=" + lastRenewal.note());
        }

        BridgeRunResult lastResult = plugin.bridgeService().lastResult();
        if (lastResult != null) {
            sender.sendMessage(ChatColor.GOLD + "[BLB] " + ChatColor.YELLOW + lastResult.mode()
                    + ChatColor.GRAY + "\uff0c\u5907\u6ce8=" + lastResult.note());
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!this.ensureAdmin(sender)) {
            return true;
        }
        plugin.reloadBridge();
        sender.sendMessage(this.prefix() + ChatColor.YELLOW + "\u914d\u7f6e\u5df2\u91cd\u8f7d\u3002");
        return true;
    }

    private boolean handleCalc(CommandSender sender, String label, String[] args) {
        if (!this.ensureAdmin(sender)) {
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(this.prefix() + ChatColor.RED + "\u7528\u6cd5: /" + label + " calc <baseMaxMinutes> <rentMinutes> <passedSeconds>");
            return true;
        }

        try {
            int baseMaxMinutes = Integer.parseInt(args[1]);
            int rentMinutes = Integer.parseInt(args[2]);
            long passedSeconds = Long.parseLong(args[3]);
            BridgeComputation computation = plugin.bridgeService().computeDynamicCap(baseMaxMinutes, rentMinutes, passedSeconds);
            sender.sendMessage(ChatColor.GOLD + "[BLB] " + ChatColor.YELLOW
                    + "\u57fa\u7840\u4e0a\u9650=" + computation.baseMaxMinutes()
                    + ChatColor.GRAY + "\uff0c\u5355\u6b21\u79df\u671f=" + computation.rentMinutes()
                    + ChatColor.GRAY + "\uff0c\u5df2\u6d41\u901d\u5206\u949f=" + computation.passedMinutes()
                    + ChatColor.GRAY + "\uff0c\u5b8c\u6574\u5468\u671f\u6570=" + computation.completedPeriods()
                    + ChatColor.GRAY + "\uff0c\u52a8\u6001\u4e0a\u9650=" + computation.dynamicMaxMinutes());
        } catch (NumberFormatException exception) {
            sender.sendMessage(this.prefix() + ChatColor.RED + "\u53c2\u6570\u5fc5\u987b\u662f\u6570\u5b57\u3002");
        }
        return true;
    }

    private boolean handleRunOrRestore(CommandSender sender, String[] args) {
        if (!this.ensureAdmin(sender)) {
            return true;
        }
        BridgeRunResult result = args[0].equalsIgnoreCase("runonce")
                ? plugin.bridgeService().runCycle()
                : plugin.bridgeService().restore(args.length >= 2 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null);
        sender.sendMessage(ChatColor.GOLD + "[BLB] " + ChatColor.YELLOW + result.note());
        return true;
    }

    private boolean ensureAdmin(CommandSender sender) {
        if (sender.hasPermission("bonfire.landsbridge.admin")) {
            return true;
        }
        sender.sendMessage(this.prefix() + ChatColor.RED + "\u4f60\u6ca1\u6709 bonfirelandsbridge \u7ba1\u7406\u6743\u9650\u3002");
        return false;
    }

    private void attachRentalQuery(CompletableFuture<List<RentalQueryEntry>> future, CommandSender sender, java.util.function.Consumer<List<RentalQueryEntry>> successConsumer) {
        future.whenComplete((entries, throwable) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!this.canSend(sender)) {
                return;
            }
            if (throwable != null) {
                plugin.getLogger().warning("Rental query failed: " + throwable.getMessage());
                sender.sendMessage(this.prefix() + ChatColor.RED + "\u67e5\u8be2\u79df\u8d41\u4fe1\u606f\u65f6\u53d1\u751f\u5f02\u5e38\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
                return;
            }
            successConsumer.accept(entries == null ? List.of() : entries);
        }));
    }

    private void sendTargetRentals(CommandSender sender, String targetName, List<RentalQueryEntry> entries) {
        this.sendTargetRentals(sender, targetName, entries, true, true);
    }

    private void sendTargetRentals(CommandSender sender, String targetName, List<RentalQueryEntry> entries, boolean detail, boolean adminView) {
        if (entries.isEmpty()) {
            sender.sendMessage(this.prefix() + ChatColor.YELLOW + "\u5c45\u6c11 " + ChatColor.GOLD + targetName + ChatColor.YELLOW + " \u5f53\u524d\u6ca1\u6709\u6b63\u5728\u79df\u8d41\u7684\u623f\u5c4b\u3002");
            return;
        }

        sender.sendMessage(this.prefix() + ChatColor.YELLOW + "\u5c45\u6c11 " + ChatColor.GOLD + targetName + ChatColor.YELLOW + " \u5f53\u524d\u5171\u79df\u8d41 " + ChatColor.GOLD + entries.size() + ChatColor.YELLOW + " \u5957\u623f\u5c4b\u3002");
        int index = 1;
        for (RentalQueryEntry entry : entries) {
            sender.sendMessage(this.prefix() + ChatColor.GOLD + index + ". " + ChatColor.YELLOW + "\u623f\u5c4b: " + ChatColor.GOLD + entry.landName() + ChatColor.GRAY + "/" + ChatColor.GOLD + entry.areaName());
            sender.sendMessage(this.prefix() + ChatColor.GRAY + "   \u5269\u4f59\u65f6\u95f4: " + ChatColor.YELLOW + this.renderRemaining(entry));
            if (detail) {
                sender.sendMessage(this.prefix() + ChatColor.GRAY + "   \u5355\u6b21\u79df\u671f: " + ChatColor.YELLOW + plugin.bridgeService().formatDurationForDisplay(entry.rentMinutes()));
                sender.sendMessage(this.prefix() + ChatColor.GRAY + "   \u5f53\u524d\u7d2f\u79ef\u79df\u671f: " + ChatColor.YELLOW + plugin.bridgeService().formatDurationForDisplay(entry.rentedMinutes()));
                String capLabel = entry.baseMaxKnown() ? "\u57fa\u7840\u4e0a\u9650" : "\u5f53\u524d\u4e0a\u9650";
                sender.sendMessage(this.prefix() + ChatColor.GRAY + "   " + capLabel + ": " + ChatColor.YELLOW + plugin.bridgeService().formatDurationForDisplay(entry.baseMaxMinutes()));
                if (adminView) {
                    sender.sendMessage(this.prefix() + ChatColor.GRAY + "   \u79df\u6237UUID: " + ChatColor.YELLOW + entry.tenantId());
                }
                if (!entry.snapshotAvailable()) {
                    sender.sendMessage(this.prefix() + ChatColor.GRAY + "   \u5feb\u7167\u72b6\u6001: " + ChatColor.RED + "\u4e0d\u53ef\u7528\uff0c\u5269\u4f59\u65f6\u95f4\u6682\u65f6\u65e0\u6cd5\u7cbe\u786e\u8ba1\u7b97");
                }
            }
            index++;
        }
    }

    private void sendPagedRentalList(CommandSender sender, List<RentalQueryEntry> entries, int requestedPage) {
        if (entries.isEmpty()) {
            sender.sendMessage(this.prefix() + ChatColor.YELLOW + "\u5f53\u524d\u5168\u670d\u6ca1\u6709\u6b63\u5728\u79df\u8d41\u7684\u623f\u5c4b\u3002");
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) RENT_LIST_PAGE_SIZE));
        int page = Math.min(Math.max(1, requestedPage), totalPages);
        int startIndex = (page - 1) * RENT_LIST_PAGE_SIZE;
        int endIndex = Math.min(entries.size(), startIndex + RENT_LIST_PAGE_SIZE);

        sender.sendMessage(this.prefix() + ChatColor.YELLOW + "\u5168\u670d\u5f53\u524d\u5df2\u79df\u623f\u5c4b " + ChatColor.GOLD + entries.size() + ChatColor.YELLOW + " \u5957\uff0c\u7b2c " + ChatColor.GOLD + page + ChatColor.YELLOW + "/" + ChatColor.GOLD + totalPages + ChatColor.YELLOW + " \u9875\u3002");
        for (int index = startIndex; index < endIndex; index++) {
            RentalQueryEntry entry = entries.get(index);
            sender.sendMessage(this.prefix()
                    + ChatColor.GOLD + (index + 1) + ". "
                    + ChatColor.YELLOW + entry.tenantName()
                    + ChatColor.GRAY + " | "
                    + ChatColor.GOLD + entry.landName() + ChatColor.GRAY + "/" + ChatColor.GOLD + entry.areaName()
                    + ChatColor.GRAY + " | "
                    + ChatColor.YELLOW + this.renderRemaining(entry));
        }
        if (totalPages > 1) {
            sender.sendMessage(this.prefix() + ChatColor.GRAY + "\u4f7f\u7528 /blb rentlist <\u9875\u7801> \u67e5\u770b\u5176\u4ed6\u9875\u3002");
        }
    }

    private String renderRemaining(RentalQueryEntry entry) {
        if (!entry.preciseRemaining()) {
            return "\u65e0\u6cd5\u7cbe\u786e\u8ba1\u7b97";
        }
        return plugin.bridgeService().formatDurationForDisplay(entry.remainingMinutes());
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(this.prefix() + ChatColor.YELLOW + "bonfirelandsbridge \u6307\u4ee4\u5217\u8868:");
        if (sender.hasPermission("bonfire.landsbridge.query")) {
            sender.sendMessage(this.prefix() + ChatColor.GRAY + "/" + label + " myrent" + ChatColor.YELLOW + " - \u67e5\u8be2\u81ea\u5df1\u5f53\u524d\u79df\u8d41\u7684\u623f\u5c4b");
            sender.sendMessage(this.prefix() + ChatColor.GRAY + "/" + label + " myrent detail" + ChatColor.YELLOW + " - \u67e5\u770b\u81ea\u5df1\u623f\u5c4b\u7684\u8be6\u7ec6\u79df\u8d41\u4fe1\u606f");
        }
        if (sender.hasPermission("bonfire.landsbridge.admin")) {
            sender.sendMessage(this.prefix() + ChatColor.GRAY + "/" + label + " rentinfo <\u73a9\u5bb6\u540d>" + ChatColor.YELLOW + " - \u67e5\u8be2\u6307\u5b9a\u73a9\u5bb6\u7684\u623f\u5c4b\u4fe1\u606f");
            sender.sendMessage(this.prefix() + ChatColor.GRAY + "/" + label + " rentlist [\u9875\u7801]" + ChatColor.YELLOW + " - \u5206\u9875\u67e5\u770b\u5168\u670d\u79df\u623f\u5217\u8868");
            sender.sendMessage(this.prefix() + ChatColor.GRAY + "/" + label + " status" + ChatColor.YELLOW + " - \u67e5\u770b\u63d2\u4ef6\u8fd0\u884c\u72b6\u6001");
            sender.sendMessage(this.prefix() + ChatColor.GRAY + "/" + label + " reload" + ChatColor.YELLOW + " - \u91cd\u8f7d\u63d2\u4ef6\u914d\u7f6e");
            sender.sendMessage(this.prefix() + ChatColor.GRAY + "/" + label + " calc <baseMax> <rent> <passedSeconds>" + ChatColor.YELLOW + " - \u52a8\u6001\u4e0a\u9650\u8ba1\u7b97\u5668");
        }
    }

    private boolean canSend(CommandSender sender) {
        return !(sender instanceof Player player) || player.isOnline();
    }

    private String safePlayerName(OfflinePlayer player) {
        String name = player.getName();
        return name == null || name.isBlank() ? player.getUniqueId().toString() : name;
    }

    private OfflinePlayer resolveKnownPlayer(String targetName) {
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

    private String prefix() {
        return ChatColor.translateAlternateColorCodes('&', plugin.bridgeConfig().runtimeSettings().messagePrefix());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("bonfire.landsbridge.query")) {
                suggestions.add("myrent");
                suggestions.add("help");
            }
            if (sender.hasPermission("bonfire.landsbridge.admin")) {
                suggestions.add("rentinfo");
                suggestions.add("rentlist");
                suggestions.add("status");
                suggestions.add("reload");
                suggestions.add("calc");
                suggestions.add("runonce");
                suggestions.add("restore");
            }
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("myrent") && sender.hasPermission("bonfire.landsbridge.query")) {
                suggestions.add("detail");
            } else if (args[0].equalsIgnoreCase("restore") && sender.hasPermission("bonfire.landsbridge.admin")) {
                suggestions.add("all");
            } else if (args[0].equalsIgnoreCase("rentinfo") && sender.hasPermission("bonfire.landsbridge.admin")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    suggestions.add(player.getName());
                }
            } else if (args[0].equalsIgnoreCase("rentlist") && sender.hasPermission("bonfire.landsbridge.admin")) {
                suggestions.add("1");
                suggestions.add("2");
            }
        }
        return suggestions;
    }
}
