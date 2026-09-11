package info.mudbourn.mmsmetro.block.entity;

import info.mudbourn.mmsmetro.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

// Holds a junction's per-approach routing: for each horizontal direction a train may enter travelling, the exit direction the path walker should take instead of following the rail greedily, so arrivals and departures can be sent down separate tracks.
public class JunctionBlockEntity extends BlockEntity {

    // Exit direction keyed by the horizontal travel direction the train arrives on; null means fall back to the greedy rail follow.
    private final Direction[] exits = new Direction[4];

    public JunctionBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.JUNCTION, pos, state);
    }

    // Exit for a train entering travelling in the given direction, or null to let the walker pick greedily.
    public Direction getExit(Direction travel) {
        int i = index(travel);
        return i < 0 ? null : this.exits[i];
    }

    // Direction name for a travel direction, empty when unset, for the editor.
    public String getExitName(Direction travel) {
        Direction exit = getExit(travel);
        return exit == null ? "" : exit.asString();
    }

    // Sets the exit for a travel direction from a name; an unrecognised or empty name clears it.
    public void setExit(Direction travel, String name) {
        int i = index(travel);
        if (i < 0) {
            return;
        }
        this.exits[i] = parseHorizontal(name);
        this.markDirty();
    }

    // Slot for a horizontal travel direction, or -1 for a non-horizontal one.
    private static int index(Direction travel) {
        return switch (travel) {
            case NORTH -> 0;
            case SOUTH -> 1;
            case EAST -> 2;
            case WEST -> 3;
            default -> -1;
        };
    }

    // The horizontal direction a name spells, or null when it names none.
    private static Direction parseHorizontal(String name) {
        return switch (name.trim().toLowerCase()) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.exits[0] = parseHorizontal(view.getString("North", ""));
        this.exits[1] = parseHorizontal(view.getString("South", ""));
        this.exits[2] = parseHorizontal(view.getString("East", ""));
        this.exits[3] = parseHorizontal(view.getString("West", ""));
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putString("North", this.exits[0] == null ? "" : this.exits[0].asString());
        view.putString("South", this.exits[1] == null ? "" : this.exits[1].asString());
        view.putString("East", this.exits[2] == null ? "" : this.exits[2].asString());
        view.putString("West", this.exits[3] == null ? "" : this.exits[3].asString());
    }
}
