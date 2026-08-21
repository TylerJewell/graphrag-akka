package io.akka.graphrag.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Which levels there are to roll up, and in what order — SPEC-001 §3 rule 27. */
public final class Levels {

    private Levels() {}

    /**
     * Distinct levels, {@code -1} and nulls dropped, highest first. The sentinel is dropped
     * rather than sorted to the front because a community at level {@code -1} is one the
     * hierarchy never placed.
     */
    public static List<Integer> descending(List<Integer> levels) {
        TreeSet<Integer> distinct = new TreeSet<>(Comparator.reverseOrder());
        for (Integer level : levels) {
            if (level != null && level != -1) {
                distinct.add(level);
            }
        }
        return new ArrayList<>(distinct);
    }
}
