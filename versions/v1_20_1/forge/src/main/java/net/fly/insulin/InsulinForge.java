package net.fly.insulin;

import net.fly.insulin.backend.BackendIdentity;
import net.fly.insulin.backend.EmbeddiumMeshRestorer;
import net.fly.insulin.cache.CacheNamespace;
import net.fly.insulin.cache.ResourceChecksum;
import net.fly.insulin.client.InsulinStatsOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.common.Mod;

@Mod("insulin")
public final class InsulinForge {

    public static final String MODID = "insulin";
    private ClientLevel level;

    public InsulinForge() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            InsulinCommon.init(new BackendIdentity("embeddium", "0.3.31+mc1.20.1", 5),
                new EmbeddiumMeshRestorer(), FMLPaths.GAMEDIR.get());
            IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
            modBus.addListener(this::registerReloadListeners);
            MinecraftForge.EVENT_BUS.addListener(this::clientTick);
            MinecraftForge.EVENT_BUS.register(new InsulinStatsOverlay());
        }
    }

    private void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager ->
            InsulinCommon.updateResourceChecksum(ResourceChecksum.compute(manager)));
    }

    private void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClientLevel current = Minecraft.getInstance().level;
        if (this.level != current) {
            this.level = current;
            InsulinCommon.switchNamespace(CacheNamespace.forLevel(Minecraft.getInstance(), this.level));
        }
    }
}
