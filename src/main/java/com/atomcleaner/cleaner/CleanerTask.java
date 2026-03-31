package com.atomcleaner.cleaner;

import com.atomcleaner.AtomCleaner;
import com.atomcleaner.config.PluginConfig;
import com.atomcleaner.protection.ChunkProtectionChecker;
import com.atomcleaner.protection.ChunkProtectionChecker.ProtectionResult;
import com.atomcleaner.tracker.ActivityPersistenceManager;
import com.atomcleaner.tracker.ChunkActivityData;
import com.atomcleaner.tracker.ChunkActivityTracker;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

public class CleanerTask {

    record RegionScanCursor(
            List<File> regionFiles,
            int currentRegionIndex,
            int currentChunkOffset,
            long lastFileListRefresh
    ) {
        RegionScanCursor withProgress(int newRegionIndex, int newChunkOffset) {
            return new RegionScanCursor(regionFiles, newRegionIndex, newChunkOffset, lastFileListRefresh);
        }

        RegionScanCursor withRefreshed(List<File> newFiles) {
            return new RegionScanCursor(newFiles, 0, 0, System.currentTimeMillis());
        }
    }

    record WorldInfo(String worldName, String regionSubfolder, File regionDir) {}

    record ChunkCandidate(String worldName, int chunkX, int chunkZ) {}

    private static final long FILE_LIST_REFRESH_MS = 5 * 60 * 1000L; // 5 minutes

    private final AtomCleaner plugin;
    private final PluginConfig config;
    private final ChunkActivityTracker tracker;
    private final ChunkProtectionChecker protectionChecker;
    private final RegionFileManager regionFileManager;
    private final ExecutorService executor;
    private final ActivityPersistenceManager persistenceManager;

    private final ConcurrentHashMap<String, RegionScanCursor> cursors = new ConcurrentHashMap<>();

    // Stats
    private volatile long totalScanned = 0;
    private volatile long totalDeleted = 0;
    private volatile long totalProtected = 0;

    public CleanerTask(AtomCleaner plugin, PluginConfig config, ChunkActivityTracker tracker,
                       ChunkProtectionChecker protectionChecker, RegionFileManager regionFileManager,
                       ExecutorService executor, ActivityPersistenceManager persistenceManager) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
        this.protectionChecker = protectionChecker;
        this.regionFileManager = regionFileManager;
        this.executor = executor;
        this.persistenceManager = persistenceManager;
    }

    /**
     * Starts the cleanup asynchronously.
     *
     * @return a future that completes when all async work (scans, checks, deletions, persistence) is done.
     */
    public CompletableFuture<Void> runAsync() {
        if (!config.isEnabled()) return CompletableFuture.completedFuture(null);

        if (config.isVerboseLogging()) {
            plugin.getLogger().info("Temizlik başlatılıyor...");
        }

        // done is completed exactly once at every terminal path so callers can track real completion
        CompletableFuture<Void> done = new CompletableFuture<>();

        // Step 1: Gather world folder info on the main thread (world API is main-thread-safe)
        CompletableFuture<List<WorldInfo>> worldInfoFuture = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            List<WorldInfo> worlds = new ArrayList<>();
            for (Map.Entry<String, String> entry : config.getWorldRegionSubfolders().entrySet()) {
                String worldName = entry.getKey();
                String regionSubfolder = entry.getValue();
                World world = plugin.getServer().getWorld(worldName);
                if (world == null) continue;
                File worldFolder = world.getWorldFolder();
                File regionDir = new File(worldFolder, regionSubfolder);
                if (regionDir.exists() && regionDir.isDirectory()) {
                    worlds.add(new WorldInfo(worldName, regionSubfolder, regionDir));
                }
            }
            worldInfoFuture.complete(worlds);
        });

        // Step 2: After getting world info, collect candidates from region files (file I/O, async is fine)
        worldInfoFuture.thenAcceptAsync(worldInfoList -> {
            if (worldInfoList.isEmpty()) {
                plugin.getLogger().info("Taranacak dünya bulunamadı.");
                done.complete(null);
                return;
            }

            List<ChunkCandidate> candidates = collectCandidates(worldInfoList);

            if (candidates.isEmpty()) {
                if (config.isVerboseLogging()) {
                    plugin.getLogger().info("Taranacak chunk bulunamadı.");
                }
                done.complete(null);
                return;
            }

            if (config.isVerboseLogging()) {
                plugin.getLogger().info("Taranan chunk adayı: " + candidates.size());
            }

            // Step 3: Batch protection check on main thread
            CompletableFuture<Map<ChunkCandidate, ProtectionResult>> checkFuture = new CompletableFuture<>();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Map<ChunkCandidate, ProtectionResult> results = new HashMap<>();
                for (ChunkCandidate candidate : candidates) {
                    World world = plugin.getServer().getWorld(candidate.worldName());
                    if (world == null) {
                        results.put(candidate, new ProtectionResult(true, "world not loaded"));
                        continue;
                    }
                    ProtectionResult result = protectionChecker.check(world, candidate.chunkX(), candidate.chunkZ());
                    results.put(candidate, result);
                }
                checkFuture.complete(results);
            });

            // Step 4: Process check results on async thread
            checkFuture.thenAcceptAsync(results -> {
                int scanned = 0;
                int protected_ = 0;
                Map<String, Integer> worldDeletedCounts = new HashMap<>();
                Map<String, Integer> worldProtectedCounts = new HashMap<>();

                List<ChunkCandidate> toDelete = new ArrayList<>();
                for (Map.Entry<ChunkCandidate, ProtectionResult> entry : results.entrySet()) {
                    scanned++;
                    if (!entry.getValue().isProtected()) {
                        toDelete.add(entry.getKey());
                    } else {
                        protected_++;
                        worldProtectedCounts.merge(entry.getKey().worldName(), 1, Integer::sum);
                        if (config.isVerboseLogging()) {
                            plugin.getLogger().fine("Chunk korunuyor ["
                                    + entry.getKey().worldName() + " "
                                    + entry.getKey().chunkX() + "," + entry.getKey().chunkZ()
                                    + "]: " + entry.getValue().reason());
                        }
                    }
                }

                final int finalScanned = scanned;
                final int finalProtected = protected_;

                if (!toDelete.isEmpty()) {
                    // Step 5: Final isLoaded check on main thread, then delete async
                    CompletableFuture<List<ChunkCandidate>> loadedCheckFuture = new CompletableFuture<>();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        List<ChunkCandidate> confirmed = new ArrayList<>();
                        for (ChunkCandidate candidate : toDelete) {
                            World world = plugin.getServer().getWorld(candidate.worldName());
                            if (world == null) continue;
                            if (world.isChunkLoaded(candidate.chunkX(), candidate.chunkZ())) continue;
                            confirmed.add(candidate);
                        }
                        loadedCheckFuture.complete(confirmed);
                    });

                    loadedCheckFuture.thenAcceptAsync(confirmedList -> {
                        int asyncDeleted = 0;
                        for (ChunkCandidate candidate : confirmedList) {
                            World world = plugin.getServer().getWorld(candidate.worldName());
                            if (world == null) continue;
                            String regionSubfolder = config.getRegionSubfolder(candidate.worldName());
                            boolean wasPresent = regionFileManager.deleteChunk(
                                    world, candidate.chunkX(), candidate.chunkZ(), regionSubfolder);
                            if (wasPresent) {
                                asyncDeleted++;
                                worldDeletedCounts.merge(candidate.worldName(), 1, Integer::sum);
                                long chunkKey = ChunkActivityData.chunkKey(candidate.chunkX(), candidate.chunkZ());
                                tracker.markChunkDeleted(candidate.worldName(), chunkKey);

                                if (config.isVerboseLogging()) {
                                    plugin.getLogger().info("Chunk silindi: [" + candidate.worldName()
                                            + " " + candidate.chunkX() + "," + candidate.chunkZ() + "]");
                                }
                            }
                        }

                        totalScanned += finalScanned;
                        totalDeleted += asyncDeleted;
                        totalProtected += finalProtected;

                        plugin.getLogger().info(String.format(
                                "Temizlik tamamlandı: %d tarandı, %d silindi, %d korundu",
                                finalScanned, asyncDeleted, finalProtected));

                        if (config.isVerboseLogging()) {
                            worldDeletedCounts.forEach((w, count) ->
                                    plugin.getLogger().info("  [" + w + "] silindi: " + count));
                            worldProtectedCounts.forEach((w, count) ->
                                    plugin.getLogger().info("  [" + w + "] korundu: " + count));
                        }

                        if (config.isPersistenceEnabled()) {
                            persistenceManager.saveAll(tracker.getAllWorldData(), config.getMinTimeThresholdMs());
                        }

                        done.complete(null);
                    }, executor).exceptionally(ex -> {
                        plugin.getLogger().log(Level.SEVERE, "Chunk silme sırasında hata oluştu", ex);
                        done.complete(null);
                        return null;
                    });

                } else {
                    totalScanned += finalScanned;
                    totalProtected += finalProtected;

                    plugin.getLogger().info(String.format(
                            "Temizlik tamamlandı: %d tarandı, 0 silindi, %d korundu",
                            finalScanned, finalProtected));

                    if (config.isVerboseLogging()) {
                        worldProtectedCounts.forEach((w, count) ->
                                plugin.getLogger().info("  [" + w + "] korundu: " + count));
                    }

                    if (config.isPersistenceEnabled()) {
                        persistenceManager.saveAll(tracker.getAllWorldData(), config.getMinTimeThresholdMs());
                    }

                    done.complete(null);
                }
            }, executor).exceptionally(ex -> {
                plugin.getLogger().log(Level.SEVERE, "Koruma kontrolü sonrası hata oluştu", ex);
                done.complete(null);
                return null;
            });

        }, executor).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Temizlik sırasında hata oluştu", ex);
            done.complete(null);
            return null;
        });

        return done;
    }

    private List<ChunkCandidate> collectCandidates(List<WorldInfo> worldInfoList) {
        List<ChunkCandidate> candidates = new ArrayList<>();
        int limit = config.getChunksPerCleanup();

        for (WorldInfo worldInfo : worldInfoList) {
            if (candidates.size() >= limit) break;

            String worldName = worldInfo.worldName();
            File regionDir = worldInfo.regionDir();

            RegionScanCursor cursor = getOrCreateCursor(worldName, regionDir);
            List<File> regionFiles = cursor.regionFiles();
            if (regionFiles.isEmpty()) continue;

            int regionIndex = cursor.currentRegionIndex();
            int chunkOffset = cursor.currentChunkOffset();

            outer:
            while (regionIndex < regionFiles.size()) {
                File regionFile = regionFiles.get(regionIndex);
                if (!regionFile.exists() || regionFile.length() < 4096) {
                    regionIndex++;
                    chunkOffset = 0;
                    continue;
                }

                String[] parts = parseRegionFileName(regionFile.getName());
                if (parts == null) {
                    regionIndex++;
                    chunkOffset = 0;
                    continue;
                }

                int regionX, regionZ;
                try {
                    regionX = Integer.parseInt(parts[0]);
                    regionZ = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    regionIndex++;
                    chunkOffset = 0;
                    continue;
                }

                // Read the region file location table (first 4096 bytes)
                byte[] header = new byte[4096];
                try (RandomAccessFile raf = new RandomAccessFile(regionFile, "r")) {
                    int read = raf.read(header);
                    if (read < 4096) {
                        regionIndex++;
                        chunkOffset = 0;
                        continue;
                    }
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Region dosyası okunamadı: " + regionFile.getAbsolutePath(), e);
                    regionIndex++;
                    chunkOffset = 0;
                    continue;
                }

                while (chunkOffset < 1024) {
                    if (candidates.size() >= limit) break outer;

                    int byteOff = chunkOffset * 4;
                    int locationEntry = ((header[byteOff] & 0xFF) << 24)
                            | ((header[byteOff + 1] & 0xFF) << 16)
                            | ((header[byteOff + 2] & 0xFF) << 8)
                            | (header[byteOff + 3] & 0xFF);

                    if (locationEntry != 0) {
                        int localX = chunkOffset % 32;
                        int localZ = chunkOffset / 32;
                        int chunkX = regionX * 32 + localX;
                        int chunkZ = regionZ * 32 + localZ;
                        candidates.add(new ChunkCandidate(worldName, chunkX, chunkZ));
                    }

                    chunkOffset++;
                }

                if (chunkOffset >= 1024) {
                    regionIndex++;
                    chunkOffset = 0;
                }
            }

            // Wrap around if we've scanned all region files
            if (regionIndex >= regionFiles.size()) {
                regionIndex = 0;
                chunkOffset = 0;
            }

            cursors.put(worldName, cursor.withProgress(regionIndex, chunkOffset));
        }

        return candidates;
    }

    private RegionScanCursor getOrCreateCursor(String worldName, File regionDir) {
        RegionScanCursor existing = cursors.get(worldName);
        long now = System.currentTimeMillis();

        if (existing == null || (now - existing.lastFileListRefresh()) > FILE_LIST_REFRESH_MS) {
            File[] files = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
            List<File> fileList = (files != null) ? List.of(files) : List.of();
            RegionScanCursor newCursor = new RegionScanCursor(fileList, 0, 0, now);
            cursors.put(worldName, newCursor);
            return newCursor;
        }

        return existing;
    }

    private String[] parseRegionFileName(String name) {
        // Expected format: r.X.Z.mca
        if (!name.startsWith("r.") || !name.endsWith(".mca")) return null;
        String middle = name.substring(2, name.length() - 4);
        int dot = middle.indexOf('.');
        if (dot < 0) return null;
        return new String[]{middle.substring(0, dot), middle.substring(dot + 1)};
    }

    public long getTotalScanned() {
        return totalScanned;
    }

    public long getTotalDeleted() {
        return totalDeleted;
    }

    public long getTotalProtected() {
        return totalProtected;
    }
}
