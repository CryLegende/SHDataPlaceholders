package org.Cry_Legende.sHDataPlaceholders.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.Cry_Legende.sHDataPlaceholders.DataManager.Operation;
import org.Cry_Legende.sHDataPlaceholders.SHDataPlaceholders;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;
import java.util.logging.Level;

/**
 * Mode réseau : les valeurs des joueurs vivent dans la base partagée, le cache mémoire ne sert qu'aux placeholders.
 *
 * <ul>
 *   <li>à l'arrivée d'un joueur : une fois le départ de l'autre serveur confirmé, ses valeurs sont lues en base ;
 *       d'ici là les placeholders répondent la valeur par défaut, sans attendre ;</li>
 *   <li>chaque modification (add, take, set) est faite par la base, sur un fil à part, pour un joueur connecté ici,
 *       connecté ailleurs ou hors ligne : un ajout n'écrase jamais celui d'un autre serveur ;</li>
 *   <li>le serveur qui modifie prévient les autres par le bus : celui où le joueur est connecté relit la clé en base ;</li>
 *   <li>au départ : la barrière de SHNetwork attend que les écritures en cours soient finies ;</li>
 *   <li>au départ aussi, le joueur sort du cache : la base fait foi à son prochain passage.</li>
 * </ul>
 *
 * Les fichiers userdata/*.json ne sont plus lus ni écrits dans ce mode (sauf par la commande d'import).
 */
public final class NetworkData {

    private final SHDataPlaceholders plugin;
    /** Cache du plugin : UUID -> (clé -> valeur), lu par les placeholders. */
    private final Map<UUID, Map<String, Integer>> cache;
    private final IntSupplier defaultValue;
    private final ValuesDatabase db;
    private final NetworkLink link;
    private final Duration waitMax;
    private final String channel;
    private final String accountsTable;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SHDataPlaceholders-reseau");
        thread.setDaemon(true);
        return thread;
    });
    /** Joueurs connectés à ce serveur (tenu par les événements de connexion). */
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();

    public NetworkData(SHDataPlaceholders plugin, Map<UUID, Map<String, Integer>> cache, IntSupplier defaultValue,
                       ValuesDatabase db, NetworkLink link, Duration waitMax, String channel, String accountsTable) {
        this.plugin = plugin;
        this.cache = cache;
        this.defaultValue = defaultValue;
        this.db = db;
        this.link = link;
        this.waitMax = waitMax;
        this.channel = channel;
        this.accountsTable = accountsTable == null ? "" : accountsTable.trim();
    }

    /** Branche le mode réseau : sauvegarde au départ et invalidations venues des autres serveurs. */
    public void start() {
        link.registerSaveTask(player -> flush());
        link.subscribe(channel, this::onRemoteChange);
    }

    /** Termine les écritures en cours puis ferme la base. */
    public void stop() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Les écritures en cours n'ont pas fini en 10 s.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
    }

    // ------------------------------------------------------------------ joueurs connectés

    /** Un joueur arrive sur ce serveur : ses valeurs sont lues en base une fois le départ de l'autre serveur confirmé. */
    public void beginLoad(UUID uuid) {
        online.add(uuid);
        link.awaitSaved(uuid, waitMax).whenComplete((saved, error) -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                plugin.getLogger().warning("Départ de " + uuid + " non confirmé par l'autre serveur"
                    + (error != null ? " (" + error + ")" : "") + " : valeurs relues quand même.");
            }
            submit(() -> load(uuid));
        });
    }

    /** Un joueur quitte ce serveur : il sort du cache, la base garde ses valeurs. */
    public void unload(UUID uuid) {
        online.remove(uuid);
        submit(() -> {
            // un joueur revenu entre-temps garde son cache
            if (!online.contains(uuid)) {
                cache.remove(uuid);
            }
        });
    }

    private void load(UUID uuid) {
        if (!online.contains(uuid)) {
            return; // reparti avant que la lecture ne passe
        }
        try {
            cache.put(uuid, new ConcurrentHashMap<>(db.loadAll(uuid)));
        } catch (SQLException e) {
            // les placeholders répondent la valeur par défaut tant que la base ne répond pas
            plugin.getLogger().log(Level.WARNING, "Valeurs de " + uuid + " non relues en base : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ modifications

    /**
     * Modifie une valeur en base (joueur de ce serveur, d'un autre serveur ou hors ligne) et rend la nouvelle.
     * Le futur échoue si la base est injoignable.
     */
    public CompletableFuture<Integer> modify(UUID uuid, String key, int amount, Operation op) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    int value = db.modify(uuid, key, amount, op, defaultValue.getAsInt());
                    Map<String, Integer> data = cache.get(uuid);
                    if (data != null && online.contains(uuid)) {
                        data.put(key, value);
                    }
                    JsonObject o = new JsonObject();
                    o.addProperty("uuid", uuid.toString());
                    o.addProperty("key", key);
                    link.publish(channel, o.toString());
                    future.complete(value);
                } catch (SQLException e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException stopped) {
            future.completeExceptionally(stopped);
        }
        return future;
    }

    /** Un autre serveur a modifié une valeur : si le joueur est ici, la clé est relue en base. Fil du bus : ne bloque pas. */
    private void onRemoteChange(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            UUID uuid = UUID.fromString(o.get("uuid").getAsString());
            String key = o.get("key").getAsString();
            if (!online.contains(uuid)) {
                return;
            }
            submit(() -> refresh(uuid, key));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Message " + channel + " illisible : " + e.getMessage());
        }
    }

    private void refresh(UUID uuid, String key) {
        Map<String, Integer> data = cache.get(uuid);
        if (data == null || !online.contains(uuid)) {
            return; // lecture complète à venir, ou joueur parti
        }
        try {
            Integer value = db.load(uuid, key);
            if (value == null) {
                data.remove(key);
            } else {
                data.put(key, value);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Valeur " + key + " de " + uuid + " non relue en base : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ joueur visé par une commande

    /** Joueur retrouvé par son pseudo : son UUID (celui de tout le réseau) et son pseudo exact. */
    public record Resolved(UUID uuid, String name) {
    }

    /**
     * Joueur visé par /shdata : un joueur connecté ici ou sur un autre serveur, un compte connu de la table des
     * comptes ({@code reseau.table-comptes}), sinon un joueur déjà venu sur ce serveur. Jamais de recherche Mojang.
     * Bloque : hors du fil principal.
     */
    public Optional<Resolved> resolve(String name) {
        Player local = Bukkit.getPlayerExact(name);
        if (local != null) {
            return Optional.of(new Resolved(local.getUniqueId(), local.getName()));
        }
        Optional<NetworkLink.Target> remote = link.find(name);
        if (remote.isPresent()) {
            return Optional.of(new Resolved(remote.get().uuid(), remote.get().name()));
        }
        if (!accountsTable.isEmpty()) {
            try {
                String known = db.knownName(accountsTable, name.toLowerCase(Locale.ROOT));
                if (known != null) {
                    return Optional.of(new Resolved(offlineUuid(known), known));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Table des comptes " + accountsTable + " illisible, joueurs retrouvés sur ce serveur seulement : " + e.getMessage());
            }
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null || cached.getName() == null || !(cached.hasPlayedBefore() || cached.isOnline())) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(offlineUuid(cached.getName()), cached.getName()));
    }

    /** UUID « hors ligne » d'un pseudo, celui de tout le réseau (pas de recherche Mojang). */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ import

    /**
     * Reprend les fichiers userdata/*.json dans la base, sans toucher aux valeurs qui y sont déjà. Tourne hors du fil
     * principal ; {@code done} reçoit (joueurs lus, valeurs ajoutées, valeurs déjà présentes) ou l'erreur.
     */
    public void importFolder(File folder, BiConsumer<int[], String> done) {
        submit(() -> {
            int players = 0;
            int added = 0;
            int present = 0;
            File[] files = folder.listFiles((dir, fileName) -> fileName.endsWith(".json"));
            try {
                if (files != null) {
                    Type type = new TypeToken<Map<String, Integer>>() {
                    }.getType();
                    for (File file : files) {
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(file.getName().substring(0, file.getName().length() - ".json".length()));
                        } catch (IllegalArgumentException notAPlayer) {
                            continue;
                        }
                        Map<String, Integer> values;
                        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                            values = new Gson().fromJson(reader, type);
                        } catch (IOException | JsonParseException unreadable) {
                            plugin.getLogger().warning("Fichier " + file.getName() + " illisible, ignoré : " + unreadable.getMessage());
                            continue;
                        }
                        if (values == null || values.isEmpty()) {
                            continue;
                        }
                        int inserted = db.importValues(uuid, values);
                        players++;
                        added += inserted;
                        present += values.size() - inserted;
                    }
                }
                done.accept(new int[]{players, added, present}, null);
            } catch (SQLException e) {
                done.accept(null, e.getMessage());
            }
        });
    }

    /** Futur terminé quand toutes les opérations déjà demandées sont faites (le fil du réseau les traite dans l'ordre). */
    private CompletableFuture<Void> flush() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            io.execute(() -> done.complete(null));
        } catch (RejectedExecutionException stopped) {
            done.complete(null);
        }
        return done;
    }

    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException stopped) {
            // plugin arrêté : plus rien à faire
        }
    }
}
