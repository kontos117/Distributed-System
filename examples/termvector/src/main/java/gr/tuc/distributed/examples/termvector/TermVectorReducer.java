package gr.tuc.distributed.examples.termvector;

import gr.tuc.distributed.common.api.Reducer;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Term Vector Reducer.
 *
 * Input : (host, ["word1:1", "word2:1", "word1:1", ...])
 * Output: (word, count) — one line per word, sorted descending by count,
 *          with words below a minimum frequency cut-off removed.
 *
 * The cut-off is set to 2 by default (words appearing only once are discarded).
 *
 * Registered via ServiceLoader in:
 *   META-INF/services/gr.tuc.distributed.common.api.Reducer
 */
public class TermVectorReducer implements Reducer {

    /** Minimum frequency a word must have to be included in the output. */
    private static final int FREQUENCY_CUTOFF = 2;

    @Override
    public void reduce(String key, List<String> values, BiConsumer<String, String> emit) {
        // Aggregate word counts from all "word:count" entries
        Map<String, Long> wordCounts = new HashMap<>();

        for (String entry : values) {
            String trimmed = entry.trim();
            int colonIdx = trimmed.lastIndexOf(':');
            if (colonIdx <= 0) continue;

            String word = trimmed.substring(0, colonIdx);
            long count;
            try {
                count = Long.parseLong(trimmed.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                count = 0L;
            }
            wordCounts.merge(word, count, Long::sum);
        }

        // Filter by cut-off, sort descending by count, emit one line per word
        wordCounts.entrySet().stream()
                .filter(e -> e.getValue() >= FREQUENCY_CUTOFF)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> emit.accept(e.getKey(), String.valueOf(e.getValue())));
    }
}
