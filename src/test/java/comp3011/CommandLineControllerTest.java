/* Tests generated with Codex for Touch Chanchoronaiy (a1978787); review before submission. */
package comp3011;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineControllerTest {
    @TempDir Path directory;
    private String movie() throws Exception { return Files.createFile(directory.resolve("movie.mp4")).toString(); }
    private List<Class<?>> classes(CommandLineController command) {
        return command.getFrameProcessors().stream().<Class<?>>map(Object::getClass).toList();
    }

    @Test void shortLongStackedAndMixedFormsHaveIdenticalOrder() throws Exception {
        String file = movie();
        List<Class<?>> expected = List.of(FrameJitterer.class, FrameFlickerer.class,
                FrameDuster.class, FrameDuster.class, FrameNumberer.class);
        for (String[] args : List.of(
                new String[]{"--jitter-frames", "--flicker-frames", "--dust-frames", "--dust-frames", "--number-frames", file},
                new String[]{"-j", "-f", "-d", "-d", "-n", file},
                new String[]{"-jfddn", file}, new String[]{"-jf", "--dust-frames", "-d", "--number-frames", file})) {
            CommandLineController command = new CommandLineController(args);
            assertNull(command.getErrorMessage());
            assertEquals(expected, classes(command));
            assertNotSame(command.getFrameProcessors().get(2), command.getFrameProcessors().get(3));
            assertTrue(command.shouldLaunchApplication());
        }
    }
    @Test void everyEffectAndOriginalLaunchOptionsRemainAvailable() throws Exception {
        CommandLineController command = new CommandLineController(new String[]{"-ax2nssnfwyvdjmbp", movie()});
        assertNull(command.getErrorMessage());
        assertTrue(command.isAudioRequested());
        assertTrue(command.isMaximiseRequested());
        assertEquals(2, command.getDisplayId());
        assertEquals(13, command.getFrameProcessors().size());
        assertNotSame(command.getFrameProcessors().get(1), command.getFrameProcessors().get(2));
        assertThrows(UnsupportedOperationException.class, () -> command.getFrameProcessors().clear());
    }
    @Test void invalidOptionsFilesAndConflictingDisplaysDoNotLaunch() throws Exception {
        String file = movie();
        for (String[] args : List.of(new String[]{"-q", file}, new String[]{"--unknown", file},
                new String[]{"-12", file}, new String[]{file, file}, new String[]{"missing.mp4"}, new String[]{})) {
            CommandLineController command = new CommandLineController(args);
            assertEquals(1, command.getExitCode());
            assertFalse(command.shouldLaunchApplication());
        }
    }
    @Test void helpMatchesRequiredEffectParagraphsWithoutLaunchingUi() {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            CommandLineController command = new CommandLineController(new String[]{"--help"});
            assertEquals(0, command.getExitCode());
            assertFalse(command.shouldLaunchApplication());
        } finally { System.setOut(original); }
        String output = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(output.startsWith("Usage: VideoPlayer [options] [video-file]\n\nOptions:\n"));
        assertTrue(output.contains("\n\nFrame processors:\n"));
        assertTrue(output.contains("  -w, --black-and-white  Convert frames to black and white\n"));
        assertTrue(output.endsWith("\n\nFrame processors are applied in command-line order and may be repeated.\n\n"
                + "Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,\n"
                + "converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.\n"));
        for (FrameProcessorOption option : FrameProcessorOption.values()) assertTrue(output.contains(option.helpLine()));
    }
}
