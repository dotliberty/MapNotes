package ru.main.explorer.motion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MotionClassifierTest {
    private MotionClassifier classifier() {
        return new MotionClassifier(() -> 1_000_000L);
    }

    private void sustain(MotionClassifier classifier, MovementType type, long start) {
        classifier.observe(type, 90, start);
        classifier.observe(type, 90, start + 5_000L);
        classifier.observe(type, 90, start + 10_000L);
    }

    @Test
    public void singleHighConfidenceEventCannotSwitch() {
        MotionClassifier classifier = classifier();
        assertEquals(MovementType.UNKNOWN, classifier.observe(MovementType.RUN, 100, 0));
        assertEquals(MovementType.UNKNOWN, classifier.current(10_000));
    }

    @Test
    public void repeatedEvidenceMustSpanTenSeconds() {
        MotionClassifier classifier = classifier();
        classifier.observe(MovementType.WALK, 90, 0);
        classifier.observe(MovementType.WALK, 90, 1);
        assertEquals(MovementType.UNKNOWN, classifier.observe(MovementType.WALK, 90, 2));
        assertEquals(MovementType.WALK, classifier.observe(MovementType.WALK, 90, 10_000));
    }

    @Test
    public void conflictingCandidateDoesNotOscillate() {
        MotionClassifier classifier = classifier();
        sustain(classifier, MovementType.WALK, 0);
        classifier.observe(MovementType.RUN, 100, 11_000);
        classifier.observe(MovementType.VEHICLE, 100, 16_000);
        assertEquals(MovementType.WALK, classifier.observe(MovementType.RUN, 100, 21_000));
        sustain(classifier, MovementType.RUN, 26_000);
        assertEquals(MovementType.RUN, classifier.current(36_000));
    }

    @Test
    public void onFootIsNotInventedWalking() {
        MotionClassifier classifier = classifier();
        sustain(classifier, MovementType.ON_FOOT, 0);
        assertEquals(MovementType.ON_FOOT, classifier.current(10_000));
    }

    @Test
    public void candidateCannotBridgeMissingEvents() {
        MotionClassifier classifier = classifier();
        classifier.observe(MovementType.RUN, 100, 0);
        classifier.observe(MovementType.RUN, 100, 20_000);
        assertEquals(MovementType.UNKNOWN, classifier.observe(MovementType.RUN, 100, 25_000));
        assertEquals(MovementType.RUN, classifier.observe(MovementType.RUN, 100, 30_000));
    }

    @Test
    public void oldAndDuplicateObservationsCannotAccumulateEvidence() {
        MotionClassifier classifier = classifier();
        classifier.observe(MovementType.RUN, 90, 10_000);
        classifier.observe(MovementType.RUN, 90, 0);
        classifier.observe(MovementType.RUN, 90, 10_000);
        assertEquals(MovementType.UNKNOWN, classifier.observe(MovementType.RUN, 90, 20_000));
    }

    @Test
    public void futureAndNegativeTimesDoNotPoisonClock() {
        MotionClassifier classifier = classifier();
        classifier.observe(MovementType.VEHICLE, 90, 1_000_001);
        classifier.observe(MovementType.VEHICLE, 90, -1);
        sustain(classifier, MovementType.WALK, 0);
        assertEquals(MovementType.WALK, classifier.current(10_000));
    }

    @Test
    public void queriesEstablishMonotonicWatermark() {
        MotionClassifier classifier = classifier();
        classifier.current(20_000);
        sustain(classifier, MovementType.RUN, 0);
        assertEquals(MovementType.UNKNOWN, classifier.current(20_000));
    }

    @Test
    public void invalidConfidenceDoesNotTrainOrAdvanceTime() {
        MotionClassifier classifier = classifier();
        classifier.observe(MovementType.RUN, 101, 50_000);
        classifier.observe(MovementType.RUN, -1, 50_000);
        sustain(classifier, MovementType.WALK, 0);
        assertEquals(MovementType.WALK, classifier.current(10_000));
    }

    @Test
    public void lowConfidenceBreaksCandidateAndDoesNotRefreshActivity() {
        MotionClassifier classifier = classifier();
        sustain(classifier, MovementType.WALK, 0);
        classifier.observe(MovementType.RUN, 90, 15_000);
        classifier.observe(MovementType.RUN, 1, 20_000);
        assertEquals(MovementType.WALK, classifier.observe(MovementType.RUN, 90, 25_000));
        assertEquals(MovementType.UNKNOWN, classifier.current(85_000));
    }

    @Test
    public void stateExpiresExactlyAfterSixtySeconds() {
        MotionClassifier classifier = classifier();
        sustain(classifier, MovementType.WALK, 0);
        assertEquals(MovementType.WALK, classifier.current(69_999));
        assertEquals(MovementType.UNKNOWN, classifier.current(70_000));
    }

    @Test
    public void stillPreservesVehicleButDoesNotKeepItAliveForever() {
        MotionClassifier classifier = classifier();
        sustain(classifier, MovementType.VEHICLE, 0);
        assertEquals(MovementType.VEHICLE, classifier.stopped(20_000));
        assertEquals(MovementType.UNKNOWN, classifier.stopped(70_000));
    }

    @Test
    public void stillBreaksCandidate() {
        MotionClassifier classifier = classifier();
        classifier.observe(MovementType.RUN, 90, 0);
        classifier.observe(MovementType.RUN, 90, 5_000);
        classifier.stopped(6_000);
        assertEquals(MovementType.UNKNOWN, classifier.observe(MovementType.RUN, 90, 10_000));
    }

    @Test
    public void manualOverrideAndClearingRequireNewEvidence() {
        MotionClassifier classifier = classifier();
        sustain(classifier, MovementType.RUN, 0);
        classifier.setManual(MovementType.WALK);
        sustain(classifier, MovementType.VEHICLE, 20_000);
        assertEquals(MovementType.WALK, classifier.current(100_000));
        classifier.setManual(null);
        assertEquals(MovementType.UNKNOWN, classifier.current(100_000));
        sustain(classifier, MovementType.RUN, 100_001);
        assertEquals(MovementType.RUN, classifier.current(110_001));
    }

    @Test
    public void resetClearsManualAndSessionClock() {
        MotionClassifier classifier = classifier();
        classifier.setManual(MovementType.WALK);
        classifier.current(100_000);
        classifier.reset();
        sustain(classifier, MovementType.RUN, 0);
        assertEquals(MovementType.RUN, classifier.current(10_000));
    }
}
