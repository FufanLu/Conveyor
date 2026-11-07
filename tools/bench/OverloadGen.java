import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * Open-loop load: requests are sent on a fixed schedule whether or not earlier ones finished,
 * as real users do (a closed loop would slow down with the server and hide overload). The mix
 * is 60 % search, 25 % product page, 15 % order status. For each class it prints how many were
 * answered 2xx, shed (503 from the gateway's admission), or failed (anything else, including
 * the client's 5 s timeout), and the latency of the successful ones.
 *
 *   java tools/bench/OverloadGen.java GATEWAY_URL RATE_PER_S SECONDS
 */
public class OverloadGen {
    record Result(String cls, int status, long micros, boolean shed) {}

    public static void main(String[] args) throws Exception {
        String base = args[0];
        int rate = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        long period = 1_000_000_000L / rate;
        long start = System.nanoTime();
        long total = (long) rate * seconds;
        for (long i = 0; i < total; i++) {
            long due = start + i * period;
            long wait = due - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            int pick = (int) (i % 20);
            String cls = pick < 12 ? "search" : pick < 17 ? "product" : "order";
            String path = switch (cls) {
                case "search" -> "/api/v1/search?q=kettle";
                case "product" -> "/api/v1/products/B0DEE0D8F5";
                default -> "/api/v1/orders/033R2HD9EG000";
            };
            pool.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    HttpResponse<Void> r = http.send(
                            HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                    boolean shed = r.statusCode() == 503 && r.headers().firstValue("X-Conveyor-Shed").isPresent();
                    results.add(new Result(cls, r.statusCode(), (System.nanoTime() - t0) / 1000, shed));
                } catch (Exception e) {
                    results.add(new Result(cls, -1, (System.nanoTime() - t0) / 1000, false));
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
        for (String cls : List.of("order", "product", "search")) {
            List<Result> rs = results.stream().filter(r -> r.cls().equals(cls)).toList();
            long ok = rs.stream().filter(r -> r.status() / 100 == 2).count();
            long shed = rs.stream().filter(Result::shed).count();
            long failed = rs.size() - ok - shed;
            long[] lat = rs.stream().filter(r -> r.status() / 100 == 2).mapToLong(Result::micros).sorted().toArray();
            System.out.printf(Locale.ROOT, "  %-7s sent %6d  ok %6d (%5.1f%%)  shed %6d  failed %6d  ok-latency p50 %7s p99 %7s ms%n",
                    cls, rs.size(), ok, 100.0 * ok / Math.max(1, rs.size()), shed, failed, pct(lat, 0.5), pct(lat, 0.99));
        }
        long ok = results.stream().filter(r -> r.status() / 100 == 2).count();
        System.out.printf(Locale.ROOT, "  goodput %.0f req/s of %d offered%n", ok / (double) seconds, rate);
        System.exit(0);
    }

    static String pct(long[] sorted, double p) {
        if (sorted.length == 0) return "-";
        return String.format(Locale.ROOT, "%.1f", sorted[(int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)] / 1000.0);
    }
}
