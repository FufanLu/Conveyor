import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Closed-loop HTTP/1.1 load generator: C workers (virtual threads), each sends its next request
 * as soon as the previous one completes, until N requests total. Prints throughput and latency
 * percentiles. Usage: java LoadGen.java URL N C [warmupN]
 *
 * <p>With N == C every worker sends exactly one request at the same moment: a burst. If URL
 * contains {@code %08X}, request i asks for {@code String.format(URL, 0xF0000000 + i)}, which
 * gives N distinct SKUs that the seeded catalog does not contain (penetration tests).
 */
public class LoadGen {
    public static void main(String[] args) throws Exception {
        URI uri = URI.create(args[0].replace("%08X", "F0000000"));
        int total = Integer.parseInt(args[1]);
        int concurrency = Integer.parseInt(args[2]);
        int warmup = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        boolean template = args[0].contains("%08X");

        if (!template) {
            run(client, request, warmup, concurrency); // JIT, connection pools, caches
        }
        long[] latencies = new long[total];
        Map<Integer, LongAdder> statuses = new ConcurrentHashMap<>();
        LongAdder errors = new LongAdder();
        AtomicInteger next = new AtomicInteger();
        long start = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < concurrency; w++) {
                pool.submit(() -> {
                    int i;
                    while ((i = next.getAndIncrement()) < total) {
                        HttpRequest current = template
                                ? HttpRequest.newBuilder(URI.create(String.format(args[0], 0xF000_0000L + i)))
                                        .timeout(Duration.ofSeconds(5))
                                        .GET()
                                        .build()
                                : request;
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<Void> r = client.send(current, HttpResponse.BodyHandlers.discarding());
                            statuses.computeIfAbsent(r.statusCode(), k -> new LongAdder()).increment();
                        } catch (Exception e) {
                            errors.increment();
                            if (errors.sum() <= 3) {
                                System.err.println("request failed: " + e);
                            }
                        }
                        latencies[i] = System.nanoTime() - t0;
                    }
                    return null;
                });
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        Arrays.sort(latencies);
        System.out.printf("url=%s n=%d c=%d%n", uri, total, concurrency);
        System.out.printf("throughput=%.0f req/s  elapsed=%.2fs  errors=%d  statuses=%s%n",
                total / seconds, seconds, errors.sum(), new TreeMap<>(statuses));
        for (double p : new double[] {50, 90, 99, 99.9}) {
            System.out.printf("p%-5s %.3f ms%n", p, latencies[(int) Math.min(total - 1, Math.ceil(p / 100 * total) - 1)] / 1e6);
        }
        System.out.printf("max    %.3f ms%n", latencies[total - 1] / 1e6);
    }

    private static void run(HttpClient client, HttpRequest request, int n, int c) throws Exception {
        if (n == 0) return;
        AtomicInteger next = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < c; w++) {
                pool.submit(() -> {
                    while (next.getAndIncrement() < n) {
                        client.send(request, HttpResponse.BodyHandlers.discarding());
                    }
                    return null;
                });
            }
        }
    }
}
