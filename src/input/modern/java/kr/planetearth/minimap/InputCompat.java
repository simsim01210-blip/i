package kr.planetearth.minimap;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;

final class InputCompat {
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(
            Identifier.tryParse(PlanetEarthMinimapClient.MOD_ID + ":main"));

    private InputCompat() {}

    static KeyBinding createKeyBinding(String translationKey, int keyCode) {
        return new KeyBinding(translationKey, keyCode, CATEGORY);
    }
}
