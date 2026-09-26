package com.mototriptracker.app.debug

import android.database.sqlite.SQLiteFullException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** REC-006: the debug-only flag-file injector does nothing until a flag file exists. */
@RunWith(RobolectricTestRunner::class)
class FileFlagRawWriteFaultInjectorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun injector() = FileFlagRawWriteFaultInjector(folder.root)

    private fun flag(text: String) = File(folder.root, FileFlagRawWriteFaultInjector.FAIL_FLAG_FILE).writeText(text)

    @Test
    fun withNoFlagFileNothingIsInjected() {
        injector().beforeRawInsert() // must not throw
        assertNull(injector().bufferCapacity())
    }

    @Test
    fun aTransientFlagMakesInsertsThrowAnOrdinaryFailure() {
        flag("transient\n")

        assertThrows(IllegalStateException::class.java) { injector().beforeRawInsert() }
    }

    @Test
    fun aFullFlagMakesInsertsThrowTheDiskFullException() {
        flag("full")

        assertThrows(SQLiteFullException::class.java) { injector().beforeRawInsert() }
    }

    @Test
    fun removingTheFlagStopsTheFailuresAtOnce() {
        flag("transient")
        assertThrows(IllegalStateException::class.java) { injector().beforeRawInsert() }

        File(folder.root, FileFlagRawWriteFaultInjector.FAIL_FLAG_FILE).delete()

        injector().beforeRawInsert()
    }

    @Test
    fun theBufferCapacityCanBeShrunkButGarbageAndNonsenseAreIgnored() {
        val file = File(folder.root, FileFlagRawWriteFaultInjector.CAPACITY_FILE)

        file.writeText("10\n")
        assertEquals(10, injector().bufferCapacity())

        file.writeText("not a number")
        assertNull(injector().bufferCapacity())

        file.writeText("0")
        assertNull("a zero or negative buffer would be nonsense - the default stays", injector().bufferCapacity())
    }
}
