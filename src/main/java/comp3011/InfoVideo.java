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

// PlaybackSession reads the decoder's metadata and passes this record to every effect's initialise(). 
// FrameProcessor stores it for later pixel calculations. 
public record InfoVideo(
        // Name also contributes to deterministic effect seeds; count describes length.
        String mediaName,
        int totalVideoFrames,
        // Width/height are pixels. Depth is the sample type/bit-depth indicator.
        int imageWidth,
        int imageHeight,
        int imageDepth,
        // Channels per pixel and distance between row starts describe memory layout.
        int imageChannels,
        int imageStride,
        // Timing values: fractional frames/second, rounded fps, approximate frame duration.
        double frameRate,
        int intFrameRate,
        int frameDurationUs,
        // FFmpeg's numeric identifier for the decoded pixel layout, not a colour value.
        int pixelFormat) {
}
