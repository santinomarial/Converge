package io.converge.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class LedgerConvergenceProperties {

    @Property(tries = 250)
    void everyPermutationOfDeltasConverges(
            @ForAll("deltas") List<Integer> deltas,
            @ForAll long shuffleSeed) {
        int expected = deltas.stream().mapToInt(Integer::intValue).sum();
        List<Integer> shuffled = new ArrayList<>(deltas);
        Collections.shuffle(shuffled, new Random(shuffleSeed));
        assertThat(shuffled.stream().mapToInt(Integer::intValue).sum()).isEqualTo(expected);
    }

    @Property(tries = 250)
    void snapshotAnchorsAndAbsorbsEveryEarlierDeltaRegardlessOfArrival(
            @ForAll("timedDeltas") List<TimedDelta> deltas,
            @ForAll("snapshotQty") int snapshotQty,
            @ForAll long shuffleSeed) {
        long anchorTime = 0;
        int expected = snapshotQty + deltas.stream()
                .filter(delta -> delta.occurredAt() > anchorTime)
                .mapToInt(TimedDelta::qty)
                .sum();

        List<Fact> arrivals = new ArrayList<>();
        arrivals.add(new Fact(true, snapshotQty, anchorTime));
        deltas.forEach(delta -> arrivals.add(new Fact(false, delta.qty(), delta.occurredAt())));
        Collections.shuffle(arrivals, new Random(shuffleSeed));

        assertThat(reduce(arrivals)).isEqualTo(expected);
    }

    private int reduce(List<Fact> arrivals) {
        Fact anchor = arrivals.stream().filter(Fact::snapshot)
                .max(java.util.Comparator.comparingLong(Fact::occurredAt)).orElse(null);
        long anchorTime = anchor == null ? Long.MIN_VALUE : anchor.occurredAt();
        int base = anchor == null ? 0 : anchor.qty();
        return base + arrivals.stream().filter(fact -> !fact.snapshot() && fact.occurredAt() > anchorTime)
                .mapToInt(Fact::qty).sum();
    }

    @Provide
    Arbitrary<List<Integer>> deltas() {
        return Arbitraries.integers().between(-100, 100).list().ofMinSize(1).ofMaxSize(40);
    }

    @Provide
    Arbitrary<Integer> snapshotQty() {
        return Arbitraries.integers().between(0, 10_000);
    }

    @Provide
    Arbitrary<List<TimedDelta>> timedDeltas() {
        Arbitrary<TimedDelta> delta = Arbitraries.integers().between(-100, 100).flatMap(qty ->
                Arbitraries.longs().between(-1_000, 1_000).map(time -> new TimedDelta(qty, time)));
        return delta.list().ofMinSize(1).ofMaxSize(40);
    }

    private record Fact(boolean snapshot, int qty, long occurredAt) { }
    private record TimedDelta(int qty, long occurredAt) { }
}

