package kr.planetearth.minimap;

public final class MinimapEditorScreen extends MinimapEditorScreenBase {
    public MinimapEditorScreen() {
        super();
    }

    // See FullMapScreen's identical comment: vanilla calls every one of these
    // directly, so each is wrapped to keep a bug in this mod from crashing the game.
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            // Controls may overlap the preview, so they receive clicks first.
            if (super.mouseClicked(mouseX, mouseY, button)) return true;
            return handleMouseClicked(mouseX, mouseY, button);
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.error("미니맵 편집 클릭 처리 중 오류가 발생했습니다", error);
            return true;
        }
    }

    @Override
    public boolean mouseDragged(
            double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        try {
            if (handleMouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true;
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.error("미니맵 편집 드래그 처리 중 오류가 발생했습니다", error);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        try {
            handleMouseReleased(mouseX, mouseY, button);
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.error("미니맵 편집 마우스 놓기 처리 중 오류가 발생했습니다", error);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            if (PlanetEarthMinimapClient.editKey.matchesKey(keyCode, scanCode)) {
                close();
                return true;
            }
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.error("미니맵 편집 키 입력 처리 중 오류가 발생했습니다", error);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
