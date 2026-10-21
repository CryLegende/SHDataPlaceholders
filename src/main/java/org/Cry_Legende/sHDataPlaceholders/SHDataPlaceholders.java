package org.Cry_Legende.sHDataPlaceholders;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class SHDataPlaceholders extends JavaPlugin {

    private static SHDataPlaceholders instance;
    private DataManager dataManager;
    private ConfigManager configManager;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.dataManager = new DataManager(this);

        getCommand("shdataplaceholders").setExecutor(new MainCommand(this));
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PapiExpansion(this).register();
        } else {
            getLogger().warning("PlaceholderAPI manquant !");
        }

        // Autosave async
        int interval = getConfig().getInt("autosave-interval", 300);
        if (interval > 0) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> dataManager.saveAll(), 20L * interval, 20L * interval);
        }

        // CRUCIAL: Charger les joueurs déjà en ligne (cas du /reload)
        for (Player p : Bukkit.getOnlinePlayers()) {
            dataManager.loadPlayer(p.getUniqueId());
        }

        getLogger().info("SHDataPlaceholders activé avec succès !");
    }

    @Override
    public void onDisable() {
        if (dataManager != null) {
            dataManager.saveAllSync(); // Sauvegarde finale synchrone
        }
    }

    public static SHDataPlaceholders getInstance() {
        return instance;
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
}
