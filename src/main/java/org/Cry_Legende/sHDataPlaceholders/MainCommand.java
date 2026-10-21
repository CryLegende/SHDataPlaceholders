package org.Cry_Legende.sHDataPlaceholders;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class MainCommand implements CommandExecutor {

    private final SHDataPlaceholders plugin;

    public MainCommand(SHDataPlaceholders plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("shdata.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.getConfigManager().reload();
            sender.sendMessage(plugin.getConfigManager().getMessage("reload"));
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage(plugin.getConfigManager().getMessage("usage"));
            return true;
        }

        String action = args[0].toLowerCase();
        String key = args[1].toLowerCase(); // On force la minuscule ici aussi
        int amount;

        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessage("invalid-number"));
            return true;
        }

        String targetName = args[3];

        // Exécution async pour ne pas freeze le serveur en cherchant l'UUID
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            @SuppressWarnings("deprecation")
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

            if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
                sender.sendMessage(plugin.getConfigManager().getMessage("player-not-found"));
                return;
            }

            UUID uuid = target.getUniqueId();
            DataManager.Operation op;

            if (action.equals("add")) op = DataManager.Operation.ADD;
            else if (action.equals("take") || action.equals("remove")) op = DataManager.Operation.TAKE;
            else if (action.equals("set")) op = DataManager.Operation.SET;
            else {
                sender.sendMessage(plugin.getConfigManager().getMessage("usage"));
                return;
            }

            plugin.getDataManager().modifyStat(uuid, key, amount, op).thenAccept(newValue -> {
                String msgKey = "";
                switch (op) {
                    case ADD:
                        msgKey = "success-add";
                        break;
                    case TAKE:
                        msgKey = "success-take";
                        break;
                    case SET:
                        msgKey = "success-set";
                        break;
                }
                String msg = plugin.getConfigManager().getMessage(msgKey)
                    .replace("%key%", key)
                    .replace("%player%", target.getName() != null ? target.getName() : targetName)
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%value%", String.valueOf(newValue));
                sender.sendMessage(msg);
            });
        });

        return true;
    }
}
