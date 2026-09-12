package net.fly.insulin.diagnostics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DoppelgangerStats {

    private final AtomicInteger rendered = new AtomicInteger();
    private final AtomicInteger ready = new AtomicInteger();
    private final AtomicLong shown = new AtomicLong();
    private final AtomicLong authoritative = new AtomicLong();
    private final AtomicLong authoritativeNanos = new AtomicLong();
    private final AtomicLong renderSubmitted = new AtomicLong();
    private final AtomicLong renderSubmittedNanos = new AtomicLong();
    private final AtomicLong gpuCompleted = new AtomicLong();
    private final AtomicLong gpuCompletedNanos = new AtomicLong();

    public int rendered() {
        return this.rendered.get();
    }

    public void setRendered(int rendered) {
        this.rendered.set(rendered);
    }

    public int ready() {
        return this.ready.get();
    }

    public void setReady(int ready) {
        this.ready.set(ready);
    }

    public long shown() {
        return this.shown.get();
    }

    public void recordShown() {
        this.shown.incrementAndGet();
    }

    public void recordAuthoritative(long elapsedNanos) {
        this.authoritative.incrementAndGet();
        this.authoritativeNanos.addAndGet(elapsedNanos);
    }

    public void recordRenderSubmitted(long elapsedNanos) {
        this.renderSubmitted.incrementAndGet();
        this.renderSubmittedNanos.addAndGet(elapsedNanos);
    }

    public void recordGpuCompleted(long elapsedNanos) {
        this.gpuCompleted.incrementAndGet();
        this.gpuCompletedNanos.addAndGet(elapsedNanos);
    }

    public void reset() {
        this.rendered.set(0);
        this.ready.set(0);
        this.shown.set(0L);
        this.authoritative.set(0L);
        this.authoritativeNanos.set(0L);
        this.renderSubmitted.set(0L);
        this.renderSubmittedNanos.set(0L);
        this.gpuCompleted.set(0L);
        this.gpuCompletedNanos.set(0L);
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.authoritative.get(),
            this.authoritativeNanos.get(),
            this.renderSubmitted.get(),
            this.renderSubmittedNanos.get(),
            this.gpuCompleted.get(),
            this.gpuCompletedNanos.get()
        );
    }

    public record Snapshot(
        long authoritative,
        long authoritativeNanos,
        long renderSubmitted,
        long renderSubmittedNanos,
        long gpuCompleted,
        long gpuCompletedNanos
    ) {

        public double averageAuthoritativeNanos() {
            return this.authoritative == 0L ? 0.0D : (double) this.authoritativeNanos / this.authoritative;
        }

        public double averageRenderSubmittedNanos() {
            return this.renderSubmitted == 0L ? 0.0D : (double) this.renderSubmittedNanos / this.renderSubmitted;
        }

        public double averageGpuCompletedNanos() {
            return this.gpuCompleted == 0L ? 0.0D : (double) this.gpuCompletedNanos / this.gpuCompleted;
        }
    }
}
