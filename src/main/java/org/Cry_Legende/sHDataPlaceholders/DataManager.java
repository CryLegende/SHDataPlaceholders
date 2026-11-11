package org.Cry_Legende.sHDataPlaceholders;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import org.Cry_Legende.sHDataPlaceholders.network.NetworkData;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DataManager {

    private final SHDataPlaceholders plugin;
    private final File dataFolder;
    private final Gson gson;

    // Cache RAM : UUID -> (NomStat -> Valeur)
    private final Map<UUID, Map<String, Integer>> cache = new ConcurrentHashMap<>();

    // Toutes les lectures, modifications et écritures de fichiers passent par ce thread unique :
    // pas de mise à jour perdue entre deux modifications, ni d'écritures croisées sur un même fichier.
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SHDataPlaceholders-io");
        thread.setDaemon(true);
        return thread;
    });
    // Joueurs connectés (tenu par le thread principal) : une lecture lancée à la connexion ne remplit pas
    // le cache d'un joueur déjà reparti, dont l'entrée ne serait jamais retirée
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    // Joueurs en cache dont le dernier enregistrement a échoué (modifyStat écrit déjà chaque modification)
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private volatile int defaultValue;
    // Non nul seulement avec SHNetwork : les valeurs vivent alors dans la base partagée, le cache ne sert qu'aux lectures
    private volatile NetworkData network;

    public DataManager(SHDataPlaceholders plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "userdata");
        if (!dataFolder.exists()) dataFolder.mkdirs();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        reload();
    }

    /** Passe en mode réseau : valeurs en base partagée au lieu des fichiers userdata. */
    public void enableNetwork(NetworkData network) {
        this.network = network;
    }

    /** Mode réseau actif, ou null sur un serveur seul. */
    public NetworkData getNetwork() {
        return network;
    }

    /** Cache des valeurs des joueurs connectés (UUID -> clé -> valeur). */
    public Map<UUID, Map<String, Integer>> getCache() {
        return cache;
    }

    public int getDefaultValue() {
        return defaultValue;
    }

    /** Relit la valeur par défaut en config (appelé au chargement et au /shdata reload). */
    public void reload() {
        this.defaultValue = plugin.getConfig().getInt("default-value");
    }

    public int getStat(UUID uuid, String key) {
        // Toujours utiliser lowercase pour éviter les erreurs de majuscules
        String safeKey = key.toLowerCase();
        Map<String, Integer> data = cache.get(uuid);
        if (data == null) {
            return defaultValue;
        }
        return data.getOrDefault(safeKey, defaultValue);
    }

    public void loadPlayer(UUID uuid) {
        if (network != null) {
            network.beginLoad(uuid);
            return;
        }
        online.add(uuid);
        io.execute(() -> {
            if (!online.contains(uuid)) return; // reparti avant que la lecture ne passe
            Map<String, Integer> data = loadDataFromFile(uuid);
            cache.put(uuid, new ConcurrentHashMap<>(data));
        });
    }

    public void unloadPlayer(UUID uuid) {
        if (network != null) {
            network.unload(uuid);
            return;
        }
        online.remove(uuid);
        io.execute(() -> {
            Map<String, Integer> data = cache.remove(uuid);
            if (data != null && dirty.remove(uuid)) {
                saveToFile(uuid, data);
            }
        });
    }

    /** Réenregistre les joueurs dont l'écriture a échoué (les autres sont déjà à jour sur le disque). */
    public void saveAll() {
        if (network != null) return; // les valeurs sont écrites en base à chaque modification
        io.execute(this::saveDirty);
    }

    /** Arrêt du plugin : termine les opérations en cours puis réenregistre ce qui reste à écrire. */
    public void saveAllSync() {
        if (network != null) {
            network.stop();
            return;
        }
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Les écritures en cours n'ont pas fini en 10 s.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        saveDirty();
    }

    private void saveDirty() {
        for (UUID uuid : new ArrayList<>(dirty)) {
            Map<String, Integer> data = cache.get(uuid);
            if (data == null || saveToFile(uuid, data)) {
                dirty.remove(uuid);
            }
        }
    }

    /** Toutes les valeurs d'un joueur (lecture seule), triées par clé : en mémoire s'il est connecté ici, sinon lues. */
    public CompletableFuture<Map<String, Integer>> readStats(UUID uuid) {
        if (network != null) {
            return network.readAll(uuid);
        }
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Integer> data = cache.get(uuid);
            return new TreeMap<>(data != null ? data : loadDataFromFile(uuid));
        }, io);
    }

    public CompletableFuture<Integer> modifyStat(UUID uuid, String key, int amount, Operation op) {
        String safeKey = key.toLowerCase(); // Conversion auto en minuscule

        if (network != null) {
            return network.modify(uuid, safeKey, amount, op);
        }

        return CompletableFuture.supplyAsync(() -> {
            // En ligne : on prend l'objet DIRECTEMENT dans la mémoire (PAPI le voit tout de suite),
            // sinon on charge le fichier temporairement
            Map<String, Integer> data = cache.get(uuid);
            boolean cached = data != null;
            if (!cached) {
                data = loadDataFromFile(uuid);
            }

            int current = data.getOrDefault(safeKey, defaultValue);
            long result = switch (op) {
                case ADD -> (long) current + amount;
                case TAKE -> (long) current - amount;
                case SET -> amount;
            };
            // Pas de dépassement silencieux vers un nombre négatif
            int newValue = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, result));

            data.put(safeKey, newValue);

            // Sauvegarde disque (toujours, par sécurité)
            if (!saveToFile(uuid, data) && cached) {
                dirty.add(uuid);
            }

            return newValue;
        }, io);
    }

    private Map<String, Integer> loadDataFromFile(UUID uuid) {
        File file = new File(dataFolder, uuid.toString() + ".json");
        if (!file.exists()) return new HashMap<>();
        try (Reader reader = new FileReader(file)) {
            Type type = new TypeToken<HashMap<String, Integer>>() {
            }.getType();
            Map<String, Integer> data = gson.fromJson(reader, type);
            return (data != null) ? data : new HashMap<>();
        } catch (IOException | JsonParseException e) {
            plugin.getLogger().severe("Lecture impossible de " + file.getName() + " : " + e.getMessage());
            // La prochaine écriture remplacera le fichier : on garde une copie de celui qui est illisible
            try {
                Files.copy(file.toPath(), new File(dataFolder, uuid + ".json.corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            return new HashMap<>();
        }
    }

    private boolean saveToFile(UUID uuid, Map<String, Integer> data) {
        File file = new File(dataFolder, uuid.toString() + ".json");
        // Fichier temporaire puis remplacement : un arrêt en plein milieu ne tronque pas le fichier du joueur
        File tmp = new File(dataFolder, uuid + ".json.tmp");
        try (Writer writer = new FileWriter(tmp)) {
            gson.toJson(data, writer);
        } catch (IOException e) {
            plugin.getLogger().severe("Écriture impossible de " + file.getName() + " : " + e.getMessage());
            return false;
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Remplacement impossible de " + file.getName() + " : " + e.getMessage());
            return false;
        }
    }

    public enum Operation {ADD, TAKE, SET}
}
