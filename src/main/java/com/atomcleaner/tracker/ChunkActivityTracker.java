package com.atomcleaner.tracker;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkActivityTracker {

    // world -> (chunkKey -> data)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, ChunkActivityData>> worldData
            = new ConcurrentHashMap<>();

    // player -> (chunkKey -> entryTime)
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Long>> playerEntryTimes
            = new ConcurrentHashMap<>();

    private ChunkActivityData getOrCreate(String world, int chunkX, int chunkZ) {
        long key = ChunkActivityData.chunkKey(chunkX, chunkZ);
        ConcurrentHashMap<Long, ChunkActivityData> chunks = worldData.computeIfAbsent(
                world, w -> new ConcurrentHashMap<>());
        return chunks.computeIfAbsent(key, k -> new ChunkActivityData(k, world, chunkX, chunkZ));
    }

    public void recordBlockActivity(String world, int chunkX, int chunkZ) {
        ChunkActivityData data = getOrCreate(world, chunkX, chunkZ);
        data.markActivity();
    }

    public void recordPlayerEntry(String world, int chunkX, int chunkZ, UUID player) {
        long key = ChunkActivityData.chunkKey(chunkX, chunkZ);
        long now = System.currentTimeMillis();

        // Record entry time for time-spent calculation
        ConcurrentHashMap<Long, Long> playerChunks = playerEntryTimes.computeIfAbsent(
                player, p -> new ConcurrentHashMap<>());
        playerChunks.put(key, now);

        // Mark visitor
        ChunkActivityData data = getOrCreate(world, chunkX, chunkZ);
        data.addVisitor(player);
    }

    public void recordPlayerExit(String world, int chunkX, int chunkZ, UUID player) {
        long key = ChunkActivityData.chunkKey(chunkX, chunkZ);
        long now = System.currentTimeMillis();

        ConcurrentHashMap<Long, Long> playerChunks = playerEntryTimes.get(player);
        if (playerChunks != null) {
            Long entryTime = playerChunks.remove(key);
            if (entryTime != null) {
                long elapsed = now - entryTime;
                ChunkActivityData data = getOrCreate(world, chunkX, chunkZ);
                data.addTimeSpent(elapsed);
            }
        }
    }

    public ChunkActivityData getChunkData(String world, long chunkKey) {
        ConcurrentHashMap<Long, ChunkActivityData> chunks = worldData.get(world);
        if (chunks == null) return null;
        return chunks.get(chunkKey);
    }

    public boolean isChunkSafeToDelete(String world, long chunkKey, long minTimeThresholdMs) {
        ChunkActivityData data = getChunkData(world, chunkKey);
        if (data == null) return false; // No data means unknown, not safe
        return !data.isHasPlayerActivity() && data.getTotalTimeSpent() < minTimeThresholdMs;
    }

    public void markChunkDeleted(String world, long chunkKey) {
        ConcurrentHashMap<Long, ChunkActivityData> chunks = worldData.get(world);
        if (chunks != null) {
            chunks.remove(chunkKey);
        }
    }

    public void cleanupPlayer(UUID playerId) {
        playerEntryTimes.remove(playerId);
    }

    public void markChunkPersisted(String world, long chunkKey) {
        ConcurrentHashMap<Long, ChunkActivityData> chunks = worldData.get(world);
        if (chunks != null) {
            chunks.remove(chunkKey);
        }
    }

    public ConcurrentHashMap<String, ConcurrentHashMap<Long, ChunkActivityData>> getAllWorldData() {
        return worldData;
    }

    public void loadData(String world, Map<Long, ChunkActivityData> data) {
        ConcurrentHashMap<Long, ChunkActivityData> existing = worldData.computeIfAbsent(
                world, w -> new ConcurrentHashMap<>());
        existing.putAll(data);
    }

    public int getTotalTrackedChunks() {
        int total = 0;
        for (ConcurrentHashMap<Long, ChunkActivityData> chunks : worldData.values()) {
            total += chunks.size();
        }
        return total;
    }

    public int getTrackedWorldCount() {
        return worldData.size();
    }
}
