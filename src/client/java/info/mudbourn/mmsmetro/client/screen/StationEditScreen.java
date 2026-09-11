package info.mudbourn.mmsmetro.client.screen;

import info.mudbourn.mmsmetro.network.MetroNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;

// The station editor: one field per station property, applied all at once when Done is pressed.
public class StationEditScreen extends Screen {

    private static final int FIELD_WIDTH = 220;
    private static final int FIELD_HEIGHT = 18;
    private static final int ROW_SPACING = 30;

    private final MetroNetworking.OpenStationScreen data;

    private TextFieldWidget nameField;
    private TextFieldWidget lineField;
    private TextFieldWidget directionField;
    private TextFieldWidget nextField;
    private TextFieldWidget exitField;
    private TextFieldWidget transferField;
    private TextFieldWidget dwellField;
    private boolean hub;
    private boolean terminus;
    private ButtonWidget hubButton;
    private ButtonWidget terminusButton;
    private DyeColor lineColor = DyeColor.WHITE;

    public StationEditScreen(MetroNetworking.OpenStationScreen data) {
        super(Text.literal("Edit Station"));
        this.data = data;
        this.hub = data.hub();
        this.terminus = data.terminus();
    }

    @Override
    protected void init() {
        // Two columns of fields, then the toggles and Done row beneath them.
        int leftX = this.width / 2 - FIELD_WIDTH - 8;
        int rightX = this.width / 2 + 8;
        // Center the block vertically so it never runs off the bottom at high GUI scales.
        int contentHeight = ROW_SPACING * 5 + 28;
        int top = Math.max(30, (this.height - contentHeight) / 2 + 10);

        int titleWidth = this.textRenderer.getWidth(this.title);
        this.addDrawableChild(new TextWidget((this.width - titleWidth) / 2, top - 24, titleWidth, 9,
            this.title, this.textRenderer));

        this.nameField = labeledField(leftX, top, "Station name", this.data.name(), 64, "e.g. Central");
        this.lineField = labeledField(leftX, top + ROW_SPACING, "Line", this.data.line(), 64, "e.g. Red Line");
        this.directionField = labeledField(leftX, top + ROW_SPACING * 2, "Direction", this.data.direction(), 64, "e.g. Northbound");
        this.nextField = labeledField(leftX, top + ROW_SPACING * 3, "Next stop", this.data.next(), 64, "next station name");

        this.exitField = labeledField(rightX, top, "Exit side (left/right/both)", this.data.exit(), 32, "left / right / both");
        this.transferField = labeledField(rightX, top + ROW_SPACING, "Transfer line", this.data.transfer(), 64, "line to transfer to");
        this.dwellField = labeledField(rightX, top + ROW_SPACING * 2, "Dwell (ticks)", Integer.toString(this.data.dwell()), 6, "e.g. 60");

        int toggleY = top + ROW_SPACING * 3;
        this.hubButton = ButtonWidget.builder(hubMessage(), b -> {
            this.hub = !this.hub;
            b.setMessage(hubMessage());
        }).dimensions(rightX, toggleY, FIELD_WIDTH / 2 - 4, FIELD_HEIGHT).build();
        this.addDrawableChild(this.hubButton);

        this.terminusButton = ButtonWidget.builder(terminusMessage(), b -> {
            this.terminus = !this.terminus;
            b.setMessage(terminusMessage());
        }).dimensions(rightX + FIELD_WIDTH / 2 + 4, toggleY, FIELD_WIDTH / 2 - 4, FIELD_HEIGHT).build();
        this.addDrawableChild(this.terminusButton);

        // Full-width line-colour dropdown spanning both columns.
        this.lineColor = DyeColor.byId(this.data.color(), DyeColor.WHITE);
        int colorY = top + ROW_SPACING * 4;
        this.addDrawableChild(CyclingButtonWidget.builder(
                (DyeColor dye) -> Text.literal("Line color: " + prettyName(dye)), this.lineColor)
            .values(DyeColor.values())
            .omitKeyText()
            .build(leftX, colorY, FIELD_WIDTH * 2 + 16, FIELD_HEIGHT, Text.literal("Line color"),
                (button, value) -> this.lineColor = value));

        int bottom = top + ROW_SPACING * 5 + 8;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> save())
            .dimensions(this.width / 2 - 154, bottom, 150, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
            .dimensions(this.width / 2 + 4, bottom, 150, 20).build());
    }

    private TextFieldWidget labeledField(int x, int y, String label, String value, int maxLength, String placeholder) {
        this.addDrawableChild(new TextWidget(x, y - 10, FIELD_WIDTH, 9, Text.literal(label), this.textRenderer));
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, FIELD_WIDTH, FIELD_HEIGHT, Text.empty());
        field.setMaxLength(maxLength);
        field.setPlaceholder(Text.literal(placeholder));
        field.setText(value);
        this.addDrawableChild(field);
        return field;
    }

    // Turns a DyeColor's snake_case name into a spaced, capitalised label.
    private static String prettyName(DyeColor dye) {
        String[] parts = dye.getId().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private Text hubMessage() {
        return Text.literal("Hub: " + (this.hub ? "yes" : "no"));
    }

    private Text terminusMessage() {
        return Text.literal("Terminus: " + (this.terminus ? "yes" : "no"));
    }

    private void save() {
        int dwell;
        try {
            dwell = Math.max(0, Integer.parseInt(this.dwellField.getText().trim()));
        } catch (NumberFormatException e) {
            dwell = this.data.dwell();
        }
        ClientPlayNetworking.send(new MetroNetworking.StationEdit(this.data.pos(),
            this.nameField.getText(), this.lineField.getText(), this.lineColor.getId(), this.directionField.getText(),
            this.nextField.getText(), this.exitField.getText(), this.transferField.getText(),
            this.hub, this.terminus, dwell));
        this.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
