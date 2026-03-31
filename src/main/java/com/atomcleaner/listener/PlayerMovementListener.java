package com.atomcleaner.listener;

import com.atomcleaner.config.PluginConfig;
import com.atomcleaner.tracker.ChunkActivityTracker;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerMovementListener implements Listener {

    record ChunkLocationKey(String world, int chunkX, int chunkZ) {}

    private final PluginConfig config;
    private final ChunkActivityTracker tracker;
    private final Map<UUID, ChunkLocationKey> playerCurrentChunk = new ConcurrentHashMap<>();

    public PlayerMovementListener(PluginConfig config, ChunkActivityTracker tracker) {
        this.config = config;
        this.tracker = tracker;
    }

    private ChunkLocationKey toChunkKey(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return new ChunkLocationKey(
                loc.getWorld().getName(),
                loc.getBlockX() >> 4,
                loc.getBlockZ() >> 4
        );
    }

    private void handleChunkTransition(Player player, ChunkLocationKey from, ChunkLocationKey to) {
        if (from != null && config.isTrackedWorld(from.world())) {
            tracker.recordPlayerExit(from.world(), from.chunkX(), from.chunkZ(), player.getUniqueId());
        }
        if (to != null && config.isTrackedWorld(to.world())) {
            tracker.recordPlayerEntry(to.world(), to.chunkX(), to.chunkZ(), player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        int fromChunkX = from.getBlockX() >> 4;
        int fromChunkZ = from.getBlockZ() >> 4;
        int toChunkX = to.getBlockX() >> 4;
        int toChunkZ = to.getBlockZ() >> 4;

        // Only process if chunk changed
        if (fromChunkX == toChunkX && fromChunkZ == toChunkZ
                && from.getWorld() != null && to.getWorld() != null
                && from.getWorld().getName().equals(to.getWorld().getName())) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ChunkLocationKey fromKey = toChunkKey(from);
        ChunkLocationKey toKey = toChunkKey(to);

        handleChunkTransition(player, fromKey, toKey);
        if (toKey != null) {
            playerCurrentChunk.put(uuid, toKey);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        int fromChunkX = from.getBlockX() >> 4;
        int fromChunkZ = from.getBlockZ() >> 4;
        int toChunkX = to.getBlockX() >> 4;
        int toChunkZ = to.getBlockZ() >> 4;

        boolean sameWorld = from.getWorld() != null && to.getWorld() != null
                && from.getWorld().getName().equals(to.getWorld().getName());
        if (sameWorld && fromChunkX == toChunkX && fromChunkZ == toChunkZ) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ChunkLocationKey fromKey = toChunkKey(from);
        ChunkLocationKey toKey = toChunkKey(to);

        handleChunkTransition(player, fromKey, toKey);
        if (toKey != null) {
            playerCurrentChunk.put(uuid, toKey);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        ChunkLocationKey key = toChunkKey(loc);
        if (key != null) {
            playerCurrentChunk.put(player.getUniqueId(), key);
            if (config.isTrackedWorld(key.world())) {
                tracker.recordPlayerEntry(key.world(), key.chunkX(), key.chunkZ(), player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ChunkLocationKey key = playerCurrentChunk.remove(uuid);
        if (key != null && config.isTrackedWorld(key.world())) {
            tracker.recordPlayerExit(key.world(), key.chunkX(), key.chunkZ(), uuid);
        }
        tracker.cleanupPlayer(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Exit old world chunk
        String oldWorldName = event.getFrom().getName();
        ChunkLocationKey existing = playerCurrentChunk.get(uuid);
        if (existing != null && config.isTrackedWorld(existing.world())) {
            tracker.recordPlayerExit(existing.world(), existing.chunkX(), existing.chunkZ(), uuid);
        }

        // Entry for new world chunk
        Location newLoc = player.getLocation();
        ChunkLocationKey newKey = toChunkKey(newLoc);
        if (newKey != null) {
            playerCurrentChunk.put(uuid, newKey);
            if (config.isTrackedWorld(newKey.world())) {
                tracker.recordPlayerEntry(newKey.world(), newKey.chunkX(), newKey.chunkZ(), uuid);
            }
        } else {
            playerCurrentChunk.remove(uuid);
        }
    }

    public void recordCurrentPlayers(Iterable<? extends Player> players) {
        for (Player player : players) {
            Location loc = player.getLocation();
            ChunkLocationKey key = toChunkKey(loc);
            if (key != null) {
                playerCurrentChunk.put(player.getUniqueId(), key);
                if (config.isTrackedWorld(key.world())) {
                    tracker.recordPlayerEntry(key.world(), key.chunkX(), key.chunkZ(), player.getUniqueId());
                }
            }
        }
    }
}
