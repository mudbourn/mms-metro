package info.mudbourn.mmsmetro.path;

import net.minecraft.util.math.Vec3d;

// A sampled point on a resolved path: where a car sits and how it is oriented.
public record PathPoint(Vec3d pos, float yaw, float pitch) {
}
