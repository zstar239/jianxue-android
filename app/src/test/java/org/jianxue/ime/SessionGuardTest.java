package org.jianxue.ime;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SessionGuardTest {
    @Test public void switchingEditorRejectsLateCommitFromPreviousEditor() {
        SessionGuard guard = new SessionGuard(); long old = guard.next();
        long current = guard.next();
        assertFalse(guard.accepts(old)); assertTrue(guard.accepts(current));
    }
    @Test public void closingAndReopeningRejectsEvenTheMostRecentOldCallback() {
        SessionGuard guard = new SessionGuard(); long pending = guard.next();
        guard.next(); long reopened = guard.next();
        assertFalse(guard.accepts(pending)); assertTrue(guard.accepts(reopened));
    }
}
