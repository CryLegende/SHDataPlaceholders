package org.Cry_Legende.sHDataPlaceholders;

import org.Cry_Legende.sHDataPlaceholders.network.NetworkData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiConsumer;

/** /shdataplaceholders add|take|set|reload|import|info */
public class MainCommand implements TabExecutor {

    private static final String ADMIN = "shdataplaceholders.admin";
    private static final List<String> SUBCOMMANDS = List.of("add", "take", "set", "reload", "import", "info");

    private final SHDataPlaceholders plugin;

    public MainCommand(SHDataPlaceholders plugin) {
        this.plugin = plugin;
    }

    /** « remove » est l'ancien nom de « take » : même sous-commande, même permission. */
    private static String canonical(String sub) {
        return sub.equals("remove") ? "take" : sub;
    }

    private static boolean can(CommandSender sender, String sub) {
        return sender.hasPermission(ADMIN + "." + sub) || sender.hasPermission(ADMIN);
    }

    private static List<String> allowed(CommandSender sender) {
        return SUBCOMMANDS.stream().filter(sub -> can(sender, sub)).toList();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Messages messages = plugin.getMessages();
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            messages.send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : canonical(args[0].toLowerCase(Locale.ROOT));
        if (!allowed.contains(sub)) {
            if (SUBCOMMANDS.contains(sub)) {
                messages.send(sender, "no-permission");
            } else {
                messages.send(sender, "usage", "%subcommands%", String.join("|", allowed));
            }
            return true;
        }
        switch (sub) {
            case "reload" -> reload(sender);
            case "import" -> importData(sender);
            case "info" -> info(sender, args);
            default -> modify(sender, sub, args);
        }
        return true;
    }

    private void reload(CommandSender sender) {
        Messages messages = plugin.getMessages();
        List<String> errors = plugin.reloadAll();
        if (!errors.isEmpty()) {
            for (String error : errors) {
                int split = error.indexOf('\n');
                messages.send(sender, "reload-error", "%file%", error.substring(0, split), "%error%", error.substring(split + 1));
            }
            return;
        }
        messages.send(sender, "reload-ok");
        messages.send(sender, "reload-network");
    }

    private void modify(CommandSender sender, String sub, String[] args) {
        Messages messages = plugin.getMessages();
        if (args.length < 4) {
            messages.send(sender, "usage-modify");
            return;
        }
        String key = args[1].toLowerCase(Locale.ROOT); // On force la minuscule ici aussi
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            messages.send(sender, "invalid-number");
            return;
        }
        DataManager.Operation op = switch (sub) {
            case "add" -> DataManager.Operation.ADD;
            case "take" -> DataManager.Operation.TAKE;
            default -> DataManager.Operation.SET;
        };

        // Exécution async pour ne pas freeze le serveur en cherchant l'UUID
        resolveTarget(sender, args[3], (uuid, displayName) ->
            plugin.getDataManager().modifyStat(uuid, key, amount, op).thenAccept(newValue -> {
                String msgKey = switch (op) {
                    case ADD -> "success-add";
                    case TAKE -> "success-take";
                    case SET -> "success-set";
                };
                messages.send(sender, msgKey, "%key%", key, "%player%", displayName,
                    "%amount%", String.valueOf(amount), "%value%", String.valueOf(newValue));
            }).exceptionally(error -> {
                if (plugin.getDataManager().getNetwork() != null) {
                    messages.send(sender, "reseau-erreur", "%erreur%", String.valueOf(error.getMessage()));
                }
                return null;
            }));
    }

    /** /shdataplaceholders info <joueur> [clé] : lecture seule, une valeur ou toutes. */
    private void info(CommandSender sender, String[] args) {
        Messages messages = plugin.getMessages();
        if (args.length < 2 || args.length > 3) {
            messages.send(sender, "usage-info");
            return;
        }
        String key = args.length == 3 ? args[2].toLowerCase(Locale.ROOT) : null;
        resolveTarget(sender, args[1], (uuid, displayName) ->
            plugin.getDataManager().readStats(uuid).thenAccept(values -> {
                int defaultValue = plugin.getDataManager().getDefaultValue();
                if (key != null) {
                    Integer value = values.get(key);
                    if (value == null) {
                        messages.send(sender, "info-default", "%key%", key, "%player%", displayName, "%value%", String.valueOf(defaultValue));
                    } else {
                        messages.send(sender, "info-value", "%key%", key, "%player%", displayName, "%value%", String.valueOf(value));
                    }
                    return;
                }
                if (values.isEmpty()) {
                    messages.send(sender, "info-empty", "%player%", displayName);
                    return;
                }
                messages.send(sender, "info-header", "%player%", displayName, "%count%", String.valueOf(values.size()));
                values.forEach((name, value) -> messages.send(sender, "info-entry", "%key%", name, "%value%", String.valueOf(value)));
            }).exceptionally(error -> {
                messages.send(sender, "reseau-erreur", "%erreur%", String.valueOf(error.getMessage()));
                return null;
            }));
    }

    /** Joueur visé, retrouvé hors du fil principal ; « player-not-found » s'il est inconnu. */
    private void resolveTarget(CommandSender sender, String targetName, BiConsumer<UUID, String> action) {
        Messages messages = plugin.getMessages();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            NetworkData network = plugin.getDataManager().getNetwork();
            UUID uuid;
            String displayName;
            if (network != null) {
                // Avec SHNetwork : joueur de ce serveur, d'un autre serveur ou hors ligne, retrouvé sans recherche Mojang
                Optional<NetworkData.Resolved> resolved = network.resolve(targetName);
                if (resolved.isEmpty()) {
                    messages.send(sender, "player-not-found");
                    return;
                }
                uuid = resolved.get().uuid();
                displayName = resolved.get().name();
            } else {
                @SuppressWarnings("deprecation")
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

                if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
                    messages.send(sender, "player-not-found");
                    return;
                }

                uuid = target.getUniqueId();
                displayName = target.getName() != null ? target.getName() : targetName;
            }
            action.accept(uuid, displayName);
        });
    }

    /** /shdataplaceholders import : reprend les fichiers userdata dans la base partagée (une fois, sans écraser ce qui y est déjà). */
    private void importData(CommandSender sender) {
        Messages messages = plugin.getMessages();
        NetworkData network = plugin.getDataManager().getNetwork();
        if (network == null) {
            messages.send(sender, "reseau-inactif");
            return;
        }
        network.importFolder(new File(plugin.getDataFolder(), "userdata"), (counts, error) -> {
            if (error != null) {
                messages.send(sender, "reseau-import-erreur", "%erreur%", error);
            } else {
                messages.send(sender, "reseau-import", "%joueurs%", String.valueOf(counts[0]),
                    "%ajoutees%", String.valueOf(counts[1]), "%deja%", String.valueOf(counts[2]));
            }
        });
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> allowed = allowed(sender);
        List<String> suggestions = new ArrayList<>();
        String sub = args.length == 0 ? "" : canonical(args[0].toLowerCase(Locale.ROOT));
        if (args.length == 1) {
            suggestions.addAll(allowed);
        } else if (allowed.contains(sub)) {
            boolean modify = sub.equals("add") || sub.equals("take") || sub.equals("set");
            if (modify && args.length == 2 || sub.equals("info") && args.length == 3) {
                TreeSet<String> keys = new TreeSet<>();
                plugin.getDataManager().getCache().values().forEach(values -> keys.addAll(values.keySet()));
                suggestions.addAll(keys);
            } else if (modify && args.length == 4 || sub.equals("info") && args.length == 2) {
                Bukkit.getOnlinePlayers().forEach(player -> suggestions.add(player.getName()));
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        return suggestions.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }
}
