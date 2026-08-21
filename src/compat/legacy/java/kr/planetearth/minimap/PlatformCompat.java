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

    /** Draws a cropped texture region into an independently sized destination. Used
     *  only while a newly selected map zoom is loading so a cached neighbouring zoom
     *  can cover the same tile without a blank/loading flash. */
    static void drawTextureRegion(DrawContext context, Identifier texture,
                                  int x, int y, int width, int height,
                                  int u, int v, int regionWidth, int regionHeight,
                                  int textureWidth, int textureHeight) {
        context.drawTexture(texture, x, y, width, height, u, v,
                regionWidth, regionHeight, textureWidth, textureHeight);
    }

    /** For textures whose own PNG already has real per-pixel alpha baked in (the
     *  hotbar watermark) rather than trying to tint an opaque texture at draw time —
     *  that shader-colour approach is the specific technique that repeatedly failed
     *  above. A plain drawTexture() call can still come out fully opaque if GL blend
     *  simply isn't enabled at that exact point in the frame; the watermark draws as
     *  the very first thing in the HUD layer, before anything else in this mod has
     *  implicitly turned blend on via its own fill() calls, so it was hitting exactly
     *  that. Blend is switched on only around this one call and restored after,
     *  rather than assumed to already be in the right state. */
    static void drawTranslucentTexture(DrawContext context, Identifier texture,
                                       int x, int y, int u, int v, int width, int height,
                                       int textureWidth, int textureHeight) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        context.drawTexture(texture, x, y, u, v, width, height, textureWidth, textureHeight);
        RenderSystem.disableBlend();
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

    /** Composites a full-window transparent framebuffer through a circular mesh. The
     *  source uses OpenGL's bottom-left texture origin, hence the flipped V coordinate. */
    static void drawFramebufferCircle(DrawContext context, int textureId,
                                      int centerX, int centerY, int radius,
                                      int scaledWidth, int scaledHeight) {
        if (radius <= 0 || scaledWidth <= 0 || scaledHeight <= 0) return;
        context.draw();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderTexture(0, textureId);

        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        builder.begin(VertexFormat.DrawMode.TRIANGLE_FAN, VertexFormats.POSITION_TEXTURE);
        addFramebufferVertex(builder, matrix, centerX, centerY, scaledWidth, scaledHeight);
        final int segments = 128;
        for (int i = 0; i <= segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            float x = centerX + (float) Math.cos(angle) * radius;
            float y = centerY + (float) Math.sin(angle) * radius;
            addFramebufferVertex(builder, matrix, x, y, scaledWidth, scaledHeight);
        }
        BufferRenderer.drawWithGlobalProgram(builder.end());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void addFramebufferVertex(BufferBuilder builder, Matrix4f matrix,
                                             float x, float y,
                                             int scaledWidth, int scaledHeight) {
        float u = x / scaledWidth;
        float v = 1.0f - y / scaledHeight;
        builder.vertex(matrix, x, y, 0.0f).texture(u, v).next();
    }

    /** Pixel-width circular equivalent of the classic hotbar bevel. Geometry is sent
     *  in one batch and its band widths stay fixed when the minimap is resized. */
    static void drawCircularHotbarFrame(DrawContext context, int centerX, int centerY, int radius) {
        if (radius <= 0) return;
        context.draw();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        builder.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        addRingBand(builder, matrix, centerX, centerY, radius + 4.0f, radius + 3.0f, 0xF0101010);
        addRingBand(builder, matrix, centerX, centerY, radius + 3.0f, radius + 1.0f, 0xFF8B8B8B);
        addRingBand(builder, matrix, centerX, centerY, radius + 1.0f, radius, 0xFFC6C6C6);
        addRingBand(builder, matrix, centerX, centerY, radius, Math.max(0.0f, radius - 2.0f), 0xFF373737);
        BufferRenderer.drawWithGlobalProgram(builder.end());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void addRingBand(BufferBuilder builder, Matrix4f matrix,
                                    float centerX, float centerY,
                                    float outerRadius, float innerRadius, int argb) {
        final int segments = 128;
        for (int i = 0; i < segments; i++) {
            double angle0 = Math.PI * 2.0 * i / segments;
            double angle1 = Math.PI * 2.0 * (i + 1) / segments;
            float outerX0 = centerX + (float) Math.cos(angle0) * outerRadius;
            float outerY0 = centerY + (float) Math.sin(angle0) * outerRadius;
            float outerX1 = centerX + (float) Math.cos(angle1) * outerRadius;
            float outerY1 = centerY + (float) Math.sin(angle1) * outerRadius;
            float innerX0 = centerX + (float) Math.cos(angle0) * innerRadius;
            float innerY0 = centerY + (float) Math.sin(angle0) * innerRadius;
            float innerX1 = centerX + (float) Math.cos(angle1) * innerRadius;
            float innerY1 = centerY + (float) Math.sin(angle1) * innerRadius;
            addColorVertex(builder, matrix, outerX0, outerY0, argb);
            addColorVertex(builder, matrix, outerX1, outerY1, argb);
            addColorVertex(builder, matrix, innerX1, innerY1, argb);
            addColorVertex(builder, matrix, outerX0, outerY0, argb);
            addColorVertex(builder, matrix, innerX1, innerY1, argb);
            addColorVertex(builder, matrix, innerX0, innerY0, argb);
        }
    }

    private static void addColorVertex(BufferBuilder builder, Matrix4f matrix,
                                       float x, float y, int argb) {
        builder.vertex(matrix, x, y, 0.0f)
                .color((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF,
                        argb & 0xFF, (argb >>> 24) & 0xFF)
                .next();
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
