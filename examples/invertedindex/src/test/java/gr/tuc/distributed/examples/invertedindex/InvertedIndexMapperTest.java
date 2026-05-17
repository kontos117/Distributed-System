package gr.tuc.distributed.examples.invertedindex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InvertedIndexMapperTest {

    private InvertedIndexMapper mapper;
    private List<String[]> emitted;

    @BeforeEach
    void setUp() {
        mapper  = new InvertedIndexMapper();
        emitted = new ArrayList<>();
    }

    @Test
    void emitsWordDocIdPairs() {
        mapper.map("doc1.txt", "Hello World", (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(2, emitted.size());
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("hello") && p[1].equals("doc1.txt")));
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("world") && p[1].equals("doc1.txt")));
    }

    @Test
    void usesInputKeyAsDocId() {
        mapper.map("books/chapter3.txt", "distributed systems", (k, v) -> emitted.add(new String[]{k, v}));

        assertTrue(emitted.stream().allMatch(p -> p[1].equals("books/chapter3.txt")));
    }

    @Test
    void stripsNonAlphanumericCharacters() {
        mapper.map("doc.txt", "hello, world!", (k, v) -> emitted.add(new String[]{k, v}));

        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("hello")));
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("world")));
    }

    @Test
    void ignoresBlankLines() {
        mapper.map("doc.txt", "   ", (k, v) -> emitted.add(new String[]{k, v}));
        assertTrue(emitted.isEmpty());
    }

    @Test
    void ignoresNullLine() {
        mapper.map("doc.txt", null, (k, v) -> emitted.add(new String[]{k, v}));
        assertTrue(emitted.isEmpty());
    }

    @Test
    void lowercasesTokens() {
        mapper.map("doc.txt", "HELLO Hello hello", (k, v) -> emitted.add(new String[]{k, v}));
        assertTrue(emitted.stream().allMatch(p -> p[0].equals("hello")));
        assertEquals(3, emitted.size());
    }
}
