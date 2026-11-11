package org.Cry_Legende.sHDataPlaceholders;

import org.Cry_Legende.sHDataPlaceholders.network.NetworkData;
import org.Cry_Legende.sHDataPlaceholders.network.NetworkLink;
import org.Cry_Legende.sHDataPlaceholders.network.ShNetworkLink;
import org.Cry_Legende.sHDataPlaceholders.network.ValuesDatabase;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class SHDataPlaceholders extends JavaPlugin {

    private static SHDataPlaceholders instance;
    private DataManager dataManager;
    private Messages messages;
    private BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        this.messages = new Messages(this);
        this.dataManager = new DataManager(this);
        loadMessages();
        enableNetwork();

        MainCommand command = new MainCommand(this);
        getCommand("shdataplaceholders").setExecutor(command);
        getCommand("shdataplaceholders").setTabCompleter(command);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PapiExpansion(this).register();
        } else {
            getLogger().warning("PlaceholderAPI manquant !");
        }

        scheduleAutosave();

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

    /** Sauvegarde automatique asynchrone ; relancée par le reload avec le nouvel intervalle (-1 ou 0 : arrêtée). */
    private void scheduleAutosave() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        int interval = getConfig().getInt("autosave-interval", 300);
        if (interval > 0) {
            autosaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> dataManager.saveAll(), 20L * interval, 20L * interval);
        }
    }

    /** Lecture de messages.yml au démarrage : chaque fichier illisible est signalé, les défauts du jar prennent le relais. */
    private void loadMessages() {
        try {
            YamlConfiguration settings = new YamlConfiguration();
            YamlConfiguration parsed = null;
            try {
                settings.load(new File(getDataFolder(), "config.yml"));
                parsed = settings;
            } catch (IOException | InvalidConfigurationException e) {
                getLogger().severe("config.yml illisible : " + e.getMessage());
            }
            messages.use(messages.read(parsed));
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("messages.yml illisible, textes par défaut utilisés : " + e.getMessage());
        }
    }

    /**
     * Relit config.yml et messages.yml. Les deux sont d'abord lus à part : si l'un est illisible, rien ne change.
     * Les données (userdata, base) ne sont pas touchées ; les clés de 'reseau' demandent un redémarrage.
     *
     * @return une entrée « fichier\nerreur » par fichier illisible, vide si tout a été rechargé
     */
    List<String> reloadAll() {
        List<String> errors = new ArrayList<>();
        YamlConfiguration newSettings = new YamlConfiguration();
        try {
            newSettings.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            newSettings = null;
            errors.add(describe("config.yml", e));
        }
        YamlConfiguration newMessages = null;
        try {
            newMessages = messages.read(newSettings);
        } catch (IOException | InvalidConfigurationException e) {
            errors.add(describe("messages.yml", e));
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        reloadConfig();
        dataManager.reload();
        messages.use(newMessages);
        scheduleAutosave();
        return errors;
    }

    private static String describe(String file, Exception e) {
        String reason = String.valueOf(e.getMessage()).strip();
        int newline = reason.indexOf('\n');
        return file + "\n" + (newline < 0 ? reason : reason.substring(0, newline));
    }

    public Messages getMessages() {
        return messages;
    }

    public static SHDataPlaceholders getInstance() {
        return instance;
    }

    public DataManager getDataManager() {
        return dataManager;
    }
}
