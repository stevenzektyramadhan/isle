package io.github.stevenzektyramadhan.isle.update

import io.github.stevenzektyramadhan.isle.data.YtDlpExecution
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class YtDlpExecutionTest {
    private fun CountDownLatch.awaitEvent() = assertTrue("event did not arrive", await(5, TimeUnit.SECONDS))

    // Wait for the contender to either enter the block or park on the real gate.
    // The deadline only detects a broken test; scheduling never decides the assertion.
    private fun awaitContention(thread: AtomicReference<Thread>, entered: AtomicBoolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!entered.get() && thread.get().state != Thread.State.WAITING) {
            check(System.nanoTime() < deadline) { "contender neither entered nor parked" }
            Thread.yield()
        }
    }

    @Test fun cancelledCallerKeepsUpdateExcludedUntilExecuteActuallyExits() = runBlocking {
        val started = CountDownLatch(1)
        val destroyRequested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = AtomicBoolean(false)
        val entered = AtomicBoolean(false)
        val contender = AtomicReference<Thread>()
        val attempting = CountDownLatch(1)
        val runner = YtDlpExecution { destroyRequested.countDown() }
        val execution = launch(Dispatchers.IO) {
            runner.execute {
                started.countDown()
                release.awaitEvent()
                exited.set(true)
                "done"
            }
        }
        started.awaitEvent()
        execution.cancelAndJoin()
        destroyRequested.awaitEvent()
        val update = async(Dispatchers.IO) {
            contender.set(Thread.currentThread())
            attempting.countDown()
            YtDlpExecutionGate.withUpdate {
                entered.set(true)
                assertTrue("update overlapped cancelled process", exited.get())
            }
        }
        try {
            attempting.awaitEvent()
            awaitContention(contender, entered)
            assertFalse("update entered before process exit", entered.get())
        } finally { release.countDown() }
        update.await()
    }

    @Test fun updatesSerializeAndExcludeNewExecutions() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activeUpdate = AtomicBoolean(false)
        val first = async(Dispatchers.IO) {
            YtDlpExecutionGate.withUpdate {
                activeUpdate.set(true)
                started.countDown()
                release.awaitEvent()
                activeUpdate.set(false)
            }
        }
        started.awaitEvent()
        val entered = AtomicBoolean(false)
        val contender = AtomicReference<Thread>()
        val attempting = CountDownLatch(1)
        val second = async(Dispatchers.IO) {
            contender.set(Thread.currentThread())
            attempting.countDown()
            YtDlpExecutionGate.withUpdate {
                entered.set(true)
                assertFalse("updates overlapped", activeUpdate.get())
            }
        }
        val executionEntered = AtomicBoolean(false)
        val executionThread = AtomicReference<Thread>()
        val executionAttempting = CountDownLatch(1)
        val reader = async(Dispatchers.IO) {
            executionThread.set(Thread.currentThread())
            executionAttempting.countDown()
            YtDlpExecutionGate.withExecution {
                executionEntered.set(true)
                assertFalse("execute overlapped update", activeUpdate.get())
            }
        }
        try {
            attempting.awaitEvent()
            executionAttempting.awaitEvent()
            awaitContention(contender, entered)
            awaitContention(executionThread, executionEntered)
            assertFalse(entered.get())
            assertFalse(executionEntered.get())
        } finally { release.countDown() }
        first.await()
        second.await()
        reader.await()
    }

    @Test fun cancelledUpdateWaiterDoesNotBlockFollowingExecution() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val execution = async(Dispatchers.IO) {
            YtDlpExecution { }.execute {
                started.countDown()
                release.awaitEvent()
                "first"
            }
        }
        started.awaitEvent()
        val entered = AtomicBoolean(false)
        val contender = AtomicReference<Thread>()
        val attempting = CountDownLatch(1)
        val update = launch(Dispatchers.IO) {
            contender.set(Thread.currentThread())
            attempting.countDown()
            YtDlpExecutionGate.withUpdate { entered.set(true) }
        }
        try {
            attempting.awaitEvent()
            awaitContention(contender, entered)
            update.cancelAndJoin()
            assertFalse(entered.get())
        } finally { release.countDown() }
        assertEquals("first", execution.await())
        assertEquals("following", YtDlpExecution { }.execute { "following" })
    }

    @Test fun executionAndUpdateFailuresReleaseTheirLocks() = runBlocking {
        val expected = IllegalStateException("failure")
        try {
            YtDlpExecution { }.execute { throw expected }
            fail("execution failure lost")
        } catch (error: IllegalStateException) { assertEquals("failure", error.message) }
        try {
            YtDlpExecutionGate.withUpdate { throw expected }
            fail("update failure lost")
        } catch (error: IllegalStateException) { assertEquals("failure", error.message) }
        assertEquals("recovered", YtDlpExecution { }.execute { "recovered" })
    }
}
