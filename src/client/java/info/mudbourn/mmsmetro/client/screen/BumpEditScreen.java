package info.mudbourn.mmsmetro.client.screen;

import info.mudbourn.mmsmetro.network.MetroNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

// The speed-bump editor: a direction that must match the stop it heralds, plus an exact station tether picked from the stops reachable down the line.
public class BumpEditScreen extends Screen {

    private static final int FIELD_WIDTH = 220;
    private static final int FIELD_HEIGHT = 20;

    // Dropdown entry that clears the tether and falls back to direction matching.
    private static final String NONE = "(none)";

    private final MetroNetworking.OpenBumpScreen data;
    private String direction;
    private String stationKey;

    public BumpEditScreen(MetroNetworking.OpenBumpScreen data) {
        super(Text.literal("Edit Speed Bump"));
        this.data = data;
        this.stationKey = data.stationKey();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = this.height / 2 - 20;

        int titleWidth = this.textRenderer.getWidth(this.title);
        this.addDrawableChild(new TextWidget((this.width - titleWidth) / 2, top - 34, titleWidth, 9,
            this.title, this.textRenderer));
        this.addDrawableChild(new TextWidget(cx - FIELD_WIDTH / 2, top - 10, FIELD_WIDTH, 9,
            Text.literal("Direction"), this.textRenderer));

        this.direction = this.data.direction();
        this.addDrawableChild(CyclingButtonWidget.builder((String d) -> Text.literal(d.isEmpty() ? "Any" : d), this.direction)
            .values(directionOptions(this.direction))
            .omitKeyText()
            .build(cx - FIELD_WIDTH / 2, top, FIELD_WIDTH, FIELD_HEIGHT, Text.literal("Direction"),
                (button, value) -> this.direction = value));

        // Exact station tether: overrides direction matching, picked from the stops reachable from this bump.
        this.addDrawableChild(new TextWidget(cx - FIELD_WIDTH / 2, top + 26, FIELD_WIDTH, 9,
            Text.literal("Station tether"), this.textRenderer));
        List<String> options = buildOptions();
        String initial = this.stationKey.isEmpty() ? NONE : this.stationKey;
        this.addDrawableChild(CyclingButtonWidget.builder((String s) -> Text.literal(s), initial)
            .values(options)
            .omitKeyText()
            .build(cx - FIELD_WIDTH / 2, top + 36, FIELD_WIDTH, FIELD_HEIGHT, Text.literal("Station tether"),
                (button, value) -> this.stationKey = value.equals(NONE) ? "" : value));

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> save())
            .dimensions(cx - FIELD_WIDTH / 2, top + 70, FIELD_WIDTH / 2 - 2, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
            .dimensions(cx + 2, top + 70, FIELD_WIDTH / 2 - 2, 20).build());
    }

    // The dropdown values: "(none)" first, then the reachable stops' keys, and the stored key when it is not among them so an unloaded target is not lost.
    private List<String> buildOptions() {
        List<String> options = new ArrayList<>();
        options.add(NONE);
        for (String key : this.data.stationKeys()) {
            if (!options.contains(key)) {
                options.add(key);
            }
        }
        if (!this.stationKey.isEmpty() && !options.contains(this.stationKey)) {
            options.add(this.stationKey);
        }
        return options;
    }

    // Blank for any, the four bounds, their terminus forms, and the stored value when it is none of them.
    private static List<String> directionOptions(String current) {
        List<String> options = new ArrayList<>(List.of(
            "",
            "Northbound",
            "Eastbound",
            "Southbound",
            "Westbound",
            "T_Northbound",
            "T_Eastbound",
            "T_Southbound",
            "T_Westbound"));
        if (!options.contains(current)) {
            options.add(current);
        }
        return options;
    }

    private void save() {
        ClientPlayNetworking.send(new MetroNetworking.BumpEdit(this.data.pos(),
            this.direction, this.stationKey));
        this.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
