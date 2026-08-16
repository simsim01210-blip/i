package kr.planetearth.minimap;

import net.minecraft.client.gui.DrawContext;
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

    // Three different attempts at tinting the texture draw itself (shader-colour only,
    // a hand-rolled vertex-coloured quad, and shader-colour with blending forced on)
    // all came out visually broken instead of translucent, for reasons that didn't match
    // what the shader source or bytecode said should happen. Rather than keep guessing
    // at GL state blind, tiles are drawn plain here again; OverlayMap achieves the
    // opacity slider's effect with a plain fill() overlay instead, which is proven to
    // blend correctly everywhere else in this mod.
    static void drawTexture(DrawContext context, Identifier texture,
                            int x, int y, int u, int v, int width, int height,
                            int textureWidth, int textureHeight) {
        context.drawTexture(texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    /**
     * Draws through DrawContext's own VertexConsumerProvider. Minecraft already keeps
     * same-layer GUI fills in one buffered batch, so this retains batching without
     * opening the global Tessellator directly. The latter can be "already building"
     * when another rendering mod is active and is a known crash-risk pattern.
     */
    static void fillBatch(DrawContext context, int[] left, int[] top, int[] right, int[] bottom,
                          int[] color, int count) {
        for (int i = 0; i < count; i++) {
            context.fill(left[i], top[i], right[i], bottom[i], color[i]);
        }
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
        int abgr = (argb & 0xFF00FF00)
                | ((argb & 0x00FF0000) >>> 16)
                | ((argb & 0x000000FF) << 16);
        image.setColor(x, y, abgr);
    }

    static Identifier registerDynamicTexture(TextureManager manager, String path, NativeImage image) {
        Identifier id = Identifier.tryParse(PlanetEarthMinimapClient.MOD_ID + ":dynamic/" + path);
        manager.registerTexture(id, new NativeImageBackedTexture(image));
        return id;
    }

    static PositionedSoundInstance controlSound(float pitch, float volume) {
        return PositionedSoundInstance.master(SoundEvents.ENTITY_GENERIC_EAT, pitch, volume);
    }

    static PositionedSoundInstance openCloseSound(float pitch, float volume) {
        return PositionedSoundInstance.master(
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pitch, volume);
    }

    static PositionedSoundInstance navigationCompleteSound(float pitch, float volume) {
        return PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, pitch, volume);
    }
}
