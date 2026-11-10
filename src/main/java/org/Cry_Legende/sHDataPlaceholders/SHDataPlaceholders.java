package org.Cry_Legende.sHDataPlaceholders;

import org.Cry_Legende.sHDataPlaceholders.network.NetworkData;
import org.Cry_Legende.sHDataPlaceholders.network.NetworkLink;
import org.Cry_Legende.sHDataPlaceholders.network.ShNetworkLink;
import org.Cry_Legende.sHDataPlaceholders.network.ValuesDatabase;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;

public class SHDataPlaceholders extends JavaPlugin {

    private static SHDataPlaceholders instance;
    private DataManager dataManager;
    private ConfigManager configManager;
    private YamlConfiguration networkMessages;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.dataManager = new DataManager(this);
        loadNetworkMessages();
        enableNetwork();

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

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont chargées qu'ici : sans lui, le plugin se comporte exactement comme avant.
     */
    private void enableNetwork() {
        if (!getConfig().getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        ValuesDatabase db = null;
        try {
            NetworkLink link = ShNetworkLink.open();
            db = new ValuesDatabase(
                getConfig().getString("reseau.base-de-donnees.url"),
                getConfig().getString("reseau.base-de-donnees.utilisateur"),
                getConfig().getString("reseau.base-de-donnees.mot-de-passe"),
                Math.max(250, getConfig().getInt("reseau.base-de-donnees.delai-ms", 3000)));
            db.createTable();
            Duration wait = Duration.ofSeconds(Math.max(1, getConfig().getInt("reseau.attente-max-secondes", 10)));
            NetworkData net = new NetworkData(this, dataManager.getCache(), dataManager::getDefaultValue, db, link, wait,
                getConfig().getString("reseau.canal", "shdataplaceholders"),
                getConfig().getString("reseau.table-comptes", "Global.sh_track_firstseen"));
            net.start();
            dataManager.enableNetwork(net);
            getLogger().info("SHNetwork détecté : les valeurs des joueurs sont partagées entre les serveurs (base partagée).");
        } catch (Exception | LinkageError e) {
            if (db != null) db.close();
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, valeurs locales à ce serveur : " + e);
        }
    }

    /** messages.yml, avec les valeurs du jar pour toute clé absente du fichier du serveur. */
    private void loadNetworkMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        networkMessages = YamlConfiguration.loadConfiguration(file);
        try (InputStreamReader defaults = new InputStreamReader(Objects.requireNonNull(getResource("messages.yml")), StandardCharsets.UTF_8)) {
            networkMessages.setDefaults(YamlConfiguration.loadConfiguration(defaults));
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "messages.yml de référence illisible : " + e.getMessage());
        }
    }

    /** Texte de messages.yml (mode réseau), couleurs appliquées. */
    public String getNetworkMessage(String key) {
        return ChatColor.translateAlternateColorCodes('&', networkMessages.getString(key, key));
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
