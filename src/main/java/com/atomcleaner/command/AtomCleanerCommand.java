package com.atomcleaner.command;

import com.atomcleaner.AtomCleaner;
import com.atomcleaner.cleaner.CleanerScheduler;
import com.atomcleaner.cleaner.CleanerTask;
import com.atomcleaner.config.PluginConfig;
import com.atomcleaner.tracker.ActivityPersistenceManager;
import com.atomcleaner.tracker.ChunkActivityTracker;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

@SuppressWarnings("UnstableApiUsage")
public class AtomCleanerCommand {

    private final AtomCleaner plugin;
    private final PluginConfig config;
    private final ChunkActivityTracker tracker;
    private final CleanerScheduler scheduler;
    private final ActivityPersistenceManager persistenceManager;

    public AtomCleanerCommand(AtomCleaner plugin, PluginConfig config,
                               ChunkActivityTracker tracker,
                               CleanerScheduler scheduler,
                               ActivityPersistenceManager persistenceManager) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
        this.scheduler = scheduler;
        this.persistenceManager = persistenceManager;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            LiteralCommandNode<CommandSourceStack> rootNode = Commands.literal("atomcleaner")
                    .requires(source -> source.getSender().hasPermission("atomcleaner.admin"))
                    .then(Commands.literal("status")
                            .executes(ctx -> executeStatus(ctx.getSource().getSender())))
                    .then(Commands.literal("run")
                            .executes(ctx -> executeRun(ctx.getSource().getSender())))
                    .then(Commands.literal("pause")
                            .executes(ctx -> executePause(ctx.getSource().getSender())))
                    .then(Commands.literal("resume")
                            .executes(ctx -> executeResume(ctx.getSource().getSender())))
                    .then(Commands.literal("stats")
                            .executes(ctx -> executeStats(ctx.getSource().getSender())))
                    .then(Commands.literal("save")
                            .executes(ctx -> executeSave(ctx.getSource().getSender())))
                    .then(Commands.literal("reload")
                            .executes(ctx -> executeReload(ctx.getSource().getSender())))
                    .then(Commands.literal("whitelist")
                            .then(Commands.literal("add")
                                    .then(Commands.argument("chunk", StringArgumentType.string())
                                            .executes(ctx -> executeWhitelistAdd(
                                                    ctx.getSource().getSender(),
                                                    StringArgumentType.getString(ctx, "chunk")))))
                            .then(Commands.literal("remove")
                                    .then(Commands.argument("chunk", StringArgumentType.string())
                                            .executes(ctx -> executeWhitelistRemove(
                                                    ctx.getSource().getSender(),
                                                    StringArgumentType.getString(ctx, "chunk"))))))
                    .build();

            commands.register(rootNode, "AtomCleaner yönetim komutu");
        });
    }

    private int executeStatus(CommandSender sender) {
        sender.sendMessage(Component.text("=== AtomCleaner Durum ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Etkin: ", NamedTextColor.GRAY)
                .append(Component.text(config.isEnabled() ? "Evet" : "Hayır",
                        config.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        sender.sendMessage(Component.text("Duraklatıldı: ", NamedTextColor.GRAY)
                .append(Component.text(scheduler.isPaused() ? "Evet" : "Hayır",
                        scheduler.isPaused() ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("Şu an çalışıyor: ", NamedTextColor.GRAY)
                .append(Component.text(scheduler.isCurrentlyRunning() ? "Evet" : "Hayır",
                        scheduler.isCurrentlyRunning() ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("Takip edilen dünya sayısı: ", NamedTextColor.GRAY)
                .append(Component.text(tracker.getTrackedWorldCount(), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Toplam takip edilen chunk: ", NamedTextColor.GRAY)
                .append(Component.text(tracker.getTotalTrackedChunks(), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Temizlik aralığı: ", NamedTextColor.GRAY)
                .append(Component.text(config.getCleanupIntervalMinutes() + " dakika", NamedTextColor.AQUA)));
        return Command.SINGLE_SUCCESS;
    }

    private int executeRun(CommandSender sender) {
        sender.sendMessage(Component.text("Manuel temizlik başlatılıyor...", NamedTextColor.YELLOW));
        scheduler.triggerManualCleanup();
        return Command.SINGLE_SUCCESS;
    }

    private int executePause(CommandSender sender) {
        if (scheduler.isPaused()) {
            sender.sendMessage(Component.text("Temizlik zaten duraklatılmış.", NamedTextColor.YELLOW));
        } else {
            scheduler.pause();
            sender.sendMessage(Component.text("Temizlik zamanlayıcı duraklatıldı.", NamedTextColor.GREEN));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int executeResume(CommandSender sender) {
        if (!scheduler.isPaused()) {
            sender.sendMessage(Component.text("Temizlik zaten çalışıyor.", NamedTextColor.YELLOW));
        } else {
            scheduler.resume();
            sender.sendMessage(Component.text("Temizlik zamanlayıcı devam ettirildi.", NamedTextColor.GREEN));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int executeStats(CommandSender sender) {
        CleanerTask task = scheduler.getCleanerTask();
        sender.sendMessage(Component.text("=== AtomCleaner İstatistikler ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Toplam tarandı: ", NamedTextColor.GRAY)
                .append(Component.text(task.getTotalScanned(), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Toplam silindi: ", NamedTextColor.GRAY)
                .append(Component.text(task.getTotalDeleted(), NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("Toplam korundu: ", NamedTextColor.GRAY)
                .append(Component.text(task.getTotalProtected(), NamedTextColor.YELLOW)));
        return Command.SINGLE_SUCCESS;
    }

    private int executeSave(CommandSender sender) {
        sender.sendMessage(Component.text("Kalıcılık verisi kaydediliyor...", NamedTextColor.YELLOW));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            persistenceManager.saveAll(tracker.getAllWorldData(), config.getMinTimeThresholdMs());
            sender.sendMessage(Component.text("Kalıcılık verisi başarıyla kaydedildi.", NamedTextColor.GREEN));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int executeReload(CommandSender sender) {
        sender.sendMessage(Component.text("Yapılandırma yeniden yükleniyor...", NamedTextColor.YELLOW));
        plugin.reloadConfig();
        sender.sendMessage(Component.text("Yapılandırma yeniden yüklendi. Not: Yeni ayarların tam etkisi için sunucuyu yeniden başlatın.", NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private int executeWhitelistAdd(CommandSender sender, String chunkArg) {
        // Expected format: world:x:z
        if (!validateChunkArg(chunkArg)) {
            sender.sendMessage(Component.text("Geçersiz format. Kullanım: /atomcleaner whitelist add <dünya:x:z>", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        config.addWhitelistedChunk(chunkArg);
        sender.sendMessage(Component.text("Chunk beyaz listeye eklendi: " + chunkArg, NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private int executeWhitelistRemove(CommandSender sender, String chunkArg) {
        if (!validateChunkArg(chunkArg)) {
            sender.sendMessage(Component.text("Geçersiz format. Kullanım: /atomcleaner whitelist remove <dünya:x:z>", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        config.removeWhitelistedChunk(chunkArg);
        sender.sendMessage(Component.text("Chunk beyaz listeden kaldırıldı: " + chunkArg, NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private boolean validateChunkArg(String arg) {
        if (arg == null || arg.isEmpty()) return false;
        String[] parts = arg.split(":");
        if (parts.length != 3) return false;
        try {
            Integer.parseInt(parts[1].trim());
            Integer.parseInt(parts[2].trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
