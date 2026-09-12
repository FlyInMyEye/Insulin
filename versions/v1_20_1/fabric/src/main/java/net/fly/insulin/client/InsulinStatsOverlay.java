package net.fly.insulin.client;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fly.insulin.InsulinCommon;
import net.fly.insulin.cache.ArtifactStore;
import net.fly.insulin.cache.DiskArtifactStore;
import net.minecraft.network.chat.Component;

public final class InsulinStatsOverlay {

    private InsulinStatsOverlay() {
    }

    public static void initialize() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("insulin")
                .then(ClientCommandManager.literal("stats")
                    .then(ClientCommandManager.literal("on").executes(context -> setEnabled(
                        context.getSource(),
                        true
                    )))
                    .then(ClientCommandManager.literal("off").executes(context -> setEnabled(
                        context.getSource(),
                        false
                    )))
                )
                .then(ClientCommandManager.literal("storage")
                    .then(ClientCommandManager.literal("stats").executes(context -> storageStats(context.getSource())))
                    .then(ClientCommandManager.literal("wipe").executes(context -> wipeStorage(context.getSource()))))
        ));
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> StatsDisplay.render(graphics));
    }

    private static int setEnabled(FabricClientCommandSource source, boolean enabled) {
        InsulinCommon.stats().setEnabled(enabled);
        InsulinCommon.pipeline().setEnabled(enabled);
        InsulinCommon.serverStats().setEnabled(enabled);
        DiskArtifactStore disk = InsulinCommon.disk();
        if (disk != null) {
            disk.resetLookupStats();
        }
        source.sendFeedback(Component.translatable(
            enabled ? "message.insulin.stats.enabled" : "message.insulin.stats.disabled"
        ));
        return 1;
    }

    private static int storageStats(FabricClientCommandSource source) {
        ArtifactStore.Snapshot ram = InsulinCommon.artifacts().snapshot();
        DiskArtifactStore disk = InsulinCommon.disk();
        DiskArtifactStore.Snapshot snapshot = disk == null ? new DiskArtifactStore.Snapshot(0L, 0L) : disk.snapshot();
        source.sendFeedback(Component.literal(String.format(
            java.util.Locale.ROOT,
            "Insulin storage: RAM %,d sections / %.1f MiB, disk %,d sections / %.1f MiB",
            ram.sections(), ram.currentBytes() / 1048576.0D, snapshot.sections(), snapshot.bytes() / 1048576.0D
        )));
        return 1;
    }

    private static int wipeStorage(FabricClientCommandSource source) {
        InsulinCommon.wipeStorage();
        source.getClient().levelRenderer.allChanged();
        source.sendFeedback(Component.literal("Insulin storage wiped and loaded chunks scheduled for rebuild"));
        return 1;
    }
}
