package com.ctl.metaglasses.vision;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class AgeDisplayFormatterTest {
    @Test
    public void ageOver18ShowsMinorNo() {
        String text = AgeDisplayFormatter.format(24f, 0.7f);

        assertTrue(text.contains("Minor: No"));
        assertTrue(text.contains("Age: 24"));
        assertTrue(text.contains("Confidence score:"));
    }

    @Test
    public void highMinorProbabilityDoesNotOverrideAdultAge() {
        String text = AgeDisplayFormatter.format(24f, 0.9f);

        assertTrue(text.contains("Minor: No"));
        assertTrue(text.contains("Age: 24"));
    }

    @Test
    public void exactly18ShowsMinorNo() {
        String text = AgeDisplayFormatter.format(18f, 1f);

        assertTrue(text.contains("Minor: No"));
        assertTrue(text.contains("Age: 18"));
    }

    @Test
    public void ageUnder18ShowsMinorYes() {
        String text = AgeDisplayFormatter.format(16f, 0.2f);

        assertTrue(text.contains("Minor: Yes"));
        assertTrue(text.contains("Age: 16"));
    }

    @Test
    public void unknownAgeDoesNotClaimMinorFromPrivacyRisk() {
        String text = AgeDisplayFormatter.format(null, 0.8f);

        assertTrue(text.contains("Minor: No"));
        assertTrue(text.contains("Age: Unknown"));
    }
}
