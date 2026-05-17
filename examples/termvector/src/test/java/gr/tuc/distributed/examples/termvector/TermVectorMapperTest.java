package gr.tuc.distributed.examples.termvector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TermVectorMapperTest {

    private TermVectorMapper mapper;
    private List<String[]> emitted;

    @BeforeEach
    void setUp() {
        mapper  = new TermVectorMapper();
        emitted = new ArrayList<>();
    }

    @Test
    void emitsHostAndTermVectorEntries() {
        mapper.map("site1.txt", "Hello World", (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(2, emitted.size());
        assertTrue(emitted.stream().allMatch(p -> p[0].equals("site1.txt")));
        assertTrue(emitted.stream().anyMatch(p -> p[1].equals("hello:1")));
        assertTrue(emitted.stream().anyMatch(p -> p[1].equals("world:1")));
    }

    @Test
    void usesInputKeyAsHost() {
        mapper.map("hosts/example.com/page1.txt", "data", (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals("hosts/example.com/page1.txt", emitted.get(0)[0]);
    }

    @Test
    void stripsNonAlphanumericCharacters() {
        mapper.map("doc.txt", "hello, world!", (k, v) -> emitted.add(new String[]{k, v}));

        assertTrue(emitted.stream().anyMatch(p -> p[1].equals("hello:1")));
        assertTrue(emitted.stream().anyMatch(p -> p[1].equals("world:1")));
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
        mapper.map("doc.txt", "HELLO Hello", (k, v) -> emitted.add(new String[]{k, v}));
        assertTrue(emitted.stream().allMatch(p -> p[1].equals("hello:1")));
        assertEquals(2, emitted.size());
    }
}
