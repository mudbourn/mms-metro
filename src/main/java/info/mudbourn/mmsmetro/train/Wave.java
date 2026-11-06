package info.mudbourn.mmsmetro.train;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

// One wave of the circulation plan: a canonical path to seed from, the stop arcs to drop trains at, and the arc its own terminal train must reach before the next wave launches.
public record Wave(BlockPos origin, Direction dir, List<Double> arcs, double triggerArc) {

    NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putLong("Origin", this.origin.asLong());
        nbt.putInt("Dir", this.dir.getIndex());
        NbtList list = new NbtList();
        for (double arc : this.arcs) {
            list.add(NbtDouble.of(arc));
        }
        nbt.put("Arcs", list);
        nbt.putDouble("Trigger", this.triggerArc);
        return nbt;
    }

    static Wave fromNbt(NbtCompound nbt) {
        List<Double> arcs = new ArrayList<>();
        NbtList list = nbt.getListOrEmpty("Arcs");
        for (int i = 0; i < list.size(); i++) {
            arcs.add(list.getDouble(i, 0.0));
        }
        return new Wave(
            BlockPos.fromLong(nbt.getLong("Origin", 0L)),
            Direction.byIndex(nbt.getInt("Dir", Direction.NORTH.getIndex())),
            arcs,
            nbt.getDouble("Trigger", 0.0)
        );
    }
}
