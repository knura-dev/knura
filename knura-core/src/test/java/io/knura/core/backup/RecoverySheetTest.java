package io.knura.core.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knura.core.backup.RecoverySheet.SheetException;
import io.knura.core.crypto.Hex;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoverySheetTest {
    private static final byte[] MASTER = Hex.decode("3F9A21C07B44E2D10A6E91F322B84C07");

    @Test
    void rowsAreGroupsOfFourWithACheck() {
        List<String> rows = RecoverySheet.rows("master.key", MASTER);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).matches("3F9A 21C0 7B44 E2D1  [0-9A-F]{2}"), rows.get(0));
        assertTrue(rows.get(1).matches("0A6E 91F3 22B8 4C07  [0-9A-F]{2}"), rows.get(1));
    }

    @Test
    void readsBackForgivingly() throws Exception {
        List<String> typed = new ArrayList<>();
        for (String row : RecoverySheet.rows("master.key", MASTER)) {
            typed.add(row.toLowerCase().replace(' ', '-').replace('0', 'o'));
        }
        assertArrayEquals(MASTER, RecoverySheet.read("master.key", typed, 16));
    }

    @Test
    void catchesATypoOnItsRow() {
        List<String> typed = new ArrayList<>(RecoverySheet.rows("master.key", MASTER));
        typed.set(1, typed.get(1).replaceFirst("91F3", "91E3"));
        SheetException e = assertThrows(SheetException.class, () -> RecoverySheet.read("master.key", typed, 16));
        assertEquals(2, e.row());
    }

    @Test
    void catchesSwappedRowsAndWrongLabels() {
        List<String> rows = RecoverySheet.rows("master.key", MASTER);
        assertThrows(SheetException.class, () -> RecoverySheet.read("master.key", List.of(rows.get(1), rows.get(0)), 16));
        assertThrows(SheetException.class, () -> RecoverySheet.read("resolver.key", rows, 16));
    }

    @Test
    void thirtyTwoBytesIsFourRows() throws Exception {
        byte[] seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) (i * 7);
        }
        List<String> rows = RecoverySheet.rows("resolver.key", seed);
        assertEquals(4, rows.size());
        assertArrayEquals(seed, RecoverySheet.read("resolver.key", rows, 32));
        assertThrows(SheetException.class, () -> RecoverySheet.read("resolver.key", rows.subList(0, 3), 32));
    }

    @Test
    void rejectsNonHex() {
        SheetException e = assertThrows(SheetException.class, () -> RecoverySheet.readRow("master.key", 1, "3F9A 21C0 7B44 E2DZ 5C"));
        assertEquals(1, e.row());
    }
}
