package gr.tuc.distributed.examples.wordcount;

import gr.tuc.distributed.common.api.Reducer;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Word Count Reducer.
 *
 * Input : (word, ["1", "1", ...])
 * Output: (word, total_count)
 *
 * Registered via ServiceLoader in:
 *   META-INF/services/gr.tuc.distributed.worker.runner.Reducer
 */
public class WordCountReducer implements Reducer {

    @Override
    public void reduce(String key, List<String> values, BiConsumer<String, String> emit) {
        long count = values.stream()
                .mapToLong(v -> {
                    try { return Long.parseLong(v.trim()); }
                    catch (NumberFormatException e) { return 0L; }
                })
                .sum();
        emit.accept(key, String.valueOf(count));
    }
}
