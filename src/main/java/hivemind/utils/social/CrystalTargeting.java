/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.utils.social;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Visible, normal-reach hit points, including crystals whose lower edge is behind a ledge. */
public final class CrystalTargeting {
    private CrystalTargeting() {}

    public static Vec3 hit(AABB box, Vec3 eye, double reach, Predicate<Vec3> visible) {
        if (!Double.isFinite(reach) || reach <= 0 || !finite(eye)
                || !Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
                || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)
                || box.maxX <= box.minX || box.maxY <= box.minY || box.maxZ <= box.minZ) return null;
        List<Vec3> points = new ArrayList<>(64);
        for (double x : samples(box.minX, box.maxX, eye.x))
            for (double y : samples(box.minY, box.maxY, eye.y))
                for (double z : samples(box.minZ, box.maxZ, eye.z)) {
                    Vec3 point = new Vec3(x, y, z);
                    if (eye.distanceToSqr(point) <= reach * reach) points.add(point);
                }
        points.sort(Comparator.comparingDouble(eye::distanceToSqr));
        return points.stream().filter(visible).findFirst().orElse(null);
    }

    /** Check the actual sent aim, rather than the visibility of the entity's center. */
    public static boolean facing(AABB box, Vec3 eye, Vec3 direction, double reach, Predicate<Vec3> visible) {
        if (!finite(eye) || !finite(direction) || !Double.isFinite(reach) || reach <= 0
                || direction.lengthSqr() < 1e-12) return false;
        if (box.contains(eye)) return visible.test(eye);
        return box.clip(eye, eye.add(direction.normalize().scale(reach))).filter(visible).isPresent();
    }

    private static double[] samples(double min, double max, double eye) {
        double inset = Math.min(0.1, (max - min) / 4);
        double nearestInset = Math.min(0.001, inset);
        return new double[]{Math.max(min + nearestInset, Math.min(max - nearestInset, eye)),
                (min + max) / 2, min + inset, max - inset};
    }

    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
