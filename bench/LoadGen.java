import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Closed-loop load generator for the gateway-overhead benchmark.
 *
 * Every request is timed twice — time-to-first-response-byte and time-to-last —
 * against two targets: the gateway, and the stub provider directly. The direct
 * run is the baseline; gateway minus baseline at the same percentile and the same
 * concurrency is the gateway's added latency. Measuring the delta rather than an
 * absolute is the point: loopback TCP, the JVM's own scheduling, and the load
 * generator all sit in both numbers and cancel out.
 *
 * Raw sockets, one connection pinned per worker, no HTTP client library: at these
 * rates a pooling client's own queueing lands inside the number we are trying to
 * measure.
 *
 * Usage:
 *   java LoadGen.java --gateway http://127.0.0.1:4000 --stub http://127.0.0.1:11500
 *                     --key sk_live_... [--levels 10,50,200] [--duration 20] [--warmup 8]
 *                     [--stream] [--out results.md]
 */
public class LoadGen {

    private static final String OPENAI_BODY = """
            {"model":"bench-stub/stub-model","messages":[\
            {"role":"system","content":"You are a helpful assistant."},\
            {"role":"user","content":"Say hello to the benchmark."}],"stream":%s}""";

    private static final String OLLAMA_BODY = """
            {"model":"stub-model","messages":[\
            {"role":"system","content":"You are a helpful assistant."},\
            {"role":"user","content":"Say hello to the benchmark."}],"stream":%s,"think":false}""";

    public static void main(String[] args) throws Exception {
        String gateway = null;
        String stub = null;
        String key = null;
        String out = null;
        boolean stream = false;
        int[] levels = {10, 50, 200};
        int duration = 20;
        int warmup = 8;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--gateway" -> gateway = args[++i];
                case "--stub" -> stub = args[++i];
                case "--key" -> key = args[++i];
                case "--out" -> out = args[++i];
                case "--stream" -> stream = true;
                case "--duration" -> duration = Integer.parseInt(args[++i]);
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--levels" -> levels = Arrays.stream(args[++i].split(",")).mapToInt(Integer::parseInt).toArray();
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (gateway == null || stub == null) {
            throw new IllegalArgumentException("--gateway and --stub are required");
        }

        Target direct = new Target("direct to stub", URI.create(stub), request(
                "POST /api/chat HTTP/1.1", URI.create(stub), null, OLLAMA_BODY.formatted(stream)));
        Target through = new Target("through gateway", URI.create(gateway), request(
                "POST /v1/chat/completions HTTP/1.1", URI.create(gateway), key, OPENAI_BODY.formatted(stream)));

        StringBuilder report = new StringBuilder();
        report.append("# Gateway overhead benchmark\n\n")
                .append("- mode: **").append(stream ? "streaming (SSE)" : "non-streaming").append("**\n")
                .append("- latency: ").append(stream
                        ? "time to first response byte (TTFT)" : "full request/response completion").append('\n')
                .append("- measure: ").append(duration).append("s per run, ")
                .append(warmup).append("s warmup discarded\n")
                .append("- host: ").append(System.getProperty("os.name")).append(", ")
                .append(Runtime.getRuntime().availableProcessors()).append(" logical CPUs, JDK ")
                .append(System.getProperty("java.version")).append("\n\n");

        List<String> deltaRows = new ArrayList<>();
        for (int level : levels) {
            System.out.printf("=== concurrency %d ===%n", level);
            Result baseline = run(direct, level, warmup, duration, stream);
            Result gatewayResult = run(through, level, warmup, duration, stream);

            report.append("## Concurrency ").append(level).append("\n\n")
                    .append("| Path | Throughput | p50 | p90 | p95 | p99 | p99.9 | max | errors |\n")
                    .append("|---|---|---|---|---|---|---|---|---|\n")
                    .append(baseline.row()).append('\n')
                    .append(gatewayResult.row()).append("\n\n");

            deltaRows.add("| %d | %s | %s | %s | %s | %s |".formatted(
                    level,
                    ms(gatewayResult.percentile(50) - baseline.percentile(50)),
                    ms(gatewayResult.percentile(90) - baseline.percentile(90)),
                    ms(gatewayResult.percentile(95) - baseline.percentile(95)),
                    ms(gatewayResult.percentile(99) - baseline.percentile(99)),
                    ms(gatewayResult.percentile(99.9) - baseline.percentile(99.9))));
        }

        report.append("## Added latency (gateway − direct, same concurrency)\n\n")
                .append("| Concurrency | p50 | p90 | p95 | p99 | p99.9 |\n")
                .append("|---|---|---|---|---|---|\n");
        deltaRows.forEach(row -> report.append(row).append('\n'));

        System.out.println();
        System.out.println(report);
        if (out != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(out), report.toString());
            System.out.println("written to " + out);
        }
    }

    private record Target(String label, URI uri, byte[] request) {
    }

    private static byte[] request(String requestLine, URI uri, String key, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder()
                .append(requestLine).append("\r\n")
                .append("Host: ").append(uri.getHost()).append(':').append(uri.getPort()).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Accept: */*\r\n");
        if (key != null) {
            head.append("Authorization: Bearer ").append(key).append("\r\n");
        }
        head.append("Content-Length: ").append(payload.length).append("\r\n\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] full = new byte[headBytes.length + payload.length];
        System.arraycopy(headBytes, 0, full, 0, headBytes.length);
        System.arraycopy(payload, 0, full, headBytes.length, payload.length);
        return full;
    }

    private static Result run(Target target, int concurrency, int warmupSeconds, int measureSeconds,
                              boolean reportTtfb) throws InterruptedException {
        long now = System.nanoTime();
        long measureStart = now + warmupSeconds * 1_000_000_000L;
        long end = measureStart + measureSeconds * 1_000_000_000L;

        Worker[] workers = new Worker[concurrency];
        CountDownLatch done = new CountDownLatch(concurrency);
        Thread[] threads = new Thread[concurrency];
        for (int i = 0; i < concurrency; i++) {
            workers[i] = new Worker(target, measureStart, end, done);
            threads[i] = new Thread(workers[i], "load-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        done.await();
        for (Thread thread : threads) {
            thread.join();
        }

        Samples total = new Samples();
        Samples ttfb = new Samples();
        List<String> failures = new ArrayList<>();
        long errors = 0;
        for (Worker worker : workers) {
            total.addAll(worker.total);
            ttfb.addAll(worker.ttfb);
            errors += worker.errors;
            worker.failures.stream().filter(f -> !failures.contains(f)).forEach(failures::add);
        }
        Result result = new Result(target.label(), total, ttfb, reportTtfb, errors, measureSeconds);
        System.out.printf("  %-16s %,10.0f req/s  p50 %.2fms  p99 %.2fms  errors %d%n",
                target.label(), result.throughput(), result.percentile(50) / 1e6,
                result.percentile(99) / 1e6, errors);
        failures.forEach(failure -> System.out.println("      failure: " + failure));
        return result;
    }

    private static final class Worker implements Runnable {

        private final Target target;
        private final long measureStart;
        private final long end;
        private final CountDownLatch done;
        final Samples total = new Samples();
        final Samples ttfb = new Samples();
        final List<String> failures = new ArrayList<>();
        long errors;

        Worker(Target target, long measureStart, long end, CountDownLatch done) {
            this.target = target;
            this.measureStart = measureStart;
            this.end = end;
            this.done = done;
        }

        @Override
        public void run() {
            Connection connection = null;
            try {
                while (System.nanoTime() < end) {
                    try {
                        if (connection == null) {
                            connection = new Connection(target.uri());
                        }
                        long start = System.nanoTime();
                        int status = connection.exchange(target.request());
                        long finish = System.nanoTime();
                        boolean measuring = start >= measureStart;
                        if (status != 200) {
                            if (measuring) {
                                record("HTTP " + status);
                            }
                            // A rejected request is a different code path; its timing is not comparable.
                            continue;
                        }
                        if (measuring) {
                            total.add(finish - start);
                            ttfb.add(connection.firstByteNanos - start);
                        }
                    } catch (IOException e) {
                        // A keep-alive connection the server reaped fails with nothing received and
                        // nothing sent that mattered — that is a reconnect, not a failed request.
                        boolean staleKeepAlive = connection != null && connection.reusable();
                        if (!staleKeepAlive && System.nanoTime() >= measureStart) {
                            record(e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                        closeQuietly(connection);
                        connection = null;
                    }
                }
            } finally {
                closeQuietly(connection);
                done.countDown();
            }
        }

        private void record(String failure) {
            errors++;
            if (failures.size() < 5) {
                failures.add(failure);
            }
        }

        private static void closeQuietly(Connection connection) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                    // Closing a socket we are already abandoning.
                }
            }
        }
    }

    /** One keep-alive connection; enough HTTP/1.1 response parsing to time the body exactly. */
    private static final class Connection implements AutoCloseable {

        private final Socket socket;
        private final BufferedInputStream in;
        private final OutputStream out;
        private final StampedStream stamped;
        private int completed;
        long firstByteNanos;

        Connection(URI uri) throws IOException {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 5_000);
            socket.setSoTimeout(30_000);
            stamped = new StampedStream(socket.getInputStream());
            in = new BufferedInputStream(stamped, 32768);
            out = socket.getOutputStream();
        }

        int exchange(byte[] request) throws IOException {
            stamped.first = 0;
            out.write(request);
            out.flush();

            String statusLine = readLine();
            if (statusLine == null) {
                throw new IOException("connection closed before response");
            }
            int status = Integer.parseInt(statusLine.substring(9, 12));
            int contentLength = -1;
            boolean chunked = false;
            String header;
            while ((header = readLine()) != null && !header.isEmpty()) {
                if (header.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    contentLength = Integer.parseInt(header.substring(15).trim());
                } else if (header.regionMatches(true, 0, "Transfer-Encoding:", 0, 18)
                        && header.toLowerCase(Locale.ROOT).contains("chunked")) {
                    chunked = true;
                }
            }
            if (chunked) {
                drainChunked();
            } else if (contentLength > 0) {
                skip(contentLength);
            }
            firstByteNanos = stamped.first;
            completed++;
            return status;
        }

        /** True when this failure is a reused connection dying before it answered anything. */
        boolean reusable() {
            return completed > 0 && stamped.first == 0;
        }

        private void drainChunked() throws IOException {
            while (true) {
                String sizeLine = readLine();
                if (sizeLine == null) {
                    throw new IOException("truncated chunked body");
                }
                int semicolon = sizeLine.indexOf(';');
                int size = Integer.parseInt(
                        (semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim(), 16);
                if (size == 0) {
                    readLine();
                    return;
                }
                skip(size);
                readLine();
            }
        }

        private void skip(int count) throws IOException {
            int remaining = count;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        throw new IOException("truncated body");
                    }
                    remaining--;
                } else {
                    remaining -= (int) skipped;
                }
            }
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder(64);
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    int end = sb.length();
                    if (end > 0 && sb.charAt(end - 1) == '\r') {
                        sb.setLength(end - 1);
                    }
                    return sb.toString();
                }
                sb.append((char) c);
            }
            return sb.isEmpty() ? null : sb.toString();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** Stamps the arrival of the first byte of each response — TTFB, and for a stream, TTFT. */
    private static final class StampedStream extends InputStream {

        private final InputStream source;
        long first;

        StampedStream(InputStream source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            int b = source.read();
            stamp();
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = source.read(buffer, offset, length);
            stamp();
            return n;
        }

        private void stamp() {
            if (first == 0) {
                first = System.nanoTime();
            }
        }
    }

    /** Growable primitive sample buffer — an ArrayList<Long> at these counts is all boxing. */
    private static final class Samples {

        private long[] values = new long[1 << 16];
        private int size;

        void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        void addAll(Samples other) {
            for (int i = 0; i < other.size; i++) {
                add(other.values[i]);
            }
        }

        void sort() {
            Arrays.sort(values, 0, size);
        }

        int size() {
            return size;
        }

        /** Nearest-rank on the sorted buffer; sort() must have run. */
        long percentile(double p) {
            if (size == 0) {
                return 0;
            }
            int index = (int) Math.ceil(p / 100.0 * size) - 1;
            return values[Math.clamp(index, 0, size - 1)];
        }

        long max() {
            return size == 0 ? 0 : values[size - 1];
        }
    }

    private static final class Result {

        private final String label;
        private final Samples total;
        private final Samples reported;
        private final long errors;
        private final int seconds;

        /** In streaming mode the latency that matters is time-to-first-token, not completion. */
        Result(String label, Samples total, Samples ttfb, boolean reportTtfb, long errors, int seconds) {
            this.label = label;
            this.total = total;
            this.reported = reportTtfb ? ttfb : total;
            this.errors = errors;
            this.seconds = seconds;
            total.sort();
            ttfb.sort();
        }

        long percentile(double p) {
            return reported.percentile(p);
        }

        double throughput() {
            return total.size() / (double) seconds;
        }

        String row() {
            return "| %s | %,.0f req/s | %s | %s | %s | %s | %s | %s | %d |".formatted(
                    label, throughput(), ms(percentile(50)), ms(percentile(90)), ms(percentile(95)),
                    ms(percentile(99)), ms(percentile(99.9)), ms(reported.max()), errors);
        }
    }

    private static String ms(long nanos) {
        return "%.2f ms".formatted(nanos / 1e6);
    }
}
