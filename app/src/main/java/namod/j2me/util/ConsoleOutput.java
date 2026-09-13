package namod.j2me.util;

import java.io.OutputStream;
import java.io.PrintStream;

/* loaded from: classes.dex */
public class ConsoleOutput extends PrintStream {
    private static final int MAX_LOG_SIZE = 30000;
    private static PrintStream err;
    private static LogListener listener;
    private static final StringBuilder log = new StringBuilder();
    private static PrintStream out;

    public interface LogListener {
        void onLogClear();

        void onLogUpdate(String str);
    }

    private ConsoleOutput(OutputStream outputStream) {
        super(outputStream);
    }

    public static void clear() {
        StringBuilder sb = log;
        synchronized (sb) {
            sb.setLength(0);
        }
        LogListener logListener = listener;
        if (logListener != null) {
            logListener.onLogClear();
        }
    }

    public static String getLog() {
        String sb;
        StringBuilder sb2 = log;
        synchronized (sb2) {
            sb = sb2.toString();
        }
        return sb;
    }

    public static void init() {
        if (out == null) {
            out = System.out;
            err = System.err;
            System.setOut(new ConsoleOutput(out));
            System.setErr(new ConsoleOutput(err));
        }
    }

    public static void setListener(LogListener logListener) {
        listener = logListener;
    }

    @Override // java.io.PrintStream, java.io.FilterOutputStream, java.io.OutputStream
    public void write(int i5) {
        String valueOf = String.valueOf((char) i5);
        StringBuilder sb = log;
        synchronized (sb) {
            sb.append(valueOf);
            if (sb.length() > MAX_LOG_SIZE) {
                sb.delete(0, sb.length() - MAX_LOG_SIZE);
            }
        }
        LogListener logListener = listener;
        if (logListener != null) {
            logListener.onLogUpdate(valueOf);
        }
        super.write(i5);
    }

    @Override // java.io.PrintStream, java.io.FilterOutputStream, java.io.OutputStream
    public void write(byte[] bArr, int i5, int i6) {
        String str = new String(bArr, i5, i6);
        StringBuilder sb = log;
        synchronized (sb) {
            sb.append(str);
            if (sb.length() > MAX_LOG_SIZE) {
                sb.delete(0, sb.length() - MAX_LOG_SIZE);
            }
        }
        LogListener logListener = listener;
        if (logListener != null) {
            logListener.onLogUpdate(str);
        }
        super.write(bArr, i5, i6);
    }
}
