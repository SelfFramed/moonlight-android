package com.limelight.selfframed.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.selfframed.grading.GradingPreset;

import org.junit.Test;

public class PanelValuesTest {
    @Test
    public void presetSlotsCycleWithinOneToNine() {
        assertEquals(2, PanelValues.cyclePresetSlot(1, 1));
        assertEquals(1, PanelValues.cyclePresetSlot(9, 1));
        assertEquals(9, PanelValues.cyclePresetSlot(1, -1));
        assertEquals(4, PanelValues.cyclePresetSlot(5, -1));
        // From neutral the cycle starts at slot 1
        assertEquals(2, PanelValues.cyclePresetSlot(0, 1));
        assertEquals(9, PanelValues.cyclePresetSlot(0, -1));
    }

    @Test
    public void weightStepsStayOnAThreeDecimalGridAndInRange() {
        assertEquals(0.309, PanelValues.stepWeight(GradingPreset.MONO_DEFAULT_R, PanelValues.WEIGHT_STEP), 0);
        assertEquals(0.014, PanelValues.stepWeight(GradingPreset.MONO_DEFAULT_B, -PanelValues.WEIGHT_STEP_COARSE), 0);
        assertEquals(0, PanelValues.stepWeight(0.05, -PanelValues.WEIGHT_STEP_COARSE), 0);
        assertEquals(1, PanelValues.stepWeight(0.95, PanelValues.WEIGHT_STEP_COARSE), 0);
        double w = 0.299;
        for (int i = 0; i < 37; i++) {
            w = PanelValues.stepWeight(w, PanelValues.WEIGHT_STEP);
        }
        assertEquals(0.669, w, 0);
    }

    @Test
    public void weightPercentsAlwaysSumToHundred() {
        int[] out = new int[3];
        assertTrue(PanelValues.weightPercents(0.299, 0.587, 0.114, out));
        assertArrayEquals(new int[] {30, 59, 11}, out);
        assertTrue(PanelValues.weightPercents(1, 1, 1, out));
        assertEquals(100, out[0] + out[1] + out[2]);
        assertTrue(PanelValues.weightPercents(0.2, 0, 0, out));
        assertArrayEquals(new int[] {100, 0, 0}, out);
        int[] untouched = {7, 7, 7};
        assertFalse(PanelValues.weightPercents(0, 0, 0, untouched));
        assertArrayEquals(new int[] {7, 7, 7}, untouched);
    }

    @Test
    public void curveCoordinatesMapToByteValues() {
        assertEquals(0, PanelValues.toByte(0));
        assertEquals(255, PanelValues.toByte(1));
        assertEquals(128, PanelValues.toByte(0.5));
        assertEquals(0, PanelValues.toByte(-0.2));
        assertEquals(255, PanelValues.toByte(1.5));
    }
}
