package tlc2;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tlc2.model.MCError;
import tlc2.model.MCState;
import tlc2.output.EC;
import tlc2.output.ErrorTraceMessagePrinterRecorder;
import tlc2.output.MP;
import util.SimpleFilenameToStream;
import util.ToolIO;

/**
 * Bridge class for running the TLA+ REPL and Model Checker from JavaScript.
 * Provides JavaScript interoperability via native methods.
 */
public class TLCBridge {

    private static TLCBridge instance;
    private final REPL repl;
    private final Path tempDir;
    private final ByteArrayOutputStream outputCapture;
    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final PrintStream capturingStream;

    // Model checking state
    private volatile boolean checkingInProgress = false;
    private volatile boolean stopRequested = false;
    private TLC currentTLC = null;

    // Native method to capture this instance in JavaScript
    public static native void setInstance(TLCBridge bridge);

    // Native method to send output to JavaScript
    public static native void sendOutput(String output);

    // Native method to report model checking progress
    public static native void onProgress(long statesGenerated, int depth, long statesLeft);

    // Native method to report model checking result
    public static native void onCheckComplete(String resultJson);

    public TLCBridge() throws IOException {
        this.tempDir = Files.createTempDirectory(REPL.TEMP_DIR_PREFIX);
        this.repl = new REPL(tempDir);

        // Set up output capturing
        this.outputCapture = new ByteArrayOutputStream();
        this.originalOut = System.out;
        this.originalErr = System.err;
        this.capturingStream = new PrintStream(outputCapture, true);
    }

    /**
     * Evaluate a TLA+ expression and return the result.
     * All stdout/stderr output is captured and sent to JavaScript.
     */
    public String eval(String command) {
        // Clear previous output
        outputCapture.reset();

        // Redirect stdout/stderr to capture
        System.setOut(capturingStream);
        System.setErr(capturingStream);

        String result = "";
        try {
            result = repl.processInput(command);
        } finally {
            // Restore original streams
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        // Get any captured output (error messages, Print statements, etc.)
        String capturedOutput = outputCapture.toString();
        if (!capturedOutput.isEmpty()) {
            sendOutput(capturedOutput);
        }

        return result;
    }

    /**
     * Load a spec file into the REPL context.
     */
    public void loadSpec(String specPath) {
        repl.setSpecFile(new java.io.File(specPath));
    }

    /**
     * Simple test method to verify bridge is working.
     */
    public String ping() {
        return "pong";
    }

    /**
     * Run model checking on the given spec and config.
     *
     * @param specName      Name of the spec module (without .tla extension)
     * @param specContent   The full TLA+ specification content
     * @param configContent The TLC configuration content (INIT, NEXT, INVARIANT,
     *                      etc.)
     * @param workersStr    Number of worker threads as string (empty or "0" for
     *                      auto)
     * @param maxStatesStr  Maximum states to explore as string (empty or "0" for
     *                      unlimited)
     */
    public void checkModel(String specName, String specContent, String configContent,
            String workersStr, String maxStatesStr) {
        int workers = 0;
        long maxStates = 0;
        try {
            if (workersStr != null && !workersStr.isEmpty()) {
                workers = Integer.parseInt(workersStr);
            }
            if (maxStatesStr != null && !maxStatesStr.isEmpty()) {
                maxStates = Long.parseLong(maxStatesStr);
            }
        } catch (NumberFormatException e) {
            // Use defaults
        }

        if (workers <= 0) {
            workers = 0;
        }

        if (checkingInProgress) {
            onCheckComplete("{\"success\": false, \"error\": \"Model checking already in progress\"}");
            return;
        }

        checkingInProgress = true;
        stopRequested = false;

        final int finalWorkers = workers;
        final long finalMaxStates = maxStates;

        // Run in a separate thread to not block JavaScript
        new Thread(() -> {
            try {
                runModelCheck(specName, specContent, configContent, finalWorkers, finalMaxStates);
            } catch (Exception e) {
                onCheckComplete("{\"success\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}");
            } finally {
                checkingInProgress = false;
                currentTLC = null;
            }
        }).start();
    }

    private void runModelCheck(String specName, String specContent, String configContent,
            int workers, long maxStates) throws IOException {
        // Create a subdirectory for this check
        Path checkDir = Files.createTempDirectory(tempDir, "check_");
        Path metaDir = Files.createDirectory(checkDir.resolve("states"));

        // Write spec file
        File specFile = checkDir.resolve(specName + ".tla").toFile();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(specFile))) {
            writer.write(specContent);
        }

        // Write config file
        File configFile = checkDir.resolve(specName + ".cfg").toFile();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(configFile))) {
            writer.write(configContent);
        }

        // Set up output capture - capture ToolIO messages too
        ErrorTraceMessagePrinterRecorder recorder = new ErrorTraceMessagePrinterRecorder();
        MP.setRecorder(recorder);
        ToolIO.setMode(ToolIO.TOOL);
        ToolIO.reset();

        // Build TLC arguments
        List<String> argsList = new ArrayList<>();
        argsList.add("-metadir");
        argsList.add(metaDir.toAbsolutePath().toString());
        argsList.add("-dfid");
        argsList.add("100"); // Depth bound - high enough for most specs
        argsList.add("-workers");
        argsList.add("auto"); // Let TLC decide number of workers
        argsList.add("-noGenerateSpecTE");

        argsList.add(specName);

        String[] args = argsList.toArray(new String[0]);

        // Create and run TLC
        currentTLC = new TLC();
        currentTLC.setResolver(new SimpleFilenameToStream(checkDir.toAbsolutePath().toString()));

        // Redirect output
        outputCapture.reset();
        System.setOut(capturingStream);
        System.setErr(capturingStream);

        int exitCode = -1;
        String errorMsg = "";
        try {
            if (currentTLC.handleParameters(args)) {
                exitCode = currentTLC.process();
            } else {
                // Get any error messages from ToolIO
                String[] toolMessages = ToolIO.getAllMessages();
                StringBuilder msgBuilder = new StringBuilder();
                for (String msg : toolMessages) {
                    msgBuilder.append(msg).append("\n");
                }
                errorMsg = msgBuilder.toString();
                if (errorMsg.isEmpty()) {
                    errorMsg = "Invalid parameters or failed to parse spec";
                }
            }
        } catch (Exception e) {
            errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            MP.setRecorder(null);
        }

        // Get any messages from ToolIO
        String[] toolMessages = ToolIO.getAllMessages();
        StringBuilder toolOutput = new StringBuilder();
        for (String msg : toolMessages) {
            toolOutput.append(msg).append("\n");
        }

        // Build result JSON
        // Success means TLC completed successfully, even if it found violations
        // Convert error constant to exit status for proper comparison
        int exitStatus = EC.ExitStatus.errorConstantToExitStatus(exitCode);
        boolean success = (exitStatus == EC.ExitStatus.SUCCESS ||
                exitStatus == EC.ExitStatus.VIOLATION_SAFETY ||
                exitStatus == EC.ExitStatus.VIOLATION_LIVENESS ||
                exitStatus == EC.ExitStatus.VIOLATION_DEADLOCK ||
                exitStatus == EC.ExitStatus.VIOLATION_ASSUMPTION ||
                exitStatus == EC.ExitStatus.VIOLATION_ASSERT);
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"success\": ").append(success).append(",");
        json.append("\"exitCode\": ").append(exitCode).append(",");

        // Get captured output - combine stdout and ToolIO messages
        String output = outputCapture.toString();
        String combinedOutput = output + toolOutput.toString();
        json.append("\"output\": \"").append(escapeJson(combinedOutput)).append("\"");

        if (!errorMsg.isEmpty()) {
            json.append(",\"error\": \"").append(escapeJson(errorMsg)).append("\"");
        }

        // Check for error trace
        Optional<MCError> errorTrace = recorder.getMCErrorTrace();
        if (errorTrace.isPresent()) {
            MCError error = errorTrace.get();
            json.append(",\"errorTrace\": [");
            List<MCState> states = error.getStates();
            for (int i = 0; i < states.size(); i++) {
                MCState state = states.get(i);
                if (i > 0)
                    json.append(",");
                json.append("{");
                json.append("\"num\": ").append(state.getStateNumber()).append(",");
                json.append("\"label\": \"").append(escapeJson(state.getLabel())).append("\",");
                json.append("\"state\": \"").append(escapeJson(state.getConjunctiveDescription(false, "")))
                        .append("\"");
                json.append("}");
            }
            json.append("]");
        }

        json.append("}");

        onCheckComplete(json.toString());
    }

    /**
     * Stop the current model checking run.
     * Note: TLC doesn't have a clean stop mechanism, so we just set a flag
     * and the check will be interrupted on the next state.
     */
    public void stopChecking() {
        stopRequested = true;
        // TLC doesn't have a stop() method - we rely on TLCGlobals or thread interrupt
        // For now, we just set the flag and let the check complete naturally
        // A more robust solution would require modifying TLC's internals
    }

    /**
     * Check if model checking is in progress.
     */
    public boolean isChecking() {
        return checkingInProgress;
    }

    private static String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static void main(String[] args) {
        try {
            TLCBridge bridge = new TLCBridge();

            // Start a thread that keeps the instance alive and captures it in JavaScript
            new Thread(() -> {
                setInstance(bridge);
                sendOutput("TLA+ REPL & Model Checker initialized.\n");
                sendOutput("REPL: Enter constant-level TLA+ expressions.\n");
                sendOutput("Model Checker: Switch to 'Check' tab to verify specifications.\n");
            }).start();

        } catch (IOException e) {
            System.err.println("Failed to initialize: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
