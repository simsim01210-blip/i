package kr.planetearth.minimap;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

final class PlatformCompat {
    private PlatformCompat() {}

    static net.minecraft.util.math.Vec3d cameraPosition(net.minecraft.client.render.Camera camera) {
        return camera.getPos();
    }

    static void drawTexture(DrawContext context, Identifier texture,
                            int x, int y, int u, int v, int width, int height,
                            int textureWidth, int textureHeight) {
        context.drawTexture(RenderLayer::getGuiTextured, texture, x, y,
                u, v, width, height, textureWidth, textureHeight);
    }

    static void push(DrawContext context) { context.getMatrices().push(); }
    static void pop(DrawContext context) { context.getMatrices().pop(); }
    static void translate(DrawContext context, float x, float y) {
        context.getMatrices().translate(x, y, 0.0f);
    }
    static void scale(DrawContext context, float x, float y) {
        context.getMatrices().scale(x, y, 1.0f);
    }
    static void rotate(DrawContext context, float degrees) {
        context.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(degrees));
    }

    static void setNativeImageColor(NativeImage image, int x, int y, int argb) {
        image.setColorArgb(x, y, argb);
    }

    static Identifier registerDynamicTexture(TextureManager manager, String path, NativeImage image) {
        Identifier id = Identifier.tryParse(PlanetEarthMinimapClient.MOD_ID + ":dynamic/" + path);
        manager.registerTexture(id, new NativeImageBackedTexture(id::toString, image));
        return id;
    }

    static PositionedSoundInstance controlSound(float pitch, float volume) {
        return PositionedSoundInstance.master(SoundEvents.ENTITY_GENERIC_EAT.value(), pitch, volume);
    }

    static PositionedSoundInstance openCloseSound(float pitch, float volume) {
        return PositionedSoundInstance.master(
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pitch, volume);
    }

    static PositionedSoundInstance navigationCompleteSound(float pitch, float volume) {
        return PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, pitch, volume);
    }
}
