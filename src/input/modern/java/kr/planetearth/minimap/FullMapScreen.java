package kr.planetearth.minimap;

import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;

public final class FullMapScreen extends FullMapScreenBase {
    public FullMapScreen() {
        super();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (interceptMouseClicked(click.x(), click.y(), click.button())) return true;
        return handleMouseClickedAfterChildren(click.x(), click.y(), click.button(),
                super.mouseClicked(click, doubled));
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
        if (PlanetEarthMinimapClient.fullMapKey.matchesKey(input)
                && !isSearchInputFocused()) {
            close();
            return true;
        }
        if (handleKeyPressed(input.key(), input.scancode(), input.modifiers())) return true;
        return super.keyPressed(input);
    }
}
