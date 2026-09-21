/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Touch Chanchoronaiy (a1978787), added documentatino for further understanding
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.nio.ByteBuffer;

import org.bytedeco.javacv.Frame;

/**
 * Simulates bright light bleeding into nearby pixels, with a warm colour bias.
 * This one is expensive in CPU time!
 *
 * <p>
 * For each frame, {@link #buildBrightMask(ByteBuffer)} identifies bright
 * regions, {@link #blurBrightMask()} spreads their influence, and
 * {@link #applyBleed(ByteBuffer)} adds the resulting coloured glow.
 * </p>
 */


public class FrameBleeder extends FrameProcessor {

    // -b selects this effect. PlaybackSession initialises its reusable arrays
    // one pipeline stage owns them while process() adds glow to each image.
    // The arrays are mutable, so the same instance cannot process two frames at once.
    private static final int MASK_BLOCK_SIZE = 4;
    private static final int BLUR_RADIUS = 3;
    private static final int BLEED_LUMINANCE_THRESHOLD = 150;
    private static final double BLEED_GAMMA = 1.25;
    private static final double BLEED_MASK_GAIN = 2.4;
    private static final double RED_BLEED_STRENGTH = 105.0;
    private static final double GREEN_BLEED_STRENGTH = 62.0;
    private static final double BLUE_BLEED_STRENGTH = 18.0;

    private int maskWidth;
    private int maskHeight;
    private double[] brightMask;
    private double[] horizontalBlurMask;
    private double[] blurredMask;
    private int[] xMaskLeft;
    private double[] xMaskBlend;
    private int[] yMaskTop;
    private double[] yMaskBlend;

    public FrameBleeder() {
        super();
    }

    //----------ALLOCATE A SMALL BRIGHTNESS MASK----------
    // Ceiling-style integer division includes partial 4-by-4 image blocks at the edges. 
    // Flat arrays store rows of mask values, not full colour pixels.
    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);

        maskWidth = (videoInfo.imageWidth() + MASK_BLOCK_SIZE - 1) / MASK_BLOCK_SIZE;
        maskHeight = (videoInfo.imageHeight() + MASK_BLOCK_SIZE - 1) / MASK_BLOCK_SIZE;
        brightMask = new double[maskWidth * maskHeight];
        horizontalBlurMask = new double[brightMask.length];
        blurredMask = new double[brightMask.length];
        buildSamplingLookups();
    }

    // Validate byte/BGR layout, then run three steps: find highlights, spread
    // their influence, and add the warm glow to the original owned image.
    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameBleeder requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameBleeder requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 3) {
            throw new Exception("FrameBleeder requires at least three image channels");
        }

        buildBrightMask(pixels);
        blurBrightMask();
        applyBleed(pixels);
    }

    //----------MAP FULL-SIZED PIXELS TO THE SMALL MASK----------
    // Store neighbouring mask indices and blend fractions for each image axis.
    // Reusing these tables avoids coordinate maths on every frame's every pixel.
    private void buildSamplingLookups() {
        xMaskLeft = new int[videoInfo.imageWidth()];
        xMaskBlend = new double[videoInfo.imageWidth()];
        yMaskTop = new int[videoInfo.imageHeight()];
        yMaskBlend = new double[videoInfo.imageHeight()];

        buildAxisSamplingLookups(videoInfo.imageWidth(), maskWidth, xMaskLeft, xMaskBlend);
        buildAxisSamplingLookups(videoInfo.imageHeight(), maskHeight, yMaskTop, yMaskBlend);
    }

    // Pixel-centre coordinates align image blocks with mask samples. 
    // floor() selects the left/top neighbour; clamping keeps neighbours within the mask.
    // A one-cell axis uses zero indices/fractions instead of dividing or overrunning
    private void buildAxisSamplingLookups(int imageSize, int maskSize, int[] maskStarts, double[] maskBlends) {
        if (imageSize <= 1 || maskSize <= 1) {
            for (int position = 0; position < imageSize; position++) {
                maskStarts[position] = 0;
                maskBlends[position] = 0.0;
            }
            return;
        }

        for (int position = 0; position < imageSize; position++) {
            double maskPosition = (position + 0.5) / MASK_BLOCK_SIZE - 0.5;
            maskPosition = clamp(maskPosition, 0.0, maskSize - 1.0);

            int maskStart = (int) Math.floor(maskPosition);
            if (maskStart >= maskSize - 1) {
                maskStart = maskSize - 2;
                maskPosition = maskSize - 1;
            }

            maskStarts[position] = maskStart;
            maskBlends[position] = maskPosition - maskStart;
        }
    }
/* For each frame, buildBrightMask(ByteBuffer) identifies bright regions*/
    //----------FIND HIGHLIGHTS----------
    // Each mask cell keeps the brightest luminance in its image block. 
    // BGR bytes become unsigned 0..255 intensities before the weighted brightness calculation.
    // row * stride + column * channels addresses a full-image pixel.
    private void buildBrightMask(ByteBuffer pixels) {
        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        for (int maskY = 0; maskY < maskHeight; maskY++) {
            int blockY = maskY * MASK_BLOCK_SIZE;
            int blockYEnd = Math.min(blockY + MASK_BLOCK_SIZE, height);

            for (int maskX = 0; maskX < maskWidth; maskX++) {
                int blockX = maskX * MASK_BLOCK_SIZE;
                int blockXEnd = Math.min(blockX + MASK_BLOCK_SIZE, width);
                int maximumLuminance = 0;

                for (int y = blockY; y < blockYEnd; y++) {
                    int rowStart = y * stride;
                    for (int x = blockX; x < blockXEnd; x++) {
                        int base = rowStart + x * channels;
                        // JavaCV supplies these decoded frames in BGR byte order.
                        int b = Byte.toUnsignedInt(pixels.get(base));
                        int g = Byte.toUnsignedInt(pixels.get(base + 1));
                        int r = Byte.toUnsignedInt(pixels.get(base + 2));
                        int luminance = (54 * r + 183 * g + 19 * b) / 256;
                        maximumLuminance = Math.max(maximumLuminance, luminance);
                    }
                }

                brightMask[maskY * maskWidth + maskX] = thresholdedBrightness(maximumLuminance);
            }
        }
    }

    // Ignore values at/below the highlight threshold. Map the rest to [0,1],
    // then the supplied gamma power controls how strongly they contribute.
    private double thresholdedBrightness(int luminance) {
        if (luminance <= BLEED_LUMINANCE_THRESHOLD) {
            return 0.0;
        }

        double normalised = (luminance - BLEED_LUMINANCE_THRESHOLD) / (double) (255 - BLEED_LUMINANCE_THRESHOLD);
        return Math.pow(normalised, BLEED_GAMMA);
    }

    //----------SPREAD THE HIGHLIGHTS----------
    // Two 1D box blurs create a rectangular 2D blur with less work than a nested
    // 2D neighbourhood per cell. Separate arrays prevent reading half-updated values.
    private void blurBrightMask() {
        blurHorizontally(brightMask, horizontalBlurMask);
        blurVertically(horizontalBlurMask, blurredMask);
    }

    // Average the seven horizontal samples (radius 3); repeat border values
    // through clamping rather than reading outside the array.
    private void blurHorizontally(double[] source, double[] destination) {
        for (int y = 0; y < maskHeight; y++) {
            int rowStart = y * maskWidth;
            for (int x = 0; x < maskWidth; x++) {
                double total = 0.0;
                int samples = 0;

                for (int dx = -BLUR_RADIUS; dx <= BLUR_RADIUS; dx++) {
                    int sampleX = clamp(x + dx, 0, maskWidth - 1);
                    total += source[rowStart + sampleX];
                    samples++;
                }

                destination[rowStart + x] = total / samples;
            }
        }
    }

    // Same averaging down columns, taking the horizontal pass as input.
    private void blurVertically(double[] source, double[] destination) {
        for (int y = 0; y < maskHeight; y++) {
            for (int x = 0; x < maskWidth; x++) {
                double total = 0.0;
                int samples = 0;

                for (int dy = -BLUR_RADIUS; dy <= BLUR_RADIUS; dy++) {
                    int sampleY = clamp(y + dy, 0, maskHeight - 1);
                    total += source[sampleY * maskWidth + x];
                    samples++;
                }

                destination[y * maskWidth + x] = total / samples;
            }
        }
    }

    //----------ADD WARM GLOW TO BGR PIXELS----------
    private void applyBleed(ByteBuffer pixels) {
        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        for (int y = 0; y < height; y++) {
            int rowStart = y * stride;

            for (int x = 0; x < width; x++) {
                double bleedStrength = sampleBlurredMask(x, y);
                if (bleedStrength <= 0.0) {
                    continue;
                }

                int blueBoost = (int) Math.round(bleedStrength * BLUE_BLEED_STRENGTH);
                int greenBoost = (int) Math.round(bleedStrength * GREEN_BLEED_STRENGTH);
                int redBoost = (int) Math.round(bleedStrength * RED_BLEED_STRENGTH);
                int base = rowStart + x * channels;

                pixels.put(base, boostedByte(pixels.get(base), blueBoost));
                pixels.put(base + 1, boostedByte(pixels.get(base + 1), greenBoost));
                pixels.put(base + 2, boostedByte(pixels.get(base + 2), redBoost));
            }
        }
    }

    // Bilinear interpolation: blend two mask cells on the top row and two on
    // the bottom row, then blend the row results. Gain is capped at strength 1.
    private double sampleBlurredMask(int x, int y) {
        int x0 = xMaskLeft[x];
        int x1 = Math.min(x0 + 1, maskWidth - 1);
        double xBlend = xMaskBlend[x];

        int y0 = yMaskTop[y];
        int y1 = Math.min(y0 + 1, maskHeight - 1);
        double yBlend = yMaskBlend[y];

        double top = lerp(blurredMask[y0 * maskWidth + x0], blurredMask[y0 * maskWidth + x1], xBlend);
        double bottom = lerp(blurredMask[y1 * maskWidth + x0], blurredMask[y1 * maskWidth + x1], xBlend);
        return Math.min(1.0, lerp(top, bottom, yBlend) * BLEED_MASK_GAIN);
    }

    // Saturating addition: stop at white (255), rather than overflow and wrap dark.
    private byte boostedByte(byte value, int boost) {
        return (byte) Math.min(255, Byte.toUnsignedInt(value) + boost);
    }

    // Overloaded integer/decimal helpers keep coordinates or strengths in range.
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    // Linear interpolation: blend 0 selects a, blend 1 selects b.
    private double lerp(double a, double b, double blend) {
        return a + (b - a) * blend;
    }
}
