/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Touch Chanchoronaiy (a1978787). I added notes for understanding.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

/**
 * Plain Java entry point - public static void main(String[]) - for the packaged application . Seems overly complicated hopping through a main function that
 * calls a main function, but JavaFX likes it this way when the app is bundled into a fat .jar (still don't quite
 * understand why).
 *
 * <p>
 * The runnable jar manifest points here instead of directly at
 * {@link VideoPlayerApp}, because {@code VideoPlayerApp} extends JavaFX
 * {@code Application}. Keeping the manifest entry point as an ordinary class
 * avoids JavaFX launcher special cases in shaded jars while still delegating all
 * real startup work to {@link VideoPlayerApp#main(String[])}.
 */
public class VideoPlayerAppLauncher {

    // Launcher -> VideoPlayerApp -> controller/model/view. 
    // This is a small entry point, not another implementation of the playback model.

    // delegate to the real app's main. do not create a second player or effect list.
    // This bridge does not bundle dependencies into the ordinary Maven JAR.
    public static void main(String[] args) {
        VideoPlayerApp.main(args);
    }
}
