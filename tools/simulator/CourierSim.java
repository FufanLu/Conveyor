import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * Simulated couriers for tracking-svc. Each courier carries S/C shipments, drives each one from
 * its FC towards a random address 3-20 km away at about 11 m/s, and every 1/hz seconds posts one
 * batch: a position per shipment it carries (plus PICKED / LOADED / OUT_FOR_DELIVERY scans at
 * the start and DELIVERED on arrival). Prints per-second ingest numbers and POST latencies.
 *
 * <pre>
 * java tools/simulator/CourierSim.java URL SHIPMENTS COURIERS HZ SECONDS [SHIPMENT_IDS_FILE|-] [SPEEDUP]
 * java tools/simulator/CourierSim.java http://localhost:8085 2000 200 1 60
 * java tools/simulator/CourierSim.java http://localhost:8085 0 13 1 90 ready.txt 30   # real shipments, 30x time
 * </pre>
 *
 * With SHIPMENT_IDS_FILE (one id per line) the couriers deliver those shipments, e.g. real
 * confirmed orders; otherwise ids are synthetic ("SIM" + run + number).
 */
public class CourierSim {

    // FC coordinates: east-1 Newark, east-2 Baltimore, central-1 Joliet, south-1 Dallas,
    // west-1 Tracy, west-2 Kent. Ids and regions match inventory-svc's seed.
    static final String[] FC = {"east-1", "east-2", "central-1", "south-1", "west-1", "west-2"};
    static final double[][] AT = {
        {40.7357, -74.1724}, {39.2904, -76.6122}, {41.5250, -88.0817},
        {32.7767, -96.7970}, {37.7397, -121.4252}, {47.3809, -122.2348}
    };

    static final class Route {
        final String id;
        final String fc;
        final double lat0, lon0, lat1, lon1, metres;
        long seq;
        double travelled;
        boolean done;

        Route(String id, int fcIndex, Random rnd) {
            this.id = id;
            this.fc = FC[fcIndex];
            this.lat0 = AT[fcIndex][0];
            this.lon0 = AT[fcIndex][1];
            double km = 3 + rnd.nextDouble() * 17, bearing = rnd.nextDouble() * 2 * Math.PI;
            this.lat1 = lat0 + km / 111.0 * Math.cos(bearing);
            this.lon1 = lon0 + km / (111.0 * Math.cos(Math.toRadians(lat0))) * Math.sin(bearing);
            this.metres = km * 1000;
        }
    }

    public static void main(String[] args) throws Exception {
        String url = args[0];
        int shipments = Integer.parseInt(args[1]);
        int couriers = Integer.parseInt(args[2]);
        double hz = Double.parseDouble(args[3]);
        int seconds = Integer.parseInt(args[4]);
        List<String> ids = new ArrayList<>();
        double speedup = args.length > 6 ? Double.parseDouble(args[6]) : 1;
        if (args.length > 5 && !args[5].equals("-")) {
            ids.addAll(java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[5])).stream()
                    .map(String::trim).filter(s -> !s.isEmpty()).toList());
            shipments = ids.size();
        } else {
            String run = Long.toString(System.currentTimeMillis() / 1000 % 100000, 36).toUpperCase(Locale.ROOT);
            for (int i = 0; i < shipments; i++) ids.add("SIM" + run + "-" + i);
        }
        Random rnd = new Random(42);
        List<List<Route>> load = new ArrayList<>();
        for (int c = 0; c < couriers; c++) load.add(new ArrayList<>());
        for (int i = 0; i < shipments; i++) {
            int fc = i % FC.length;
            load.get(i % couriers).add(new Route(ids.get(i), fc, rnd));
        }

        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        LongAdder sent = new LongAdder(), accepted = new LongAdder(), errors = new LongAdder(), posts = new LongAdder();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> allLatencies = new ConcurrentLinkedQueue<>();
        long periodNanos = (long) (1e9 / hz);
        double step = 11.0 * speedup / hz; // metres per tick at ~40 km/h (times SPEEDUP)
        long start = System.nanoTime();
        long end = start + seconds * 1_000_000_000L;

        var pool = Executors.newVirtualThreadPerTaskExecutor();
        for (int c = 0; c < couriers; c++) {
            final String courier = "courier-" + c;
            final List<Route> routes = load.get(c);
            final long offset = (long) (periodNanos * (c / (double) couriers)); // spread couriers over the tick
            pool.submit(() -> {
                long next = start + offset;
                while (next < end) {
                    long wait = next - System.nanoTime();
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    StringBuilder body = new StringBuilder("{\"events\":[");
                    int n = 0;
                    String now = Instant.now().toString();
                    for (Route r : routes) {
                        if (r.done) continue;
                        if (r.seq == 0) {
                            for (String scan : new String[] {"PICKED", "LOADED", "OUT_FOR_DELIVERY"}) {
                                if (n++ > 0) body.append(',');
                                body.append("{\"shipmentId\":\"").append(r.id).append("\",\"seq\":").append(++r.seq)
                                        .append(",\"recordedAt\":\"").append(now).append("\",\"scan\":{\"type\":\"")
                                        .append(scan).append("\",\"location\":\"").append(r.fc).append("\"}}");
                            }
                        }
                        r.travelled = Math.min(r.metres, r.travelled + step);
                        double f = r.travelled / r.metres;
                        if (n++ > 0) body.append(',');
                        body.append(String.format(Locale.ROOT,
                                "{\"shipmentId\":\"%s\",\"seq\":%d,\"recordedAt\":\"%s\",\"position\":{\"lat\":%.6f,\"lon\":%.6f,\"speed\":%.1f}}",
                                r.id, ++r.seq, now, r.lat0 + (r.lat1 - r.lat0) * f, r.lon0 + (r.lon1 - r.lon0) * f, 11.0));
                        if (f >= 1) {
                            body.append(",{\"shipmentId\":\"").append(r.id).append("\",\"seq\":").append(++r.seq)
                                    .append(",\"recordedAt\":\"").append(now).append("\",\"scan\":{\"type\":\"DELIVERED\"}}");
                            n++;
                            r.done = true;
                        }
                    }
                    body.append("]}");
                    if (n > 0) {
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<String> res = http.send(
                                    HttpRequest.newBuilder(URI.create(url + "/v1/couriers/" + courier + "/pings"))
                                            .timeout(Duration.ofSeconds(10))
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
                            if (res.statusCode() == 202) accepted.add(n); else errors.increment();
                        } catch (Exception e) {
                            errors.increment();
                        }
                        long us = (System.nanoTime() - t0) / 1000;
                        latencies.add(us);
                        allLatencies.add(us);
                        sent.add(n);
                        posts.increment();
                    }
                    next += periodNanos;
                }
                return null;
            });
        }

        long lastSent = 0, lastAccepted = 0;
        for (int s = 1; System.nanoTime() < end + 2_000_000_000L; s++) {
            Thread.sleep(1000);
            long[] l = drain(latencies);
            long nowSent = sent.sum(), nowAcc = accepted.sum();
            System.out.printf(Locale.ROOT, "t=%3ds  sent %6d/s  accepted %6d/s  errors %d  POST p50 %s p99 %s ms%n",
                    s, nowSent - lastSent, nowAcc - lastAccepted, errors.sum(), pct(l, 0.5), pct(l, 0.99));
            lastSent = nowSent;
            lastAccepted = nowAcc;
            if (System.nanoTime() > end && nowSent == lastSent && pool.isTerminated()) break;
        }
        pool.shutdown();
        long[] all = drain(allLatencies);
        System.out.printf(Locale.ROOT, "total: %d reports in %d posts, %d accepted, %d failed posts; POST p50 %s p99 %s max %s ms%n",
                sent.sum(), posts.sum(), accepted.sum(), errors.sum(), pct(all, 0.5), pct(all, 0.99), pct(all, 1.0));
        System.exit(0);
    }

    static long[] drain(ConcurrentLinkedQueue<Long> q) {
        List<Long> out = new ArrayList<>();
        for (Long v; (v = q.poll()) != null; ) out.add(v);
        long[] a = out.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(a);
        return a;
    }

    static String pct(long[] sorted, double p) {
        if (sorted.length == 0) return "-";
        int i = (int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1);
        return String.format(Locale.ROOT, "%.1f", sorted[Math.max(0, i)] / 1000.0);
    }
}
