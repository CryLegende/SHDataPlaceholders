package org.Cry_Legende.sHDataPlaceholders;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class DataManager {

    private final SHDataPlaceholders plugin;
    private final File dataFolder;
    private final Gson gson;

    // Cache RAM : UUID -> (NomStat -> Valeur)
    private final Map<UUID, Map<String, Integer>> cache = new ConcurrentHashMap<>();

    public DataManager(SHDataPlaceholders plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "userdata");
        if (!dataFolder.exists()) dataFolder.mkdirs();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public int getStat(UUID uuid, String key) {
        // Toujours utiliser lowercase pour éviter les erreurs de majuscules
        String safeKey = key.toLowerCase();
        if (cache.containsKey(uuid)) {
            return cache.get(uuid).getOrDefault(safeKey, plugin.getConfig().getInt("default-value"));
        }
        return plugin.getConfig().getInt("default-value");
    }

    public void loadPlayer(UUID uuid) {
        CompletableFuture.runAsync(() -> {
            Map<String, Integer> data = loadDataFromFile(uuid);
            cache.put(uuid, new ConcurrentHashMap<>(data));
        });
    }

    public void unloadPlayer(UUID uuid) {
        if (cache.containsKey(uuid)) {
            Map<String, Integer> data = cache.get(uuid);
            CompletableFuture.runAsync(() -> saveToFile(uuid, data));
            cache.remove(uuid);
        }
    }

    public void saveAll() {
        cache.forEach(this::saveToFile);
    }

    public void saveAllSync() {
        cache.forEach(this::saveToFile);
    }

    public CompletableFuture<Integer> modifyStat(UUID uuid, String key, int amount, Operation op) {
        String safeKey = key.toLowerCase(); // Conversion auto en minuscule

        return CompletableFuture.supplyAsync(() -> {
            boolean isOnline = cache.containsKey(uuid);
            Map<String, Integer> data;

            if (isOnline) {
                // Si en ligne, on prend l'objet DIRECTEMENT dans la mémoire
                data = cache.get(uuid);
            } else {
                // Sinon on charge le fichier temporairement
                data = loadDataFromFile(uuid);
            }

            int current = data.getOrDefault(safeKey, plugin.getConfig().getInt("default-value"));
            int newValue = current;

            switch (op) {
                case ADD:
                    newValue = current + amount;
                    break;
                case TAKE:
                    newValue = current - amount;
                    break;
                case SET:
                    newValue = amount;
                    break;
            }

            // Mise à jour de la map (Si en ligne, PAPI le voit tout de suite ici)
            data.put(safeKey, newValue);

            // Sauvegarde disque (toujours, par sécurité)
            saveToFile(uuid, data);

            return newValue;
        });
    }

    private Map<String, Integer> loadDataFromFile(UUID uuid) {
        File file = new File(dataFolder, uuid.toString() + ".json");
        if (!file.exists()) return new HashMap<>();
        try (Reader reader = new FileReader(file)) {
            Type type = new TypeToken<HashMap<String, Integer>>() {
            }.getType();
            Map<String, Integer> data = gson.fromJson(reader, type);
            return (data != null) ? data : new HashMap<>();
        } catch (IOException e) {
            e.printStackTrace();
            return new HashMap<>();
        }
    }

    private void saveToFile(UUID uuid, Map<String, Integer> data) {
        File file = new File(dataFolder, uuid.toString() + ".json");
        try (Writer writer = new FileWriter(file)) {
            gson.toJson(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public enum Operation {ADD, TAKE, SET}
}
