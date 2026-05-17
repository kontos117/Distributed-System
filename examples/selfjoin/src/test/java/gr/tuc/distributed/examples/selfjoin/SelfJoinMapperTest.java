package gr.tuc.distributed.examples.selfjoin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelfJoinMapperTest {

    private SelfJoinMapper mapper;
    private List<String[]> emitted;

    @BeforeEach
    void setUp() {
        mapper  = new SelfJoinMapper();
        emitted = new ArrayList<>();
    }

    @Test
    void splitsThreeElementSet() {
        mapper.map("file.txt", "a,b,c", (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(1, emitted.size());
        assertEquals("a,b", emitted.get(0)[0]);
        assertEquals("c", emitted.get(0)[1]);
    }

    @Test
    void splitsFourElementSet() {
        mapper.map("file.txt", "a,b,c,d", (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(1, emitted.size());
        assertEquals("a,b,c", emitted.get(0)[0]);
        assertEquals("d", emitted.get(0)[1]);
    }

    @Test
    void splitsTwoElementSet() {
        mapper.map("file.txt", "x,y", (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(1, emitted.size());
        assertEquals("x", emitted.get(0)[0]);
        assertEquals("y", emitted.get(0)[1]);
    }

    @Test
    void ignoresSingleElement() {
        mapper.map("file.txt", "a", (k, v) -> emitted.add(new String[]{k, v}));
        assertTrue(emitted.isEmpty());
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
    void handlesWhitespaceAroundElements() {
        mapper.map("file.txt", " a , b , c ", (k, v) -> emitted.add(new String[]{k, v}));

        assertEquals(1, emitted.size());
        assertEquals("a,b", emitted.get(0)[0]);
        assertEquals("c", emitted.get(0)[1]);
    }
}
