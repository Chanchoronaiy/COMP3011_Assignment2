/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Touch Chanchoronaiy (a1978787). I added my own notes
 *
 * Copyright 2026 Simon Ratcliffe
 *
 */
package comp3011;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.bytedeco.javacv.Frame;

/**
 * Base class for all the video player's frame-based compositing effects.
 *
 * <p>
 * {@link PlaybackSession} initialises each processor for the current media/session;
 * {@link FramePipeline} invokes {@link #process(Frame, InfoFrame)} on its owning stage
 * for each image in the selected order, before display conversion. Processors must
 * be deterministic: the same media, instance seed and frame should produce the same
 * pixels, even if the pattern looks random. This supports reliable comparisons;
 * determinism alone does not prevent races. Exclusive frame/effect ownership in
 * the pipeline prevents simultaneous mutation and is a separate requirement.
 * </p>
 */
public abstract class FrameProcessor {
//----------COMMON EFFECT CONTRACT----------
// abstract = a base type that cannot itself be instantiated with new.
// Subclasses use extends FrameProcessor, inherit metadata/helper methods, and implement process(). protected allows access in subclasses (and package)
    protected InfoVideo videoInfo;

    public FrameProcessor() {
    }

    // called before effect threads start. 
    // !!! NOT safe to reinitialise an effect while an older session still processes frames with that instance
    public void initialise(InfoVideo videoInfo) throws Exception {
        this.videoInfo = videoInfo;
    }

    // Obtain a unique seed for this media name and effect instance index. That is, for a given media name, say
    // 'movie.mp4' and a given position in the list of effects being applied, this will return a unique and different
    // seed for each effect. This is useful for creating the illusion of randomness whilst keeping everything
    // deterministic.
    // A seed produces repeatable pseudo-random patterns, not cryptographic safety.
    // Retain the starter's seed inputs so threaded/reference pixel tests agree.
    protected int saltedMediaSeed(String mediaName, int instanceIndex) {
        return hashMediaName(mediaName) + instanceIndex;
    }

    private int hashMediaName(String mediaName) {
        CRC32 crc = new CRC32();
        byte[] bytes = mediaName.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    // !!! No body here: dynamic dispatch calls the actual subclass implementation
    // The frame is changed in place, callers must enforce exclusive ownership.
    public abstract void process(Frame frame, InfoFrame info) throws Exception;
}
