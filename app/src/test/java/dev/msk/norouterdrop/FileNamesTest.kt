package dev.msk.norouterdrop

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {
    @Test fun stripsPathParts() {
        assertEquals("passwd", FileNames.safe("../../etc/passwd"))
        assertEquals("a.txt", FileNames.safe("C:\\x\\a.txt"))
    }
    @Test fun replacesIllegalCharsAndReserved() {
        assertEquals("a_b.png", FileNames.safe("a:b.png"))
        assertEquals("_CON.txt", FileNames.safe("CON.txt"))
        assertEquals("unnamed", FileNames.safe("..."))
    }
}
