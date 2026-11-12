package info.mudbourn.mmsmetro.client.screen;

import info.mudbourn.mmsmetro.network.MetroNetworking;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

// The junction editor: one exit button per heading a train may arrive on, cycling the track follow and the three forward exits.
public class JunctionEditScreen extends Screen {

    private static final int FIELD_WIDTH = 260;
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW_GAP = 42;

    private final MetroNetworking.OpenJunctionScreen data;
    private String north;
    private String south;
    private String east;
    private String west;

    public JunctionEditScreen(MetroNetworking.OpenJunctionScreen data) {
        super(Text.literal("Edit Junction"));
        this.data = data;
        this.north = normalise(data.north());
        this.south = normalise(data.south());
        this.east = normalise(data.east());
        this.west = normalise(data.west());
    }

    @Override
    protected void init() {
        int left = this.width / 2 - FIELD_WIDTH / 2;
        int top = this.height / 2 - 96;

        row(left, top, "Train heading NORTH (in from the south) exits:", this.north,
            List.of("", "NORTH", "EAST", "WEST"), v -> this.north = v);
        row(left, top + ROW_GAP, "Train heading EAST (in from the west) exits:", this.east,
            List.of("", "EAST", "NORTH", "SOUTH"), v -> this.east = v);
        row(left, top + ROW_GAP * 2, "Train heading SOUTH (in from the north) exits:", this.south,
            List.of("", "SOUTH", "EAST", "WEST"), v -> this.south = v);
        row(left, top + ROW_GAP * 3, "Train heading WEST (in from the east) exits:", this.west,
            List.of("", "WEST", "NORTH", "SOUTH"), v -> this.west = v);

        int buttons = top + ROW_GAP * 4;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> save())
            .dimensions(left, buttons, FIELD_WIDTH / 2 - 2, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
            .dimensions(left + FIELD_WIDTH / 2 + 2, buttons, FIELD_WIDTH / 2 - 2, 20).build());
    }

    // Builds one row: a caption above an exit button cycling the given options.
    private void row(int left, int top, String caption, String value, List<String> options,
                     java.util.function.Consumer<String> setter) {
        this.addDrawableChild(new TextWidget(left, top, FIELD_WIDTH, 9, Text.literal(caption), this.textRenderer));
        String initial = options.contains(value) ? value : "";
        setter.accept(initial);
        this.addDrawableChild(CyclingButtonWidget.builder(
                (String d) -> Text.literal(d.isEmpty() ? "Follow the track" : d), initial)
            .values(options)
            .omitKeyText()
            .build(left, top + 11, FIELD_WIDTH, FIELD_HEIGHT, Text.literal(caption),
                (button, v) -> setter.accept(v)));
    }

    // Upper-cases a stored exit name.
    private static String normalise(String value) {
        return value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private void save() {
        ClientPlayNetworking.send(new MetroNetworking.JunctionEdit(this.data.pos(),
            this.north,
            this.south,
            this.east,
            this.west));
        this.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
