package org.pureagave.zodiac.control.data.sensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.telemetry.BeaconSensors
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Exercises the UDP receive path end-to-end over real loopback sockets: a
 * datagram of NMEA in on the wire must come out as an [LocationSourceState.Active]
 * fix, and non-NMEA junk must not. Packets are re-sent across a window so the
 * test doesn't race the listener's bind (UDP has no pre-bind buffering).
 */
@Suppress("LargeClass")
class NetworkLocationSourceTest {
    // Canonical valid GGA: 4807.038N 01131.000E → 48.1173, 11.5167 (checksum *47).
    private val validGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n"

    @Test
    fun receives_nmea_over_udp_and_emits_active_fix() =
        runBlocking {
            val port = 10176
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                val active =
                    waitUntil(4_000) {
                        sendUdp(validGga, port) // re-send until received; harmless once Active
                        source.state.value is LocationSourceState.Active
                    }
                assertTrue("a valid GGA over UDP should produce an Active fix; state=${source.state.value}", active)
                val fix = (source.state.value as LocationSourceState.Active).fix
                assertEquals(48.1173, fix.location.lat, 0.001)
                assertEquals(11.5167, fix.location.lon, 0.001)
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun non_nmea_garbage_does_not_produce_a_fix() =
        runBlocking {
            val port = 10177
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                // Blast junk across a window wide enough to cover the bind; the
                // parser must reject all of it and the source must stay Searching.
                val deadline = System.currentTimeMillis() + 600
                while (System.currentTimeMillis() < deadline) {
                    sendUdp("hello, this is not nmea\r\n", port)
                    Thread.sleep(30)
                }
                assertTrue(
                    "junk must never yield a fix",
                    source.state.value is LocationSourceState.Searching,
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun compass_heading_from_hdt_merges_into_the_fix() =
        runBlocking {
            val port = 10178
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                // Position (GGA, no heading) + a compass heading (HDT) arrive as
                // separate sentences; the fix must carry the HDT heading.
                val ok =
                    waitUntil(4_000) {
                        sendUdp(validGga, port)
                        sendUdp(nmea("GPHDT,123.4,T"), port)
                        val st = source.state.value
                        st is LocationSourceState.Active && (st.fix.headingDeg ?: -1.0) in 123.0..124.0
                    }
                assertTrue("HDT heading should merge into the network fix", ok)
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun vehicle_telemetry_from_ztlm_over_udp() =
        runBlocking {
            val port = 10179
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                val ok =
                    waitUntil(4_000) {
                        sendUdp(nmea("ZTLM,4.0,-1.5,25.0"), port)
                        source.telemetry.value?.let { it.speedKph in 24.0..26.0 && it.pitchDeg in 3.5..4.5 } ?: false
                    }
                assertTrue("a ZTLM frame should populate the telemetry flow", ok)
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun beacon_sensor_channels_populate_the_bundle() =
        runBlocking {
            val port = 10182
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                val ok =
                    waitUntil(4_000) {
                        sendUdp(nmea("ZENV,315.0"), port)
                        sendUdp(nmea("ZBCN,87,1,9,3600"), port)
                        sendUdp(nmea("ZODO,1234.5,987654.0"), port)
                        val s = source.beaconSensors.value
                        s.ambientLight?.lux == 315.0 && s.beaconHealth?.batteryPct == 87 && s.odometer?.tripMeters == 1234.5
                    }
                assertTrue("ZENV/ZBCN/ZODO should populate the beacon-sensors bundle", ok)
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun a_shock_event_increments_the_count_and_records_its_peak() =
        runBlocking {
            val port = 10183
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                val ok =
                    waitUntil(4_000) {
                        sendUdp(nmea("ZSHK,2.35"), port)
                        val s = source.beaconSensors.value
                        s.shockCount >= 1 && s.lastShockG in 2.3..2.4
                    }
                assertTrue("a ZSHK frame should bump the shock count + record its peak", ok)
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun active_fix_goes_stale_when_position_stops_arriving() =
        runBlocking {
            val port = 10180
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            // Short stale window so the test doesn't wait the production 5 s.
            val source = NetworkLocationSource(scope = scope, port = port, staleMs = 250)
            try {
                source.start()
                assertTrue(
                    "position should produce an Active fix",
                    waitUntil(4_000) {
                        sendUdp(validGga, port)
                        source.state.value is LocationSourceState.Active
                    },
                )
                // Stop sending position; the watchdog must demote off the frozen fix.
                assertTrue(
                    "a dead GPS feed must not stay Active on a frozen fix",
                    waitUntil(4_000) { source.state.value is LocationSourceState.Searching },
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun compass_only_traffic_does_not_keep_a_dead_gps_alive() =
        runBlocking {
            val port = 10181
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port, staleMs = 250)
            try {
                source.start()
                assertTrue(
                    waitUntil(4_000) {
                        sendUdp(validGga, port)
                        source.state.value is LocationSourceState.Active
                    },
                )
                // GPS dies but the compass keeps broadcasting HDT — must still go
                // stale rather than hold Active on the frozen position.
                assertTrue(
                    "HDT-only traffic must not hold Active on a stale position",
                    waitUntil(4_000) {
                        sendUdp(nmea("GPHDT,90.0,T"), port)
                        source.state.value is LocationSourceState.Searching
                    },
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    /** Wrap a sentence body in `$...*<checksum>` NMEA framing. */
    private fun nmea(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "\$$body*%02X\r\n".format(c)
    }

    private fun sendUdp(
        msg: String,
        port: Int,
    ) {
        DatagramSocket().use {
            val bytes = msg.toByteArray(Charsets.US_ASCII)
            it.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
        }
    }

    private inline fun waitUntil(
        timeoutMs: Long,
        cond: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test
    fun a_silent_beacon_stops_reporting_its_last_known_readings() =
        runBlocking {
            // A dead hub must not keep the ops footer showing its battery,
            // satellite count and uptime as though they were current. Silence is
            // fine; a stale number presented as live is not.
            val port = 10186
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source =
                NetworkLocationSource(scope = scope, port = port, staleMs = 400, beaconSilentMs = 600)
            try {
                source.start()
                val populated =
                    waitUntil(4_000) {
                        sendUdp(nmea("ZBCN,87,1,9,3600"), port)
                        sendUdp(nmea("ZODO,1234.5,987654.0"), port)
                        source.beaconSensors.value.beaconHealth?.batteryPct == 87
                    }
                assertTrue("precondition: the beacon reported once", populated)

                // Now say nothing at all and let the silence window elapse.
                val cleared =
                    waitUntil(4_000) {
                        val s = source.beaconSensors.value
                        s.beaconHealth == null && s.odometer == null && s.ambientLight == null
                    }
                assertTrue("a silent beacon's readings should be dropped, not frozen", cleared)
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun going_silent_preserves_the_shock_counter() =
        runBlocking {
            // shockCount is a monotonic event counter the ViewModel diffs
            // against; rewinding it on silence would swallow the next real
            // impact after the beacon came back.
            val port = 10187
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source =
                NetworkLocationSource(scope = scope, port = port, staleMs = 400, beaconSilentMs = 600)
            try {
                source.start()
                assertTrue(
                    "precondition: a shock was recorded",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZSHK,2.35"), port)
                        source.beaconSensors.value.shockCount >= 1
                    },
                )
                val counted = source.beaconSensors.value.shockCount
                assertTrue(
                    "readings clear but the shock counter must not rewind",
                    waitUntil(4_000) {
                        val s = source.beaconSensors.value
                        s.beaconHealth == null && s.shockCount >= counted
                    },
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun a_dead_compass_stops_overriding_the_live_gps_course() =
        runBlocking {
            // The compass is preferred over GPS course because it is valid when
            // stopped. But if that channel dies, the last heading was frozen and
            // then written over every subsequent fix forever — the map rotation,
            // turn cues and guidance chevron all steering off a value that
            // stopped updating, while the source stayed Active.
            val port = 10188
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source =
                NetworkLocationSource(scope = scope, port = port, staleMs = 4_000, headingStaleMs = 500)
            try {
                source.start()
                assertTrue(
                    "precondition: the compass heading is adopted",
                    waitUntil(4_000) {
                        sendUdp(nmea("GPHDT,90.0,T"), port)
                        sendUdp(nmea("GPRMC,123519,A,4807.038,N,01131.000,E,0.0,180.0,230394,,"), port)
                        (source.state.value as? LocationSourceState.Active)?.fix?.headingDeg?.let { it in 89.0..91.0 } == true
                    },
                )
                // Compass silent from here; only position keeps arriving.
                assertTrue(
                    "a stale compass must fall back to GPS course, not steer forever",
                    waitUntil(6_000) {
                        sendUdp(nmea("GPRMC,123519,A,4807.038,N,01131.000,E,12.0,180.0,230394,,"), port)
                        (source.state.value as? LocationSourceState.Active)?.fix?.headingDeg?.let { it in 179.0..181.0 } == true
                    },
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun a_fresh_compass_still_wins_over_gps_course() =
        runBlocking {
            val port = 10189
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port, headingStaleMs = 10_000)
            try {
                source.start()
                assertTrue(
                    "compass is valid when stopped; GPS course is not",
                    waitUntil(4_000) {
                        sendUdp(nmea("GPHDT,45.0,T"), port)
                        sendUdp(nmea("GPRMC,123519,A,4807.038,N,01131.000,E,0.0,200.0,230394,,"), port)
                        (source.state.value as? LocationSourceState.Active)?.fix?.headingDeg?.let { it in 44.0..46.0 } == true
                    },
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun foreign_nmea_traffic_does_not_keep_a_dead_hub_alive() =
        runBlocking {
            // The beacon-silence watchdog exists so a dead hub's battery and
            // uptime stop being shown as current. Stamping liveness on *any*
            // line defeats it: a second GPS or a bring-up forwarder sharing this
            // port would refresh it forever.
            val port = 10190
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source =
                NetworkLocationSource(scope = scope, port = port, staleMs = 400, beaconSilentMs = 600)
            try {
                source.start()
                assertTrue(
                    "precondition: the hub reported",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZBCN,87,1,9,3600"), port)
                        source.beaconSensors.value.beaconHealth?.batteryPct == 87
                    },
                )
                assertTrue(
                    "unrelated GPS traffic must not stand in for the hub",
                    waitUntil(4_000) {
                        sendUdp(nmea("GPRMC,123519,A,4807.038,N,01131.000,E,0.0,180.0,230394,,"), port)
                        source.beaconSensors.value.beaconHealth == null
                    },
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    // --- stop() must clear beacon-derived readings, not just tear down the
    // socket (AUDIT-2026-08-09 C4). Before this fix, only the silence
    // watchdog ever cleared _beaconSensors/_telemetry/_audioLevel, and stop()
    // killed the watchdog without doing its job — so switching GPS sources at
    // night froze ambientLux at the night value, holding the screen at the
    // 0.05 brightness floor through the next day. ---

    @Test
    fun stop_clears_beacon_health() =
        runBlocking {
            // Mutation: delete the clearBeaconReadings() call in stop().
            val port = 10191
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                assertTrue(
                    "precondition: beacon health populated",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZBCN,87,1,9,3600"), port)
                        source.beaconSensors.value.beaconHealth?.batteryPct == 87
                    },
                )
                source.stop()
                assertEquals(null, source.beaconSensors.value.beaconHealth)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun stop_clears_ambient_light() =
        runBlocking {
            // This is the one that holds the screen at 5% through a day: a
            // frozen ambientLight after switching off NET at night is, after
            // C2, the sole input to the brightness arbiter outside ACTIVE.
            val port = 10192
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                assertTrue(
                    "precondition: ambient light populated",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZENV,3.0"), port)
                        source.beaconSensors.value.ambientLight?.lux == 3.0
                    },
                )
                source.stop()
                assertEquals(null, source.beaconSensors.value.ambientLight)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun stop_clears_the_odometer() =
        runBlocking {
            val port = 10193
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                assertTrue(
                    "precondition: odometer populated",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZODO,1234.5,987654.0"), port)
                        source.beaconSensors.value.odometer?.tripMeters == 1234.5
                    },
                )
                source.stop()
                assertEquals(null, source.beaconSensors.value.odometer)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun stop_clears_audio_level() =
        runBlocking {
            val port = 10194
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                assertTrue(
                    "precondition: audio level populated",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZAUD,0.500,0.800,1"), port)
                        source.audioLevel.value != null
                    },
                )
                source.stop()
                assertEquals(null, source.audioLevel.value)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun stop_clears_vehicle_telemetry() =
        runBlocking {
            val port = 10195
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                assertTrue(
                    "precondition: vehicle telemetry populated",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZTLM,4.0,-1.5,25.0"), port)
                        source.telemetry.value != null
                    },
                )
                source.stop()
                assertEquals(null, source.telemetry.value)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun stop_preserves_the_monotonic_shock_count() =
        runBlocking {
            // Mutation: clear shockCount too (BeaconSensors() with no args) —
            // this is the one thing that must survive; a naive "clear
            // everything" fix breaks the counter the ViewModel diffs against.
            //
            // Uses >= rather than exact equality, matching
            // going_silent_preserves_the_shock_counter above: the waitUntil
            // precondition below resends on every retry (needed so the test
            // doesn't race the listener's bind), so duplicate datagrams can
            // still be in flight and land after the snapshot is taken —
            // that's more shocks landing, never fewer, so >= is exact enough
            // to catch a reset-to-zero without being racy.
            val port = 10196
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                assertTrue(
                    "precondition: two shocks recorded",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZSHK,1.10"), port)
                        sendUdp(nmea("ZSHK,2.35"), port)
                        source.beaconSensors.value.shockCount >= 2
                    },
                )
                val countBeforeStop = source.beaconSensors.value.shockCount
                source.stop()
                assertTrue(
                    "shockCount is a monotonic counter, not a reading — stop() must not rewind it",
                    source.beaconSensors.value.shockCount >= countBeforeStop,
                )
            } finally {
                scope.cancel()
            }
        }

    /** Everything [NetworkLocationSource.clearBeaconReadings] touches, snapshotted for comparison. */
    private data class ClearedSnapshot(
        val beaconSensors: BeaconSensors,
        val hasTelemetry: Boolean,
        val hasAudioLevel: Boolean,
    )

    @Test
    fun the_silence_watchdog_and_stop_clear_the_same_things() =
        runBlocking {
            // Mutation: change one path's clearing but not the other (e.g.
            // have stop() clear _telemetry too but leave the watchdog's inline
            // clearing not doing so, or vice versa) — this is the test that
            // stops the two paths drifting, the actual reason clearBeaconReadings()
            // exists as one shared helper. Snapshots cover telemetry and
            // audioLevel too, not just beaconSensors — a mutation that only
            // desyncs one of those two separate flows must still be caught
            // here, not only by their single-field stop_clears_* tests.
            val watchdogPort = 10197
            val stopPort = 10198
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            // Run 1: drive the silence watchdog to fire.
            val watchdogSource =
                NetworkLocationSource(scope = scope, port = watchdogPort, staleMs = 400, beaconSilentMs = 600)
            val watchdogSnapshot: ClearedSnapshot
            try {
                watchdogSource.start()
                assertTrue(
                    "precondition: beacon reported before going silent",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZBCN,87,1,9,3600"), watchdogPort)
                        sendUdp(nmea("ZENV,3.0"), watchdogPort)
                        sendUdp(nmea("ZODO,1234.5,987654.0"), watchdogPort)
                        sendUdp(nmea("ZSHK,2.35"), watchdogPort)
                        sendUdp(nmea("ZTLM,4.0,-1.5,25.0"), watchdogPort)
                        sendUdp(nmea("ZAUD,0.500,0.800,1"), watchdogPort)
                        watchdogSource.beaconSensors.value.beaconHealth?.batteryPct == 87 &&
                            watchdogSource.telemetry.value != null &&
                            watchdogSource.audioLevel.value != null
                    },
                )
                assertTrue(
                    "precondition: the watchdog cleared the readings on silence",
                    waitUntil(4_000) {
                        val s = watchdogSource.beaconSensors.value
                        s.beaconHealth == null && s.ambientLight == null && s.odometer == null &&
                            watchdogSource.telemetry.value == null && watchdogSource.audioLevel.value == null
                    },
                )
                watchdogSnapshot =
                    ClearedSnapshot(
                        watchdogSource.beaconSensors.value,
                        watchdogSource.telemetry.value != null,
                        watchdogSource.audioLevel.value != null,
                    )
            } finally {
                watchdogSource.stop()
            }

            // Run 2: reach the same cleared state via stop() instead.
            val stopSource = NetworkLocationSource(scope = scope, port = stopPort)
            val stopSnapshot: ClearedSnapshot
            try {
                stopSource.start()
                assertTrue(
                    "precondition: beacon reported before stop()",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZBCN,87,1,9,3600"), stopPort)
                        sendUdp(nmea("ZENV,3.0"), stopPort)
                        sendUdp(nmea("ZODO,1234.5,987654.0"), stopPort)
                        sendUdp(nmea("ZSHK,2.35"), stopPort)
                        sendUdp(nmea("ZTLM,4.0,-1.5,25.0"), stopPort)
                        sendUdp(nmea("ZAUD,0.500,0.800,1"), stopPort)
                        stopSource.beaconSensors.value.beaconHealth?.batteryPct == 87 &&
                            stopSource.telemetry.value != null &&
                            stopSource.audioLevel.value != null
                    },
                )
                stopSource.stop()
                stopSnapshot =
                    ClearedSnapshot(
                        stopSource.beaconSensors.value,
                        stopSource.telemetry.value != null,
                        stopSource.audioLevel.value != null,
                    )
            } finally {
                scope.cancel()
            }

            // shockCount is compared separately (both > 0, i.e. preserved by
            // both paths) rather than folded into the equality check: each
            // precondition loop above resends its ZSHK on every retry (needed
            // so the test doesn't race the listener's bind), so the exact
            // count landing by the time of the snapshot is a race between the
            // two independent runs and isn't the thing this test is checking.
            assertEquals(
                "the watchdog path and stop() must clear the same non-counter readings identically",
                watchdogSnapshot.copy(beaconSensors = watchdogSnapshot.beaconSensors.copy(shockCount = 0)),
                stopSnapshot.copy(beaconSensors = stopSnapshot.beaconSensors.copy(shockCount = 0)),
            )
            assertTrue("the watchdog path must preserve the shock count", watchdogSnapshot.beaconSensors.shockCount > 0)
            assertTrue("stop() must preserve the shock count", stopSnapshot.beaconSensors.shockCount > 0)
        }

    // --- VTG is GPS course, not compass; only HDT may feed the
    // compass-preferred slot (AUDIT-2026-08-09 C6). Android GNSS chips emit
    // $GxVTG every epoch and the beacon forwards raw GNSS verbatim, so on the
    // real wire HDT and VTG interleave continuously. ---

    @Test
    fun vtg_arriving_after_hdt_does_not_override_the_compass_heading() =
        runBlocking {
            // Mutation: restore VTG to parseHeadingDeg's matched types.
            val port = 10300
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                assertTrue(
                    "precondition: HDT heading adopted",
                    waitUntil(4_000) {
                        sendUdp(validGga, port)
                        sendUdp(nmea("GPHDT,45.0,T"), port)
                        (source.state.value as? LocationSourceState.Active)?.fix?.headingDeg?.let { it in 44.0..46.0 } == true
                    },
                )
                // A stopped vehicle's GNSS chip still emits VTG every epoch
                // with a meaningless/noisy course. If VTG fed the compass
                // slot, this would flip the heading away from 45.
                sendUdp(nmea("GPVTG,200.0,T,198.0,M,000.0,N,000.0,K,A"), port)
                Thread.sleep(150)
                val heading = (source.state.value as? LocationSourceState.Active)?.fix?.headingDeg
                assertTrue(
                    "VTG must not override the live compass heading; heading=$heading",
                    heading != null && heading in 44.0..46.0,
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun vtg_traffic_does_not_keep_a_dead_compass_fresh() =
        runBlocking {
            // Mutation: restore VTG to parseHeadingDeg's matched types (this
            // is the watchdog-defeat half of C6: if VTG stamped headingRxMs,
            // a dead HDT channel would never be judged stale and course would
            // never take back over).
            val port = 10301
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port, staleMs = 4_000, headingStaleMs = 300)
            try {
                source.start()
                assertTrue(
                    "precondition: HDT heading adopted",
                    waitUntil(4_000) {
                        sendUdp(validGga, port)
                        sendUdp(nmea("GPHDT,45.0,T"), port)
                        (source.state.value as? LocationSourceState.Active)?.fix?.headingDeg?.let { it in 44.0..46.0 } == true
                    },
                )
                // HDT goes silent; only VTG and position keep arriving. If VTG
                // fed headingRxMs, the compass would never be judged stale and
                // course (180) would never take over.
                assertTrue(
                    "a dead compass fed only by VTG traffic must still fall back to GPS course",
                    waitUntil(4_000) {
                        sendUdp(nmea("GPVTG,200.0,T,198.0,M,000.0,N,000.0,K,A"), port)
                        sendUdp(nmea("GPRMC,123519,A,4807.038,N,01131.000,E,12.0,180.0,230394,,"), port)
                        (source.state.value as? LocationSourceState.Active)?.fix?.headingDeg?.let { it in 179.0..181.0 } == true
                    },
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    // --- $ZSHK double-delivery dedup (AUDIT-2026-08-09 C7): the beacon sends
    // every sentence to both the multicast group and the subnet-broadcast
    // fallback, and this source binds wildcard *and* joins the group, so on
    // an AP that forwards multicast the identical datagram arrives twice. ---

    @Test
    fun a_duplicated_zshk_datagram_counts_once() =
        runBlocking {
            // Mutation: delete the isDuplicateShockLine() guard in
            // ingestSensorChannels.
            val port = 10302
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                val line = nmea("ZSHK,2.35")
                assertTrue(
                    "precondition: the first copy registers",
                    waitUntil(4_000) {
                        sendUdp(line, port)
                        source.beaconSensors.value.shockCount >= 1
                    },
                )
                // The AP's second copy of the exact same datagram — not a new
                // impact.
                sendUdp(line, port)
                sendUdp(line, port)
                Thread.sleep(200)
                assertEquals(
                    "duplicated datagrams within the dedup window must not double-count",
                    1,
                    source.beaconSensors.value.shockCount,
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun two_genuine_impacts_outside_the_dedup_window_both_count() =
        runBlocking {
            // Mutation: widen SHOCK_DEDUP_WINDOW_MS to swallow a real second
            // impact, or dedup on something coarser than exact line bytes.
            val port = 10303
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                assertTrue(
                    "precondition: first impact registers",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZSHK,2.35"), port)
                        source.beaconSensors.value.shockCount >= 1
                    },
                )
                // A real second impact well outside the ~200 ms dedup window
                // (identical peak-g is realistic — two similar bumps) must
                // not be swallowed as a duplicate of the first.
                Thread.sleep(400)
                sendUdp(nmea("ZSHK,2.35"), port)
                assertTrue(
                    "a genuine second impact outside the dedup window must count",
                    waitUntil(4_000) { source.beaconSensors.value.shockCount >= 2 },
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    // --- start() must be genuinely idempotent (AUDIT-2026-08-09 C5):
    // CockpitViewModel init calls select(saved) — which already starts the
    // source — then calls start() again unconditionally. Before this fix
    // that cancelled and relaunched the live listener and re-acquired (i.e.
    // leaked) the multicast lock on every app launch for every fleet tablet,
    // since every fleet tablet persists NET. ---

    private class FakeMulticastLockHandle : MulticastLockHandle {
        var acquireCalls = 0
            private set
        var releaseCalls = 0
            private set
        override var isHeld: Boolean = false
            private set

        override fun acquire() {
            acquireCalls++
            isHeld = true
        }

        override fun release() {
            releaseCalls++
            isHeld = false
        }
    }

    @Test
    fun double_start_acquires_the_multicast_lock_exactly_once() =
        runBlocking {
            // Mutation: remove the `job?.isActive == true` guard at the top
            // of start().
            val port = 10304
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val lock = FakeMulticastLockHandle()
            val source = NetworkLocationSource(scope = scope, port = port, multicastLockHandle = lock)
            try {
                source.start()
                // The exact double-start CockpitViewModel init performs:
                // select(saved) already started it, then start() again.
                source.start()
                assertEquals("a redundant start() must not re-acquire the lock", 1, lock.acquireCalls)
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun stop_releases_the_multicast_lock_acquired_by_start() =
        runBlocking {
            // Mutation: delete the multicastLockHandle.release() call in stop().
            val port = 10305
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val lock = FakeMulticastLockHandle()
            val source = NetworkLocationSource(scope = scope, port = port, multicastLockHandle = lock)
            source.start()
            source.stop()
            scope.cancel()
            assertEquals(1, lock.acquireCalls)
            assertEquals(1, lock.releaseCalls)
        }

    @Test
    fun a_redundant_start_does_not_reset_a_live_fix_to_searching() =
        runBlocking {
            // The listener-relaunch half of the C5 bug: a second start() must
            // not cancel and restart the live listener, which would drop
            // straight back to Searching even though real fixes are still
            // arriving.
            val port = 10306
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                assertTrue(
                    "precondition: an active fix",
                    waitUntil(4_000) {
                        sendUdp(validGga, port)
                        source.state.value is LocationSourceState.Active
                    },
                )
                source.start() // redundant, as CockpitViewModel init performs
                assertTrue(
                    "a redundant start() must not discard a live fix",
                    source.state.value is LocationSourceState.Active,
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun stop_then_start_does_not_resurrect_the_old_readings() =
        runBlocking {
            val port = 10199
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                assertTrue(
                    "precondition: readings populated",
                    waitUntil(4_000) {
                        sendUdp(nmea("ZBCN,87,1,9,3600"), port)
                        source.beaconSensors.value.beaconHealth?.batteryPct == 87
                    },
                )
                source.stop()
                assertEquals(null, source.beaconSensors.value.beaconHealth)
                source.start()
                // Old readings must not resurrect just from restarting — only
                // a fresh datagram should repopulate them.
                assertEquals(null, source.beaconSensors.value.beaconHealth)
                assertEquals(null, source.telemetry.value)
                assertEquals(null, source.audioLevel.value)
            } finally {
                source.stop()
                scope.cancel()
            }
        }
}
