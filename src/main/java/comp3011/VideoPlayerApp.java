/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Touch Chanchoronaiy (a1978787). Comments and modified code, with help from GPT-5.6 Terra.
 *
 * Copyright 2026 Simon Ratcliffe
 *
 * summary: previously passed launch settings only, now also passes the ordered effect list to the controller. 
 * WHY: command-line selection belongs at startup, not as hard-coded effects in the playback model.
 * + comments explain the supplied JavaFX startup/window code. I also added a method to resize the video display to fit the selected screen, 
 * and a method to align the scene to the selected screen. The supplied window title is retained for compatibility.
 */
package comp3011;

import java.util.List;

import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Extends JavaFX's Application class and is the entry point for the video player.
 * Provides required JavaFX methods start and stop and also basic on-screen window
 * management functions. Owns the {@link VideoPlayerController}, which in turn owns
 * the {@link VideoPlayerView} and {@link VideoPlayerModel}.
 *
 * <p>
 * Creates the JavaFX Stage and Scene, the VideoPlayerController, and applies
 * command-line launch settings, and starts playback. It also resizes the video
 * display to fit the selected screen.
 * </p>
 */
public class VideoPlayerApp extends Application {
    //----------CLASS/STARTUP STATE----------
    // extends = inherits JavaFX's Application lifecycle (similar to a C# base class)
    // recap: private = only this class can access the field. static = shared by the class,
    // so main() can store parsed settings before JavaFX creates the app object
    private static CommandLineController commandLineController;

    // instance fields: belong to this application's window.
    // Stage = top-level window
    // Scene = the tree of UI content inside that window.
    private VideoPlayerController controller;
    private Stage stage;
    private Rectangle2D selectedScreenBounds;
    private int videoWidth;
    private int videoHeight;

    //----------BUILD AND SHOW WINDOW----------
    // recap: @Override tells the compiler this replaces an inherited method
    // !! JavaFX calls start(Stage) on its application/UI thread after launch().
    @Override
    public void start(Stage stage) {
        // this.stage is the field. stage = parameter supplied by JavaFX
        this.stage = stage;

        // new calls constructor. 
        // List<FrameProcessor> preserves effect order (bc list, so odered collection).
        // this::setVideoSize = method reference: pass the action to call LATER
        // when model reports video dimensions, rather than calling it now.
        controller = new VideoPlayerController(
                commandLineController.isAudioRequested(),
                // !! MODIFIED - Forward CLI-selected effects instead of hard-coding them in playback
                commandLineController.getFrameProcessors(),
                this::setVideoSize);

        Scene scene = new Scene(controller.getView().getRoot());
        controller.installKeyboardShortcuts(scene);

        // Keep the supplied window title for TITAN's exact-title lookup.
        // This is separate from the Maven project name, VideoPlayerMultiThreaded.
        stage.setTitle("COMP3011 Video Player");
        stage.setScene(scene);
        moveToSelectedScreen(stage);
        stage.setMaximized(commandLineController.isMaximiseRequested());
        stage.show();

        // -> introduces a lambda/callback. Each _ is an unused listener argument.
        // Listen for display scaling changes (for example moving to a Retina screen).
        // A listener runs when the property changes, not repeatedly in a polling loop.
        stage.outputScaleXProperty().addListener((_, _, _) -> updateVideoSize());
        stage.outputScaleYProperty().addListener((_, _, _) -> updateVideoSize());
        stage.setOnCloseRequest(_ -> controller.shutdown());

        playVideo();
    }

    //----------MONITOR SELECTION----------
    // Integer can be null (no explicit monitor), unlike primitive int
    // List<Screen> = typed list of screens, screen positions use logical UI coordinates.
    private void moveToSelectedScreen(Stage stage) {
        List<Screen> screens = Screen.getScreens();
        Integer displayId = commandLineController.getDisplayId();
        Screen targetScreen;

        if (displayId == null) {
            // stream/filter/findFirst searches for a non-primary screen
            // orElse supplies a fallback for a one-screen machine
            targetScreen = screens.stream()
                    .filter(screen -> !screen.equals(Screen.getPrimary()))
                    .findFirst()
                    .orElse(Screen.getPrimary());
        } else if (displayId <= screens.size()) {
            // CLI monitor numbers start at 1, Java List indexes start at 0.
            targetScreen = screens.get(displayId - 1);
        } else {
            targetScreen = Screen.getPrimary();
            controller.getView().setStatus("Display " + displayId + " not found");
        }

        Rectangle2D bounds = targetScreen.getVisualBounds();
        // Visual bounds exclude reserved desktop areas such as the menu bar/dock.
        selectedScreenBounds = bounds;
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
    }

    //----------START PLAYBACK/RECEIVE SIZE CALLBACKS----------
    // The app delegates playback, does not decode or process pixels itself
    private void playVideo() {
        controller.play(commandLineController.getVideoFile());
    }

    private void setVideoSize(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        updateVideoSize();
    }

    // Convert source pixels to logical window size. 
    // BECAUSE: a high-DPI screen can use multiple physical pixels per UI unit, ignoring scale changes sizing.
    private void updateVideoSize() {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }

        double outputScaleX = stage.getOutputScaleX();
        double outputScaleY = stage.getOutputScaleY();

        if (outputScaleX <= 0) {
            outputScaleX = 1;
        }
        if (outputScaleY <= 0) {
            outputScaleY = 1;
        }

        // double keeps fractional sizes, integer division would truncate them.
        double displayWidth = videoWidth / outputScaleX;
        double displayHeight = videoHeight / outputScaleY;

        controller.getView().setVideoDisplaySize(displayWidth, displayHeight);
        if (!stage.isMaximized()) {
            stage.sizeToScene();
            alignSceneToSelectedScreen();
        }
    }

    // Offset the outer window so the scene's content starts at the screen edge,
    // accounting for the platform's title bar and window decoration.
    private void alignSceneToSelectedScreen() {
        if (selectedScreenBounds == null || stage.getScene() == null) {
            return;
        }

        stage.setX(selectedScreenBounds.getMinX() - stage.getScene().getX());
        stage.setY(selectedScreenBounds.getMinY() - stage.getScene().getY());
    }

    //----------CLEAN SHUTDOWN----------
    // JavaFX invokes stop() during normal application exit
    // shutdown() is safeto call again after the close-request callback: it checks its closed flag
    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    //----------JAVA ENTRY POINT----------
    // public = callable by the launcher    static = no app object is needed yet
    // void = no returned value;            String[] args = command-line arguments
    // Parse FIRST: --help and input errors should not open a JavaFX window
    public static void main(String[] args) {
        commandLineController = new CommandLineController(args);

        if (!commandLineController.shouldLaunchApplication()) {
            System.exit(commandLineController.getExitCode());
        }

        // !! Inherited Application.launch starts JavaFX then calls start()
        launch(args);
    }
}
