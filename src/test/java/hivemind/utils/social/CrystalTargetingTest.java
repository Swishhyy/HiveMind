/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.utils.social;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import java.util.function.Predicate;
import static org.junit.Assert.*;

public final class CrystalTargetingTest {
    private final AABB crystal = new AABB(0, 1, 0, 2, 3, 2);

    private static Predicate<Vec3> pastBase(Vec3 eye) {
        return point -> !BlastPolicy.blockedByCube(-1, 0, 0, eye.x, eye.y, eye.z, point.x, point.y, point.z);
    }

    @Test public void aShorelineLedgeDoesNotHideTheWholeCrystal() {
        Vec3 eye = new Vec3(-2, 0.4, 0.5);
        Predicate<Vec3> clear = pastBase(eye);
        assertFalse("The nearest lower point is behind obsidian", clear.test(new Vec3(0.001, 1.001, 0.5)));
        Vec3 hit = CrystalTargeting.hit(crystal, eye, 3, clear);
        assertNotNull("Aim at an exposed part before trying to move closer", hit);
        assertTrue(crystal.contains(hit));
        assertTrue(eye.distanceToSqr(hit) <= 9);
        assertTrue(clear.test(hit));
        assertTrue("The selected point must also pass the attack's actual-aim check",
                CrystalTargeting.facing(crystal, eye, hit.subtract(eye), 3, clear));
    }

    @Test public void aWallStillBlocksEveryHitPoint() {
        Vec3 eye = new Vec3(-2, 2, 1);
        Predicate<Vec3> wall = point -> point.x < -1;
        assertNull(CrystalTargeting.hit(crystal, eye, 3, wall));
        assertFalse(CrystalTargeting.facing(crystal, eye, new Vec3(1, 0, 0), 3, wall));
    }

    @Test public void clearSightDoesNotIncreaseNormalAttackReach() {
        Vec3 eye = new Vec3(-3.1, 2, 1);
        int[] rays = {0};
        assertNull(CrystalTargeting.hit(crystal, eye, 3, point -> {rays[0]++; return true;}));
        assertEquals("Out-of-reach points need no world raycasts", 0, rays[0]);
        assertFalse("An oversized direction vector cannot extend reach",
                CrystalTargeting.facing(crystal, eye, new Vec3(100, 0, 0), 3, point -> true));
    }

    @Test public void theNearestClearPointAvoidsUnnecessaryRotation() {
        Vec3 eye = new Vec3(-2, 2, 1);
        Vec3 hit = CrystalTargeting.hit(crystal, eye, 3, point -> true);
        assertNotNull(hit);
        assertEquals(0.001, hit.x, 1e-9);
        assertEquals(eye.y, hit.y, 1e-9);
        assertEquals(eye.z, hit.z, 1e-9);
        assertFalse("Looking past the crystal must not attack it",
                CrystalTargeting.facing(crystal, eye, new Vec3(0, 0, 1), 3, point -> true));
    }

    @Test public void invalidInputsCannotProduceAnAttack() {
        Vec3 eye = new Vec3(-2, 2, 1);
        assertNull(CrystalTargeting.hit(crystal, eye, Double.NaN, point -> true));
        assertNull(CrystalTargeting.hit(crystal, new Vec3(Double.NaN, 0, 0), 3, point -> true));
        assertNull(CrystalTargeting.hit(new AABB(0, 0, 0, 0, 0, 0), eye, 3, point -> true));
        assertFalse(CrystalTargeting.facing(crystal, eye, Vec3.ZERO, 3, point -> true));
    }
}
