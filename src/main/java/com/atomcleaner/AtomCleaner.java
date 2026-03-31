package com.atomcleaner;

import com.atomcleaner.cleaner.CleanerScheduler;
import com.atomcleaner.cleaner.RegionFileManager;
import com.atomcleaner.command.AtomCleanerCommand;
import com.atomcleaner.config.PluginConfig;
import com.atomcleaner.listener.BlockActivityListener;
import com.atomcleaner.listener.PlayerMovementListener;
import com.atomcleaner.protection.ChunkProtectionChecker;
import com.atomcleaner.tracker.ActivityPersistenceManager;
import com.atomcleaner.tracker.ChunkActivityData;
import com.atomcleaner.tracker.ChunkActivityTracker;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public class AtomCleaner extends JavaPlugin {

    private PluginConfig pluginConfig;
    private ChunkActivityTracker tracker;
    private ActivityPersistenceManager persistenceManager;
    private CleanerScheduler cleanerScheduler;
    private PlayerMovementListener playerMovementListener;

    @Override
    public void onEnable() {
        // 1. Save default config
        saveDefaultConfig();

        // 2. Load PluginConfig
        pluginConfig = PluginConfig.fromConfig(getConfig());

        // 3. Create ChunkActivityTracker
        tracker = new ChunkActivityTracker();

        // 4. Create ActivityPersistenceManager, load data from files
        persistenceManager = new ActivityPersistenceManager(this, pluginConfig.getPersistenceDirectory());
        if (pluginConfig.isPersistenceEnabled()) {
            loadPersistenceData();
        }

        // 5. Register listeners
        BlockActivityListener blockActivityListener = new BlockActivityListener(pluginConfig, tracker);
        playerMovementListener = new PlayerMovementListener(pluginConfig, tracker);
        getServer().getPluginManager().registerEvents(blockActivityListener, this);
        getServer().getPluginManager().registerEvents(playerMovementListener, this);

        // 6. Create and start CleanerScheduler
        RegionFileManager regionFileManager = new RegionFileManager(this);
        ChunkProtectionChecker protectionChecker = new ChunkProtectionChecker(this, pluginConfig, tracker);
        cleanerScheduler = new CleanerScheduler(this, pluginConfig, tracker,
                protectionChecker, regionFileManager, persistenceManager);
        cleanerScheduler.start();

        // 7. Register Brigadier commands
        AtomCleanerCommand commandHandler = new AtomCleanerCommand(
                this, pluginConfig, tracker, cleanerScheduler, persistenceManager);
        commandHandler.register();

        // 8. Record current online players' chunks in tracker
        playerMovementListener.recordCurrentPlayers(getServer().getOnlinePlayers());

        // 9. Log startup message
        getLogger().info("AtomCleaner v" + getPluginMeta().getVersion() + " etkinleştirildi!");
        getLogger().info("Takip edilen dünya sayısı: " + pluginConfig.getWorldRegionSubfolders().size());
    }

    @Override
    public void onDisable() {
        // 1. Stop CleanerScheduler
        if (cleanerScheduler != null) {
            cleanerScheduler.stop();
        }

        // 2. Save persistence data
        if (persistenceManager != null && pluginConfig != null && pluginConfig.isPersistenceEnabled()) {
            persistenceManager.saveAll(tracker.getAllWorldData(), pluginConfig.getMinTimeThresholdMs());
        }

        // 3. Log shutdown message
        getLogger().info("AtomCleaner devre dışı bırakıldı.");
    }

    private void loadPersistenceData() {
        for (String worldName : pluginConfig.getWorldRegionSubfolders().keySet()) {
            Map<Long, ChunkActivityData> data = persistenceManager.load(worldName);
            if (!data.isEmpty()) {
                tracker.loadData(worldName, data);
                getLogger().info("Kalıcılık verisi yüklendi [" + worldName + "]: " + data.size() + " chunk");
            }
        }
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public ChunkActivityTracker getTracker() {
        return tracker;
    }

    public ActivityPersistenceManager getPersistenceManager() {
        return persistenceManager;
    }

    public CleanerScheduler getCleanerScheduler() {
        return cleanerScheduler;
    }
}
