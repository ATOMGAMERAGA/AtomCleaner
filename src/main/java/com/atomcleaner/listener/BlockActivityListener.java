package com.atomcleaner.listener;

import com.atomcleaner.config.PluginConfig;
import com.atomcleaner.tracker.ChunkActivityTracker;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.block.Action;

public class BlockActivityListener implements Listener {

    private final PluginConfig config;
    private final ChunkActivityTracker tracker;

    public BlockActivityListener(PluginConfig config, ChunkActivityTracker tracker) {
        this.config = config;
        this.tracker = tracker;
    }

    private void record(String worldName, int blockX, int blockZ) {
        if (!config.isTrackedWorld(worldName)) return;
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        tracker.recordBlockActivity(worldName, chunkX, chunkZ);
    }

    private void record(Block block) {
        if (block == null) return;
        record(block.getWorld().getName(), block.getX(), block.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.RIGHT_CLICK_AIR) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        record(clicked);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;
        if (!(tnt.getSource() instanceof Player)) return;
        Location loc = event.getLocation();
        record(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        Block block = event.getBlock();
        if (block == null) return;
        record(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        if (block == null) return;
        record(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (event.getPlayer() == null) return;
        Location loc = event.getLocation();
        record(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Location loc = event.getEntity().getLocation();
        record(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (!(event.getRemover() instanceof Player)) return;
        Location loc = event.getEntity().getLocation();
        record(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        record(event.getBed());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPistonExtend(BlockPistonExtendEvent event) {
        // Mark source chunk
        record(event.getBlock());
        // Mark all target block chunks
        String worldName = event.getBlock().getWorld().getName();
        if (!config.isTrackedWorld(worldName)) return;
        for (Block b : event.getBlocks()) {
            record(b);
        }
    }
}
