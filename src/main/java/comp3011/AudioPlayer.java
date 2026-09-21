/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Touch Chanchoronaiy (a1978787), I added my own notes.
 *
 * Copyright 2026 Simon Ratcliffe
 * 
 */
package comp3011;

import java.nio.Buffer;
import java.nio.ShortBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import org.bytedeco.javacv.Frame;

/**
 * Streams decoded audio from JavaCV frames to the Java Sound output device, in support of the video player being able
 * to play the sound track as well. This is a good test of a player because sound continuity (or lack thereof) is easy
 * for the ear to pick up on if/when playback gets laggy.
 *
 * <p>
 * Converts supported packed or planar 16-bit sample buffers to the
 * interleaved, little-endian PCM format required by {@link SourceDataLine}.
 * {@link VideoPlayerModel} owns the output-device instance and schedules writes
 * on the JavaFX thread. {@link PlaybackSession} owns a separate copy-only instance
 * that never opens a sound device. Neither instance is shared for concurrent use.
 * </p>
 */
public class AudioPlayer implements AutoCloseable {

    // PlaybackSession uses copySamples() to detach audio from decoder buffers.
    // The model's separate AudioPlayer opens the device and writes those copies.
    // Effects change video pixels, so they do not pass through this class.

// AutoCloseable defines close(): try-with-resources can invoke it on exit.
// not automatic thread safety. Keep each instance's mutable buffers/line on one owning thread. 
// PCM = uncompressed numeric audio samples.
    private static final long WRITE_TRACE_THRESHOLD_NS = 2_000_000L;
    private static final int AUDIO_WARMUP_BYTES = 4096;

    private SourceDataLine line; // Java Sound's audio-output interface. for us a destination, not a source.
    private byte[] outputBuffer = new byte[0];

//----------OPEN THE OUTPUT DEVICE----------
// AudioFormat arguments: samples/second, bits/sample, channels, signed, bigEndian=false (least-significant byte first)
// throws reports device errors.
    public void open(int sampleRate, int channels) throws LineUnavailableException {
        if (sampleRate <= 0 || channels <= 0) {
            return;
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        line = AudioSystem.getSourceDataLine(format);
        line.open(format);
        line.start();
        warmUpLine();
    }

//----------COPY BEFORE THE DECODER REUSES ITS BUFFER----------
// byte[] is an array. Return a new owned array, not the reusable outputBuffer
// WHY: copying a reference would let later decoding overwrite queued sound
    public byte[] copySamples(Frame frame) {
        if (frame.samples == null || frame.samples.length == 0) {
            return new byte[0];
        }

        int byteCount = copySamplesToOutputBuffer(frame);
        if (byteCount <= 0) {
            return new byte[0];
        }

        byte[] samples = new byte[byteCount];
        System.arraycopy(outputBuffer, 0, samples, 0, byteCount);
        return samples;
    }

//----------PARTIAL OUTPUT WRITES----------
// Return the byte count actually written. 
// The model retains an offset for the remainder and retries on another pulse rather than blocking the UI
    public int write(byte[] samples, int offset, int length) {
        if (line == null || samples.length == 0 || length <= 0) {
            return 0;
        }

        // Try and avoid blocking writes to the audio stream by only adding equal or less than the number of bytes available.
        // return only the bytes we were able to write without overflowing the buffer, so remaining bytes can be tracked and written later. 
        // --- avoids audio buffer blocking in a cooperative multitasking architecture (ex. we are being cooperative and not stalling the thread on line.write below!)
        int availableBeforeWrite = line.available();
        int byteCount = Math.min(length, availableBeforeWrite);
        if (byteCount <= 0) {
            return 0;
        }

        long startNs = System.nanoTime();
        int written = line.write(samples, offset, byteCount); // Will block if we get it wrong.
        long elapsedNs = System.nanoTime() - startNs;
        if (elapsedNs >= WRITE_TRACE_THRESHOLD_NS) {
            // Hopefully you'll never see this.
            System.out.printf(
                    "WARNING: Audio write blocked for %dus writing %d bytes, line available before write: %d bytes%n",
                    elapsedNs / 1000,
                    written,
                    availableBeforeWrite);
        }
        return written;
    }

//----------DISCARD / CLOSE----------
// flush discards queued sound on pause/seek; close also releases the device.
    public void flush() {
        if (line != null) {
            line.flush();
        }
    }

    @Override
    public void close() {
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
            line = null;
        }
    }

    // Why do we have this? Some (many) audio output systems have a long 'wake up' delay before they output the first
    // audio. This is not great for real time playback of audio that is synched with video frames. It can lead to a
    // noticeable lag between the vision and the audio. This method writes a bunch of silence into the audio system
    // then flushes it out as soon as it is written to get any wake up lag out of the way, so that it is hopefully ready
    // for real time output when the real audio bytes start showing up.
    private void warmUpLine() {
        byte[] silence = new byte[Math.min(AUDIO_WARMUP_BYTES, line.getBufferSize())]; // All zeros.
        line.write(silence, 0, silence.length); // May play, but harmless.
        line.flush(); // Get rid of the rest straight away, hopefully warm up is done.
    }

//----------PACKED / PLANAR SAMPLE LAYOUT----------
// Packed/interleaved stereo is L0,R0,L1,R1,... in one buffer. 
// Planar keeps channels in separate buffers. interleave them for Java Sound's output.
// instanceof checks the type; "instanceof ShortBuffer channelSamples" also binds a typed variable when the check succeeds.
// duplicate() shares storage but has independent position, so reading does not move FFmpeg's position.
    private int copySamplesToOutputBuffer(Frame frame) {
        Buffer[] samples = frame.samples;
        int channels = frame.audioChannels;

        if (channels > 1 && samples.length == channels && samples[0] instanceof ShortBuffer) {
            ShortBuffer[] planarSamples = new ShortBuffer[channels];
            int sampleCount = Integer.MAX_VALUE;

            for (int channel = 0; channel < channels; channel++) {
                if (!(samples[channel] instanceof ShortBuffer channelSamples)) {
                    return 0;
                }
                planarSamples[channel] = channelSamples.duplicate();
                sampleCount = Math.min(sampleCount, planarSamples[channel].remaining());
            }

            ensureOutputBufferCapacity(sampleCount * channels * Short.BYTES);
            return copyPlanarSamples(planarSamples, sampleCount);
        }

        if (samples[0] instanceof ShortBuffer packedSamples) {
            ShortBuffer source = packedSamples.duplicate();
            ensureOutputBufferCapacity(source.remaining() * Short.BYTES);
            return copyPackedSamples(source);
        }

        return 0;
    }

    // A short contains 16 bits. & 0xff extracts the low byte; >> 8 shifts the
    // high byte down. Store low then high to produce little-endian output.
    private int copyPackedSamples(ShortBuffer source) {
        int outputIndex = 0;
        while (source.hasRemaining()) {
            short sample = source.get();
            outputBuffer[outputIndex++] = (byte) (sample & 0xff);
            outputBuffer[outputIndex++] = (byte) ((sample >> 8) & 0xff);
        }
        return outputIndex;
    }

    // visit each time sample, then each channel: L0,R0 then L1,R1, not all L first
    private int copyPlanarSamples(ShortBuffer[] planarSamples, int sampleCount) {
        int outputIndex = 0;
        for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
            for (ShortBuffer channelSamples : planarSamples) {
                short sample = channelSamples.get();
                outputBuffer[outputIndex++] = (byte) (sample & 0xff);
                outputBuffer[outputIndex++] = (byte) ((sample >> 8) & 0xff);
            }
        }
        return outputIndex;
    }

    // grow scratch only when needed, reducing allocation on the audio hot path
    // copySamples still returns a separate array so scratch is never queued itself.
    private void ensureOutputBufferCapacity(int byteCount) {
        if (outputBuffer.length < byteCount) {
            outputBuffer = new byte[byteCount];
        }
    }

}
