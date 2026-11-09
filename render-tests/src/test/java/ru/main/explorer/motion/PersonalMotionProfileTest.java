package ru.main.explorer.motion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class PersonalMotionProfileTest {
    private boolean confirm(
            PersonalMotionProfile profile, String id, MovementType type, double speed) {
        return profile.confirm(id, type, Arrays.asList(speed, speed, speed), 60_000L, 20.0);
    }

    private PersonalMotionProfile trained(double walk, double run) {
        PersonalMotionProfile profile = new PersonalMotionProfile();
        for (int index = 0; index < 3; index++) {
            assertTrue(confirm(profile, "walk-" + index, MovementType.WALK, walk));
            assertTrue(confirm(profile, "run-" + index, MovementType.RUN, run));
        }
        return profile;
    }

    @Test
    public void needsThreeIndependentSegmentsOfEachType() {
        PersonalMotionProfile profile = new PersonalMotionProfile();
        for (int index = 0; index < 3; index++) {
            confirm(profile, "walk-" + index, MovementType.WALK, 1.0);
        }
        confirm(profile, "run-0", MovementType.RUN, 3.0);
        confirm(profile, "run-1", MovementType.RUN, 3.0);
        assertFalse(profile.getReady());
        assertEquals(MovementType.UNKNOWN, profile.suggest(1.0));
        confirm(profile, "run-2", MovementType.RUN, 3.0);
        assertTrue(profile.getReady());
    }

    @Test
    public void fastWalkingAndSlowRunningAreNotForcedAcrossSpeedCutoff() {
        PersonalMotionProfile profile = trained(3.0, 1.2);
        assertEquals(MovementType.WALK, profile.suggest(3.0));
        assertEquals(MovementType.RUN, profile.suggest(1.2));
        assertEquals(MovementType.UNKNOWN, profile.suggest(8.0));
    }

    @Test
    public void overlappingDistributionsRemainUnknown() {
        PersonalMotionProfile profile = trained(2.0, 2.1);
        assertTrue(profile.getReady());
        assertEquals(MovementType.UNKNOWN, profile.suggest(2.05));
        assertEquals(MovementType.ON_FOOT, profile.suggest(2.05, MovementType.ON_FOOT));
    }

    @Test
    public void strongLabelsAreNeverOverwrittenByProfile() {
        PersonalMotionProfile profile = trained(1.0, 3.0);
        assertEquals(MovementType.WALK, profile.suggest(3.0, MovementType.WALK));
        assertEquals(MovementType.RUN, profile.suggest(1.0, MovementType.RUN));
        assertEquals(MovementType.VEHICLE, profile.suggest(1.0, MovementType.VEHICLE));
        assertEquals(MovementType.WALK, profile.suggest(1.0, MovementType.ON_FOOT));
    }

    @Test
    public void repeatedConfirmationReplacesAndCorrectionMovesContribution() {
        PersonalMotionProfile profile = trained(1.0, 3.0);
        confirm(profile, "walk-0", MovementType.WALK, 1.1);
        assertEquals(3, profile.getWalkCount());
        confirm(profile, "walk-0", MovementType.RUN, 3.0);
        assertEquals(2, profile.getWalkCount());
        assertEquals(4, profile.getRunCount());
        assertFalse(profile.getReady());
    }

    @Test
    public void removalAndResetRetractContributions() {
        PersonalMotionProfile profile = trained(1.0, 3.0);
        assertTrue(profile.remove("walk-0"));
        assertFalse(profile.remove("walk-0"));
        assertFalse(profile.getReady());
        profile.reset();
        assertEquals(0, profile.getWalkCount());
        assertEquals(0, profile.getRunCount());
    }

    @Test
    public void rejectsShortInaccurateUnsupportedAndInvalidSamples() {
        PersonalMotionProfile profile = new PersonalMotionProfile();
        assertFalse(profile.confirm("short", MovementType.WALK, Arrays.asList(1.0), 59_999, 10));
        assertFalse(profile.confirm("bad", MovementType.RUN, Arrays.asList(1.0), 60_000, 20.01));
        assertFalse(profile.confirm("bad", MovementType.RUN, Arrays.asList(1.0), 60_000, -1));
        assertFalse(
                profile.confirm("bad", MovementType.RUN, Arrays.asList(1.0), 60_000, Double.NaN));
        assertFalse(confirm(profile, "", MovementType.WALK, 1.0));
        assertFalse(confirm(profile, "foot", MovementType.ON_FOOT, 1.0));
        assertFalse(confirm(profile, "car", MovementType.VEHICLE, 1.0));
        for (double speed : new double[] {0, -1, 15.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(confirm(profile, "invalid", MovementType.RUN, speed));
        }
        assertFalse(
                profile.confirm("empty", MovementType.WALK, Collections.emptyList(), 60_000, 10));
        assertEquals(0, profile.getWalkCount());
        assertEquals(0, profile.getRunCount());
    }

    @Test
    public void qualitySpeedLimitIsNotWalkingRunningBoundary() {
        PersonalMotionProfile profile = trained(15.0, 0.2);
        assertEquals(MovementType.WALK, profile.suggest(15.0));
        assertEquals(MovementType.RUN, profile.suggest(0.2));
        assertEquals(MovementType.UNKNOWN, profile.suggest(Double.NaN));
        assertEquals(MovementType.UNKNOWN, profile.suggest(-1));
    }

    @Test
    public void longSegmentHasBoundedContributionAndOutlierDoesNotDominate() {
        PersonalMotionProfile profile = trained(1.0, 3.0);
        List<Double> longSegment = new ArrayList<>(Collections.nCopies(10_000, 1.0));
        longSegment.add(15.0);
        assertTrue(profile.confirm("walk-0", MovementType.WALK, longSegment, 9_000_000, 10));
        assertEquals(9, profile.exportSamples().get(0).getSpeeds().size());
        assertEquals(MovementType.WALK, profile.suggest(1.0));
        assertEquals(MovementType.RUN, profile.suggest(3.0));
    }

    @Test
    public void suggestionsDoNotSelfTrain() {
        PersonalMotionProfile profile = new PersonalMotionProfile();
        for (int index = 0; index < 100; index++) {
            profile.suggest(1.0, MovementType.WALK);
            profile.suggest(3.0, MovementType.RUN);
        }
        assertFalse(profile.getReady());
        assertTrue(profile.exportSamples().isEmpty());
    }

    @Test
    public void exportedSamplesRoundTripAndAreDetached() {
        PersonalMotionProfile original = trained(1.0, 3.0);
        List<ConfirmedMotionSample> data = original.exportSamples();
        PersonalMotionProfile restored = new PersonalMotionProfile();
        assertTrue(restored.importSamples(data));
        assertTrue(restored.getReady());
        assertEquals(MovementType.WALK, restored.suggest(1.0));
        assertEquals(MovementType.RUN, restored.suggest(3.0));
        data.get(0).getSpeeds().set(0, 15.0);
        data.clear();
        assertEquals(3, restored.getWalkCount());
        assertEquals(1.0, original.exportSamples().get(0).getSpeeds().get(0), 0.0);
    }

    @Test
    public void invalidAndDuplicateImportsAreAtomic() {
        PersonalMotionProfile profile = trained(1.0, 3.0);
        ConfirmedMotionSample sample = profile.exportSamples().get(0);
        assertFalse(profile.importSamples(Arrays.asList(sample, sample)));
        ConfirmedMotionSample invalid =
                new ConfirmedMotionSample(
                        "invalid", MovementType.VEHICLE, Arrays.asList(1.0), 60_000, 10);
        assertFalse(profile.importSamples(Arrays.asList(invalid)));
        assertTrue(profile.getReady());
        assertEquals(3, profile.getWalkCount());
        assertTrue(profile.importSamples(Collections.emptyList()));
        assertFalse(profile.getReady());
    }

    @Test
    public void rejectedReplacementLeavesPriorConfirmationIntact() {
        PersonalMotionProfile profile = trained(1.0, 3.0);
        assertFalse(confirm(profile, "walk-0", MovementType.RUN, Double.NaN));
        assertTrue(profile.getReady());
        assertEquals(3, profile.getWalkCount());
    }
}
