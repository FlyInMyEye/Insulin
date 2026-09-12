package net.fly.insulin.mixin.renderer;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;
import net.fly.insulin.renderer.DoppelgangerSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VisibleChunkCollector.class, remap = false)
public abstract class MixinVisibleChunkCollector {

    @Inject(method = "visit", at = @At("HEAD"), remap = false)
    private void insulin$recordVisibleSection(RenderSection section, boolean visible, CallbackInfo callback) {
        ((DoppelgangerSection) section).insulin$setVisibleFrame(visible ? section.getLastVisibleFrame() : -1);
    }
}
