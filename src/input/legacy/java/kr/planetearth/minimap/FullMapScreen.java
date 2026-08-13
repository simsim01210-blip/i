package kr.planetearth.minimap;

public final class FullMapScreen extends FullMapScreenBase {
    public FullMapScreen() {
        super();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (interceptMouseClicked(mouseX, mouseY, button)) return true;
        return handleMouseClickedAfterChildren(
                mouseX, mouseY, button, super.mouseClicked(mouseX, mouseY, button));
    }

    @Override
    public boolean mouseDragged(
            double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (handleMouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        handleMouseReleased(mouseX, mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (PlanetEarthMinimapClient.fullMapKey.matchesKey(keyCode, scanCode)
                && !isSearchInputFocused()) {
            close();
            return true;
        }
        if (handleKeyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
