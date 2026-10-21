package org.Cry_Legende.sHDataPlaceholders;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {
    private final SHDataPlaceholders plugin;
    private FileConfiguration config;

    public ConfigManager(SHDataPlaceholders plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    public void reload() {
        this.config = plugin.getConfig();
    }

    public String getMessage(String path) {
        String msg = config.getString("messages." + path);
        if (msg == null) return path;
        String prefix = config.getString("messages.prefix", "");
        return ChatColor.translateAlternateColorCodes('&', prefix + msg);
    }
}
