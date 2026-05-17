package gr.tuc.distributed.examples.wordcount;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WordCountMapperTest {

    private WordCountMapper mapper;
    private List<String[]> emitted;

    @BeforeEach
    void setUp() {
        mapper  = new WordCountMapper();
        emitted = new ArrayList<>();
    }

    @Test
    void splitsLineIntoWords() {
        mapper.map("file.txt", "Hello World hello", (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(3, emitted.size());
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("hello") && p[1].equals("1")));
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("world") && p[1].equals("1")));
    }

    @Test
    void stripsLeadingTrailingPunctuation() {
        mapper.map("file.txt", "hello, world!", (k, v) -> emitted.add(new String[]{k, v}));

        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("hello")));
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("world")));
    }

    @Test
    void ignoresBlankLines() {
        mapper.map("file.txt", "   ", (k, v) -> emitted.add(new String[]{k, v}));
        assertTrue(emitted.isEmpty());
    }

    @Test
    void ignoresNullLine() {
        mapper.map("file.txt", null, (k, v) -> emitted.add(new String[]{k, v}));
        assertTrue(emitted.isEmpty());
    }

    @Test
    void lowercasesTokens() {
        mapper.map("file.txt", "TUC TUC tuc", (k, v) -> emitted.add(new String[]{k, v}));
        assertTrue(emitted.stream().allMatch(p -> p[0].equals("tuc")));
        assertEquals(3, emitted.size());
    }
}
