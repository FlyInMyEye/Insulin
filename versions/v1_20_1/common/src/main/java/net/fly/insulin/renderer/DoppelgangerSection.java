package net.fly.insulin.renderer;

public interface DoppelgangerSection {

    boolean insulin$isDoppelganger();

    void insulin$setDoppelganger(boolean doppelganger);

    boolean insulin$needsGraphFallback();

    void insulin$setGraphFallback(boolean graphFallback);

    int insulin$getVisibleFrame();

    void insulin$setVisibleFrame(int frame);
}
