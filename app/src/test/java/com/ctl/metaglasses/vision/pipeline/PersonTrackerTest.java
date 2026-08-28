package com.ctl.metaglasses.vision.pipeline;

import com.ctl.metaglasses.vision.AgeLabel;
import com.ctl.metaglasses.vision.AgeVerdict;

import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PersonTrackerTest {
    @Test
    public void reusesTrackByEmbeddingSimilarity() {
        PersonTracker tracker = new PersonTracker(0.38f, 0.30f, 45, 0.10f, 60);

        PersonTracker.Track first = tracker.assign(
                new FaceBox(0, 0, 100, 100),
                new float[] {1f, 0f, 0f},
                0.7f,
                1
        );
        PersonTracker.Track second = tracker.assign(
                new FaceBox(300, 300, 400, 400),
                new float[] {0.95f, 0.05f, 0f},
                0.8f,
                20
        );

        assertEquals(first.personId, second.personId);
    }

    @Test
    public void createsNewTrackForDifferentEmbeddingAndLowIou() {
        PersonTracker tracker = new PersonTracker(0.38f, 0.30f, 45, 0.10f, 60);

        PersonTracker.Track first = tracker.assign(
                new FaceBox(0, 0, 100, 100),
                new float[] {1f, 0f, 0f},
                0.7f,
                1
        );
        PersonTracker.Track second = tracker.assign(
                new FaceBox(300, 300, 400, 400),
                new float[] {0f, 1f, 0f},
                0.8f,
                2
        );

        assertFalse(first.personId == second.personId);
    }

    @Test
    public void ageRefreshUsesQualityDeltaAndFrameAge() {
        PersonTracker tracker = new PersonTracker(0.38f, 0.30f, 45, 0.10f, 60);
        PersonTracker.Track track = tracker.assign(
                new FaceBox(0, 0, 100, 100),
                new float[] {1f, 0f, 0f},
                0.7f,
                1
        );
        track.updateAge(
                new AgeVerdict(AgeLabel.ADULT_CONFIRMED, 0.01f, 30f, 0.6f,
                        Collections.singletonList("test"), "test"),
                0.6f,
                1
        );

        assertFalse(tracker.shouldRefreshAge(track, 0.65f, 20));
        assertTrue(tracker.shouldRefreshAge(track, 0.71f, 20));
        assertTrue(tracker.shouldRefreshAge(track, 0.61f, 61));
    }
}
