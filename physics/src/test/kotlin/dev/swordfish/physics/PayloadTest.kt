package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PayloadTest {

    private val fullTankKg = Units.gallonsToKg(11.9)

    // --- The two entry paths, neither privileged ---

    @Test
    fun `average and exact occupants are interchangeable to the model`() {
        // The whole point of the design: the physics does not care which way
        // a mass arrived, so nothing downstream can treat "average" as
        // second-class data needing a nag toward precision.
        val viaAverage = Payload(Occupant.Average(AdultAverage.ADULT))
        val viaExact = Payload(Occupant.Exact.ofPounds(180.0))
        assertEquals(viaAverage.totalKg, viaExact.totalKg, 1e-9)
    }

    @Test
    fun `standard adult masses are the documented figures`() {
        assertEquals(180.0, Units.kgToLb(AdultAverage.ADULT.massKg), 0.1)
        assertEquals(200.0, Units.kgToLb(AdultAverage.ADULT_MALE.massKg), 0.1)
        assertEquals(170.0, Units.kgToLb(AdultAverage.ADULT_FEMALE.massKg), 0.1)
    }

    @Test
    fun `the neutral adult default sits between the gendered averages`() {
        // ADULT exists so the UI can offer an option that requires stating
        // nothing at all -- not even a category.
        assertTrue(AdultAverage.ADULT.massKg < AdultAverage.ADULT_MALE.massKg)
        assertTrue(AdultAverage.ADULT.massKg > AdultAverage.ADULT_FEMALE.massKg)
    }

    @Test
    fun `exact entry accepts pounds or kilograms`() {
        assertEquals(
            Occupant.Exact.ofPounds(154.0).massKg,
            Occupant.Exact(69.85).massKg,
            0.01
        )
    }

    @Test
    fun `a negative exact mass is clamped rather than corrupting the model`() {
        assertEquals(0.0, Occupant.Exact(-50.0).massKg, 1e-9)
    }

    // --- Totals ---

    @Test
    fun `solo default is one standard adult and no cargo`() {
        val p = Payload.SOLO_DEFAULT
        assertEquals(1, p.crewCount)
        assertEquals(180.0, p.totalLb, 0.1)
    }

    @Test
    fun `adding a passenger raises crew count and mass`() {
        val p = Payload(
            driver = Occupant.Average(AdultAverage.ADULT),
            passenger = Occupant.Average(AdultAverage.ADULT_FEMALE)
        )
        assertEquals(2, p.crewCount)
        assertEquals(350.0, p.totalLb, 0.1)
    }

    @Test
    fun `cargo adds to the total`() {
        val p = Payload(
            driver = Occupant.Exact.ofPounds(175.0),
            cargoKg = Units.lbToKg(40.0)
        )
        assertEquals(215.0, p.totalLb, 0.1)
    }

    @Test
    fun `negative cargo is ignored rather than subtracting mass`() {
        val p = Payload(Occupant.Average(AdultAverage.ADULT), cargoKg = -100.0)
        assertEquals(180.0, p.totalLb, 0.1)
    }

    @Test
    fun `two up convenience builder matches an explicit construction`() {
        val a = Payload.twoUp(cargoLb = 30.0)
        val b = Payload(
            driver = Occupant.Average(AdultAverage.ADULT),
            passenger = Occupant.Average(AdultAverage.ADULT),
            cargoKg = Units.lbToKg(30.0)
        )
        assertEquals(b.totalKg, a.totalKg, 1e-9)
        assertEquals(390.0, a.totalLb, 0.1)
    }

    // --- Description strings for the settings screen ---

    @Test
    fun `describe names the crew without exposing exact masses`() {
        val p = Payload(
            driver = Occupant.Average(AdultAverage.ADULT_MALE),
            passenger = Occupant.Average(AdultAverage.ADULT_FEMALE),
            cargoKg = Units.lbToKg(25.0)
        )
        val d = p.describe()
        assertTrue(d.contains("Crew 2"))
        assertTrue(d.contains("average adult male"))
        assertTrue(d.contains("average adult female"))
        assertTrue(d.contains("cargo 25 lb"))
    }

    @Test
    fun `describe omits cargo when there is none`() {
        assertTrue(!Payload.SOLO_DEFAULT.describe().contains("cargo"))
    }

    // --- Effect on the delta-V model ---

    @Test
    fun `payload feeds through to structural mass`() {
        val solo = Vehicle.ND2_CLUB
        val loaded = Vehicle.ND2_CLUB.copy(payload = Payload.twoUp(cargoLb = 50.0))
        assertTrue(loaded.structuralMassKg > solo.structuralMassKg)
        // driver 180 + passenger 180 + cargo 50 = 410 vs 180 solo -> +230 lb
        assertEquals(
            230.0,
            Units.kgToLb(loaded.structuralMassKg - solo.structuralMassKg),
            0.5
        )
    }

    @Test
    fun `more payload lowers the mass ratio and so the delta v budget`() {
        // Heavier dry mass means the same fuel buys less: the rocket
        // equation's core trade, and a real (if small) effect here.
        val solo = Vehicle.ND2_CLUB
        val loaded = Vehicle.ND2_CLUB.copy(payload = Payload.twoUp(cargoLb = 50.0))
        val isp = 30_000.0

        val dvSolo = DeltaVModel.tsiolkovskyDeltaV(solo, fullTankKg, isp)
        val dvLoaded = DeltaVModel.tsiolkovskyDeltaV(loaded, fullTankKg, isp)
        assertTrue(dvLoaded < dvSolo)
    }

    @Test
    fun `rounding an occupant mass barely moves the readout`() {
        // The honest answer to "does it matter if I use the average?" is
        // "barely" -- and the UI should say so. A 30 lb difference shifts
        // delta-V by well under two percent.
        val exact = Vehicle.ND2_CLUB.copy(
            payload = Payload(Occupant.Exact.ofPounds(210.0))
        )
        val averaged = Vehicle.ND2_CLUB.copy(
            payload = Payload(Occupant.Average(AdultAverage.ADULT))
        )
        val isp = 30_000.0
        val dvExact = DeltaVModel.tsiolkovskyDeltaV(exact, fullTankKg, isp)
        val dvAvg = DeltaVModel.tsiolkovskyDeltaV(averaged, fullTankKg, isp)

        val relDiff = kotlin.math.abs(dvExact - dvAvg) / dvAvg
        assertTrue(relDiff < 0.02, "30 lb changed delta-V by ${relDiff * 100}%")
    }

    @Test
    fun `payload also raises rolling resistance`() {
        // Mass shows up twice in road load: through the rocket equation and
        // through Crr*m*g. The second is the larger effect for a car.
        val solo = Vehicle.ND2_CLUB
        val loaded = Vehicle.ND2_CLUB.copy(payload = Payload.twoUp(cargoLb = 50.0))
        val rrSolo = DeltaVModel.rollingResistanceNewtons(
            solo, solo.totalMassKg(fullTankKg), 0.0
        )
        val rrLoaded = DeltaVModel.rollingResistanceNewtons(
            loaded, loaded.totalMassKg(fullTankKg), 0.0
        )
        assertTrue(rrLoaded > rrSolo)
    }
}
