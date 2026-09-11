package info.mudbourn.mmsmetro.client.screen;

import info.mudbourn.mmsmetro.network.MetroNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

// The junction editor: one exit direction per heading a train may arrive on, so arrivals and departures through the same block are routed onto separate tracks; a blank field falls back to the greedy rail follow.
public class JunctionEditScreen extends Screen {

    private static final int FIELD_WIDTH = 260;
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW_GAP = 42;

    private final MetroNetworking.OpenJunctionScreen data;
    private TextFieldWidget northField;
    private TextFieldWidget southField;
    private TextFieldWidget eastField;
    private TextFieldWidget westField;

    public JunctionEditScreen(MetroNetworking.OpenJunctionScreen data) {
        super(Text.literal("Edit Junction"));
        this.data = data;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - FIELD_WIDTH / 2;
        int top = this.height / 2 - 96;

        this.northField = row(left, top, "Train heading NORTH (in from the south) exits:", this.data.north());
        this.eastField = row(left, top + ROW_GAP, "Train heading EAST (in from the west) exits:", this.data.east());
        this.southField = row(left, top + ROW_GAP * 2, "Train heading SOUTH (in from the north) exits:", this.data.south());
        this.westField = row(left, top + ROW_GAP * 3, "Train heading WEST (in from the east) exits:", this.data.west());

        int buttons = top + ROW_GAP * 4;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> save())
            .dimensions(left, buttons, FIELD_WIDTH / 2 - 2, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
            .dimensions(left + FIELD_WIDTH / 2 + 2, buttons, FIELD_WIDTH / 2 - 2, 20).build());
    }

    // Builds one row: a caption widget naming the approach, above an exit field seeded with its current value; both are real widgets so they render through the same pipeline as every other control.
    private TextFieldWidget row(int left, int top, String caption, String value) {
        TextWidget label = new TextWidget(left, top, FIELD_WIDTH, 9, Text.literal(caption), this.textRenderer);
        this.addDrawableChild(label);

        TextFieldWidget field = new TextFieldWidget(this.textRenderer, left, top + 11,
            FIELD_WIDTH, FIELD_HEIGHT, Text.empty());
        field.setMaxLength(16);
        field.setPlaceholder(Text.literal("blank = follow the track"));
        field.setTextPredicate(JunctionEditScreen::isCapitalsOnly);
        field.setText(value.toUpperCase());
        this.addDrawableChild(field);
        return field;
    }

    // True when a string is empty or made only of capital letters, so the fields reject lower case as it is typed.
    private static boolean isCapitalsOnly(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        return true;
    }

    private void save() {
        ClientPlayNetworking.send(new MetroNetworking.JunctionEdit(this.data.pos(),
            this.northField.getText(),
            this.southField.getText(),
            this.eastField.getText(),
            this.westField.getText()));
        this.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
