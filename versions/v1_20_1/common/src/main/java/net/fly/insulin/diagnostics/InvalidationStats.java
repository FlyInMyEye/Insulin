package net.fly.insulin.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

public final class InvalidationStats {

    private final ConcurrentMap<String, Accumulator> reasons = new ConcurrentHashMap<>();

    public void record(String reason, int sections, long bytes) {
        Accumulator accumulator = this.reasons.computeIfAbsent(reason, ignored -> new Accumulator());
        accumulator.count.increment();
        accumulator.sections.add(sections);
        accumulator.bytes.add(bytes);
    }

    public List<Snapshot> snapshot() {
        List<Snapshot> snapshots = new ArrayList<>(this.reasons.size());
        for (var entry : this.reasons.entrySet()) {
            Accumulator accumulator = entry.getValue();
            snapshots.add(new Snapshot(
                entry.getKey(),
                accumulator.count.sum(),
                accumulator.sections.sum(),
                accumulator.bytes.sum()
            ));
        }
        snapshots.sort(Comparator.comparing(Snapshot::reason));
        return snapshots;
    }

    private static final class Accumulator {

        private final LongAdder count = new LongAdder();
        private final LongAdder sections = new LongAdder();
        private final LongAdder bytes = new LongAdder();
    }

    public record Snapshot(String reason, long count, long sections, long bytes) {

    }
}
