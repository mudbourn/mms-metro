package info.mudbourn.mmsmetro.client.screen;

import info.mudbourn.mmsmetro.network.MetroNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

// The junction editor: one exit direction per heading a train may arrive on, so arrivals and departures through the same block are routed onto separate tracks; a blank field falls back to the greedy rail follow.
public class JunctionEditScreen extends Screen {

    private static final int FIELD_WIDTH = 220;
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW_GAP = 32;

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
        int cx = this.width / 2;
        int left = cx - FIELD_WIDTH / 2;
        int top = this.height / 2 - 70;

        this.northField = row(left, top, "e.g. west", this.data.north());
        this.eastField = row(left, top + ROW_GAP, "e.g. south", this.data.east());
        this.southField = row(left, top + ROW_GAP * 2, "e.g. east", this.data.south());
        this.westField = row(left, top + ROW_GAP * 3, "e.g. north", this.data.west());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> save())
            .dimensions(left, top + ROW_GAP * 4, FIELD_WIDTH / 2 - 2, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
            .dimensions(cx + 2, top + ROW_GAP * 4, FIELD_WIDTH / 2 - 2, 20).build());
    }

    // Builds one labelled exit field seeded with its current value.
    private TextFieldWidget row(int left, int top, String placeholder, String value) {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, left, top,
            FIELD_WIDTH, FIELD_HEIGHT, Text.empty());
        field.setMaxLength(16);
        field.setPlaceholder(Text.literal(placeholder));
        field.setText(value);
        this.addDrawableChild(field);
        return field;
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 90, 0xFFFFFF);
        label(context, this.northField, "Train heading NORTH (in from the south) exits:");
        label(context, this.eastField, "Train heading EAST (in from the west) exits:");
        label(context, this.southField, "Train heading SOUTH (in from the north) exits:");
        label(context, this.westField, "Train heading WEST (in from the east) exits:");
    }

    // Draws a caption above a field.
    private void label(DrawContext context, TextFieldWidget field, String text) {
        context.drawTextWithShadow(this.textRenderer, Text.literal(text),
            field.getX(), field.getY() - 10, 0xA0A0A0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
