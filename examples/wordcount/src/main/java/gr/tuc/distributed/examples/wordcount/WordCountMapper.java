package gr.tuc.distributed.examples.wordcount;

import gr.tuc.distributed.common.api.Mapper;

import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Word Count Mapper.
 *
 * Input : one line of text.
 * Output: (word, "1") for every whitespace-separated token, lowercased,
 *          with leading/trailing punctuation stripped.
 *
 * Registered via ServiceLoader in:
 *   META-INF/services/gr.tuc.distributed.worker.runner.Mapper
 */
public class WordCountMapper implements Mapper {

    private static final Pattern PUNCTUATION = Pattern.compile("[^a-zA-Z0-9']");
    private static final Pattern WHITESPACE  = Pattern.compile("\\s+");

    @Override
    public void map(String inputKey, String line, BiConsumer<String, String> emit) {
        if (line == null || line.isBlank()) return;

        for (String token : WHITESPACE.split(line.trim())) {
            String word = PUNCTUATION.matcher(token).replaceAll("").toLowerCase();
            if (!word.isEmpty()) {
                emit.accept(word, "1");
            }
        }
    }
}
