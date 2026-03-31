package com.atomcleaner.cleaner;

import com.atomcleaner.AtomCleaner;
import com.atomcleaner.config.PluginConfig;
import com.atomcleaner.protection.ChunkProtectionChecker;
import com.atomcleaner.tracker.ActivityPersistenceManager;
import com.atomcleaner.tracker.ChunkActivityTracker;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class CleanerScheduler {

    private final AtomCleaner plugin;
    private final PluginConfig config;
    private final CleanerTask cleanerTask;
    private final ExecutorService cleanerExecutor;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private BukkitTask scheduledTask;

    public CleanerScheduler(AtomCleaner plugin, PluginConfig config,
                            ChunkActivityTracker tracker,
                            ChunkProtectionChecker protectionChecker,
                            RegionFileManager regionFileManager,
                            ActivityPersistenceManager persistenceManager) {
        this.plugin = plugin;
        this.config = config;
        this.cleanerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.cleanerTask = new CleanerTask(plugin, config, tracker, protectionChecker,
                regionFileManager, cleanerExecutor, persistenceManager);
    }

    public void start() {
        if (!config.isEnabled()) {
            plugin.getLogger().info("AtomCleaner devre dışı bırakıldı (config).");
            return;
        }

        long intervalTicks = config.getCleanupIntervalMinutes() * 60L * 20L;
        long initialDelayTicks = 20L * 30; // 30 seconds initial delay

        scheduledTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::startCleanup, initialDelayTicks, intervalTicks);

        plugin.getLogger().info("Temizlik zamanlayıcı başlatıldı. Interval: "
                + config.getCleanupIntervalMinutes() + " dakika.");
    }

    private void startCleanup() {
        if (paused.get()) {
            if (config.isVerboseLogging()) {
                plugin.getLogger().info("Temizlik duraklatıldı, atlanıyor.");
            }
            return;
        }

        if (!isRunning.compareAndSet(false, true)) {
            plugin.getLogger().warning("Önceki temizlik hâlâ çalışıyor, bu döngü atlandı.");
            return;
        }

        try {
            cleanerTask.runAsync().whenComplete((v, ex) -> {
                if (ex != null) {
                    plugin.getLogger().log(Level.SEVERE, "Temizlik görevi çalışırken hata oluştu", ex);
                }
                isRunning.set(false);
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Temizlik başlatılırken hata oluştu", e);
            isRunning.set(false);
        }
    }

    public void triggerManualCleanup() {
        if (!isRunning.compareAndSet(false, true)) {
            plugin.getLogger().warning("Temizlik zaten çalışıyor.");
            return;
        }

        try {
            cleanerTask.runAsync().whenComplete((v, ex) -> {
                if (ex != null) {
                    plugin.getLogger().log(Level.SEVERE, "Manuel temizlik sırasında hata oluştu", ex);
                }
                isRunning.set(false);
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Manuel temizlik başlatılırken hata oluştu", e);
            isRunning.set(false);
        }
    }

    public void pause() {
        paused.set(true);
        plugin.getLogger().info("Temizlik zamanlayıcı duraklatıldı.");
    }

    public void resume() {
        paused.set(false);
        plugin.getLogger().info("Temizlik zamanlayıcı devam ettirildi.");
    }

    public boolean isPaused() {
        return paused.get();
    }

    public boolean isCurrentlyRunning() {
        return isRunning.get();
    }

    public void stop() {
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel();
            scheduledTask = null;
        }

        cleanerExecutor.shutdown();
        try {
            if (!cleanerExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                cleanerExecutor.shutdownNow();
                plugin.getLogger().warning("Temizlik executor 30 saniyede kapanmadı, zorla kapatılıyor.");
            }
        } catch (InterruptedException e) {
            cleanerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        plugin.getLogger().info("Temizlik zamanlayıcı durduruldu.");
    }

    public CleanerTask getCleanerTask() {
        return cleanerTask;
    }
}
