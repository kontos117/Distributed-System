package gr.tuc.distributed.examples.invertedindex;

import gr.tuc.distributed.common.api.Reducer;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Inverted Index Reducer.
 *
 * Input : (word, [docId1, docId2, docId1, ...])
 * Output: (word, "docId1,docId2,...") — deduplicated and sorted.
 *
 * Registered via ServiceLoader in:
 *   META-INF/services/gr.tuc.distributed.common.api.Reducer
 */
public class InvertedIndexReducer implements Reducer {

    @Override
    public void reduce(String key, List<String> values, BiConsumer<String, String> emit) {
        String docList = values.stream()
                .map(String::trim)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
        emit.accept(key, docList);
    }
}
