package info.mudbourn.mmsmetro.path;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.function.Consumer;

// Finds block entities of one type inside a box by reading each covered chunk's block entity map instead of probing every block.
public final class BlockEntityScan {

    private BlockEntityScan() {
    }

    // Visits every live block entity of the given type whose position lies inside the inclusive box.
    public static <T extends BlockEntity> void forEachInBox(World world, BlockPos min, BlockPos max,
                                                            Class<T> type, Consumer<T> visitor) {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                forEachInChunk(world, cx, cz, type, be -> {
                    if (inBox(be.getPos(), min, max)) {
                        visitor.accept(be);
                    }
                });
            }
        }
    }

    // Visits every live block entity of the given type in one chunk.
    public static <T extends BlockEntity> void forEachInChunk(World world, int cx, int cz,
                                                              Class<T> type, Consumer<T> visitor) {
        for (BlockEntity be : world.getChunk(cx, cz).getBlockEntities().values()) {
            if (!be.isRemoved() && type.isInstance(be)) {
                visitor.accept(type.cast(be));
            }
        }
    }

    // Nearest block entity of the given type within a cube around center, ties going to the lowest z, then y, then x; null when there is none.
    public static BlockPos nearest(World world, BlockPos center, int radius, Class<? extends BlockEntity> type) {
        BlockPos[] best = {null};
        double[] bestSq = {Double.MAX_VALUE};
        forEachInBox(world, center.add(-radius, -radius, -radius), center.add(radius, radius, radius), type, be -> {
            BlockPos pos = be.getPos();
            double sq = pos.getSquaredDistance(center);
            if (sq < bestSq[0] || (sq == bestSq[0] && compareZyx(pos, best[0]) < 0)) {
                bestSq[0] = sq;
                best[0] = pos.toImmutable();
            }
        });
        return best[0];
    }

    // Orders positions by z, then y, then x, the order BlockPos.iterate walks a box in.
    public static int compareZyx(BlockPos a, BlockPos b) {
        if (a.getZ() != b.getZ()) {
            return Integer.compare(a.getZ(), b.getZ());
        }
        if (a.getY() != b.getY()) {
            return Integer.compare(a.getY(), b.getY());
        }
        return Integer.compare(a.getX(), b.getX());
    }

    private static boolean inBox(BlockPos pos, BlockPos min, BlockPos max) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
            && pos.getY() >= min.getY() && pos.getY() <= max.getY()
            && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }
}
