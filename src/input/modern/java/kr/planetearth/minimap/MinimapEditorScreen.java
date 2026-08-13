package kr.planetearth.minimap;

import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;

public final class MinimapEditorScreen extends MinimapEditorScreenBase {
    public MinimapEditorScreen() {
        super();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        return handleMouseClicked(click.x(), click.y(), click.button());
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (handleMouseDragged(click.x(), click.y(), click.button(), deltaX, deltaY)) return true;
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        handleMouseReleased(click.x(), click.y(), click.button());
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (PlanetEarthMinimapClient.editKey.matchesKey(input)) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }
}
