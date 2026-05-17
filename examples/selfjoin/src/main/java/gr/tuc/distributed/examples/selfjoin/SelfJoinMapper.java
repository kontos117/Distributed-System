package gr.tuc.distributed.examples.selfjoin;

import gr.tuc.distributed.common.api.Mapper;

import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * Self-Join Mapper — candidate generation for the a-priori data mining algorithm.
 *
 * Input : each line is a comma-separated, alphanumerically sorted candidate list
 *         of k elements: {@code "e1,e2,...,ek"}
 * Output: (prefix, lastElement) where prefix = "e1,e2,...,ek-1" and lastElement = "ek".
 *
 * This splits each k-element candidate into its (k-1)-element prefix key and
 * the last element as the value. The reducer then generates (k+1)-element
 * candidates by combining pairs of values that share the same prefix.
 *
 * Registered via ServiceLoader in:
 *   META-INF/services/gr.tuc.distributed.common.api.Mapper
 */
public class SelfJoinMapper implements Mapper {

    @Override
    public void map(String inputKey, String line, BiConsumer<String, String> emit) {
        if (line == null || line.isBlank()) return;

        String trimmed = line.trim();

        // Parse the comma-separated elements
        String[] elements = trimmed.split(",");
        for (int i = 0; i < elements.length; i++) {
            elements[i] = elements[i].trim();
        }

        if (elements.length < 2) {
            // Single-element sets can't be split into prefix + last
            return;
        }

        // prefix = first (k-1) elements; value = last element
        String prefix = String.join(",", Arrays.copyOfRange(elements, 0, elements.length - 1));
        String lastElement = elements[elements.length - 1];

        emit.accept(prefix, lastElement);
    }
}
