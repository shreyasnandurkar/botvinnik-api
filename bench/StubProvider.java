import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.LockSupport;

/**
 * A near-zero-latency stand-in for an Ollama backend, so a load test measures the
 * gateway and not the model. Speaks just enough HTTP/1.1 to serve /api/chat and
 * /api/tags: hand-rolled rather than com.sun.net.httpserver because that server
 * saturates below the rates we need and would become the thing under test.
 *
 * Usage: java StubProvider.java [port] [--delay-micros N] [--chunks N]
 */
public class StubProvider {

    private static final byte[] CHAT_BODY = ("""
            {"model":"stub-model","created_at":"2026-01-01T00:00:00.000000Z",\
            "message":{"role":"assistant","content":"The quick brown fox jumps over the lazy dog."},\
            "done":true,"done_reason":"stop","prompt_eval_count":24,"eval_count":11}"""
    ).getBytes(StandardCharsets.UTF_8);

    private static final byte[] TAGS_BODY =
            "{\"models\":[{\"name\":\"stub-model\"}]}".getBytes(StandardCharsets.UTF_8);

    private static final byte[] CHAT_RESPONSE = jsonResponse(CHAT_BODY);
    private static final byte[] TAGS_RESPONSE = jsonResponse(TAGS_BODY);
    private static final byte[] NOT_FOUND =
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    private static final byte[] STREAM_HEADERS = ("HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/x-ndjson\r\n"
            + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] LAST_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    private static long delayNanos;
    private static int streamChunks = 8;

    public static void main(String[] args) throws IOException {
        int port = 11500;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--delay-micros" -> delayNanos = Long.parseLong(args[++i]) * 1_000L;
                case "--chunks" -> streamChunks = Integer.parseInt(args[++i]);
                default -> port = Integer.parseInt(args[i]);
            }
        }
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port), 1024);
        System.out.println("stub listening on 127.0.0.1:" + port
                + " (delay=" + (delayNanos / 1000) + "us, stream chunks=" + streamChunks + ")");
        System.out.flush();
        while (true) {
            Socket socket = server.accept();
            Thread.startVirtualThread(() -> serve(socket));
        }
    }

    private static void serve(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 16384);
            OutputStream out = socket.getOutputStream();
            byte[] body = new byte[16384];
            while (true) {
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) {
                    return;
                }
                int contentLength = 0;
                String header;
                while ((header = readLine(in)) != null && !header.isEmpty()) {
                    if (header.length() > 15 && header.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                        contentLength = Integer.parseInt(header.substring(15).trim());
                    }
                }
                if (contentLength > body.length) {
                    body = new byte[contentLength];
                }
                readFully(in, body, contentLength);
                boolean streaming = contains(body, contentLength, "\"stream\":true");

                if (delayNanos > 0) {
                    LockSupport.parkNanos(delayNanos);
                }
                if (requestLine.startsWith("POST /api/chat")) {
                    if (streaming) {
                        writeStream(out);
                    } else {
                        out.write(CHAT_RESPONSE);
                    }
                } else if (requestLine.startsWith("GET /api/tags")) {
                    out.write(TAGS_RESPONSE);
                } else {
                    out.write(NOT_FOUND);
                }
                out.flush();
            }
        } catch (IOException e) {
            // Client hung up mid-request; nothing to do but drop the connection.
        }
    }

    /** NDJSON deltas then a terminal chunk carrying done + usage, as Ollama does. */
    private static void writeStream(OutputStream out) throws IOException {
        out.write(STREAM_HEADERS);
        out.flush();
        for (int i = 0; i < streamChunks; i++) {
            writeChunk(out, "{\"model\":\"stub-model\",\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"token" + i + " \"},\"done\":false}\n");
            out.flush();
        }
        writeChunk(out, "{\"model\":\"stub-model\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
                + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":24,\"eval_count\":"
                + streamChunks + "}\n");
        out.write(LAST_CHUNK);
    }

    private static void writeChunk(OutputStream out, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write('\r');
        out.write('\n');
    }

    private static byte[] jsonResponse(byte[] body) {
        byte[] head = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    private static boolean contains(byte[] haystack, int length, String needle) {
        byte[] probe = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= length - probe.length; i++) {
            for (int j = 0; j < probe.length; j++) {
                if (haystack[i + j] != probe[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n < 0) {
                throw new IOException("truncated body");
            }
            read += n;
        }
    }

    private static String readLine(InputStream in) throws IOException {
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
}
