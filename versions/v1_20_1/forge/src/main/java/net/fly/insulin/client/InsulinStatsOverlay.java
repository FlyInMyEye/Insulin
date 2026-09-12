package net.fly.insulin.client;

import net.fly.insulin.InsulinCommon;
import net.fly.insulin.cache.ArtifactStore;
import net.fly.insulin.cache.DiskArtifactStore;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class InsulinStatsOverlay {

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("insulin")
                .then(Commands.literal("stats")
                    .then(Commands.literal("on").executes(context -> setEnabled(context.getSource(), true)))
                    .then(Commands.literal("off").executes(context -> setEnabled(context.getSource(), false))))
                .then(Commands.literal("storage")
                    .then(Commands.literal("stats").executes(context -> storageStats(context.getSource())))
                    .then(Commands.literal("wipe").executes(context -> wipeStorage(context.getSource()))))
        );
    }

    @SubscribeEvent
    public void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (InsulinCommon.stats().isEnabled()) {
            event.getLeft().addAll(StatsDisplay.lines());
            event.getRight().addAll(StatsDisplay.invalidationLines());
        }
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        StatsDisplay.render(event.getGuiGraphics());
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        InsulinCommon.stats().setEnabled(enabled);
        InsulinCommon.pipeline().setEnabled(enabled);
        InsulinCommon.serverStats().setEnabled(enabled);
        DiskArtifactStore disk = InsulinCommon.disk();
        if (disk != null) {
            disk.resetLookupStats();
        }
        source.sendSuccess(
            () -> Component.translatable(
                enabled ? "message.insulin.stats.enabled" : "message.insulin.stats.disabled"
            ),
            false
        );
        return 1;
    }

    private static int storageStats(CommandSourceStack source) {
        ArtifactStore.Snapshot ram = InsulinCommon.artifacts().snapshot();
        DiskArtifactStore disk = InsulinCommon.disk();
        DiskArtifactStore.Snapshot snapshot = disk == null ? new DiskArtifactStore.Snapshot(0L, 0L) : disk.snapshot();
        source.sendSuccess(
            () -> Component.literal(String.format(
                java.util.Locale.ROOT,
                "Insulin storage: RAM %,d sections / %.1f MiB, disk %,d sections / %.1f MiB",
                ram.sections(), ram.currentBytes() / 1048576.0D, snapshot.sections(), snapshot.bytes() / 1048576.0D
            )),
            false
        );
        return 1;
    }

    private static int wipeStorage(CommandSourceStack source) {
        InsulinCommon.wipeStorage();
        Minecraft.getInstance().levelRenderer.allChanged();
        source.sendSuccess(() -> Component.literal("Insulin storage wiped and loaded chunks scheduled for rebuild"), false);
        return 1;
    }
}
