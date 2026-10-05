/* Touch Chanchoronaiy (a1978787) */
package comp3011;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import static org.bytedeco.ffmpeg.global.avcodec.*;

/* TEST HELPER - Create repeatable moving colours and a tone for decoder/desktop tests.
 * This is not a test suite: it supplies input media and has no test assertions.
 * Generated clips avoid needing a downloaded movie or a personal recording.
 */
final class GeneratedVideo {
    static void create(Path path, int width, int height, int frameCount, boolean audio) throws Exception {
        create(path, width, height, frameCount, audio, 0);
    }
    static void createWithAudioLeadIn(Path path, int seconds) throws Exception {
        create(path, 160, 90, 36, true, seconds);
    }
    private static void create(Path path, int width, int height, int frameCount,
                               boolean audio, int audioLeadInSeconds) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(path.toFile(), width, height, audio ? 1 : 0);
             Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3)) {
            recorder.setFormat("mp4");
            recorder.setVideoCodec(AV_CODEC_ID_H264);
            recorder.setFrameRate(24);
            recorder.setVideoOption("preset", "ultrafast");
            if (audio) {
                recorder.setAudioCodec(AV_CODEC_ID_AAC);
                recorder.setSampleRate(48000);
            }
            recorder.start();
            for (int n = 0; n < audioLeadInSeconds * 24; n++) recordTone(recorder, n);
            if (audioLeadInSeconds > 0) recorder.setTimestamp(audioLeadInSeconds * 1_000_000L);
            ByteBuffer pixels = (ByteBuffer) frame.image[0];
            for (int n = 0; n < frameCount; n++) {
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
            recorder.stop();
        }
    }
    private static void recordTone(FFmpegFrameRecorder recorder, int n) throws Exception {
        ShortBuffer tone = ShortBuffer.allocate(2000);
        for (int s = 0; s < 2000; s++) {
            tone.put((short) (1800 * Math.sin(2 * Math.PI * 440 * (n * 2000L + s) / 48000)));
        }
        tone.flip();
        recorder.recordSamples(48000, 1, tone);
    }
    public static void main(String[] args) throws Exception {
        create(Path.of(args[0]), 1280, 720, 192, true);
    }
}
