package gr.tuc.distributed.examples.invertedindex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InvertedIndexReducerTest {

    private InvertedIndexReducer reducer;
    private List<String[]> emitted;

    @BeforeEach
    void setUp() {
        reducer = new InvertedIndexReducer();
        emitted = new ArrayList<>();
    }

    @Test
    void deduplicatesDocIds() {
        reducer.reduce("hello", List.of("doc1.txt", "doc1.txt", "doc2.txt"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(1, emitted.size());
        assertEquals("hello", emitted.get(0)[0]);
        assertEquals("doc1.txt,doc2.txt", emitted.get(0)[1]);
    }

    @Test
    void sortsDocIds() {
        reducer.reduce("world", List.of("doc3.txt", "doc1.txt", "doc2.txt"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals("doc1.txt,doc2.txt,doc3.txt", emitted.get(0)[1]);
    }

    @Test
    void singleDocId() {
        reducer.reduce("fox", List.of("doc1.txt"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals("doc1.txt", emitted.get(0)[1]);
    }

    @Test
    void emptyValueList() {
        reducer.reduce("empty", List.of(),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(1, emitted.size());
        assertEquals("", emitted.get(0)[1]);
    }
}
