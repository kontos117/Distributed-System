package gr.tuc.distributed.examples.termvector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TermVectorReducerTest {

    private TermVectorReducer reducer;
    private List<String[]> emitted;

    @BeforeEach
    void setUp() {
        reducer = new TermVectorReducer();
        emitted = new ArrayList<>();
    }

    @Test
    void emitsOneLinePerWord() {
        reducer.reduce("site.com", List.of("the:1", "the:1", "the:1", "fox:1", "fox:1", "dog:1", "dog:1"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(3, emitted.size());
        // Sorted descending by count, then alphabetically
        assertEquals("the", emitted.get(0)[0]);
        assertEquals("3", emitted.get(0)[1]);
        assertEquals("dog", emitted.get(1)[0]);
        assertEquals("2", emitted.get(1)[1]);
        assertEquals("fox", emitted.get(2)[0]);
        assertEquals("2", emitted.get(2)[1]);
    }

    @Test
    void filtersWordsBelowCutoff() {
        // Only "hello" reaches the cut-off of 2; "world" appears once
        reducer.reduce("host", List.of("hello:1", "hello:1", "world:1"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(1, emitted.size());
        assertEquals("hello", emitted.get(0)[0]);
        assertEquals("2", emitted.get(0)[1]);
    }

    @Test
    void emitsNothingWhenAllBelowCutoff() {
        reducer.reduce("host", List.of("a:1", "b:1", "c:1"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertTrue(emitted.isEmpty());
    }

    @Test
    void handlesMalformedEntries() {
        reducer.reduce("host", List.of("hello:1", "hello:1", "badformat", ":1"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(1, emitted.size());
        assertEquals("hello", emitted.get(0)[0]);
        assertEquals("2", emitted.get(0)[1]);
    }

    @Test
    void emptyValueList() {
        reducer.reduce("host", List.of(),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertTrue(emitted.isEmpty());
    }
}
