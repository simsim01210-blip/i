package kr.planetearth.minimap;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

final class InputCompat {
    private InputCompat() {}

    static KeyBinding createKeyBinding(String translationKey, int keyCode) {
        return new KeyBinding(translationKey, InputUtil.Type.KEYSYM, keyCode,
                "key.categories.planetearth_minimap");
    }
}
