package net.fly.insulin.mixin.renderer;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import net.fly.insulin.renderer.DoppelgangerSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = RenderSection.class, remap = false)
public abstract class MixinRenderSection implements DoppelgangerSection {

    @Unique
    private boolean insulin$doppelganger;

    @Unique
    private boolean insulin$graphFallback;

    @Unique
    private int insulin$visibleFrame = -1;

    @Override
    public boolean insulin$isDoppelganger() {
        return this.insulin$doppelganger;
    }

    @Override
    public void insulin$setDoppelganger(boolean doppelganger) {
        this.insulin$doppelganger = doppelganger;
    }

    @Override
    public boolean insulin$needsGraphFallback() {
        return this.insulin$graphFallback;
    }

    @Override
    public void insulin$setGraphFallback(boolean graphFallback) {
        this.insulin$graphFallback = graphFallback;
    }

    @Override
    public int insulin$getVisibleFrame() {
        return this.insulin$visibleFrame;
    }

    @Override
    public void insulin$setVisibleFrame(int frame) {
        this.insulin$visibleFrame = frame;
    }
}
