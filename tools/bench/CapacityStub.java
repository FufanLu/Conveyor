import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * An upstream with a fixed capacity: WORKERS requests are served at a time, each taking
 * SERVICE_MS; everything else waits in an unbounded queue, the way a saturated service with a
 * thread pool behaves. Capacity = WORKERS * 1000 / SERVICE_MS requests per second. Answers any
 * path with a small JSON body.
 *
 *   java tools/bench/CapacityStub.java PORT WORKERS SERVICE_MS
 */
public class CapacityStub {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Semaphore workers = new Semaphore(Integer.parseInt(args[1]), true);
        long serviceMs = Long.parseLong(args[2]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 4096);
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            try {
                workers.acquire();
                try {
                    Thread.sleep(serviceMs);
                } finally {
                    workers.release();
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("stub on %d: %s workers x %d ms = %d req/s%n", port, args[1], serviceMs,
                Integer.parseInt(args[1]) * 1000 / serviceMs);
    }
}
