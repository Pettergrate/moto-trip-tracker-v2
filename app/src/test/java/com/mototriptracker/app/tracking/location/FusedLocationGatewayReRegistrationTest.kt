package com.mototriptracker.app.tracking.location

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.mototriptracker.app.core.common.FakeClock
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * REC-005 follow-up. Found on the phone: a trip started while Location was off never got a fix after Location was
 * switched on - the app held no request with the system. The gateway now asks again when Location comes on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FusedLocationGatewayReRegistrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val calls = mutableListOf<String>()

    /** What the next `requestLocationUpdates` returns; a failed task simulates Google refusing the request. */
    private var nextRequestFails = false

    // The client is a wide interface and the project has no mocking library: record the two calls that matter.
    private val fused: FusedLocationProviderClient = Proxy.newProxyInstance(
        FusedLocationProviderClient::class.java.classLoader,
        arrayOf(FusedLocationProviderClient::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "requestLocationUpdates", "removeLocationUpdates" -> calls += method.name
        }
        when {
            method.name == "hashCode" -> 0
            method.name == "equals" -> false
            method.name == "toString" -> "FakeFusedLocationProviderClient"
            method.name == "requestLocationUpdates" && nextRequestFails -> {
                nextRequestFails = false
                Tasks.forException<Void>(IllegalStateException("refused"))
            }
            Task::class.java.isAssignableFrom(method.returnType) -> Tasks.forResult<Void>(null)
            method.returnType == java.lang.Boolean.TYPE -> false
            method.returnType == Integer.TYPE -> 0
            method.returnType == java.lang.Long.TYPE -> 0L
            else -> null
        }
    } as FusedLocationProviderClient

    private val gateway = FusedLocationGateway(
        fusedClient = fused,
        clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L),
        profileSelector = InMemoryLocationProfileSelector(),
        context = context
    )

    @Before
    fun setUp() {
        shadowOf(locationManager).setLocationEnabled(false) // the trip is started with Location off
    }

    private fun broadcast(action: String) {
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun setLocation(enabled: Boolean, action: String = FusedLocationGateway.MODE_CHANGED_ACTION) {
        shadowOf(locationManager).setLocationEnabled(enabled)
        broadcast(action)
    }

    private fun collecting(block: () -> Unit) = runBlocking {
        val job: Job = launch(Dispatchers.Unconfined) { gateway.locationUpdates().collect { } }
        try {
            block()
        } finally {
            job.cancel()
        }
    }

    @Test
    fun collectingRegistersOneRequestEvenWithLocationOff() = collecting {
        assertEquals(listOf("requestLocationUpdates"), calls)
    }

    @Test
    fun whenLocationIsSwitchedOnTheRequestIsMadeAgainOnce() = collecting {
        setLocation(enabled = true)

        assertEquals(listOf("requestLocationUpdates", "removeLocationUpdates", "requestLocationUpdates"), calls)
    }

    @Test
    fun switchingLocationOffLeavesTheRequestAloneBecauseThatCaseAlreadyRecovers() {
        shadowOf(locationManager).setLocationEnabled(true) // the trip started with Location on

        collecting {
            setLocation(enabled = false)

            assertEquals("only the original request", listOf("requestLocationUpdates"), calls)
        }
    }

    /** One switch sends several broadcasts (Robolectric sends the mode one and the provider ones, and so does a phone). */
    @Test
    fun severalBroadcastsForOneSwitchOnMakeOneNewRequest() = collecting {
        setLocation(enabled = true)
        broadcast(LocationManager.PROVIDERS_CHANGED_ACTION)
        broadcast(FusedLocationGateway.MODE_CHANGED_ACTION)

        assertEquals(listOf("requestLocationUpdates", "removeLocationUpdates", "requestLocationUpdates"), calls)
    }

    /** The bug as seen on the phone: the request made while Location was off is the one that went missing. */
    @Test
    fun aRequestGoogleRefusedWhileLocationWasOffIsReplacedWhenItComesOn() {
        nextRequestFails = true

        collecting {
            shadowOf(Looper.getMainLooper()).idle() // the failure listener runs on the main looper and must not crash
            setLocation(enabled = true)

            assertEquals(listOf("requestLocationUpdates", "removeLocationUpdates", "requestLocationUpdates"), calls)
        }
    }

    /** Off then on again during a trip (the case REC-005 verified on the phone) asks again each time it comes on. */
    @Test
    fun eachOffToOnTransitionAsksAgain() = collecting {
        setLocation(enabled = true)
        setLocation(enabled = false)
        setLocation(enabled = true)

        assertEquals(
            listOf("requestLocationUpdates", "removeLocationUpdates", "requestLocationUpdates", "removeLocationUpdates", "requestLocationUpdates"),
            calls
        )
    }

    @Test
    fun onceTheCollectorStopsALaterSwitchDoesNothing() {
        collecting { }
        val afterStopping = calls.toList()
        assertEquals("the updates were removed when collection ended", "removeLocationUpdates", afterStopping.last())

        setLocation(enabled = true)

        assertEquals("no request is made for a recording that is over", afterStopping, calls)
    }
}
