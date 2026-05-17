package gr.tuc.distributed.examples.invertedindex;

import gr.tuc.distributed.common.api.Mapper;

import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Inverted Index Mapper.
 *
 * Input : one line of text from a document identified by {@code inputKey}.
 * Output: (word, docId) for every whitespace-separated token, lowercased,
 *          with non-alphanumeric characters stripped.
 *
 * Each word is emitted once per line; the reducer handles deduplication
 * across the entire document.
 *
 * Registered via ServiceLoader in:
 *   META-INF/services/gr.tuc.distributed.common.api.Mapper
 */
public class InvertedIndexMapper implements Mapper {

    private static final Pattern NON_ALPHA = Pattern.compile("[^a-zA-Z0-9']");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public void map(String inputKey, String line, BiConsumer<String, String> emit) {
        if (line == null || line.isBlank()) return;

        // Use the inputKey (MinIO object key) as the document identifier
        String docId = inputKey;

        for (String token : WHITESPACE.split(line.trim())) {
            String word = NON_ALPHA.matcher(token).replaceAll("").toLowerCase();
            if (!word.isEmpty()) {
                emit.accept(word, docId);
            }
        }
    }
}
