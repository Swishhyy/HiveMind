/* This file is part of Baritone, distributed under the LGPL-3.0 license. */
package baritone.utils.bot;

import net.minecraft.core.BlockPos;

/** A live task supplies the next survey leg without relinquishing airborne navigation. */
public interface AerialSurvey {
    BlockPos nextSurveyWaypoint();
}
