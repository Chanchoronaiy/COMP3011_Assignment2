/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Touch Chanchoronaiy (a1978787)
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.nio.ByteBuffer;

import org.bytedeco.javacv.Frame;

/**
 * Draws the current video-frame number in the bottom-right corner of each frame,
 * using tiny 'seven segment display'-like rendering. This lets us read frame number
 * off screen captures so the correctness of other effects can be compared to a
 * reference implementation display the exact same frame number.
 *
 * <p>
 * {@link #process(Frame, InfoFrame)} converts the number to digits, and
 * {@link #drawDigit(ByteBuffer, int, int, int)} renders each digit using a small
 * seven-segment-style pixel glyph.
 * </p>
 */
public class FrameNumberer extends FrameProcessor {

    // -n selects this effect. The pipeline supplies InfoFrame.frameNumber(), and
    // this draws that number INTO the image. it is not a separate JavaFX label.
    // Its position in the effect list matters: later effects can alter the digits.
    private static final int TILE_WIDTH = 5;
    private static final int TILE_HEIGHT = 7;
    private static final int TILE_SPACING = 1;
    private static final int MARGIN = 1;

    // Each power of two represents one segment. 
    // | combines segment bits in a digit's mask; & later tests whether a particular segment should be drawn.
    private static final int TOP = 1;
    private static final int UPPER_LEFT = 2;
    private static final int UPPER_RIGHT = 4;
    private static final int MIDDLE = 8;
    private static final int LOWER_LEFT = 16;
    private static final int LOWER_RIGHT = 32;
    private static final int BOTTOM = 64;

    // 7-segment display rendering rules for each decimal digit:
    private static final int[] DIGIT_SEGMENTS = {
            /* 0 */ TOP | UPPER_LEFT | UPPER_RIGHT | LOWER_LEFT | LOWER_RIGHT | BOTTOM,
            /* 1 */ UPPER_RIGHT | LOWER_RIGHT,
            /* 2 */ TOP | UPPER_RIGHT | MIDDLE | LOWER_LEFT | BOTTOM,
            /* 3 */ TOP | UPPER_RIGHT | MIDDLE | LOWER_RIGHT | BOTTOM,
            /* 4 */ UPPER_LEFT | UPPER_RIGHT | MIDDLE | LOWER_RIGHT,
            /* 5 */ TOP | UPPER_LEFT | MIDDLE | LOWER_RIGHT | BOTTOM,
            /* 6 */ TOP | UPPER_LEFT | MIDDLE | LOWER_LEFT | LOWER_RIGHT | BOTTOM,
            /* 7 */ TOP | UPPER_RIGHT | LOWER_RIGHT,
            /* 8 */ TOP | UPPER_LEFT | UPPER_RIGHT | MIDDLE | LOWER_LEFT | LOWER_RIGHT | BOTTOM,
            /* 9 */ TOP | UPPER_LEFT | UPPER_RIGHT | MIDDLE | LOWER_RIGHT | BOTTOM
    };

    public FrameNumberer() {
    }

    //----------VALIDATE IMAGE / POSITION THE DIGITS----------
    // absent image returns immediately; unsupported layouts throw an exception.
    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameNumberer requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameNumberer requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 1) {
            throw new Exception("FrameNumberer requires at least one image channel");
        }

        // Convert a nonnegative frame number to text. Width includes one tile per
        // digit plus gaps; subtract from image size to anchor it at bottom-right.
        String text = Integer.toString(Math.max(0, info.frameNumber()));
        int textWidth = text.length() * TILE_WIDTH + (text.length() - 1) * TILE_SPACING;
        int textHeight = TILE_HEIGHT;

        int startX = videoInfo.imageWidth() - MARGIN - textWidth;
        int startY = videoInfo.imageHeight() - MARGIN - textHeight;

        if (startX < 0 || startY < 0) {
            // Oops - frames are too small for our render!
            return;
        }

        for (int i = 0; i < text.length(); i++) {
            // Digit characters are consecutive: '7' - '0' gives integer 7.
            // Paint a black tile first so the white segments remain readable.
            int digit = text.charAt(i) - '0';
            int tileX = startX + i * (TILE_WIDTH + TILE_SPACING);
            fillRectangle(pixels, tileX, startY, TILE_WIDTH, textHeight, 0);
            drawDigit(pixels, tileX, startY, digit);
        }
    }

    // (segments & TOP) != 0 asks whether the TOP bit is set.
    private void drawDigit(ByteBuffer pixels, int tileX, int tileY, int digit) {
        int segments = DIGIT_SEGMENTS[digit];

        if ((segments & TOP) != 0)
            drawHorizontalSegment(pixels, tileX, tileY, 1);
        if ((segments & UPPER_LEFT) != 0)
            drawVerticalSegment(pixels, tileX, tileY, 1, 1);
        if ((segments & UPPER_RIGHT) != 0)
            drawVerticalSegment(pixels, tileX, tileY, 3, 1);
        if ((segments & MIDDLE) != 0)
            drawHorizontalSegment(pixels, tileX, tileY, 3);
        if ((segments & LOWER_LEFT) != 0)
            drawVerticalSegment(pixels, tileX, tileY, 1, 3);
        if ((segments & LOWER_RIGHT) != 0)
            drawVerticalSegment(pixels, tileX, tileY, 3, 3);
        if ((segments & BOTTOM) != 0)
            drawHorizontalSegment(pixels, tileX, tileY, 5);
    }

    // 3 white pixels across, relative to this digit's top-left tile corner.
    private void drawHorizontalSegment(ByteBuffer pixels, int tileX, int tileY, int y) {
        setPixel(pixels, tileX + 1, tileY + y, 255);
        setPixel(pixels, tileX + 2, tileY + y, 255);
        setPixel(pixels, tileX + 3, tileY + y, 255);
    }

    // 3 white pixels down. These two helpers form all seven segment shapes.
    private void drawVerticalSegment(ByteBuffer pixels, int tileX, int tileY, int x, int y) {
        setPixel(pixels, tileX + x, tileY + y, 255);
        setPixel(pixels, tileX + x, tileY + y + 1, 255);
        setPixel(pixels, tileX + x, tileY + y + 2, 255);
    }

    // Fill a tile row-by-row using setPixel; exclusive < end bounds avoid overlap.
    private void fillRectangle(
            ByteBuffer pixels,
            int x,
            int y,
            int width,
            int height,
            int value) {
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                setPixel(pixels, column, row, value);
            }
        }
    }

    //----------PIXEL ADDRESSING----------
    // y * stride skips complete rows; x * channels finds the pixel in that row.
    // Setting every channel to the same intensity produces black (0) or white (255).
    private void setPixel(ByteBuffer pixels, int x, int y, int value) {
        int base = y * videoInfo.imageStride() + x * videoInfo.imageChannels();
        byte byteValue = (byte) value;

        for (int channel = 0; channel < videoInfo.imageChannels(); channel++) {
            pixels.put(base + channel, byteValue);
        }
    }
}
