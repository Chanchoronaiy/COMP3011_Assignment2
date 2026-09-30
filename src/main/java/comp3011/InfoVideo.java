/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Touch Chanchoronaiy (a1978787). I added documentation for fruther understanding.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

/**
 * Media information given to each effect during initialise(), not on every pixel.
 * recap: record is a compact data carrier with generated named accessors and final component fields. This record uses primitive values and an immutable String.
 * - imageChannels counts colour components
 * - imageStride is the row layout including padding (for the byte-based BGR frames used here, the byte distance between rows)
 * - frameRate is fractional fps; intFrameRate is rounded 
 * - frameDurationUs is the approximate microseconds per frame. Do not confuse row padding with a channel.
 */
public record InfoVideo(
        String mediaName,
        int totalVideoFrames,
        int imageWidth,
        int imageHeight,
        int imageDepth,
        int imageChannels,
        int imageStride,
        double frameRate,
        int intFrameRate,
        int frameDurationUs,
        int pixelFormat) {
}
