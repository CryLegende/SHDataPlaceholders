package org.Cry_Legende.sHDataPlaceholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class PapiExpansion extends PlaceholderExpansion {

    private final SHDataPlaceholders plugin;

    public PapiExpansion(SHDataPlaceholders plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "shdataplaceholders";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Cry_Legende";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        // Force minuscule pour matcher avec le DataManager
        return String.valueOf(plugin.getDataManager().getStat(player.getUniqueId(), params.toLowerCase()));
    }
}
