package com.atomcleaner.protection;

import com.atomcleaner.AtomCleaner;
import com.atomcleaner.config.PluginConfig;
import com.atomcleaner.tracker.ChunkActivityData;
import com.atomcleaner.tracker.ChunkActivityTracker;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class ChunkProtectionChecker {

    public record ProtectionResult(boolean isProtected, String reason) {}

    private final AtomCleaner plugin;
    private final PluginConfig config;
    private final ChunkActivityTracker tracker;

    public ChunkProtectionChecker(AtomCleaner plugin, PluginConfig config, ChunkActivityTracker tracker) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
    }

    /**
     * Must be called from the main thread.
     */
    public ProtectionResult check(World world, int chunkX, int chunkZ) {
        String worldName = world.getName();

        // 1. Chunk loaded check
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            return new ProtectionResult(true, "chunk is loaded");
        }

        // 2. Chunk force loaded check
        if (world.isChunkForceLoaded(chunkX, chunkZ)) {
            return new ProtectionResult(true, "chunk is force loaded");
        }

        // 3. Spawn protection
        Location spawn = world.getSpawnLocation();
        int spawnChunkX = spawn.getBlockX() >> 4;
        int spawnChunkZ = spawn.getBlockZ() >> 4;
        int spawnDist = Math.max(Math.abs(spawnChunkX - chunkX), Math.abs(spawnChunkZ - chunkZ));
        if (spawnDist <= config.getSpawnProtectionRadius()) {
            return new ProtectionResult(true, "spawn protection");
        }

        // 4. Player proximity check
        int viewDistance = plugin.getServer().getViewDistance();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.getWorld().getName().equals(worldName)) continue;
            Location playerLoc = player.getLocation();
            int playerChunkX = playerLoc.getBlockX() >> 4;
            int playerChunkZ = playerLoc.getBlockZ() >> 4;
            int dist = Math.max(Math.abs(playerChunkX - chunkX), Math.abs(playerChunkZ - chunkZ));
            if (dist <= viewDistance) {
                return new ProtectionResult(true, "player nearby");
            }
        }

        // 5. Tracker data check
        long chunkKey = ChunkActivityData.chunkKey(chunkX, chunkZ);
        ChunkActivityData data = tracker.getChunkData(worldName, chunkKey);
        if (data != null) {
            if (data.isProtected(config.getMinTimeThresholdMs())) {
                return new ProtectionResult(true, "player activity");
            }
        } else {
            // 6. Unknown chunk (no data in tracker)
            return new ProtectionResult(true, "unknown chunk (no tracking data)");
        }

        // 7. Whitelist check
        if (config.isChunkWhitelisted(worldName, chunkX, chunkZ)) {
            return new ProtectionResult(true, "whitelisted");
        }

        return new ProtectionResult(false, "safe to delete");
    }
}
