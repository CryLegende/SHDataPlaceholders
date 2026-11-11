package org.Cry_Legende.sHDataPlaceholders;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;

/** Textes de messages.yml, avec les valeurs du jar pour toute clé absente du fichier du serveur. */
final class Messages {

    private final SHDataPlaceholders plugin;
    private final YamlConfiguration bundled = new YamlConfiguration();
    private volatile YamlConfiguration current;

    Messages(SHDataPlaceholders plugin) {
        this.plugin = plugin;
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                bundled.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.WARNING, "messages.yml du jar illisible : " + e.getMessage());
        }
        this.current = bundled;
    }

    /**
     * Lit messages.yml sans rien appliquer. Au premier démarrage le fichier est créé à partir du jar et reprend les
     * textes que config.yml contenait dans sa section messages (préfixe compris).
     *
     * @param settings config.yml lu, ou null s'il est illisible
     */
    YamlConfiguration read(YamlConfiguration settings) throws IOException, InvalidConfigurationException {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        boolean fresh = !file.exists();
        if (fresh) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        if (fresh && settings != null) {
            migrate(settings, yaml);
            yaml.save(file);
        }
        yaml.setDefaults(bundled);
        return yaml;
    }

    private static void migrate(YamlConfiguration settings, YamlConfiguration yaml) {
        String prefix = settings.getString("messages.prefix", "");
        for (String key : List.of("no-permission", "player-not-found", "invalid-number", "success-add", "success-take", "success-set")) {
            String text = settings.getString("messages." + key);
            if (text != null) {
                yaml.set(key, prefix + text);
            }
        }
        String reload = settings.getString("messages.reload");
        if (reload != null) {
            yaml.set("reload-ok", prefix + reload);
        }
    }

    void use(YamlConfiguration messages) {
        this.current = messages;
    }

    /** Texte avec couleurs et variables remplacées ; vide si le message est désactivé. */
    String get(String key, String... placeholders) {
        String text = current.getString(key);
        if (text == null) {
            return key;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace(placeholders[i], placeholders[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    void send(CommandSender to, String key, String... placeholders) {
        String text = get(key, placeholders);
        if (!text.isEmpty()) {
            to.sendMessage(text);
        }
    }
}
