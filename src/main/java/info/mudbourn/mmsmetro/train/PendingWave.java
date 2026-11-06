package info.mudbourn.mmsmetro.train;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Waves still to launch, held until the train with the given id reaches the trigger arc so the waves stay staggered.
public record PendingWave(UUID trigger, double triggerArc, List<Wave> remaining, int cars) {

    NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("Trigger", this.trigger.toString());
        nbt.putDouble("TriggerArc", this.triggerArc);
        nbt.putInt("Cars", this.cars);
        NbtList list = new NbtList();
        for (Wave wave : this.remaining) {
            list.add(wave.toNbt());
        }
        nbt.put("Remaining", list);
        return nbt;
    }

    static PendingWave fromNbt(NbtCompound nbt) {
        List<Wave> remaining = new ArrayList<>();
        NbtList list = nbt.getListOrEmpty("Remaining");
        for (int i = 0; i < list.size(); i++) {
            remaining.add(Wave.fromNbt(list.getCompoundOrEmpty(i)));
        }
        return new PendingWave(
            UUID.fromString(nbt.getString("Trigger", "")),
            nbt.getDouble("TriggerArc", 0.0),
            remaining,
            nbt.getInt("Cars", 1)
        );
    }
}
