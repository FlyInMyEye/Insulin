package net.fly.insulin.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fly.insulin.InsulinCommon;
import net.fly.insulin.cache.ArtifactStore;
import net.fly.insulin.cache.DiskArtifactStore;
import net.fly.insulin.diagnostics.InvalidationStats;
import net.fly.insulin.diagnostics.MeshStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class StatsDisplay {

    private static final int BACKGROUND_COLOR = -1873784752;
    private static final int TEXT_COLOR = 14737632;
    private static final long DIAGNOSTIC_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static long lastDiagnosticLogNanos;

    private StatsDisplay() {
    }

    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!InsulinCommon.stats().isEnabled() || minecraft.options.renderDebug) {
            return;
        }

        Font font = minecraft.font;
        List<String> lines = lines();
        renderLines(graphics, font, lines, 2, false);
        renderLines(graphics, font, invalidationLines(), graphics.guiWidth() - 2, true);
        logDiagnostics();
    }

    public static List<String> lines() {
        MeshStats.Snapshot stats = InsulinCommon.stats().snapshot();
        ArtifactStore.Snapshot ram = InsulinCommon.artifacts().snapshot();
        DiskArtifactStore disk = InsulinCommon.disk();
        DiskArtifactStore.Snapshot diskSnapshot = disk == null ? new DiskArtifactStore.Snapshot(0L, 0L) : disk.snapshot();
        long lookups = stats.cacheHits() + stats.cacheMisses();
        double hitRate = lookups == 0L ? 0.0D : (double) stats.cacheHits() * 100.0D / lookups;
        List<String> lines = new ArrayList<>(12);
        lines.add(Component.translatable("gui.insulin.stats.title").getString());
        lines.add(String.format(
            Locale.ROOT,
            "%s  %.3f ms avg  (%,d)",
            Component.translatable("gui.insulin.stats.meshed").getString(),
            stats.averageMeshedNanos() / 1_000_000.0D,
            stats.meshedSections()
        ));
        lines.add(String.format(
            Locale.ROOT,
            "%s  %.3f ms avg  (%,d)",
            Component.translatable("gui.insulin.stats.ram_restore").getString(),
            stats.averageRamRestoreNanos() / 1_000_000.0D,
            stats.ramRestoreSections()
        ));
        lines.add(String.format(
            Locale.ROOT,
            "%s  %.3f ms avg  (%,d)",
            Component.translatable("gui.insulin.stats.disk_restore").getString(),
            stats.averageDiskRestoreNanos() / 1_000_000.0D,
            stats.diskRestoreSections()
        ));
        lines.add(String.format(
            Locale.ROOT,
            "%s  %.1f%%  (%,d hit / %,d miss)",
            Component.translatable("gui.insulin.stats.cache").getString(),
            hitRate,
            stats.cacheHits(),
            stats.cacheMisses()
        ));
        lines.add(String.format(
            Locale.ROOT,
            "  %s  %,d",
            Component.translatable("gui.insulin.stats.ram_hits").getString(),
            stats.ramHits()
        ));
        lines.add(String.format(
            Locale.ROOT,
            "  %s  %,d",
            Component.translatable("gui.insulin.stats.disk_hits").getString(),
            stats.diskHits()
        ));
        lines.add(String.format(
            Locale.ROOT,
            "Doppelgangers  %,d active  (%,d ready)",
            InsulinCommon.doppelgangers().rendered(),
            InsulinCommon.doppelgangers().ready()
        ));
        lines.add(String.format(
            Locale.ROOT,
            "%s  %,d",
            Component.translatable("gui.insulin.stats.ram_sections").getString(),
            ram.sections()
        ));
        lines.add(String.format(
            Locale.ROOT,
            "%s  %s / %s",
            Component.translatable("gui.insulin.stats.ram_cache").getString(),
            formatBytes(ram.currentBytes()),
            formatBytes(ram.maximumBytes())
        ));
        lines.add(String.format(Locale.ROOT, "Disk sections  %,d", diskSnapshot.sections()));
        lines.add(String.format(Locale.ROOT, "Disk cache  %s", formatBytes(diskSnapshot.bytes())));
        return lines;
    }

    private static void logDiagnostics() {
        long now = System.nanoTime();
        if (now - lastDiagnosticLogNanos < DIAGNOSTIC_LOG_INTERVAL_NANOS) {
            return;
        }

        lastDiagnosticLogNanos = now;
        var doppelgangers = InsulinCommon.doppelgangers().snapshot();
        DiskArtifactStore disk = InsulinCommon.disk();
        DiskArtifactStore.LookupSnapshot diskLookups = disk == null
            ? new DiskArtifactStore.LookupSnapshot(0L, 0L)
            : disk.lookupSnapshot();
        InsulinCommon.LOGGER.info(
            "Insulin diagnostics: doppelgangers shown={}, authoritative={} avg={}ms, renderSubmit={} avg={}ms, gpuComplete={} avg={}ms, diskLookupMissing={}, diskLookupMismatch={}",
            InsulinCommon.doppelgangers().shown(),
            doppelgangers.authoritative(),
            doppelgangers.averageAuthoritativeNanos() / 1_000_000.0D,
            doppelgangers.renderSubmitted(),
            doppelgangers.averageRenderSubmittedNanos() / 1_000_000.0D,
            doppelgangers.gpuCompleted(),
            doppelgangers.averageGpuCompletedNanos() / 1_000_000.0D,
            diskLookups.missing(),
            diskLookups.mismatches()
        );
    }

    public static List<String> invalidationLines() {
        List<InvalidationStats.Snapshot> invalidations = InsulinCommon.invalidations().snapshot();
        MeshStats.Snapshot stats = InsulinCommon.stats().snapshot();
        List<String> lines = new ArrayList<>(invalidations.size() + stats.bypasses().size() + 2);
        lines.add(Component.translatable("gui.insulin.stats.invalidations").getString());
        for (InvalidationStats.Snapshot invalidation : invalidations) {
            lines.add(String.format(
                Locale.ROOT,
                "%s  %,dx  %,d sec  %s",
                invalidation.reason(),
                invalidation.count(),
                invalidation.sections(),
                formatBytes(invalidation.bytes())
            ));
        }
        lines.add("Not cached");
        for (MeshStats.BypassSnapshot bypass : stats.bypasses()) {
            lines.add(String.format(Locale.ROOT, "%s  %,dx", bypass.reason(), bypass.count()));
        }
        return lines;
    }

    private static void renderLines(GuiGraphics graphics, Font font, List<String> lines, int x, boolean right) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int width = font.width(line);
            int lineX = right ? x - width : x;
            int y = 2 + font.lineHeight * index;
            graphics.fill(lineX - 1, y - 1, lineX + width + 1, y + font.lineHeight - 1, BACKGROUND_COLOR);
            graphics.drawString(font, line, lineX, y, TEXT_COLOR, false);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0D * 1024.0D));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0D);
        }
        return bytes + " B";
    }
}
