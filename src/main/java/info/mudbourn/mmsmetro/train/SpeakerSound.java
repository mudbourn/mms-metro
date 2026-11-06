package info.mudbourn.mmsmetro.train;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Plays a speaker jingle to the players in reach of a loaded speaker block.
final class SpeakerSound {

    // Range, in blocks, a speaker's jingle carries, both straight-line and along the open path it spreads through.
    private static final int RANGE = 24;

    private SpeakerSound() {
    }

    // Sends the jingle only to players whose head the sound reaches through open blocks.
    static void play(ServerWorld world, BlockPos speaker, SoundEvent sound) {
        if (speaker == null || !world.isPosLoaded(speaker)) {
            return;
        }
        Vec3d source = Vec3d.ofCenter(speaker);
        List<ServerPlayerEntity> listeners = new ArrayList<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.squaredDistanceTo(source) <= (double) RANGE * RANGE) {
                listeners.add(player);
            }
        }
        if (listeners.isEmpty()) {
            return;
        }
        Set<BlockPos> ears = new HashSet<>();
        for (ServerPlayerEntity player : listeners) {
            ears.add(BlockPos.ofFloored(player.getEyePos()));
        }
        Set<BlockPos> heard = reach(world, speaker, ears);
        RegistryEntry<SoundEvent> entry = RegistryEntry.of(sound);
        long seed = world.getRandom().nextLong();
        for (ServerPlayerEntity player : listeners) {
            if (!heard.contains(BlockPos.ofFloored(player.getEyePos()))) {
                continue;
            }
            player.networkHandler.sendPacket(new PlaySoundS2CPacket(
                entry,
                SoundCategory.NEUTRAL,
                source.x,
                source.y,
                source.z,
                1.0f,
                1.0f,
                seed
            ));
        }
    }

    // The ears among the given blocks that the sound spreads into through blocks that are not full solid cubes.
    private static Set<BlockPos> reach(ServerWorld world, BlockPos speaker, Set<BlockPos> ears) {
        Set<BlockPos> seen = new HashSet<>();
        seen.add(speaker);
        Set<BlockPos> reached = new HashSet<>();
        if (ears.contains(speaker)) {
            reached.add(speaker);
        }
        List<BlockPos> frontier = List.of(speaker);
        for (int step = 0; step < RANGE && !frontier.isEmpty() && reached.size() < ears.size(); step++) {
            List<BlockPos> next = new ArrayList<>();
            for (BlockPos pos : frontier) {
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = pos.offset(dir);
                    if (!world.isPosLoaded(neighbor) || !seen.add(neighbor)) {
                        continue;
                    }
                    if (ears.contains(neighbor)) {
                        reached.add(neighbor);
                    }
                    BlockState state = world.getBlockState(neighbor);
                    if (!Block.isShapeFullCube(state.getCollisionShape(world, neighbor))) {
                        next.add(neighbor);
                    }
                }
            }
            frontier = next;
        }
        return reached;
    }
}
