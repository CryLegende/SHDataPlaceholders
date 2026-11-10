package org.Cry_Legende.sHDataPlaceholders;

import org.Cry_Legende.sHDataPlaceholders.network.NetworkData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Optional;
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
            plugin.getDataManager().reload();
            sender.sendMessage(plugin.getConfigManager().getMessage("reload"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("import")) {
            importData(sender);
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
            NetworkData network = plugin.getDataManager().getNetwork();
            UUID uuid;
            String displayName;
            if (network != null) {
                // Avec SHNetwork : joueur de ce serveur, d'un autre serveur ou hors ligne, retrouvé sans recherche Mojang
                Optional<NetworkData.Resolved> resolved = network.resolve(targetName);
                if (resolved.isEmpty()) {
                    sender.sendMessage(plugin.getConfigManager().getMessage("player-not-found"));
                    return;
                }
                uuid = resolved.get().uuid();
                displayName = resolved.get().name();
            } else {
                @SuppressWarnings("deprecation")
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

                if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
                    sender.sendMessage(plugin.getConfigManager().getMessage("player-not-found"));
                    return;
                }

                uuid = target.getUniqueId();
                displayName = target.getName() != null ? target.getName() : targetName;
            }
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
                    .replace("%player%", displayName)
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%value%", String.valueOf(newValue));
                sender.sendMessage(msg);
            }).exceptionally(error -> {
                if (network != null) {
                    sender.sendMessage(plugin.getNetworkMessage("reseau-erreur").replace("%erreur%", String.valueOf(error.getMessage())));
                }
                return null;
            });
        });

        return true;
    }

    /** /shdata import : reprend les fichiers userdata dans la base partagée (une fois, sans écraser ce qui y est déjà). */
    private void importData(CommandSender sender) {
        NetworkData network = plugin.getDataManager().getNetwork();
        if (network == null) {
            sender.sendMessage(plugin.getNetworkMessage("reseau-inactif"));
            return;
        }
        network.importFolder(new File(plugin.getDataFolder(), "userdata"), (counts, error) -> {
            if (error != null) {
                sender.sendMessage(plugin.getNetworkMessage("reseau-import-erreur").replace("%erreur%", error));
            } else {
                sender.sendMessage(plugin.getNetworkMessage("reseau-import")
                    .replace("%joueurs%", String.valueOf(counts[0]))
                    .replace("%ajoutees%", String.valueOf(counts[1]))
                    .replace("%deja%", String.valueOf(counts[2])));
            }
        });
    }
}
