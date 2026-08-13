package kr.planetearth.minimap;

public final class MinimapEditorScreen extends MinimapEditorScreenBase {
    public MinimapEditorScreen() {
        super();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Controls may overlap the preview, so they receive clicks first.
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        return handleMouseClicked(mouseX, mouseY, button);
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
        if (PlanetEarthMinimapClient.editKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
