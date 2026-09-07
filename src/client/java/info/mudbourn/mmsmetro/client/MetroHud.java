package info.mudbourn.mmsmetro.client;

import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;

// The onboard display shown to a seated rider: which line they are on, the
// direction of travel, and the next stop. Reads straight off the car entity the
// player rides, whose fields the server stamps on every car in the consist.
public final class MetroHud {

    private static final int PANEL_BG = 0xC0101014;
    private static final int ACCENT = 0xFFF5A623;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int LABEL = 0xFF9AA0A6;
    private static final int MARGIN = 6;
    private static final int PAD = 6;
    private static final int LINE_H = 11;

    private MetroHud() {
    }

    public static void render(DrawContext context, MinecraftClient client) {
        if (client.player == null || client.options.hudHidden) {
            return;
        }
        Entity vehicle = client.player.getVehicle();
        if (!(vehicle instanceof MetroCarEntity car)) {
            return;
        }

        TextRenderer font = client.textRenderer;
        String line = car.getHudLine();
        String direction = car.getHudDirection();
        String next = car.getHudNextStation();
        String announcement = car.getHudAnnouncement();
        boolean waiting = car.isHudWaiting();

        // Build the lines from the top down; skip anything the station left blank.
        java.util.List<Line> rows = new java.util.ArrayList<>();
        if (!line.isEmpty()) {
            rows.add(new Line(line, ACCENT, true));
        }
        if (!direction.isEmpty()) {
            rows.add(new Line(direction, WHITE, false));
        }
        String nextLabel = waiting ? "Now arriving" : "Next stop";
        rows.add(new Line(nextLabel + ": " + (next.isEmpty() ? "*" : next), LABEL, false));
        if (!announcement.isEmpty()) {
            rows.add(new Line(announcement, WHITE, false));
        }

        if (rows.isEmpty()) {
            return;
        }

        int width = 0;
        for (Line row : rows) {
            width = Math.max(width, font.getWidth(row.text));
        }
        int panelW = width + PAD * 2;
        int panelH = rows.size() * LINE_H + PAD * 2 - (LINE_H - font.fontHeight);
        int x = MARGIN;
        int y = MARGIN;

        context.fill(x, y, x + panelW, y + panelH, PANEL_BG);
        context.fill(x, y, x + 2, y + panelH, ACCENT);

        int ty = y + PAD;
        for (Line row : rows) {
            context.drawTextWithShadow(font, Text.literal(row.text), x + PAD, ty, row.color);
            ty += LINE_H;
        }
    }

    private record Line(String text, int color, boolean header) {
    }
}
