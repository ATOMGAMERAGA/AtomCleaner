package com.atomcleaner.tracker;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkActivityData {

    private final long chunkKey;
    private final String worldName;
    private final int chunkX;
    private final int chunkZ;
    private final AtomicBoolean hasPlayerActivity;
    private final long firstEntryTime;
    private final AtomicLong totalTimeSpent;
    private final AtomicLong lastActivityTime;
    private final Set<UUID> visitedBy;

    public ChunkActivityData(long chunkKey, String worldName, int chunkX, int chunkZ) {
        this.chunkKey = chunkKey;
        this.worldName = worldName;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.hasPlayerActivity = new AtomicBoolean(false);
        this.firstEntryTime = System.currentTimeMillis();
        this.totalTimeSpent = new AtomicLong(0L);
        this.lastActivityTime = new AtomicLong(System.currentTimeMillis());
        this.visitedBy = ConcurrentHashMap.newKeySet();
    }

    // Constructor for loading from persistence
    public ChunkActivityData(long chunkKey, String worldName, int chunkX, int chunkZ,
                              boolean hasPlayerActivity, long totalTimeSpent) {
        this.chunkKey = chunkKey;
        this.worldName = worldName;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.hasPlayerActivity = new AtomicBoolean(hasPlayerActivity);
        this.firstEntryTime = System.currentTimeMillis();
        this.totalTimeSpent = new AtomicLong(totalTimeSpent);
        this.lastActivityTime = new AtomicLong(System.currentTimeMillis());
        this.visitedBy = ConcurrentHashMap.newKeySet();
    }

    public static long chunkKey(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    public void markActivity() {
        hasPlayerActivity.set(true);
        lastActivityTime.set(System.currentTimeMillis());
    }

    public void addTimeSpent(long ms) {
        if (ms > 0) {
            totalTimeSpent.addAndGet(ms);
        }
        lastActivityTime.set(System.currentTimeMillis());
    }

    public void addVisitor(UUID playerId) {
        visitedBy.add(playerId);
        lastActivityTime.set(System.currentTimeMillis());
    }

    public boolean isProtected(long minTimeThresholdMs) {
        return hasPlayerActivity.get() || totalTimeSpent.get() >= minTimeThresholdMs;
    }

    public long getChunkKey() {
        return chunkKey;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public boolean isHasPlayerActivity() {
        return hasPlayerActivity.get();
    }

    public long getFirstEntryTime() {
        return firstEntryTime;
    }

    public long getTotalTimeSpent() {
        return totalTimeSpent.get();
    }

    public long getLastActivityTime() {
        return lastActivityTime.get();
    }

    public Set<UUID> getVisitedBy() {
        return visitedBy;
    }

    public int getVisitorCount() {
        return visitedBy.size();
    }
}
