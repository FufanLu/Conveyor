import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A deliberately slow log consumer: reads stdin one line at a time and sleeps 1 ms per line,
 * like a log shipper that has fallen behind or a container log driver under pressure. When the
 * pipe's buffer fills, a synchronous writer on the other end blocks. At EOF it writes the number
 * of lines it received to the file named by its argument.
 *
 * Usage: java SlowSink.java LINE_COUNT_FILE < fifo
 */
public class SlowSink {
    public static void main(String[] args) throws Exception {
        long lines = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (in.readLine() != null) {
                lines++;
                Thread.sleep(1);
            }
        }
        Files.writeString(Path.of(args[0]), Long.toString(lines));
    }
}
