package com.sun.java.fontmgr.swap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code delay:}/{@code wait:}/{@code pause:} must parse so the dispatcher
 * timeline can insert an explicit wait. Would fail if those aliases were
 * treated as unknown or as equip-by-name.
 */
public class CommandParserDelayTest {

    @Test
    public void delayAliasesParse() {
        assertDelay(CommandParser.parse("delay:200"), "200");
        assertDelay(CommandParser.parse("wait:50"), "50");
        assertDelay(CommandParser.parse("pause:0"), "0");
        assertDelay(CommandParser.parse("delay"), "120");
        assertDelay(CommandParser.parse("DELAY:15"), "15");
    }

    @Test
    public void commentsAndEmptyAreDropped() {
        assertNull(CommandParser.parse("// delay:200"));
        assertNull(CommandParser.parse(""));
        assertNull(CommandParser.parse("   "));
    }

    @Test
    public void unequipStillParsesAsRemove() {
        CommandParser.Command c = CommandParser.parse("unequip:helm");
        assertNotNull(c);
        assertEquals("r", c.type);
        assertEquals("helm", c.value);
    }

    private static void assertDelay(CommandParser.Command c, String value) {
        assertNotNull(c);
        assertEquals("delay", c.type);
        assertEquals(value, c.value);
    }
}
