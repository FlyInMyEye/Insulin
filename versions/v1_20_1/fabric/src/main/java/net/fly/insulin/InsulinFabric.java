package net.fly.insulin;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fly.insulin.backend.BackendIdentity;
import net.fly.insulin.backend.SodiumMeshRestorer;
import net.fly.insulin.cache.CacheNamespace;
import net.fly.insulin.cache.ResourceChecksum;
import net.fly.insulin.client.InsulinStatsOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public final class InsulinFabric implements ClientModInitializer {

    public static final String MODID = "insulin";
    private ClientLevel level;

    @Override
    public void onInitializeClient() {
        InsulinCommon.init(new BackendIdentity("sodium", "0.5.13+mc1.20.1", 8), new SodiumMeshRestorer(),
            FabricLoader.getInstance().getGameDir());
        InsulinStatsOverlay.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.level != client.level) {
                this.level = client.level;
                InsulinCommon.switchNamespace(CacheNamespace.forLevel(client, this.level));
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> InsulinCommon.close());
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
                @Override
                public ResourceLocation getFabricId() {
                    return new ResourceLocation(MODID, "cache_invalidation");
                }

                @Override
                public void onResourceManagerReload(ResourceManager manager) {
                    InsulinCommon.updateResourceChecksum(ResourceChecksum.compute(manager));
                }
            }
        );
    }
}
