package info.mudbourn.mmsmetro.client.screen;

import info.mudbourn.mmsmetro.network.MetroNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

// The speed-bump editor. A bump stores only whether it announces the upcoming
// stop as a terminal station, so the screen is a single toggle plus Done.
public class BumpEditScreen extends Screen {

    private final MetroNetworking.OpenBumpScreen data;
    private boolean terminus;

    public BumpEditScreen(MetroNetworking.OpenBumpScreen data) {
        super(Text.literal("Edit Speed Bump"));
        this.data = data;
        this.terminus = data.terminus();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = this.height / 2 - 30;
        this.addDrawableChild(ButtonWidget.builder(terminusMessage(), b -> {
            this.terminus = !this.terminus;
            b.setMessage(terminusMessage());
        }).dimensions(cx - 150, top, 300, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> save())
            .dimensions(cx - 150, top + 34, 148, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
            .dimensions(cx + 2, top + 34, 148, 20).build());
    }

    private Text terminusMessage() {
        return Text.literal("Announce terminal station: " + (this.terminus ? "yes" : "no"));
    }

    private void save() {
        ClientPlayNetworking.send(new MetroNetworking.BumpEdit(this.data.pos(), this.terminus));
        this.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
