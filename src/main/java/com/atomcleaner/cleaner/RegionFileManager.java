package com.atomcleaner.cleaner;

import com.atomcleaner.AtomCleaner;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.logging.Level;

public class RegionFileManager {

    private final AtomCleaner plugin;

    public RegionFileManager(AtomCleaner plugin) {
        this.plugin = plugin;
    }

    /**
     * Deletes (clears) a chunk from region files (region, entities, poi).
     *
     * @return true if region file had the chunk (offset != 0), false if already absent
     */
    public boolean deleteChunk(World world, int chunkX, int chunkZ, String regionSubfolder) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        int localX = chunkX & 31;
        int localZ = chunkZ & 31;
        int headerOffset = 4 * (localX + localZ * 32);

        File worldFolder = world.getWorldFolder();

        // Derive entities and poi subdirs
        String entitiesSubfolder = deriveRelativeDir(regionSubfolder, "entities");
        String poiSubfolder = deriveRelativeDir(regionSubfolder, "poi");

        File regionDir = new File(worldFolder, regionSubfolder);
        File entitiesDir = new File(worldFolder, entitiesSubfolder);
        File poiDir = new File(worldFolder, poiSubfolder);

        String regionFileName = "r." + regionX + "." + regionZ + ".mca";

        File regionFile = new File(regionDir, regionFileName);
        File entitiesFile = new File(entitiesDir, regionFileName);
        File poiFile = new File(poiDir, regionFileName);

        boolean hadChunk = false;

        // Process region file (primary — determines return value)
        if (regionFile.exists()) {
            Boolean result = clearChunkInRegionFile(regionFile, headerOffset);
            if (result != null && result) {
                hadChunk = true;
            }
        }

        // Process entities file
        if (entitiesFile.exists()) {
            clearChunkInRegionFile(entitiesFile, headerOffset);
        }

        // Process poi file
        if (poiFile.exists()) {
            clearChunkInRegionFile(poiFile, headerOffset);
        }

        return hadChunk;
    }

    /**
     * Derive the sibling directory (entities or poi) from the region subfolder path.
     * e.g. "region" -> "entities" or "poi"
     *      "DIM-1/region" -> "DIM-1/entities" or "DIM-1/poi"
     */
    private String deriveRelativeDir(String regionSubfolder, String targetName) {
        // Find the last path component "region" and replace it
        int lastSlash = regionSubfolder.lastIndexOf('/');
        if (lastSlash < 0) {
            // No slash, it's just "region"
            return targetName;
        } else {
            return regionSubfolder.substring(0, lastSlash + 1) + targetName;
        }
    }

    /**
     * Clear the chunk entry in the given region file by zeroing out the location and timestamp table entries.
     *
     * @return true if the chunk was present (non-zero offset), false if absent or error
     */
    private Boolean clearChunkInRegionFile(File regionFile, int headerOffset) {
        if (!regionFile.exists() || regionFile.length() < 4096) return false;

        try (RandomAccessFile raf = new RandomAccessFile(regionFile, "rw");
             FileChannel channel = raf.getChannel()) {

            try (FileLock lock = channel.lock()) {
                // Read current 4-byte location entry
                raf.seek(headerOffset);
                int b0 = raf.read();
                int b1 = raf.read();
                int b2 = raf.read();
                int b3 = raf.read();

                if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
                    return false; // EOF
                }

                int locationEntry = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
                if (locationEntry == 0) {
                    return false; // Already absent
                }

                // Zero out location entry
                raf.seek(headerOffset);
                raf.write(new byte[4]);

                // Zero out timestamp entry (offset by 4096)
                int tsOffset = 4096 + headerOffset;
                if (raf.length() >= tsOffset + 4) {
                    raf.seek(tsOffset);
                    raf.write(new byte[4]);
                }

                return true;
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to clear chunk in region file: " + regionFile.getAbsolutePath(), e);
            return null;
        }
    }
}
