package info.mudbourn.mmsmetro.path;

import net.minecraft.nbt.NbtCompound;

// A point where a train turns around and the baked path it continues on.
public record PathReverse(double arc, PathKey key) {

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putDouble("Arc", this.arc);
        nbt.put("Key", this.key.toNbt());
        return nbt;
    }

    public static PathReverse fromNbt(NbtCompound nbt) {
        return new PathReverse(
            nbt.getDouble("Arc", 0.0),
            PathKey.fromNbt(nbt.getCompoundOrEmpty("Key"))
        );
    }
}
