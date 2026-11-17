package top.zesun.berryfcm;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class RootShell implements PrivilegeShell {
    @Override public String execute(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) >= 0) output.write(buffer, 0, n);
        }
        int code = process.waitFor();
        String text = output.toString(StandardCharsets.UTF_8);
        if (code != 0) throw new IllegalStateException("Root shell exit " + code + ": " + text);
        return text;
    }
    @Override public void close() { }
}
