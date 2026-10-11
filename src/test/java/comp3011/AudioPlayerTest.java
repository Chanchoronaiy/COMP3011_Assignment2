/* Touch Chanchoronaiy (a1978787); */
package comp3011;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.Buffer;
import java.nio.ShortBuffer;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * TEST PURPOSE: Verify exact copied PCM bytes and planar/packed conversion without a sound device.
 * Changing the source after copying must not change the queued result.
 * COVERS: The supplied AudioPlayer's sample-copying methods, which the new bg decoder relies on. does not open speakers or judge sound.
 */
class AudioPlayerTest {
    @Test void packedSamplesAreCopiedWithoutMovingOrSharingTheDecoderBuffer() {
        try (Frame frame = new Frame(); AudioPlayer player = new AudioPlayer()) {
            ShortBuffer source = ShortBuffer.wrap(new short[]{99, 0x1234, -2});
            source.position(1);
            frame.samples = new Buffer[]{source};
            frame.audioChannels = 1;
            byte[] copied = player.copySamples(frame);
            //assertArrayEquals compares contents 
            assertArrayEquals(new byte[]{0x34, 0x12, (byte) 0xfe, (byte) 0xff}, copied);
            assertEquals(1, source.position());
            source.put(1, (short) 0);
            assertEquals(0x34, copied[0]); // Decoder reuse cannot change queued PCM.
        }
    }

    @Test void planarStereoIsInterleavedInLittleEndianOrder() {
        try (Frame frame = new Frame(); AudioPlayer player = new AudioPlayer()) {
            frame.audioChannels = 2;
            frame.samples = new Buffer[]{ShortBuffer.wrap(new short[]{1, 2}),
                    ShortBuffer.wrap(new short[]{3, 4})};
            assertArrayEquals(new byte[]{1, 0, 3, 0, 2, 0, 4, 0}, player.copySamples(frame));
        }
    }
}
