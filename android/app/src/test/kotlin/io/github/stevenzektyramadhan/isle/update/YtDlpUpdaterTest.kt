package io.github.stevenzektyramadhan.isle.update

import org.junit.Assert.*
import org.junit.Test

class YtDlpUpdaterTest {
    @Test fun checkIsDueWithoutPreviousAttempt() {
        assertTrue(shouldCheck(100, null))
    }

    @Test fun checkIsDueAtExactlyOneDay() {
        assertTrue(shouldCheck(100 + 86_400, 100))
    }

    @Test fun checkIsNotDueBeforeOneDayOrAfterClockMovesBackward() {
        assertFalse(shouldCheck(100 + 86_399, 100))
        assertFalse(shouldCheck(99, 100))
    }

    @Test fun updateFailureIsReturnedWithoutThrowing() {
        assertFalse(attemptUpdate { throw IllegalStateException("failure") })
    }

    @Test fun successfulUpdateIsReported() {
        assertTrue(attemptUpdate { })
    }
}
