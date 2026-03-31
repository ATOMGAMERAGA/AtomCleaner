package com.atomcleaner.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PluginConfig {

    private final boolean enabled;
    private final int cleanupIntervalMinutes;
    private final int chunksPerCleanup;
    private final long minTimeThresholdMs;
    private final Map<String, String> worldRegionSubfolders;
    private final int spawnProtectionRadius;
    private final boolean verboseLogging;
    private final boolean persistenceEnabled;
    private final int persistenceSaveIntervalMinutes;
    private final String persistenceDirectory;
    private final boolean checkProtectedBlocks;
    private final Set<String> protectedBlockTypes;
    private final Set<String> whitelistedChunks;

    private PluginConfig(
            boolean enabled,
            int cleanupIntervalMinutes,
            int chunksPerCleanup,
            long minTimeThresholdMs,
            Map<String, String> worldRegionSubfolders,
            int spawnProtectionRadius,
            boolean verboseLogging,
            boolean persistenceEnabled,
            int persistenceSaveIntervalMinutes,
            String persistenceDirectory,
            boolean checkProtectedBlocks,
            Set<String> protectedBlockTypes,
            Set<String> whitelistedChunks) {
        this.enabled = enabled;
        this.cleanupIntervalMinutes = cleanupIntervalMinutes;
        this.chunksPerCleanup = chunksPerCleanup;
        this.minTimeThresholdMs = minTimeThresholdMs;
        this.worldRegionSubfolders = worldRegionSubfolders;
        this.spawnProtectionRadius = spawnProtectionRadius;
        this.verboseLogging = verboseLogging;
        this.persistenceEnabled = persistenceEnabled;
        this.persistenceSaveIntervalMinutes = persistenceSaveIntervalMinutes;
        this.persistenceDirectory = persistenceDirectory;
        this.checkProtectedBlocks = checkProtectedBlocks;
        this.protectedBlockTypes = protectedBlockTypes;
        this.whitelistedChunks = whitelistedChunks;
    }

    public static PluginConfig fromConfig(FileConfiguration config) {
        boolean enabled = config.getBoolean("enabled", true);
        int cleanupIntervalMinutes = config.getInt("cleanup-interval-minutes", 10);
        int chunksPerCleanup = config.getInt("chunks-per-cleanup", 150);
        long minTimeThresholdSeconds = config.getLong("min-time-threshold-seconds", 300);
        long minTimeThresholdMs = minTimeThresholdSeconds * 1000L;
        int spawnProtectionRadius = config.getInt("spawn-protection-radius", 10);
        boolean verboseLogging = config.getBoolean("verbose-logging", true);

        boolean persistenceEnabled = config.getBoolean("persistence.enabled", true);
        int persistenceSaveIntervalMinutes = config.getInt("persistence.save-interval-minutes", 10);
        String persistenceDirectory = config.getString("persistence.directory", "data");

        boolean checkProtectedBlocks = config.getBoolean("check-protected-blocks", false);

        // Parse target-worlds
        Map<String, String> worldRegionSubfolders = new HashMap<>();
        if (config.isConfigurationSection("target-worlds")) {
            var section = config.getConfigurationSection("target-worlds");
            if (section != null) {
                for (String worldName : section.getKeys(false)) {
                    String subfolder = section.getString(worldName + ".region-subfolder", "region");
                    worldRegionSubfolders.put(worldName, subfolder);
                }
            }
        }

        // Parse protected-block-types
        Set<String> protectedBlockTypes = new HashSet<>();
        List<String> blockTypeList = config.getStringList("protected-block-types");
        protectedBlockTypes.addAll(blockTypeList);

        // Parse whitelisted-chunks
        Set<String> whitelistedChunks = new HashSet<>();
        List<String> whitelistList = config.getStringList("whitelisted-chunks");
        whitelistedChunks.addAll(whitelistList);

        return new PluginConfig(
                enabled,
                cleanupIntervalMinutes,
                chunksPerCleanup,
                minTimeThresholdMs,
                worldRegionSubfolders,
                spawnProtectionRadius,
                verboseLogging,
                persistenceEnabled,
                persistenceSaveIntervalMinutes,
                persistenceDirectory,
                checkProtectedBlocks,
                protectedBlockTypes,
                whitelistedChunks
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getCleanupIntervalMinutes() {
        return cleanupIntervalMinutes;
    }

    public int getChunksPerCleanup() {
        return chunksPerCleanup;
    }

    public long getMinTimeThresholdMs() {
        return minTimeThresholdMs;
    }

    public Map<String, String> getWorldRegionSubfolders() {
        return worldRegionSubfolders;
    }

    public boolean isTrackedWorld(String worldName) {
        return worldRegionSubfolders.containsKey(worldName);
    }

    public String getRegionSubfolder(String worldName) {
        return worldRegionSubfolders.getOrDefault(worldName, "region");
    }

    public int getSpawnProtectionRadius() {
        return spawnProtectionRadius;
    }

    public boolean isVerboseLogging() {
        return verboseLogging;
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    public int getPersistenceSaveIntervalMinutes() {
        return persistenceSaveIntervalMinutes;
    }

    public String getPersistenceDirectory() {
        return persistenceDirectory;
    }

    public boolean isCheckProtectedBlocks() {
        return checkProtectedBlocks;
    }

    public Set<String> getProtectedBlockTypes() {
        return protectedBlockTypes;
    }

    public Set<String> getWhitelistedChunks() {
        return whitelistedChunks;
    }

    public void addWhitelistedChunk(String chunkKey) {
        whitelistedChunks.add(chunkKey);
    }

    public void removeWhitelistedChunk(String chunkKey) {
        whitelistedChunks.remove(chunkKey);
    }

    public boolean isChunkWhitelisted(String worldName, int chunkX, int chunkZ) {
        String key = worldName + ":" + chunkX + ":" + chunkZ;
        return whitelistedChunks.contains(key);
    }
}
