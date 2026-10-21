package org.Cry_Legende.sHDataPlaceholders;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {
    private final SHDataPlaceholders plugin;

    public PlayerListener(SHDataPlaceholders plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getDataManager().loadPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getDataManager().unloadPlayer(event.getPlayer().getUniqueId());
    }
}
