package kr.planetearth.minimap;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

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

    /** Draws many solid-colour rectangles as ONE GPU draw call instead of one call per
     *  rectangle. This is the same pipeline context.fill() itself uses per-call
     *  (RenderLayer.getGui()'s shader, position_color format, the same blend function)
     *  — just accumulated into a single buffer and submitted once, the way a mod like
     *  ImmediatelyFast batches immediate-mode draws in general. Deliberately kept to
     *  exactly this proven-reliable pipeline rather than anything involving a texture
     *  or a custom blend function — see the drawTexture() comment above for why that
     *  distinction matters in this codebase specifically. */
    static void fillBatch(DrawContext context, int[] left, int[] top, int[] right, int[] bottom,
                          int[] color, int count) {
        if (count <= 0) return;
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            int packed = color[i];
            float a = ((packed >>> 24) & 0xFF) / 255.0f;
            float r = ((packed >>> 16) & 0xFF) / 255.0f;
            float g = ((packed >>> 8) & 0xFF) / 255.0f;
            float b = (packed & 0xFF) / 255.0f;
            float x1 = left[i];
            float y1 = top[i];
            float x2 = right[i];
            float y2 = bottom[i];
            buffer.vertex(matrix, x1, y2, 0).color(r, g, b, a).next();
            buffer.vertex(matrix, x2, y2, 0).color(r, g, b, a).next();
            buffer.vertex(matrix, x2, y1, 0).color(r, g, b, a).next();
            buffer.vertex(matrix, x1, y1, 0).color(r, g, b, a).next();
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.disableBlend();
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
