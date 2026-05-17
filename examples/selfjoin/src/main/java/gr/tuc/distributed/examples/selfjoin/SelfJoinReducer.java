package gr.tuc.distributed.examples.selfjoin;

import gr.tuc.distributed.common.api.Reducer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Self-Join Reducer — candidate generation for the a-priori data mining algorithm.
 *
 * Input : (prefix, [val1, val2, ..., valj]) — where prefix is a (k-1)-element key
 *         and vals are the last elements from k-element candidate sets.
 * Output: (candidate, pair) — one line per (k+1)-element candidate,
 *         where candidate is the full "e1,e2,...,ek-1" prefix and pair
 *         is the two appended elements "vali,valj".
 *
 * Registered via ServiceLoader in:
 *   META-INF/services/gr.tuc.distributed.common.api.Reducer
 */
public class SelfJoinReducer implements Reducer {

    @Override
    public void reduce(String key, List<String> values, BiConsumer<String, String> emit) {
        if (values.size() < 2) return;

        // Deduplicate and sort the values
        List<String> sorted = new ArrayList<>();
        for (String v : values) {
            String trimmed = v.trim();
            if (!trimmed.isEmpty() && !sorted.contains(trimmed)) {
                sorted.add(trimmed);
            }
        }
        Collections.sort(sorted);

        if (sorted.size() < 2) return;

        // Generate (k+1)-sized candidates, emit each as its own line
        for (int i = 0; i < sorted.size() - 1; i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                String candidate = key + "," + sorted.get(i) + "," + sorted.get(j);
                emit.accept(candidate, "1");
            }
        }
    }
}
