package com.dehnes.smarthome.service.ev_charging_station

import com.dehnes.smarthome.api.dtos.EvChargingMode
import com.dehnes.smarthome.api.dtos.EvChargingStationClient
import com.dehnes.smarthome.api.dtos.ProximityPilotAmps
import com.dehnes.smarthome.config.ConfigService
import com.dehnes.smarthome.config.EvCharger
import com.dehnes.smarthome.config.EvChargerSettings
import com.dehnes.smarthome.config.PowerConnectionSettings
import com.dehnes.smarthome.energy_pricing.CategorizedPrice
import com.dehnes.smarthome.energy_pricing.EnergyPriceService
import com.dehnes.smarthome.energy_pricing.Price
import com.dehnes.smarthome.energy_pricing.PriceCategory
import com.dehnes.smarthome.ev_charging.*
import com.dehnes.smarthome.users.UserSettingsService
import com.dehnes.smarthome.victron.VictronService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jdk.jfr.Enabled
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ExecutorService
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class EvChargingServiceTest {

    var time = Instant.now()
    val energyPriceService = mockk<EnergyPriceService>()
    val executorService = mockk<ExecutorService>()
    val configService = mockk<ConfigService>()
    val eVChargingStationConnection = mockk<EvChargingStationConnection>()
    val clockMock = mockk<Clock>()
    val userSettingsService = mockk<UserSettingsService>()

    var evSettings = EvChargerSettings(
        powerConnections = mapOf(
            "unknown" to PowerConnectionSettings()
        ),
    )

    val allChargingsStations = mutableMapOf<String, TestChargingStation>()
    val onlineChargingStations = mutableSetOf<String>()
    val victronService = mockk<VictronService>()

    init {
        every {
            energyPriceService.findSuitablePrices(any(), any(), any())
        } answers {
            listOf(CategorizedPrice(
                PriceCategory.cheap,
                Price(
                    Instant.parse("2010-01-01T00:00:00Z"),
                    Instant.parse("2050-01-01T00:00:00Z"),
                    0.0
                )
            ))
        }

        val slot = slot<Runnable>()
        every {
            executorService.submit(any())
        } answers {
            slot.captured.run()
            null
        }

        every {
            configService.getEvSettings()
        } answers {
            evSettings
        }

        val clientIdSlot = slot<String>()
        val contactorSlot = slot<Boolean>()
        every {
            eVChargingStationConnection.setContactorState(
                capture(clientIdSlot),
                capture(contactorSlot)
            )
        } answers {
            if (clientIdSlot.captured in onlineChargingStations) {
                (allChargingsStations[clientIdSlot.captured]
                    ?: error("Missing charging station ${clientIdSlot.captured}")).contactorOn = contactorSlot.captured
                true
            } else
                false
        }

        val pwmSlot = slot<Int>()
        every {
            eVChargingStationConnection.setPwmPercent(
                capture(clientIdSlot),
                capture(pwmSlot)
            )
        } answers {
            if (clientIdSlot.captured in onlineChargingStations) {
                (allChargingsStations[clientIdSlot.captured]
                    ?: error("Missing charging station ${clientIdSlot.captured}")).pwmPercent = pwmSlot.captured
                true
            } else
                false
        }

        every {
            clockMock.millis()
        } answers {
            time.toEpochMilli()
        }

        every {
            victronService.isGridOk()
        } returns true

        every {
            userSettingsService.canUserRead(any(), any())
        } returns true
        every {
            userSettingsService.canUserWrite(any(), any())
        } returns true
    }

    val evChargingService = EvChargingService(
        eVChargingStationConnection,
        executorService,
        energyPriceService,
        configService,
        clockMock,
        mapOf(
            PriorityLoadSharing::class.java.simpleName to PriorityLoadSharing(clockMock)
        ),
        victronService,
        userSettingsService,
        mockk(relaxed = true)
    )

    @Test
    fun testOnlineAndUnconnected() {

        val s1 = newTestStation("s1")
        val s2 = newTestStation("s2")

        collectDataCycle()

        assertFalse(s1.contactorOn)
        assertEquals(100, s1.pwmPercent)
        assertFalse(s2.contactorOn)
        assertEquals(100, s2.pwmPercent)
    }

    private fun setModeFor(c: String, m: EvChargingMode) {
        val ev = evSettings.chargers[c]!!
        evSettings = evSettings.copy(
            chargers = evSettings.chargers + (ev.serialNumber to ev.copy(
                evChargingMode = m
            ))
        )
    }
    private fun setCurrentPowerConnectionCapacity(value: Int) {
        val powerConnections = evSettings.powerConnections["unknown"]!!
        evSettings = evSettings.copy(
            powerConnections = evSettings.powerConnections + (powerConnections.name to powerConnections.copy(
                availableCapacity = value
            ))
        )
    }

    @Test
    @Disabled
    fun testPlayground() {

        val s1 = newTestStation("s1")
        setModeFor("s1",EvChargingMode.OFF)
        s1.phase1Milliamps = -387
        s1.phase2Milliamps = -265
        s1.phase3Milliamps = -666
        s1.pilotVoltage = PilotVoltage.Volt_12
        s1.pwmPercent = 100
        s1.contactorOn = false

        collectDataCycle()

        s1.pilotVoltage = PilotVoltage.Volt_9
        s1.phase1Milliamps = -240
        s1.phase2Milliamps = -106
        s1.phase3Milliamps = -840

        collectDataCycle()
        collectDataCycle()

        setModeFor("s1",EvChargingMode.ON)

        collectDataCycle() // -> ConnectedChargingAvailable

        s1.pwmPercent = 11
        s1.pilotVoltage = PilotVoltage.Volt_6
        s1.phase1Milliamps = -413
        s1.phase2Milliamps = -278
        s1.phase3Milliamps = -872

        collectDataCycle()

        // conactor -> on
        // why resending pwm 11?
        s1.pwmPercent = 51
        s1.contactorOn = true
        s1.phase1Milliamps = -360
        s1.phase2Milliamps = -437
        s1.phase3Milliamps = -919


        collectDataCycle()
        collectDataCycle()

    }

    @Test
    fun testOnlineAndOneStartsChargingGetsAll() {

        val s1 = newTestStation("s1")
        val s2 = newTestStation("s2")

        collectDataCycle()

        // Car connects
        s1.pilotVoltage = PilotVoltage.Volt_9

        collectDataCycle(secondsToAdd = 10)

        assertFalse(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(LOWEST_CHARGE_RATE), s1.pwmPercent)
        assertFalse(s2.contactorOn)
        assertEquals(PWM_OFF, s2.pwmPercent)

        // Car ready to charge
        s1.pilotVoltage = PilotVoltage.Volt_6

        collectDataCycle(secondsToAdd = 10)
        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(32), s1.pwmPercent)
        assertFalse(s2.contactorOn)
        assertEquals(100, s2.pwmPercent)

        // Car reached max charging rate
        s1.setMeasuredCurrent(32)
        collectDataCycle(times = 20, secondsToAdd = 10)

        // Second car connects
        s2.pilotVoltage = PilotVoltage.Volt_9
        collectDataCycle(secondsToAdd = 10)
        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(32), s1.pwmPercent)
        assertFalse(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(LOWEST_CHARGE_RATE), s2.pwmPercent)

        // Second car starts charging
        s2.pilotVoltage = PilotVoltage.Volt_6
        collectDataCycle(secondsToAdd = 10)

        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(16), s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(16), s2.pwmPercent)

        // both reach max current
        s1.setMeasuredCurrent(16)
        s2.setMeasuredCurrent(16)

        collectDataCycle(secondsToAdd = 10)

        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(16), s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(16), s2.pwmPercent)

        // Test power connection capacity drops
        setCurrentPowerConnectionCapacity(16)
        collectDataCycle(secondsToAdd = 10)
        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(8), s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(8), s2.pwmPercent)

        // Test power connection capacity drops to minimum
        setCurrentPowerConnectionCapacity(6)
        collectDataCycle(10, secondsToAdd = 10)
        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(6), s1.pwmPercent)
        assertFalse(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(0), s2.pwmPercent)

        // Test power connection capacity drops below minimum
        setCurrentPowerConnectionCapacity(2)
        collectDataCycle(10, secondsToAdd = 10)
        assertFalse(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(0), s1.pwmPercent)
        assertFalse(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(0), s2.pwmPercent)

        // Test power connection capacity back to normal
        setCurrentPowerConnectionCapacity(32)
        collectDataCycle(10, secondsToAdd = 10)
        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(16), s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(16), s2.pwmPercent)

        // Car 1 rate declining
        s1.setMeasuredCurrent(15)
        s2.setMeasuredCurrent(16)
        collectDataCycle(secondsToAdd = 10)
        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(16), s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(16), s2.pwmPercent)

        // Car 1 rate declining below threshold
        s1.setMeasuredCurrent(13)
        s2.setMeasuredCurrent(16)
        collectDataCycle(60, secondsToAdd = 2)
        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(15), s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(17), s2.pwmPercent)

        // Car 1 rate declining below threshold even more
        s1.setMeasuredCurrent(10)
        s2.setMeasuredCurrent(16)
        collectDataCycle(10, secondsToAdd = 10)
        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(12), s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(20), s2.pwmPercent)

        // Car 1 rate declining below threshold even more
        s1.setMeasuredCurrent(1)
        s2.setMeasuredCurrent(16)
        collectDataCycle(10, secondsToAdd = 10)
        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(LOWEST_CHARGE_RATE), s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(26), s2.pwmPercent)

        // Car 1 rate declining below threshold
        s1.setMeasuredCurrent(0)
        s2.setMeasuredCurrent(16)
        collectDataCycle()
        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(LOWEST_CHARGE_RATE), s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(26), s2.pwmPercent)

        // Car 1 stops
        s1.pilotVoltage = PilotVoltage.Volt_9
        collectDataCycle()
        assertFalse(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(LOWEST_CHARGE_RATE), s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(32), s2.pwmPercent)

        // Car 1 ready
        s1.pilotVoltage = PilotVoltage.Volt_6
        collectDataCycle()
        assertTrue(s1.contactorOn)
        assertEquals(chargeRateToPwmPercent(16), s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(16), s2.pwmPercent)

        // Car 1 - stopped from app
        setModeFor(s1.clientId, EvChargingMode.OFF)
        collectDataCycle()
        assertTrue(s1.contactorOn)
        assertEquals(PWM_NO_CHARGING, s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(32), s2.pwmPercent)

        // 5 seconds later ...
        collectDataCycle(5, 10)
        assertFalse(s1.contactorOn)
        assertEquals(100, s1.pwmPercent)
        assertTrue(s2.contactorOn)
        assertEquals(chargeRateToPwmPercent(32), s2.pwmPercent)

        // car 2 gets a fault
        s2.pilotVoltage = PilotVoltage.Fault
        collectDataCycle()
        assertFalse(s1.contactorOn)
        assertEquals(100, s1.pwmPercent)
        assertFalse(s2.contactorOn)
        assertEquals(100, s2.pwmPercent)

    }

    private fun collectDataCycle(times: Int = 1, secondsToAdd: Long = 0) {
        repeat(times) {
            time = time.plusSeconds(secondsToAdd)
            allChargingsStations.forEach { (_, u) ->
                val timestamp = clockMock.millis()
                val dataResponse = u.toData(timestamp)
                val evChargingStationClient = u.evChargingStationClient
                evChargingService.onIncomingDataUpdate(evChargingStationClient, dataResponse)
            }
        }
    }

    private fun newTestStation(clientId: String, powerConnectionId: String = "unknown") = TestChargingStation(
        clientId,
        powerConnectionId,
        false,
        100,
        PilotVoltage.Volt_12,
        ProximityPilotAmps.Amp32,
        230 * 1000,
        230 * 1000,
        230 * 1000,
        0,
        0,
        0
    ).apply {
        allChargingsStations[clientId] = this
        onlineChargingStations.add(clientId)
        evSettings = evSettings.copy(
            chargers = evSettings.chargers + (clientId to EvCharger(
                clientId,
                clientId,
                clientId,
                powerConnectionId,
                CalibrationData(),
                EvChargingMode.ChargeDuringCheapHours,
                LoadSharingPriority.NORMAL,
                32
            ))
        )
    }


}

data class TestChargingStation(
    val clientId: String,
    val powerConnectionId: String,
    var contactorOn: Boolean,
    var pwmPercent: Int,
    var pilotVoltage: PilotVoltage,
    var proximityPilotAmps: ProximityPilotAmps,
    var phase1Millivolts: Int,
    var phase2Millivolts: Int,
    var phase3Millivolts: Int,
    var phase1Milliamps: Int,
    var phase2Milliamps: Int,
    var phase3Milliamps: Int
) {

    fun setMeasuredCurrent(amps: Int) {
        phase1Milliamps = amps * 1000
        phase2Milliamps = amps * 1000
        phase3Milliamps = amps * 1000
    }

    fun toData(timestamp: Long) = DataResponse(
        contactorOn,
        pwmPercent,
        pilotVoltage,
        proximityPilotAmps,
        phase1Millivolts,
        1,
        phase2Millivolts,
        1,
        phase3Millivolts,
        1,
        phase1Milliamps,
        1,
        phase2Milliamps,
        1,
        phase3Milliamps,
        1,
        -20,
        0,
        0,
        0,
        emptyList(),
        timestamp
    )

    val evChargingStationClient = EvChargingStationClient(
        clientId,
        clientId,
        "127.0.0.1",
        2020,
        1,
        powerConnectionId
    )
}