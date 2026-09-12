package info.mudbourn.mmsmetro.network;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.block.JunctionBlock;
import info.mudbourn.mmsmetro.block.SpeedBumpBlock;
import info.mudbourn.mmsmetro.block.entity.JunctionBlockEntity;
import info.mudbourn.mmsmetro.block.entity.SpeedBumpBlockEntity;
import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

// The station and speed-bump editor packets: the server opens an editor, the client sends back edited values, and the server validates and applies them to the block the player looks at.
public final class MetroNetworking {

    // Squared reach a player must be within to edit a block, so a spoofed packet cannot reach across the world (a little past normal reach).
    private static final double EDIT_REACH_SQ = 8.0 * 8.0;

    private MetroNetworking() {
    }

    // Server->client: open the station editor pre-filled with current values.
    public record OpenStationScreen(BlockPos pos, String name, String line, String color, String direction,
                                    boolean fixedDirection, String next, String exit, String transfer,
                                    boolean hub, boolean terminus, int dwell) implements CustomPayload {
        public static final Id<OpenStationScreen> ID =
            new Id<>(Identifier.of(MmsMetro.MOD_ID, "open_station"));
        public static final PacketCodec<RegistryByteBuf, OpenStationScreen> CODEC = PacketCodec.of(
            (v, buf) -> {
                buf.writeBlockPos(v.pos);
                buf.writeString(v.name);
                buf.writeString(v.line);
                buf.writeString(v.color);
                buf.writeString(v.direction);
                buf.writeBoolean(v.fixedDirection);
                buf.writeString(v.next);
                buf.writeString(v.exit);
                buf.writeString(v.transfer);
                buf.writeBoolean(v.hub);
                buf.writeBoolean(v.terminus);
                buf.writeVarInt(v.dwell);
            },
            buf -> new OpenStationScreen(buf.readBlockPos(), buf.readString(), buf.readString(), buf.readString(),
                buf.readString(), buf.readBoolean(), buf.readString(), buf.readString(), buf.readString(),
                buf.readBoolean(), buf.readBoolean(), buf.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // Client->server: apply the edited station values.
    public record StationEdit(BlockPos pos, String name, String line, String color, String direction,
                              boolean fixedDirection, String next, String exit, String transfer,
                              boolean hub, boolean terminus, int dwell) implements CustomPayload {
        public static final Id<StationEdit> ID =
            new Id<>(Identifier.of(MmsMetro.MOD_ID, "station_edit"));
        public static final PacketCodec<RegistryByteBuf, StationEdit> CODEC = PacketCodec.of(
            (v, buf) -> {
                buf.writeBlockPos(v.pos);
                buf.writeString(v.name);
                buf.writeString(v.line);
                buf.writeString(v.color);
                buf.writeString(v.direction);
                buf.writeBoolean(v.fixedDirection);
                buf.writeString(v.next);
                buf.writeString(v.exit);
                buf.writeString(v.transfer);
                buf.writeBoolean(v.hub);
                buf.writeBoolean(v.terminus);
                buf.writeVarInt(v.dwell);
            },
            buf -> new StationEdit(buf.readBlockPos(), buf.readString(), buf.readString(), buf.readString(),
                buf.readString(), buf.readBoolean(), buf.readString(), buf.readString(), buf.readString(),
                buf.readBoolean(), buf.readBoolean(), buf.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // Server->client: open the speed-bump editor.
    public record OpenBumpScreen(BlockPos pos, String direction) implements CustomPayload {
        public static final Id<OpenBumpScreen> ID =
            new Id<>(Identifier.of(MmsMetro.MOD_ID, "open_bump"));
        public static final PacketCodec<RegistryByteBuf, OpenBumpScreen> CODEC = PacketCodec.of(
            (v, buf) -> {
                buf.writeBlockPos(v.pos);
                buf.writeString(v.direction);
            },
            buf -> new OpenBumpScreen(buf.readBlockPos(), buf.readString()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // Client->server: apply the edited speed-bump values.
    public record BumpEdit(BlockPos pos, String direction) implements CustomPayload {
        public static final Id<BumpEdit> ID =
            new Id<>(Identifier.of(MmsMetro.MOD_ID, "bump_edit"));
        public static final PacketCodec<RegistryByteBuf, BumpEdit> CODEC = PacketCodec.of(
            (v, buf) -> {
                buf.writeBlockPos(v.pos);
                buf.writeString(v.direction);
            },
            buf -> new BumpEdit(buf.readBlockPos(), buf.readString()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // Server->client: open the junction editor. Each field is the exit for a train arriving travelling that direction, empty for the greedy default.
    public record OpenJunctionScreen(BlockPos pos, String north, String south, String east, String west)
            implements CustomPayload {
        public static final Id<OpenJunctionScreen> ID =
            new Id<>(Identifier.of(MmsMetro.MOD_ID, "open_junction"));
        public static final PacketCodec<RegistryByteBuf, OpenJunctionScreen> CODEC = PacketCodec.of(
            (v, buf) -> {
                buf.writeBlockPos(v.pos);
                buf.writeString(v.north);
                buf.writeString(v.south);
                buf.writeString(v.east);
                buf.writeString(v.west);
            },
            buf -> new OpenJunctionScreen(buf.readBlockPos(), buf.readString(), buf.readString(),
                buf.readString(), buf.readString()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // Client->server: apply the edited junction routing.
    public record JunctionEdit(BlockPos pos, String north, String south, String east, String west)
            implements CustomPayload {
        public static final Id<JunctionEdit> ID =
            new Id<>(Identifier.of(MmsMetro.MOD_ID, "junction_edit"));
        public static final PacketCodec<RegistryByteBuf, JunctionEdit> CODEC = PacketCodec.of(
            (v, buf) -> {
                buf.writeBlockPos(v.pos);
                buf.writeString(v.north);
                buf.writeString(v.south);
                buf.writeString(v.east);
                buf.writeString(v.west);
            },
            buf -> new JunctionEdit(buf.readBlockPos(), buf.readString(), buf.readString(),
                buf.readString(), buf.readString()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // Registers every payload type (both sides must agree) and the server-side receivers that apply edits; called from the common initializer.
    public static void register() {
        PayloadTypeRegistry.playS2C().register(OpenStationScreen.ID, OpenStationScreen.CODEC);
        PayloadTypeRegistry.playC2S().register(StationEdit.ID, StationEdit.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenBumpScreen.ID, OpenBumpScreen.CODEC);
        PayloadTypeRegistry.playC2S().register(BumpEdit.ID, BumpEdit.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenJunctionScreen.ID, OpenJunctionScreen.CODEC);
        PayloadTypeRegistry.playC2S().register(JunctionEdit.ID, JunctionEdit.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(StationEdit.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            player.getEntityWorld().getServer().execute(() -> applyStation(player, payload));
        });
        ServerPlayNetworking.registerGlobalReceiver(BumpEdit.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            player.getEntityWorld().getServer().execute(() -> applyBump(player, payload));
        });
        ServerPlayNetworking.registerGlobalReceiver(JunctionEdit.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            player.getEntityWorld().getServer().execute(() -> applyJunction(player, payload));
        });
    }

    // Sends the station editor to a player looking at a station block.
    public static void openStation(ServerPlayerEntity player, BlockPos pos, StationBlockEntity station) {
        ServerPlayNetworking.send(player, new OpenStationScreen(pos,
            station.getStationName(), station.getLineName(), station.getLineColor(), station.getLineDirection(),
            station.isFixedDirection(), station.getNextStation(), station.getExitDirection(), station.getTransferLine(),
            station.isHub(), station.isTerminus(), station.getDwellTicks()));
    }

    // Sends the speed-bump editor to a player looking at a bump block.
    public static void openBump(ServerPlayerEntity player, BlockPos pos, String direction) {
        ServerPlayNetworking.send(player, new OpenBumpScreen(pos, direction));
    }

    // Sends the junction editor pre-filled with the current per-approach routing.
    public static void openJunction(ServerPlayerEntity player, BlockPos pos, JunctionBlockEntity junction) {
        ServerPlayNetworking.send(player, new OpenJunctionScreen(pos,
            junction.getExitName(Direction.NORTH),
            junction.getExitName(Direction.SOUTH),
            junction.getExitName(Direction.EAST),
            junction.getExitName(Direction.WEST)));
    }

    private static void applyStation(ServerPlayerEntity player, StationEdit edit) {
        ServerWorld world = player.getEntityWorld();
        if (!canEdit(player, edit.pos())) {
            return;
        }
        if (world.getBlockEntity(edit.pos()) instanceof StationBlockEntity station) {
            station.setStationName(edit.name());
            station.setLineName(edit.line());
            station.setLineColor(edit.color());
            station.setLineDirection(edit.direction());
            station.setFixedDirection(edit.fixedDirection());
            station.setNextStation(edit.next());
            station.setExitDirection(edit.exit());
            station.setTransferLine(edit.transfer());
            station.setHub(edit.hub());
            station.setTerminus(edit.terminus());
            station.setDwellTicks(Math.max(0, edit.dwell()));
        }
    }

    private static void applyBump(ServerPlayerEntity player, BumpEdit edit) {
        ServerWorld world = player.getEntityWorld();
        if (!canEdit(player, edit.pos())) {
            return;
        }
        if (world.getBlockState(edit.pos()).getBlock() instanceof SpeedBumpBlock
            && world.getBlockEntity(edit.pos()) instanceof SpeedBumpBlockEntity bump) {
            bump.setDirection(edit.direction());
        }
    }

    private static void applyJunction(ServerPlayerEntity player, JunctionEdit edit) {
        ServerWorld world = player.getEntityWorld();
        if (!canEdit(player, edit.pos())) {
            return;
        }
        if (world.getBlockState(edit.pos()).getBlock() instanceof JunctionBlock
            && world.getBlockEntity(edit.pos()) instanceof JunctionBlockEntity junction) {
            junction.setExit(Direction.NORTH, edit.north());
            junction.setExit(Direction.SOUTH, edit.south());
            junction.setExit(Direction.EAST, edit.east());
            junction.setExit(Direction.WEST, edit.west());
        }
    }

    // Guards an incoming edit: the target must be within reach, so a client can only edit a block it could actually right-click.
    private static boolean canEdit(ServerPlayerEntity player, BlockPos pos) {
        return player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= EDIT_REACH_SQ;
    }
}
