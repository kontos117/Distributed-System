package gr.tuc.distributed.examples.wordcount;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WordCountReducerTest {

    private WordCountReducer reducer;
    private List<String[]> emitted;

    @BeforeEach
    void setUp() {
        reducer = new WordCountReducer();
        emitted = new ArrayList<>();
    }

    @Test
    void sumsValues() {
        reducer.reduce("hello", List.of("1", "1", "1"), (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(1, emitted.size());
        assertEquals("hello", emitted.get(0)[0]);
        assertEquals("3",     emitted.get(0)[1]);
    }

    @Test
    void singleValue() {
        reducer.reduce("world", List.of("1"), (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals("1", emitted.get(0)[1]);
    }

    @Test
    void ignoresMalformedValues() {
        reducer.reduce("test", List.of("1", "bad", "2"), (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals("3", emitted.get(0)[1]);
    }

    @Test
    void emptyValueListEmitsZero() {
        reducer.reduce("empty", List.of(), (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals("0", emitted.get(0)[1]);
    }
}
