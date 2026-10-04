package org.jianxue.ime;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EditorPolicyTest {
    @Test public void everyPasswordVariationIsPrivateAndAscii() {
        for (int type : new int[]{0x81, 0x91, 0xe1, 0x12}) {
            assertTrue(EditorPolicy.password(type));
            assertTrue(EditorPolicy.privateInput(type, 0, false));
            assertTrue(EditorPolicy.ascii(type));
        }
    }
    @Test public void sameVariationBitsInPhoneClassAreNotTextPasswords() {
        assertFalse(EditorPolicy.password(0x83));
    }
    @Test public void systemPrivacyFlagAndManualPrivacyHaveIndependentEffect() {
        assertTrue(EditorPolicy.privateInput(1, 0x1000000, false));
        assertTrue(EditorPolicy.privateInput(1, 0, true));
        assertFalse(EditorPolicy.privateInput(1, 0, false));
    }
    @Test public void numericPhoneAndDateUseNumericLayout() {
        for (int type : new int[]{2, 3, 4, 0x12}) assertTrue(EditorPolicy.numeric(type));
        assertFalse(EditorPolicy.numeric(1));
    }
    @Test public void urlAndEmailUseAscii() {
        assertTrue(EditorPolicy.ascii(0x11)); assertTrue(EditorPolicy.ascii(0x21)); assertTrue(EditorPolicy.ascii(0xd1));
        assertFalse(EditorPolicy.ascii(1));
    }
}
