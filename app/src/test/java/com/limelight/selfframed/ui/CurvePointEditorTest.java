package com.limelight.selfframed.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.limelight.selfframed.grading.ToneCurve;

import org.junit.Test;

import java.util.Random;

/**
 * Editing rules of the curve editor. The central promise: whatever the user does, the points
 * stay valid, so ToneCurve.of() never silently falls back to identity.
 */
public class CurvePointEditorTest {
    private static final double EPS = 1e-12;

    private static CurvePointEditor editorWith(double[][] points) {
        CurvePointEditor e = new CurvePointEditor();
        ToneCurve c = ToneCurve.of(points);
        assertTrue("fixture must be valid", ToneCurve.isValid(points));
        e.load(c);
        return e;
    }

    private static void assertValid(CurvePointEditor e) {
        assertTrue(ToneCurve.isValid(e.points()));
        ToneCurve c = e.toCurve();
        assertNotNull(c);
        assertEquals(e.size(), c.size());
        for (int i = 0; i < e.size(); i++) {
            assertEquals(e.x(i), c.x(i), 0);
            assertEquals(e.y(i), c.y(i), 0);
        }
        assertTrue(e.selected() >= 0 && e.selected() < e.size());
    }

    @Test
    public void startsAsIdentityWithFirstPointSelected() {
        CurvePointEditor e = new CurvePointEditor();
        assertEquals(2, e.size());
        assertEquals(0, e.selected());
        assertTrue(e.isIdentity());
        assertEquals(ToneCurve.IDENTITY, e.toCurve());
    }

    @Test
    public void endpointsKeepTheirXButMoveVertically() {
        CurvePointEditor e = new CurvePointEditor();
        assertTrue(e.moveSelectedBy(0.3, 0.2));
        assertEquals(0, e.x(0), 0);
        assertEquals(0.2, e.y(0), EPS);
        e.select(1);
        assertTrue(e.moveSelectedBy(-0.5, -0.1));
        assertEquals(1, e.x(1), 0);
        assertEquals(0.9, e.y(1), EPS);
        // Only x requested: nothing changes for an endpoint
        assertFalse(e.moveSelectedBy(0.1, 0));
        assertValid(e);
    }

    @Test
    public void yIsClampedToTheUnitRange() {
        CurvePointEditor e = new CurvePointEditor();
        e.moveSelectedBy(0, -5);
        assertEquals(0, e.y(0), 0);
        e.moveSelectedBy(0, 5);
        assertEquals(1, e.y(0), 0);
        assertFalse(e.moveSelectedBy(0, 0.5));
        assertValid(e);
    }

    @Test
    public void innerPointStaysBetweenItsNeighbours() {
        CurvePointEditor e = editorWith(new double[][] {{0, 0}, {0.3, 0.2}, {0.6, 0.7}, {1, 1}});
        e.select(1);
        e.moveSelectedBy(-1, 0);
        assertTrue(e.x(1) - e.x(0) >= ToneCurve.MIN_SPACING);
        assertEquals(ToneCurve.MIN_SPACING, e.x(1), 1e-9);
        e.moveSelectedBy(+1, 0);
        assertTrue(e.x(2) - e.x(1) >= ToneCurve.MIN_SPACING);
        assertEquals(0.6 - ToneCurve.MIN_SPACING, e.x(1), 1e-9);
        assertValid(e);
    }

    @Test
    public void spacingHoldsAfterFloatingPointSubtraction() {
        // Neighbours whose sums/differences with MIN_SPACING are not exact in binary
        double[][] pts = {{0, 0}, {0.1, 0.1}, {0.13, 0.5}, {0.7, 0.3}, {0.72, 0.9}, {1, 1}};
        CurvePointEditor e = editorWith(pts);
        for (int i = 1; i < e.size() - 1; i++) {
            e.select(i);
            e.moveSelectedBy(-10, 0);
            assertValid(e);
            e.moveSelectedBy(10, 0);
            assertValid(e);
        }
        assertEquals(0.115, CurvePointEditor.minXAfter(0.1), 1e-12);
        assertTrue(CurvePointEditor.minXAfter(0.1) - 0.1 >= ToneCurve.MIN_SPACING);
        assertTrue(0.715 - CurvePointEditor.maxXBefore(0.715) >= ToneCurve.MIN_SPACING);
    }

    @Test
    public void pointWithoutRoomDoesNotMoveSideways() {
        // Middle point already at minimal spacing on both sides
        double a = 0.5;
        double b = CurvePointEditor.minXAfter(a);
        double c = CurvePointEditor.minXAfter(b);
        CurvePointEditor e = editorWith(new double[][] {{0, 0}, {a, 0.4}, {b, 0.5}, {c, 0.6}, {1, 1}});
        e.select(2);
        e.moveSelectedBy(0.005, 0);
        assertEquals(b, e.x(2), 1e-12);
        assertValid(e);
        e.moveSelectedBy(-0.005, 0);
        assertEquals(b, e.x(2), 1e-12);
        assertValid(e);
        assertTrue(e.moveSelectedBy(0, 0.005));
        assertEquals(0.505, e.y(2), 1e-12);
        assertValid(e);
    }

    @Test
    public void insertGoesHalfwayToTheNextPointOnTheCurve() {
        CurvePointEditor e = new CurvePointEditor();
        int k = e.insertNextToSelected();
        assertEquals(1, k);
        assertEquals(1, e.selected());
        assertEquals(3, e.size());
        assertEquals(0.5, e.x(1), EPS);
        assertEquals(0.5, e.y(1), EPS);
        assertValid(e);
    }

    @Test
    public void insertFollowsTheCurveShape() {
        ToneCurve before = ToneCurve.of(new double[][] {{0, 0}, {0.25, 0.12}, {0.75, 0.88}, {1, 1}});
        CurvePointEditor e = new CurvePointEditor();
        e.load(before);
        e.select(2);
        int k = e.insertNextToSelected();
        assertEquals(3, k);
        assertEquals(0.875, e.x(3), EPS);
        assertEquals(before.apply(0.875), e.y(3), EPS);
        assertValid(e);
    }

    @Test
    public void insertFromTheLastPointUsesThePreviousGap() {
        CurvePointEditor e = new CurvePointEditor();
        e.select(1);
        int k = e.insertNextToSelected();
        assertEquals(1, k);
        assertEquals(0.5, e.x(1), EPS);
        assertEquals(1, e.selected());
        assertValid(e);
    }

    @Test
    public void insertTriesTheOtherSideWhenTheGapIsTooNarrow() {
        double b = CurvePointEditor.minXAfter(0.5);
        CurvePointEditor e = editorWith(new double[][] {{0, 0}, {0.5, 0.5}, {b, 0.6}, {1, 1}});
        e.select(1);
        int k = e.insertNextToSelected();
        assertEquals(1, k);
        assertEquals(0.25, e.x(1), EPS);
        assertValid(e);
    }

    @Test
    public void insertFailsWithoutRoom() {
        double b = CurvePointEditor.minXAfter(0.5);
        double c = CurvePointEditor.minXAfter(b);
        CurvePointEditor e = editorWith(new double[][] {{0, 0}, {0.5, 0.5}, {b, 0.5}, {c, 0.6}, {1, 1}});
        e.select(2);
        assertEquals(-1, e.insertNextToSelected());
        assertEquals(5, e.size());
        assertEquals(2, e.selected());
        assertValid(e);
    }

    @Test
    public void insertStopsAtMaxPoints() {
        CurvePointEditor e = new CurvePointEditor();
        while (e.size() < ToneCurve.MAX_POINTS) {
            // Always split the widest gap, so there is room for all 32 points
            int widest = 0;
            for (int i = 1; i < e.size() - 1; i++) {
                if (e.x(i + 1) - e.x(i) > e.x(widest + 1) - e.x(widest)) {
                    widest = i;
                }
            }
            e.select(widest);
            int k = e.insertNextToSelected();
            assertTrue(k > 0);
            assertValid(e);
        }
        assertEquals(-1, e.insertNextToSelected());
        assertEquals(-1, e.insertOnCurve(0.123));
        assertEquals(ToneCurve.MAX_POINTS, e.size());
        assertValid(e);
    }

    @Test
    public void insertOnCurveKeepsTheValueAtThatX() {
        ToneCurve before = ToneCurve.of(new double[][] {{0, 0}, {0.10, 0}, {0.88, 1}, {1, 1}});
        CurvePointEditor e = new CurvePointEditor();
        e.load(before);
        int k = e.insertOnCurve(0.4);
        assertEquals(2, k);
        assertEquals(2, e.selected());
        assertEquals(0.4, e.x(2), EPS);
        assertEquals(before.apply(0.4), e.y(2), EPS);
        assertValid(e);
    }

    @Test
    public void insertOnCurveIsPulledAwayFromNeighbours() {
        CurvePointEditor e = new CurvePointEditor();
        int k = e.insertOnCurve(0.001);
        assertEquals(1, k);
        assertTrue(e.x(1) - e.x(0) >= ToneCurve.MIN_SPACING);
        k = e.insertOnCurve(0.9999);
        assertEquals(2, k);
        assertTrue(1 - e.x(2) >= ToneCurve.MIN_SPACING);
        assertValid(e);
    }

    @Test
    public void deleteRemovesInnerPointsOnly() {
        CurvePointEditor e = editorWith(new double[][] {{0, 0}, {0.3, 0.2}, {0.6, 0.7}, {1, 1}});
        e.select(0);
        assertFalse(e.deleteSelected());
        e.select(3);
        assertFalse(e.deleteSelected());
        e.select(2);
        assertTrue(e.deleteSelected());
        assertEquals(3, e.size());
        assertEquals(1, e.selected());
        assertEquals(0.3, e.x(1), 0);
        assertEquals(1, e.x(2), 0);
        assertTrue(e.deleteSelected());
        assertEquals(2, e.size());
        assertEquals(0, e.selected());
        assertFalse(e.deleteSelected());
        assertValid(e);
    }

    @Test
    public void resetGoesBackToIdentity() {
        CurvePointEditor e = editorWith(new double[][] {{0, 1}, {0.5, 0.2}, {1, 0}});
        e.select(1);
        assertTrue(e.reset());
        assertEquals(ToneCurve.IDENTITY, e.toCurve());
        assertEquals(0, e.selected());
        assertFalse(e.reset());
    }

    @Test
    public void selectionIsClampedAndKeptOnReload() {
        CurvePointEditor e = editorWith(new double[][] {{0, 0}, {0.3, 0.2}, {0.6, 0.7}, {1, 1}});
        assertTrue(e.select(2));
        assertFalse(e.select(2));
        e.load(ToneCurve.of(new double[][] {{0, 0}, {0.2, 0.3}, {0.5, 0.5}, {1, 1}}));
        assertEquals(2, e.selected());
        e.select(3);
        e.load(ToneCurve.IDENTITY);
        assertEquals(1, e.selected());
        assertFalse(e.selectRelative(1));
        assertTrue(e.selectRelative(-1));
        assertFalse(e.selectRelative(-1));
        e.select(99);
        assertEquals(1, e.selected());
    }

    @Test
    public void hitTestFindsTheNearestPointWithinTheRadius() {
        CurvePointEditor e = editorWith(new double[][] {{0, 0}, {0.3, 0.2}, {0.6, 0.7}, {1, 1}});
        assertEquals(1, e.hitTest(0.31, 0.21, 0.05));
        assertEquals(-1, e.hitTest(0.45, 0.45, 0.05));
        assertEquals(3, e.hitTest(0.98, 0.99, 0.05));
        assertEquals(2, e.nearestByX(0.5));
        assertEquals(0, e.nearestByX(-1));
    }

    @Test
    public void toCurveReturnsANewValidCurve() {
        CurvePointEditor e = new CurvePointEditor();
        e.moveSelectedBy(0, 0.1);
        ToneCurve c = e.toCurve();
        assertNotSame(ToneCurve.IDENTITY, c);
        assertEquals(0.1, c.y(0), EPS);
    }

    @Test
    public void randomEditingNeverProducesAnInvalidCurve() {
        Random rnd = new Random(20261012L);
        CurvePointEditor e = new CurvePointEditor();
        for (int step = 0; step < 50000; step++) {
            switch (rnd.nextInt(9)) {
                case 0:
                case 1: {
                    double s = rnd.nextBoolean() ? CurvePointEditor.STEP_FINE : CurvePointEditor.STEP_COARSE;
                    e.moveSelectedBy((rnd.nextInt(3) - 1) * s, (rnd.nextInt(3) - 1) * s);
                    break;
                }
                case 2:
                    e.moveTo(rnd.nextInt(e.size()), rnd.nextDouble() * 1.4 - 0.2, rnd.nextDouble() * 1.4 - 0.2);
                    break;
                case 3:
                    e.selectRelative(rnd.nextBoolean() ? 1 : -1);
                    break;
                case 4:
                    e.insertNextToSelected();
                    break;
                case 5:
                    e.insertOnCurve(rnd.nextDouble());
                    break;
                case 6:
                    e.deleteSelected();
                    break;
                case 7:
                    e.select(rnd.nextInt(e.size()));
                    break;
                default:
                    if (rnd.nextInt(50) == 0) {
                        e.reset();
                    } else {
                        // Reload from the curve, like refresh() does after every change
                        e.load(e.toCurve());
                    }
                    break;
            }
            assertTrue("step " + step, ToneCurve.isValid(e.points()));
            assertTrue(e.selected() >= 0 && e.selected() < e.size());
            assertEquals(0, e.x(0), 0);
            assertEquals(1, e.x(e.size() - 1), 0);
        }
        assertValid(e);
    }
}
