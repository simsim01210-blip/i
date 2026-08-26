package kr.planetearth.minimap;

public final class FullMapScreen extends FullMapScreenBase {
    public FullMapScreen() {
        super();
    }

    // Vanilla calls every override below directly — an exception escaping any of them
    // crashes the whole game exactly the way a bad marker/area click once already did
    // (see the mouseClicked history). Each is wrapped so a bug in this mod closes at
    // worst this one screen instead of the client; falling back to the ordinary
    // Screen behaviour on failure keeps clicking/scrolling/typing usable everywhere
    // else even if this mod's own extra handling for it broke.
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            if (interceptMouseClicked(mouseX, mouseY, button)) return true;
            return handleMouseClickedAfterChildren(
                    mouseX, mouseY, button, super.mouseClicked(mouseX, mouseY, button));
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.error("전체 지도 클릭 처리 중 오류가 발생했습니다", error);
            return true;
        }
    }

    @Override
    public boolean mouseDragged(
            double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        try {
            if (handleMouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true;
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.error("전체 지도 드래그 처리 중 오류가 발생했습니다", error);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        try {
            handleMouseReleased(mouseX, mouseY, button);
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.error("전체 지도 마우스 놓기 처리 중 오류가 발생했습니다", error);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            if (PlanetEarthMinimapClient.fullMapKey.matchesKey(keyCode, scanCode)
                    && !isSearchInputFocused()) {
                close();
                return true;
            }
            if (handleKeyPressed(keyCode, scanCode, modifiers)) return true;
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.error("전체 지도 키 입력 처리 중 오류가 발생했습니다", error);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
