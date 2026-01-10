package tlc2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal bridge for running the TLA+ REPL from JavaScript.
 *
 * CheerpJ runs this class in the browser. JavaScript captures an instance via
 * {@link #setInstance(TLCReplBridge)} and calls {@link #eval(String)}.
 */
public class TLCReplBridge {
    private final REPL repl;
    private final Path tempDir;

    private final ByteArrayOutputStream outputCapture;
    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final PrintStream capturingStream;

    public static native void setInstance(TLCReplBridge bridge);

    public static native void sendOutput(String output);

    public TLCReplBridge() throws IOException {
        this.tempDir = Files.createTempDirectory(REPL.TEMP_DIR_PREFIX);
        this.repl = new REPL(tempDir);

        this.outputCapture = new ByteArrayOutputStream();
        this.originalOut = System.out;
        this.originalErr = System.err;
        this.capturingStream = new PrintStream(outputCapture, true);
    }

    public String eval(String command) {
        outputCapture.reset();

        System.setOut(capturingStream);
        System.setErr(capturingStream);

        String result;
        try {
            result = repl.processInput(command);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        final String capturedOutput = outputCapture.toString();
        if (!capturedOutput.isEmpty()) {
            sendOutput(capturedOutput);
        }

        return result;
    }

    public static void main(String[] args) {
        try {
            final TLCReplBridge bridge = new TLCReplBridge();

            new Thread(() -> {
                setInstance(bridge);
                sendOutput("TLA+ REPL initialized.\n");
                sendOutput("Enter constant-level TLA+ expressions.\n");
            }, "TLCReplBridge-main").start();
        } catch (IOException e) {
            System.err.println("Failed to initialize REPL: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
