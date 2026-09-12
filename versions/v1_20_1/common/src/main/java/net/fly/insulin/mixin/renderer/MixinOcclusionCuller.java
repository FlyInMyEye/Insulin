package net.fly.insulin.mixin.renderer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.fly.insulin.renderer.DoppelgangerCuller;
import net.fly.insulin.renderer.DoppelgangerSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = OcclusionCuller.class, remap = false)
public abstract class MixinOcclusionCuller implements DoppelgangerCuller {

    @Unique
    private static final float INSULIN_SECTION_FRUSTUM_RADIUS = 9.125F;

    @Unique
    private final Set<RenderSection> insulin$disconnectedSections = Collections.newSetFromMap(new IdentityHashMap<>());

    @Override
    public void insulin$trackDoppelganger(RenderSection section) {
        this.insulin$disconnectedSections.add(section);
    }

    @Inject(method = "findVisible", at = @At("RETURN"), remap = false)
    private void insulin$visitDisconnectedDoppelgangers(
        OcclusionCuller.Visitor visitor,
        Viewport viewport,
        float searchDistance,
        boolean useOcclusionCulling,
        int frame,
        CallbackInfo callback
    ) {
        Iterator<RenderSection> iterator = this.insulin$disconnectedSections.iterator();
        while (iterator.hasNext()) {
            RenderSection section = iterator.next();
            DoppelgangerSection doppelganger = (DoppelgangerSection) section;
            if (section.isDisposed() || !doppelganger.insulin$needsGraphFallback()) {
                iterator.remove();
                continue;
            }

            if (section.getLastVisibleFrame() == frame) {
                doppelganger.insulin$setGraphFallback(false);
                iterator.remove();
                continue;
            }

            if (insulin$isWithinRenderDistance(viewport.getTransform(), section, searchDistance)
                && viewport.isBoxVisible(
                    section.getCenterX(),
                    section.getCenterY(),
                    section.getCenterZ(),
                    INSULIN_SECTION_FRUSTUM_RADIUS,
                    INSULIN_SECTION_FRUSTUM_RADIUS,
                    INSULIN_SECTION_FRUSTUM_RADIUS
                )) {
                section.setLastVisibleFrame(frame);
                visitor.visit(section, true);
            }
        }
    }

    @Unique
    private static boolean insulin$isWithinRenderDistance(
        CameraTransform camera,
        RenderSection section,
        float maximumDistance
    ) {
        int originX = section.getOriginX() - camera.intX;
        int originY = section.getOriginY() - camera.intY;
        int originZ = section.getOriginZ() - camera.intZ;
        float x = insulin$nearestToZero(originX, originX + 16) - camera.fracX;
        float y = insulin$nearestToZero(originY, originY + 16) - camera.fracY;
        float z = insulin$nearestToZero(originZ, originZ + 16) - camera.fracZ;
        return x * x + z * z < maximumDistance * maximumDistance && Math.abs(y) < maximumDistance;
    }

    @Unique
    private static int insulin$nearestToZero(int minimum, int maximum) {
        if (minimum > 0) {
            return minimum;
        }
        if (maximum < 0) {
            return maximum;
        }
        return 0;
    }
}
