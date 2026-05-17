package gr.tuc.distributed.examples.termvector;

import gr.tuc.distributed.common.api.Mapper;

import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Term Vector Mapper.
 *
 * Determines the most frequent words per host/document, useful for
 * analysing a document's relevance to a search query.
 *
 * Input : one line of text from a document identified by {@code inputKey}.
 * Output: (host, "word:1") — a term-vector entry for each token.
 *
 * The {@code inputKey} (MinIO object key) is used as the host/document
 * identifier. Each word occurrence emits a count of 1; the reducer
 * aggregates, filters, and sorts.
 *
 * Registered via ServiceLoader in:
 *   META-INF/services/gr.tuc.distributed.common.api.Mapper
 */
public class TermVectorMapper implements Mapper {

    private static final Pattern NON_ALPHA = Pattern.compile("[^a-zA-Z0-9']");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public void map(String inputKey, String line, BiConsumer<String, String> emit) {
        if (line == null || line.isBlank()) return;

        // Use the inputKey (MinIO object key) as the host/document identifier
        String host = inputKey;

        for (String token : WHITESPACE.split(line.trim())) {
            String word = NON_ALPHA.matcher(token).replaceAll("").toLowerCase();
            if (!word.isEmpty()) {
                // Emit (host, "word:1") — the reducer will aggregate counts per word
                emit.accept(host, word + ":1");
            }
        }
    }
}
