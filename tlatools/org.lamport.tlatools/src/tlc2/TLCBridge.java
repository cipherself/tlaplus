package tlc2;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Bridge for running TLC model checker from JavaScript.
 *
 * CheerpJ runs this class in the browser. JavaScript captures an instance via
 * {@link #setInstance(TLCBridge)} and receives real-time output via
 * {@link #sendOutput(String)}.
 */
public class TLCBridge {
    private final PrintStream originalOut;
    private final PrintStream originalErr;

    public static native void setInstance(TLCBridge bridge);

    public static native void sendOutput(String output);

    /**
     * PrintStream that forwards all output immediately to JavaScript.
     */
    private static class ForwardingPrintStream extends PrintStream {
        public ForwardingPrintStream() {
            super(new OutputStream() {
                @Override
                public void write(int b) {
                    // Single byte writes get batched into strings by print/println
                }
            });
        }

        @Override
        public void print(String s) {
            sendOutput(s);
        }

        @Override
        public void println(String s) {
            sendOutput(s + "\n");
        }

        @Override
        public void println() {
            sendOutput("\n");
        }
    }

    public TLCBridge() {
        this.originalOut = System.out;
        this.originalErr = System.err;
    }

    public void runTLC(String[] args) {
        final ForwardingPrintStream forwardingStream = new ForwardingPrintStream();

        System.setOut(forwardingStream);
        System.setErr(forwardingStream);

        try {
            tlc2.TLC.main(args);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    public static void main(String[] args) {
        final TLCBridge bridge = new TLCBridge();
        final String[] tlcArgs = args;

        new Thread(() -> {
            setInstance(bridge);
            sendOutput("Starting TLC model checker...\n");
            bridge.runTLC(tlcArgs);
        }, "TLCBridge-main").start();
    }
}
