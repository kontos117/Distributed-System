package gr.tuc.distributed.examples.selfjoin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelfJoinReducerTest {

    private SelfJoinReducer reducer;
    private List<String[]> emitted;

    @BeforeEach
    void setUp() {
        reducer = new SelfJoinReducer();
        emitted = new ArrayList<>();
    }

    @Test
    void generatesCandidatesWithFullKey() {
        // prefix "a,b" with values [c, d, e] => candidates a,b,c,d / a,b,c,e / a,b,d,e
        reducer.reduce("a,b", List.of("c", "d", "e"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(3, emitted.size());
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("a,b,c,d") && p[1].equals("1")));
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("a,b,c,e") && p[1].equals("1")));
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("a,b,d,e") && p[1].equals("1")));
    }

    @Test
    void deduplicatesValues() {
        reducer.reduce("a,b", List.of("c", "c", "d"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(1, emitted.size());
        assertEquals("a,b,c,d", emitted.get(0)[0]);
        assertEquals("1", emitted.get(0)[1]);
    }

    @Test
    void sortsValuesBeforePairing() {
        reducer.reduce("x", List.of("z", "a", "m"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(3, emitted.size());
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("x,a,m")));
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("x,a,z")));
        assertTrue(emitted.stream().anyMatch(p -> p[0].equals("x,m,z")));
    }

    @Test
    void singleValueEmitsNothing() {
        reducer.reduce("a,b", List.of("c"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertTrue(emitted.isEmpty());
    }

    @Test
    void emptyValueListEmitsNothing() {
        reducer.reduce("a,b", List.of(),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertTrue(emitted.isEmpty());
    }

    @Test
    void twoValuesEmitOneCandidate() {
        reducer.reduce("a", List.of("b", "c"),
                (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(1, emitted.size());
        assertEquals("a,b,c", emitted.get(0)[0]);
        assertEquals("1", emitted.get(0)[1]);
    }
}
