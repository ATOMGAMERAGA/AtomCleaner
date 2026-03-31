package com.atomcleaner.tracker;

import com.atomcleaner.AtomCleaner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ActivityPersistenceManager {

    private final AtomCleaner plugin;
    private final File dataDir;

    public ActivityPersistenceManager(AtomCleaner plugin, String dataDirectory) {
        this.plugin = plugin;
        this.dataDir = new File(plugin.getDataFolder(), dataDirectory);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
    }

    public void save(String worldName, Map<Long, ChunkActivityData> data, long minThresholdMs) {
        if (data == null || data.isEmpty()) return;

        File tempFile = new File(dataDir, worldName + "_activity.dat.tmp");
        File targetFile = new File(dataDir, worldName + "_activity.dat");

        try (BufferedWriter writer = Files.newBufferedWriter(tempFile.toPath())) {
            for (ChunkActivityData entry : data.values()) {
                // Only save significant chunks
                if (entry.isHasPlayerActivity() || entry.getTotalTimeSpent() >= minThresholdMs) {
                    writer.write(buildLine(entry));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write temp persistence file for world: " + worldName, e);
            return;
        }

        // Atomically move temp to target
        Path tempPath = tempFile.toPath();
        Path targetPath = targetFile.toPath();
        try {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicEx) {
            // Fall back to non-atomic move
            try {
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackEx) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save persistence file for world: " + worldName, fallbackEx);
            }
        }
    }

    private String buildLine(ChunkActivityData data) {
        return data.getChunkKey() + ";"
                + data.getWorldName() + ";"
                + data.getChunkX() + ";"
                + data.getChunkZ() + ";"
                + data.isHasPlayerActivity() + ";"
                + data.getTotalTimeSpent();
    }

    public Map<Long, ChunkActivityData> load(String worldName) {
        Map<Long, ChunkActivityData> result = new HashMap<>();
        File file = new File(dataDir, worldName + "_activity.dat");
        if (!file.exists()) return result;

        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(";", -1);
                if (parts.length < 6) {
                    plugin.getLogger().warning("Skipping malformed persistence line " + lineNum + " in " + worldName);
                    continue;
                }
                try {
                    long chunkKey = Long.parseLong(parts[0].trim());
                    String storedWorld = parts[1].trim();
                    int chunkX = Integer.parseInt(parts[2].trim());
                    int chunkZ = Integer.parseInt(parts[3].trim());
                    boolean hasPlayerActivity = Boolean.parseBoolean(parts[4].trim());
                    long totalTimeSpent = Long.parseLong(parts[5].trim());

                    ChunkActivityData data = new ChunkActivityData(
                            chunkKey, storedWorld, chunkX, chunkZ,
                            hasPlayerActivity, totalTimeSpent);
                    result.put(chunkKey, data);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Skipping bad persistence line " + lineNum + " in " + worldName + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load persistence file for world: " + worldName, e);
        }

        return result;
    }

    public void saveAll(Map<String, ConcurrentHashMap<Long, ChunkActivityData>> allData, long minThresholdMs) {
        for (Map.Entry<String, ConcurrentHashMap<Long, ChunkActivityData>> entry : allData.entrySet()) {
            save(entry.getKey(), entry.getValue(), minThresholdMs);
        }
    }
}
