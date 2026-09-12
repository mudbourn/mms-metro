package info.mudbourn.mmsmetro.client.screen;

import info.mudbourn.mmsmetro.network.MetroNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

// The speed-bump editor: a single direction that must match the stop it heralds, so a bump only announces platforms on its own side of the track.
public class BumpEditScreen extends Screen {

    private static final int FIELD_WIDTH = 220;
    private static final int FIELD_HEIGHT = 20;

    private final MetroNetworking.OpenBumpScreen data;
    private TextFieldWidget directionField;

    public BumpEditScreen(MetroNetworking.OpenBumpScreen data) {
        super(Text.literal("Edit Speed Bump"));
        this.data = data;
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

        this.directionField = new TextFieldWidget(this.textRenderer, cx - FIELD_WIDTH / 2, top,
            FIELD_WIDTH, FIELD_HEIGHT, Text.empty());
        this.directionField.setMaxLength(64);
        this.directionField.setPlaceholder(Text.literal("e.g. Eastbound, or T_Northbound for a terminus"));
        this.directionField.setText(this.data.direction());
        this.addDrawableChild(this.directionField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> save())
            .dimensions(cx - FIELD_WIDTH / 2, top + 34, FIELD_WIDTH / 2 - 2, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
            .dimensions(cx + 2, top + 34, FIELD_WIDTH / 2 - 2, 20).build());
    }

    private void save() {
        ClientPlayNetworking.send(new MetroNetworking.BumpEdit(this.data.pos(), this.directionField.getText()));
        this.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
