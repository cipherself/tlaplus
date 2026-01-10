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
    private final REPL repl;
    private final Path tempDir;
    private final ByteArrayOutputStream outputCapture;
    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final PrintStream capturingStream;

    private volatile boolean checkingInProgress = false;
    private volatile Thread checkThread = null;

    public static native void setInstance(TLCBridge bridge);

    public static native void sendOutput(String output);

    public static native void onCheckComplete(String resultJson);

    public TLCBridge() throws IOException {
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

        String result = "";
        try {
            result = repl.processInput(command);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        String capturedOutput = outputCapture.toString();
        if (!capturedOutput.isEmpty()) {
            sendOutput(capturedOutput);
        }

        return result;
    }

    /**
     * Run model checking on the given spec and config.
     *
     * @param specName      Name of the spec module (without .tla extension)
     * @param specContent   The full TLA+ specification content
     * @param configContent The TLC configuration content (INIT, NEXT, INVARIANT,
     *                      etc.)
     *
     */
    public void checkModel(String specName, String specContent, String configContent) {

        if (checkingInProgress) {
            onCheckComplete("{\"success\": false, \"error\": \"Model checking already in progress\"}");
            return;
        }

        checkingInProgress = true;

        final Thread thread = new Thread(() -> {
            try {
                runModelCheck(specName, specContent, configContent);
            } catch (Exception e) {
                onCheckComplete("{\"success\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}");
            } finally {
                checkingInProgress = false;
                checkThread = null;
            }
        }, "TLCBridge-checkModel");

        checkThread = thread;
        thread.start();
    }

    private void runModelCheck(String specName, String specContent, String configContent) throws IOException {
        Path checkDir = Files.createTempDirectory(tempDir, "check_");
        Path metaDir = Files.createDirectory(checkDir.resolve("states"));

        File specFile = checkDir.resolve(specName + ".tla").toFile();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(specFile))) {
            writer.write(specContent);
        }

        File configFile = checkDir.resolve(specName + ".cfg").toFile();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(configFile))) {
            writer.write(configContent);
        }

        ErrorTraceMessagePrinterRecorder recorder = new ErrorTraceMessagePrinterRecorder();
        MP.setRecorder(recorder);
        ToolIO.setMode(ToolIO.TOOL);
        ToolIO.reset();

        List<String> argsList = new ArrayList<>();
        argsList.add("-metadir");
        argsList.add(metaDir.toAbsolutePath().toString());
        argsList.add("-dfid");
        argsList.add("100"); // Depth bound - high enough for most specs
        argsList.add("-workers");
        argsList.add("auto");
        argsList.add("-noGenerateSpecTE");

        argsList.add(specName);

        String[] args = argsList.toArray(new String[0]);

        final TLC tlc = new TLC();
        tlc.setResolver(new SimpleFilenameToStream(checkDir.toAbsolutePath().toString()));

        outputCapture.reset();
        System.setOut(capturingStream);
        System.setErr(capturingStream);

        int exitCode = -1;
        String errorMsg = "";
        try {
            if (tlc.handleParameters(args)) {
                exitCode = tlc.process();
            } else {
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

        String[] toolMessages = ToolIO.getAllMessages();
        StringBuilder toolOutput = new StringBuilder();
        for (String msg : toolMessages) {
            toolOutput.append(msg).append("\n");
        }

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

        String output = outputCapture.toString();
        String combinedOutput = output + toolOutput.toString();
        json.append("\"output\": \"").append(escapeJson(combinedOutput)).append("\"");

        if (!errorMsg.isEmpty()) {
            json.append(",\"error\": \"").append(escapeJson(errorMsg)).append("\"");
        }

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

    public void stopChecking() {
        if (!checkingInProgress) {
            return;
        }

        final Thread thread = checkThread;
        if (thread != null) {
            thread.interrupt();
        }

        // Best-effort stop signal for TLC's checker.
        // - For BFS/standard model checking: ModelChecker implements stop().
        // - For DFID: DFIDModelChecker exposes setStop(int).
        try {
            if (TLCGlobals.mainChecker instanceof tlc2.tool.ModelChecker) {
                ((tlc2.tool.ModelChecker) TLCGlobals.mainChecker).stop();
            } else if (TLCGlobals.mainChecker instanceof tlc2.tool.DFIDModelChecker) {
                ((tlc2.tool.DFIDModelChecker) TLCGlobals.mainChecker).setStop(1);
            } else if (TLCGlobals.mainChecker != null) {
                TLCGlobals.mainChecker.stop();
            }
        } catch (UnsupportedOperationException ignored) {
        }

        sendOutput("Stop requested.\n");
    }

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
