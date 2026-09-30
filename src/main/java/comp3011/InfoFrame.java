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
 * Identifies an image's position in the source video. contains no pixel data.
 * - record generates a constructor and accessors such as frameNumber()/timestampUs(), plus equals/hashCode/toString. These primitive values cannot be reassigned.
 * Keeping number/time with the frame allows ordered effects and accurate playback.
 * - timestampUs is microseconds, not milliseconds or a calendar timestamp.
 */
public record InfoFrame(
        int frameNumber,
        long timestampUs) {
}
