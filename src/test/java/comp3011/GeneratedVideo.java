/* Touch Chanchoronaiy (a1978787) */
// Test-media helper and learning notes developed with Codex assistance.
package comp3011;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import static org.bytedeco.ffmpeg.global.avcodec.*;

/* Create repeatable moving colours and a tone for decoder/desktop tests.
 * not a test suite: it supplies input media and has no test assertions.
 * Generated clips avoid needing a downloaded movie or a personal recording.
 */
final class GeneratedVideo {
    //----------HELPER: ORDINARY VIDEO, OPTIONALLY WITH SOUND----------
    // Parameters choose the output path, pixel dimensions, number of pictures and whether to include audio.
    //recap: Overloading = same method name, different parameter lists.
    static void create(Path path, int width, int height, int frameCount, boolean audio) throws Exception {
        create(path, width, height, frameCount, audio, 0);
    }

    //----------HELPER: SOUND BEFORE THE FIRST PICTURE----------
    // Used to reproduce paused-startup/EOF issues with leading audio. This creates 36 pictures plus the lead-in.
    static void createWithAudioLeadIn(Path path, int seconds) throws Exception {
        create(path, 160, 90, 36, true, seconds);
    }

    //----------WRITE A REAL MP4 FILE----------
    // EXPECTED: a repeatable file that FFmpeg can decode. this helper itself makes no test assertions.
    // Create parent directories, configure the recorder, write optional leading sound, then pictures/sound.
    // throws Exception passes codec/filesystem errors to the calling test instead of hiding a bad input clip.
    private static void create(Path path, int width, int height, int frameCount,
                               boolean audio, int audioLeadInSeconds) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());

        // try-with-resources closes the recorder/native frame even if recording fails.
        // audio ? 1 : 0 selecting one audio channel or none.
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(path.toFile(), width, height, audio ? 1 : 0);
             Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3)) {

            // H.264 video in an MP4 container, 24 pictures/second. Request the ultrafast preset for quick generation where supported.
            //  an encoder may report that it ignores this option.
            // DEPTH_UBYTE = 8-bit components; the frame has three BGR colour channels.
            recorder.setFormat("mp4");
            recorder.setVideoCodec(AV_CODEC_ID_H264);
            recorder.setFrameRate(24);
            recorder.setVideoOption("preset", "ultrafast");

            if (audio) {
                // AAC is the encoded sound format; 48,000 numeric samples represent one second of mono audio.
                recorder.setAudioCodec(AV_CODEC_ID_AAC);
                recorder.setSampleRate(48000);
            }

            recorder.start();

            // One audio chunk lasts 1/24 second. Write lead-in chunks before recording any image.
            for (int n = 0; n < audioLeadInSeconds * 24; n++) recordTone(recorder, n);

            // Timestampsin microseconds: one second = 1,000,000 microseconds
            if (audioLeadInSeconds > 0) recorder.setTimestamp(audioLeadInSeconds * 1_000_000L);

            ByteBuffer pixels = (ByteBuffer) frame.image[0];

            for (int n = 0; n < frameCount; n++) {
                // each row/column. Stride includes row padding; x * 3 finds this pixel's BGR bytes.
                // Absolute put(index,value) changes pixels without advancing the buffer position
                // Changing n shifts the colours over time= casting to byte retains the low eight bits.
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int offset = y * frame.imageStride + x * 3;
                        pixels.put(offset, (byte) (x + n * 4));
                        pixels.put(offset + 1, (byte) (y + n * 2));
                        pixels.put(offset + 2, (byte) (x / 4 + y / 4));
                    }
                }

                recorder.record(frame);

                if (audio) recordTone(recorder, n + audioLeadInSeconds * 24);
            }

            //finish encoded output before try-with-resources releases native objects.
            recorder.stop();
        }
    }

    //----------HELPER: GENERATE A QUIET 440 HZ TONE, NOT SPEECH----------
    // 48,000 samples/second %24 video frames/second = 2,000 samples per chunk
    // sin(2*pi*frequency*time) creates the wave. 
    // n * 2000L keeps its phase continuous across chunks.
    // short stores signed 16-bit PCM samples. amplitude 1800 is intentionally modest, not full volume.
    private static void recordTone(FFmpegFrameRecorder recorder, int n) throws Exception {
        ShortBuffer tone = ShortBuffer.allocate(2000);

        for (int s = 0; s < 2000; s++) {
            tone.put((short) (1800 * Math.sin(2 * Math.PI * 440 * (n * 2000L + s) / 48000)));
        }

        // flip() switches the filled buffer to reading: reset position to zero, limit to samples written.
        tone.flip();
        recorder.recordSamples(48000, 1, tone);
    }

    //----------OPTIONAL MANUAL ENTRY POINT----------
    // args[0] must supply an output filename. Creates an eight-second 720p clip with tone (192 / 24 = 8)
    // a helper main(), not a JUnit @Test or the normal video-player launcher
    public static void main(String[] args) throws Exception {
        create(Path.of(args[0]), 1280, 720, 192, true);
    }
}
