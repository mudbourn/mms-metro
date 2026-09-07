package info.mudbourn.mmsmetro.client.screen;

import info.mudbourn.mmsmetro.network.MetroNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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

        this.directionField = new TextFieldWidget(this.textRenderer, cx - FIELD_WIDTH / 2, top,
            FIELD_WIDTH, FIELD_HEIGHT, Text.empty());
        this.directionField.setMaxLength(64);
        this.directionField.setPlaceholder(Text.literal("e.g. Eastbound (match the stop)"));
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 50, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Direction"),
            this.directionField.getX(), this.directionField.getY() - 10, 0xA0A0A0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
