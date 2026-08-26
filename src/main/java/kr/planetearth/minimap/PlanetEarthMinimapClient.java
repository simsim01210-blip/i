package kr.planetearth.minimap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWScrollCallbackI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PlanetEarthMinimapClient implements ClientModInitializer {
    public static final String MOD_ID = "planetearth_minimap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static MinimapConfig config;
    public static KeyBinding editKey;
    public static KeyBinding fullMapKey;
    public static KeyBinding overlayMapKey;

    @Override
    public void onInitializeClient() {
        config = MinimapConfig.load();
        LiveAtlasTileManager.applyLowSpecMode(config.lowSpecMode);
        editKey = KeyBindingHelper.registerKeyBinding(InputCompat.createKeyBinding(
                "key.planetearth_minimap.edit", GLFW.GLFW_KEY_M));
        fullMapKey = KeyBindingHelper.registerKeyBinding(InputCompat.createKeyBinding(
                "key.planetearth_minimap.full_map", GLFW.GLFW_KEY_N));
        overlayMapKey = KeyBindingHelper.registerKeyBinding(InputCompat.createKeyBinding(
                "key.planetearth_minimap.overlay_map", GLFW.GLFW_KEY_G));

        // The callback's second parameter is float on 1.20.x and
        // RenderTickCounter on 1.21.x. It is not needed for this HUD, so keep it
        // inside an inferred lambda instead of leaking either type into our API.
        // Every one of these three registrations is called directly by the game
        // engine every frame/tick/scroll — an exception escaping any of them (from
        // anywhere in this mod, now or in some future change) would otherwise crash
        // Minecraft itself, not just break the minimap. Catching Throwable here is a
        // deliberate last-resort net around the mod's entire rendering/input surface:
        // log it and skip that one frame/tick/scroll instead of taking the game down.
        HudRenderCallback.EVENT.register((context, ignoredTickCounter) -> {
            try {
                MinimapHud.render(context);
            } catch (Throwable error) {
                LOGGER.error("미니맵 렌더링 중 오류가 발생해 이번 프레임을 건너뜁니다", error);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                NavigationManager.tick(client);
                while (editKey.wasPressed()) {
                    MinimapEditorScreen.playOpenCloseSound();
                    if (client.currentScreen instanceof MinimapEditorScreen) {
                        client.setScreen(null);
                    } else {
                        client.setScreen(new MinimapEditorScreen());
                    }
                }
                while (fullMapKey.wasPressed()) {
                    if (client.currentScreen instanceof FullMapScreenBase screen) {
                        if (screen.isSearchInputFocused()) continue;
                        client.setScreen(null);
                    } else {
                        client.setScreen(new FullMapScreen());
                    }
                }
            } catch (Throwable error) {
                LOGGER.error("미니맵 틱 처리 중 오류가 발생했습니다", error);
            }
        });

        // No mixins in this project: the overlay map's mouse-wheel zoom is read by
        // wrapping the raw GLFW scroll callback instead. While the overlay key isn't
        // held we hand the event straight to whatever Minecraft had installed (hotbar
        // slot scrolling, menu scrolling, etc.), so nothing else about scrolling changes.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            long windowHandle = client.getWindow().getHandle();
            // Holder because the callback closure needs to call whatever was previously
            // installed, but that previous callback is only known once glfwSetScrollCallback
            // below returns — after the lambda itself has already been created.
            GLFWScrollCallback[] previousHolder = new GLFWScrollCallback[1];
            GLFWScrollCallback previous = GLFW.glfwSetScrollCallback(windowHandle,
                    (GLFWScrollCallbackI) (window, xoffset, yoffset) -> {
                        // A raw GLFW callback is a native boundary — an uncaught Java
                        // exception escaping it is worse than an ordinary crash, so this
                        // is guarded even more defensively than the other two hooks
                        // above, including the call into whatever mod installed the
                        // previous callback.
                        try {
                            if (overlayMapKey.isPressed()) {
                                OverlayMap.handleScroll(yoffset);
                                return;
                            }
                        } catch (Throwable error) {
                            LOGGER.error("보조 맵 스크롤 처리 중 오류가 발생했습니다", error);
                            return;
                        }
                        try {
                            if (previousHolder[0] != null) previousHolder[0].invoke(window, xoffset, yoffset);
                        } catch (Throwable error) {
                            LOGGER.error("이전 스크롤 콜백 처리 중 오류가 발생했습니다", error);
                        }
                    });
            previousHolder[0] = previous;
        });

        LOGGER.info("PlanetEarth Minimap initialized; edit key registered as {}",
                editKey.getBoundKeyTranslationKey());
    }
}
