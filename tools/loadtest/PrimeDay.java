import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prime Day, scaled to one machine, through the gateway with real customers:
 *
 * <pre>
 *   setup     N customers created in Keycloak (admin API), each signed in (password grant)
 *   warm      browsing + a trickle of orders
 *   deal      a deal SKU with 6,000 units opens; DEAL_BUYERS customers join its waiting room
 *             within 60 s (browsing and regular orders continue), wait for their turn, and
 *             order with the admission they get; a customer gives up after PATIENCE
 *   peak      heavier browsing and regular orders
 *   recover   back to warm
 *   verdict   deal orders by final status (ops order search), and whether any unit was sold twice
 * </pre>
 *
 * Load is open loop (fixed schedules, like real users). Per phase and class it prints answers by
 * status and latency of the 2xx ones. Prometheus has the SLO side (tools/loadtest/prime-day.sh).
 *
 *   java tools/loadtest/PrimeDay.java GATEWAY KEYCLOAK [CUSTOMERS] [DEAL_BUYERS]
 */
public class PrimeDay {

    static final String DEAL_SKU = "B09E3779B1"; // seed row 1: 1,000 units in each of 6 FCs
    static final String DEAL_ID = "lightning-1"; // tools/loadtest/prime-day.sh configures it
    static final long PATIENCE_NANOS = TimeUnit.MINUTES.toNanos(5);
    static final String[] REGIONS = {"us-east", "us-central", "us-south", "us-west"};
    static final String[] WORDS = {"kettle", "backpack", "lantern", "earbuds", "coffee", "puzzle", "serum", "leash"};

    static HttpClient http;
    static String gateway;
    static String keycloak;
    static final Map<String, String[]> tokens = new ConcurrentHashMap<>(); // user -> {token, fetchedAt}
    static final List<String> skus = new ArrayList<>();

    record Result(String phase, String cls, int status, long micros) {}

    static final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();

    public static void main(String[] args) throws Exception {
        gateway = args[0];
        keycloak = args[1];
        int customers = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        int dealBuyers = args.length > 3 ? Integer.parseInt(args[3]) : 7000;
        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        for (int i = 11; i <= 3000; i++) skus.add(sku(i)); // regular SKUs (rows 1-10 are deals)

        long t0 = System.nanoTime();
        List<String> users = setup(customers);
        System.out.printf("setup: %d customers signed in after %.1f s%n", users.size(), (System.nanoTime() - t0) / 1e9);
        // Signing in 200 customers is password hashing on Keycloak's CPU, not shopping; the first
        // run's warm phase started while that was still going on. Let it settle.
        Thread.sleep(30_000);

        Thread monitor = Thread.ofVirtual().start(PrimeDay::monitor);
        phase("warm", 60, 40, 4, 0, users);
        phase("deal", 60, 80, 4, dealBuyers / 60.0, users);
        phase("peak", 120, 120, 15, 0, users);
        phase("recover", 60, 40, 4, 0, users);
        current = "after";
        buyers.shutdown();
        buyers.awaitTermination(10, TimeUnit.MINUTES);
        monitor.interrupt();

        report();
        dealReport();
        verdict(dealBuyers);
        System.exit(0);
    }

    // ------------------------------------------------------------------ setup

    static List<String> setup(int n) throws Exception {
        String admin = field(post(keycloak + "/realms/master/protocol/openid-connect/token",
                "grant_type=password&client_id=admin-cli&username=admin&password=admin", null, "application/x-www-form-urlencoded").body(),
                "access_token");
        List<String> users = new ArrayList<>();
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        // 16 at a time: 200 parallel creations made Keycloak time out one of them in a run.
        var gate = new java.util.concurrent.Semaphore(16);
        List<java.util.concurrent.Future<?>> pending = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String user = String.format(Locale.ROOT, "shopper-%03d", i);
            users.add(user);
            pending.add(pool.submit(() -> {
                String body = """
                        {"username":"%s","enabled":true,"email":"%s@example.com","emailVerified":true,
                         "firstName":"Prime","lastName":"Shopper",
                         "credentials":[{"type":"password","value":"%s","temporary":false}]}""".formatted(user, user, user);
                gate.acquire();
                try {
                    for (int attempt = 1; ; attempt++) {
                        try {
                            var r = post(keycloak + "/admin/realms/conveyor/users", body, "Bearer " + admin, "application/json");
                            if (r.statusCode() != 201 && r.statusCode() != 409) {
                                throw new IllegalStateException("create " + user + ": " + r.statusCode());
                            }
                            token(user);
                            return null;
                        } catch (java.net.http.HttpTimeoutException e) {
                            if (attempt == 3) throw e;
                        }
                    }
                } finally {
                    gate.release();
                }
            }));
        }
        for (var f : pending) f.get();
        pool.shutdown();
        return users;
    }

    /** A customer's access token, refreshed after 4 minutes (Keycloak issues 5-minute tokens). */
    static String token(String user) throws Exception {
        String[] t = tokens.get(user);
        if (t != null && System.nanoTime() - Long.parseLong(t[1]) < TimeUnit.MINUTES.toNanos(4)) return t[0];
        String token = field(post(keycloak + "/realms/conveyor/protocol/openid-connect/token",
                "grant_type=password&client_id=conveyor-cli&username=" + user + "&password=" + user, null,
                "application/x-www-form-urlencoded").body(), "access_token");
        tokens.put(user, new String[] {token, Long.toString(System.nanoTime())});
        return token;
    }

    // ------------------------------------------------------------------ load

    static void phase(String name, int seconds, double browsePerS, double ordersPerS, double dealPerS, List<String> users)
            throws Exception {
        System.out.printf(Locale.ROOT, "### %s: %d s, browse %.0f/s, orders %.0f/s%s%n", name, seconds, browsePerS, ordersPerS,
                dealPerS > 0 ? String.format(Locale.ROOT, ", deal %.0f/s", dealPerS) : "");
        current = name;
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Thread> drivers = new ArrayList<>();
        drivers.add(Thread.ofVirtual().start(() -> schedule(pool, browsePerS, seconds, () -> browse(name, users))));
        drivers.add(Thread.ofVirtual().start(() -> schedule(pool, ordersPerS, seconds, () -> order(name, "order", pickSku(), users))));
        if (dealPerS > 0) drivers.add(Thread.ofVirtual().start(() -> schedule(buyers, dealPerS, seconds, () -> dealBuyer(users))));
        for (Thread d : drivers) d.join();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
    }

    static void schedule(java.util.concurrent.ExecutorService pool, double perSecond, int seconds, Runnable task) {
        if (perSecond <= 0) return;
        long period = (long) (1e9 / perSecond);
        long start = System.nanoTime();
        long total = (long) (perSecond * seconds);
        for (long i = 0; i < total; i++) {
            long wait = start + i * period - System.nanoTime();
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                } catch (InterruptedException e) {
                    return;
                }
            }
            pool.submit(task);
        }
    }

    static String pickSku() {
        return skus.get(ThreadLocalRandom.current().nextInt(skus.size()));
    }

    /**
     * Browsing as a signed-in customer: the gateway's browse rate limit is per customer (or per
     * address when anonymous). The first run browsed anonymously from one address, so the
     * per-address limit (50/s) answered 429 to the whole crowd at peak, an artifact of the test.
     */
    static void browse(String phase, List<String> users) {
        String user = users.get(ThreadLocalRandom.current().nextInt(users.size()));
        boolean search = ThreadLocalRandom.current().nextInt(10) < 6;
        String path = search
                ? "/api/v1/search?q=" + URLEncoder.encode(WORDS[ThreadLocalRandom.current().nextInt(WORDS.length)], StandardCharsets.UTF_8)
                : "/api/v1/products/" + pickSku();
        long t0 = System.nanoTime();
        int status;
        try {
            status = http.send(HttpRequest.newBuilder(URI.create(gateway + path))
                                    .timeout(Duration.ofSeconds(5))
                                    .header("Authorization", "Bearer " + token(user))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (Exception e) {
            status = -1;
        }
        results.add(new Result(phase, search ? "search" : "product", status, (System.nanoTime() - t0) / 1000));
    }

    static final Map<String, Boolean> dealOrders = new ConcurrentHashMap<>();

    static void order(String phase, String cls, String sku, List<String> users) {
        order(phase, cls, sku, users.get(ThreadLocalRandom.current().nextInt(users.size())), null, "pd-" + UUID.randomUUID());
    }

    static int order(String phase, String cls, String sku, String user, String admission, String key) {
        long t0 = System.nanoTime();
        int status;
        try {
            String body = "{\"items\":[{\"sku\":\"" + sku + "\",\"quantity\":1}],\"shipToRegion\":\""
                    + REGIONS[ThreadLocalRandom.current().nextInt(REGIONS.length)] + "\"}";
            var b = HttpRequest.newBuilder(URI.create(gateway + "/api/v1/orders"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token(user))
                    .header("Idempotency-Key", key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (admission != null) b.header("Conveyor-Admission", admission);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            status = r.statusCode();
            if (status == 201 && cls.equals("deal")) dealOrders.put(field(r.body(), "orderId"), true);
        } catch (Exception e) {
            status = -1;
        }
        results.add(new Result(phase, cls, status, (System.nanoTime() - t0) / 1000));
        return status;
    }

    // ------------------------------------------------------------------ the deal's waiting room

    static volatile String current = "setup";
    static final java.util.concurrent.ExecutorService buyers = Executors.newVirtualThreadPerTaskExecutor();

    /** How one deal buyer's visit ended: ORDERED, SOLD_OUT, GAVE_UP, REFUSED (order not 201), ERROR. */
    record Buyer(String outcome, long waitMillis, int orderAttempts) {}

    static final ConcurrentLinkedQueue<Buyer> visits = new ConcurrentLinkedQueue<>();

    /**
     * A deal buyer: joins the line, polls when the gateway says to, orders once admitted. An
     * order answered 503 is retried with the same Idempotency-Key after Retry-After, up to three
     * times, as a buyer holding an admission would.
     */
    static void dealBuyer(List<String> users) {
        String user = users.get(ThreadLocalRandom.current().nextInt(users.size()));
        long t0 = System.nanoTime();
        try {
            // A failed join or poll is retried after a few seconds, as the web page does; the
            // first run counted one timed-out poll as a lost customer (5,427 of them).
            String body = null;
            while (body == null) {
                HttpResponse<String> r = trySend("POST", "/api/v1/deals/" + DEAL_ID + "/queue", user);
                if (r != null && r.statusCode() == 201) body = r.body();
                else if (gaveUp(t0)) return;
                else Thread.sleep(3000 + ThreadLocalRandom.current().nextLong(2000));
            }
            String ticket = field(body, "ticket");
            while (true) {
                String state = field(body, "state");
                if (state.equals("SOLD_OUT")) {
                    visits.add(new Buyer("SOLD_OUT", (System.nanoTime() - t0) / 1_000_000, 0));
                    return;
                }
                if (state.equals("ADMITTED")) break;
                if (gaveUp(t0)) return;
                long poll = Long.parseLong(field(body, "pollAfterMs"));
                Thread.sleep(poll + ThreadLocalRandom.current().nextLong(500));
                HttpResponse<String> p;
                while ((p = trySend("GET", "/api/v1/deals/" + DEAL_ID + "/queue/" + ticket, user)) == null
                        || p.statusCode() != 200) {
                    polls.computeIfAbsent(p == null ? "timeout" : Integer.toString(p.statusCode()), k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
                    if (gaveUp(t0)) return;
                    Thread.sleep(3000 + ThreadLocalRandom.current().nextLong(2000));
                }
                polls.computeIfAbsent("200", k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
                body = p.body();
            }
            long waited = (System.nanoTime() - t0) / 1_000_000;
            String admission = field(body, "admission");
            String key = "deal-" + UUID.randomUUID();
            for (int attempt = 1; attempt <= 4; attempt++) {
                int status = order(current, "deal", DEAL_SKU, user, admission, key);
                if (status == 201) {
                    visits.add(new Buyer("ORDERED", waited, attempt));
                    return;
                }
                if (status != 503 && status != -1) {
                    visits.add(new Buyer("REFUSED", waited, attempt));
                    return;
                }
                Thread.sleep(1000 + ThreadLocalRandom.current().nextLong(1000));
            }
            visits.add(new Buyer("REFUSED", waited, 4));
        } catch (Exception e) {
            visits.add(new Buyer("ERROR " + e.getClass().getSimpleName(), (System.nanoTime() - t0) / 1_000_000, 0));
        }
    }

    static final Map<String, java.util.concurrent.atomic.AtomicLong> polls = new ConcurrentHashMap<>();

    static boolean gaveUp(long t0) {
        if (System.nanoTime() - t0 <= PATIENCE_NANOS) return false;
        visits.add(new Buyer("GAVE_UP", (System.nanoTime() - t0) / 1_000_000, 0));
        return true;
    }

    /** A request whose failure (timeout, refused connection) is an answer, not an exception. */
    static HttpResponse<String> trySend(String method, String path, String user) {
        try {
            return send(method, path, user);
        } catch (Exception e) {
            return null;
        }
    }

    static HttpResponse<String> send(String method, String path, String user) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(gateway + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + token(user))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The line as the gateway reports it, every 10 s. */
    static void monitor() {
        long t0 = System.nanoTime();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(10_000);
                String d = http.send(HttpRequest.newBuilder(URI.create(gateway + "/api/v1/deals")).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                System.out.printf(Locale.ROOT, "  [%4.0f s %-7s] line: joined %s, admitted %s, %s admissions/s%n",
                        (System.nanoTime() - t0) / 1e9, current, field(d, "joined"), field(d, "admitted"),
                        field(d, "admissionsPerSecond"));
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                System.out.println("  line: " + e);
            }
        }
    }

    static void dealReport() {
        System.out.println("### line polls by answer: " + new TreeMap<>(polls));
        System.out.println("### deal buyers by outcome (wait = join to admission, or to the answer)");
        Map<String, List<Buyer>> by = new TreeMap<>();
        visits.forEach(v -> by.computeIfAbsent(v.outcome(), k -> new ArrayList<>()).add(v));
        by.forEach((outcome, vs) -> {
            long[] waits = vs.stream().mapToLong(Buyer::waitMillis).sorted().toArray();
            long retried = vs.stream().filter(v -> v.orderAttempts() > 1).count();
            System.out.printf(Locale.ROOT, "  %-9s %6d  wait p50 %6.1f s  p99 %6.1f s%s%n", outcome, vs.size(),
                    waits[(int) Math.min(waits.length - 1, Math.ceil(0.5 * waits.length) - 1)] / 1000.0,
                    waits[(int) Math.min(waits.length - 1, Math.ceil(0.99 * waits.length) - 1)] / 1000.0,
                    retried > 0 ? "  (" + retried + " ordered on a retry)" : "");
        });
    }

    // ------------------------------------------------------------------ results

    static void report() {
        System.out.println("### answers by phase and class (status: count), latency of 2xx");
        for (String phase : List.of("warm", "deal", "peak", "recover", "after")) {
            for (String cls : List.of("deal", "order", "product", "search")) {
                List<Result> rs = results.stream().filter(r -> r.phase().equals(phase) && r.cls().equals(cls)).toList();
                if (rs.isEmpty()) continue;
                Map<Integer, Long> by = new TreeMap<>();
                rs.forEach(r -> by.merge(r.status(), 1L, Long::sum));
                long[] ok = rs.stream().filter(r -> r.status() / 100 == 2).mapToLong(Result::micros).sorted().toArray();
                System.out.printf(Locale.ROOT, "  %-8s %-8s sent %6d  %s  p50 %s p99 %s ms%n", phase, cls, rs.size(), by,
                        pct(ok, 0.5), pct(ok, 0.99));
            }
        }
    }

    /** Waits for every accepted deal order to leave PLACED, then counts them by final status. */
    static void verdict(int dealBuyers) throws Exception {
        String ops = field(post(keycloak + "/realms/conveyor/protocol/openid-connect/token",
                "grant_type=password&client_id=conveyor-cli&username=ops&password=ops", null, "application/x-www-form-urlencoded").body(),
                "access_token");
        long start = System.nanoTime();
        Map<String, Long> counts = Map.of();
        while (System.nanoTime() - start < TimeUnit.MINUTES.toNanos(10)) {
            counts = new TreeMap<>();
            for (String status : List.of("PLACED", "CONFIRMED", "REJECTED", "PAYMENT_FAILED", "CANCELLED")) {
                var r = http.send(HttpRequest.newBuilder(URI.create(gateway + "/api/v1/ops/orders?sku=" + DEAL_SKU + "&status=" + status + "&size=1"))
                        .header("Authorization", "Bearer " + ops).GET().build(), HttpResponse.BodyHandlers.ofString());
                counts.put(status, Long.parseLong(field(r.body(), "total")));
            }
            long settled = counts.values().stream().mapToLong(Long::longValue).sum() - counts.get("PLACED");
            if (counts.get("PLACED") == 0 && settled >= dealOrders.size()) break;
            Thread.sleep(5000);
        }
        System.out.printf(Locale.ROOT, "### deal %s: %d buyers tried, %d orders accepted; settled %.0f s after the load ended%n",
                DEAL_SKU, dealBuyers, dealOrders.size(), (System.nanoTime() - start) / 1e9);
        System.out.println("  by final status: " + counts);
        long sold = counts.getOrDefault("CONFIRMED", 0L);
        System.out.println(sold <= 6000 ? "  no oversell: " + sold + " confirmed of 6000 units" : "  OVERSOLD: " + sold + " confirmed of 6000 units");
    }

    // ------------------------------------------------------------------ helpers

    static HttpResponse<String> post(String url, String body, String auth, String type) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Content-Type", type)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) b.header("Authorization", auth);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]*)").matcher(json);
        if (!m.find()) throw new IllegalStateException("no " + name + " in " + json.substring(0, Math.min(200, json.length())));
        return m.group(1);
    }

    /** The catalog seed's SKU for row i (same hash as V1_1__seed_products.sql). */
    static String sku(int i) {
        long h = (i * 2654435761L) % 4294967296L;
        return "B0" + String.format(Locale.ROOT, "%08X", h);
    }

    static String pct(long[] sorted, double p) {
        if (sorted.length == 0) return "-";
        return String.format(Locale.ROOT, "%.1f", sorted[(int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)] / 1000.0);
    }
}
