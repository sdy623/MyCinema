package local.cinemamanager.client;

import com.sun.net.httpserver.HttpServer;
import org.apache.http.client.methods.HttpGet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Offline deterministic fixtures by default. --loopback also checks the real Apache HTTP stack. */
public final class PosterHttpTransportChecks {
    private static int checks;
    private static final URI START = URI.create("https://posters.example/start.png");
    private static final PosterHttpTransport.Authority ALLOW = (uri, control) -> { control.check(); return true; };
    private static final PosterHttpTransport.Budget BUDGET = PosterHttpTransport.Budget.defaults();

    public static void main(String[] args) throws Exception {
        redirectChecks();
        addressChecks();
        budgetChecks();
        cancellationChecks();
        errorChecks();
        if (Arrays.asList(args).contains("--loopback")) loopbackChecks();
        System.out.println("Poster HTTP transport checks passed: " + checks);
    }

    private static void redirectChecks() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(302, "https://cdn.example/poster.png", new byte[0]);
        fixture.add(200, null, bytes("pixels"));
        List<URI> permissions = new ArrayList<>();
        byte[] result = fixture.read(START, (uri, control) -> { permissions.add(uri); return true; });
        check(Arrays.equals(result, bytes("pixels")), "authorized redirect returns its body");
        check(permissions.equals(List.of(START, URI.create("https://cdn.example/poster.png"))), "every hop receives fresh authority");
        check(fixture.requests.size() == 2 && fixture.closed == 2, "each redirect connection closes");

        Fixture denied = new Fixture();
        denied.add(302, "https://other.example/poster.png", new byte[0]);
        rejects(() -> denied.read(START, (uri, control) -> uri.getHost().equals("posters.example")), "cross-domain redirect is denied");
        check(denied.requests.size() == 1 && denied.lookups.equals(List.of("posters.example")), "unauthorized destination is neither resolved nor requested");
        Fixture deniedInitial = new Fixture();
        rejects(() -> deniedInitial.read(START, (uri, control) -> false), "initial URI requires authority too");
        check(deniedInitial.requests.isEmpty() && deniedInitial.lookups.isEmpty(), "denied initial URL produces no network operation");

        Fixture loop = new Fixture();
        loop.add(302, "/b.png", new byte[0]);
        loop.add(301, "/start.png#same-resource", new byte[0]);
        rejects(() -> loop.read(START, ALLOW), "fragment redirect loop rejected");
        check(loop.requests.size() == 2, "loop is detected before a duplicate request");
        Fixture portLoop = new Fixture();
        portLoop.add(302, "https://POSTERS.example:443/start.png", new byte[0]);
        rejects(() -> portLoop.read(START, ALLOW), "default-port loop rejected");
        check(portLoop.requests.size() == 1, "canonical host and default port detect the same resource");

        Fixture excess = new Fixture();
        for (int i = 0; i < 6; i++) excess.add(302, "/hop" + i, new byte[0]);
        rejects(() -> excess.read(START, ALLOW), "sixth redirect rejected");
        check(excess.requests.size() == 6, "at most initial plus five redirected requests");
        Fixture five = new Fixture();
        for (int i = 0; i < 5; i++) five.add(307, "/hop" + i, new byte[0]);
        five.add(200, null, bytes("five"));
        check(Arrays.equals(five.read(START, ALLOW), bytes("five")), "exactly five redirects permitted");

        for (String unsafe : List.of("http://cdn.example/poster.png", "file:///tmp/poster.png",
                "https://user:secret@cdn.example/poster.png", "https://cdn.example:70000/a",
                "https://[fe80::1%25eth0]/a", "https://cdn.example/a\nInjected: yes", "")) {
            Fixture rejected = new Fixture();
            rejected.add(302, unsafe, new byte[0]);
            rejects(() -> rejected.read(START, ALLOW), "unsafe redirect rejected: " + unsafe.replace('\n', ' '));
            check(rejected.requests.size() == 1, "unsafe redirect never creates another request");
        }
        for (String invalid : List.of("file:///tmp/poster.png", "ftp://posters.example/a", "https://u:p@posters.example/a")) {
            Fixture rejected = new Fixture();
            rejects(() -> rejected.read(URI.create(invalid), ALLOW), "invalid initial URI rejected");
            check(rejected.requests.isEmpty(), "invalid initial URL never creates a request");
        }

        String signed = "https://cdn.example/a%2Fb/./poster.png?X-Sig=a%2Fb%2Bc%3D&x=1&x=2+3&empty=";
        Fixture signatures = new Fixture();
        signatures.add(302, signed, new byte[0]);
        signatures.add(200, null, bytes("signed"));
        signatures.read(START, ALLOW);
        check(signatures.requests.get(1).getURI().toString().equals(signed), "signed raw path and duplicate ordered query parameters preserved");
        Fixture query = new Fixture();
        query.add(308, "?signature=a%2Fb%3D", new byte[0]);
        query.add(200, null, bytes("query"));
        query.read(START, ALLOW);
        check(query.requests.get(1).getURI().toString().equals("https://posters.example/start.png?signature=a%2Fb%3D"), "query-only redirect preserves current filename");
        for (HttpGet request : signatures.requests) {
            check(request.getFirstHeader("Cookie") == null && request.getFirstHeader("Authorization") == null
                    && request.getFirstHeader("Proxy-Authorization") == null && request.getFirstHeader("Referer") == null,
                    "request never forwards ambient or redirect credentials");
            check(!request.getConfig().isRedirectsEnabled() && !request.getConfig().isAuthenticationEnabled()
                    && !request.getConfig().isNormalizeUri() && !request.getConfig().isContentCompressionEnabled(),
                    "library redirect, authentication, normalization and decompression are disabled");
        }
    }

    private static void addressChecks() throws Exception {
        List<String> reserved = List.of("0.0.0.0", "0.1.2.3", "10.1.2.3", "100.64.0.1", "100.127.255.255",
                "127.0.0.1", "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.168.1.2", "192.0.0.1",
                "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.19.255.255", "198.51.100.1", "203.0.113.1",
                "224.0.0.1", "255.255.255.255", "::", "::1", "::ffff:127.0.0.1", "fc00::1", "fd12::1",
                "fe80::1", "fec0::1", "ff02::1", "64:ff9b::a00:1", "2001::a00:1", "2001:db8::1", "2002:0a00:0001::1", "3fff::1");
        for (String literal : reserved) {
            InetAddress address = InetAddress.getByName(literal);
            check(!PosterHttpTransport.publicAddress(address), "private/reserved address rejected: " + literal);
            Fixture fixture = new Fixture();
            fixture.dns.put("cdn.example", new InetAddress[]{address});
            fixture.add(302, "https://cdn.example/poster.png", new byte[0]);
            rejects(() -> fixture.read(START, ALLOW), "redirect DNS answer cannot target " + literal);
            check(fixture.requests.size() == 1, "private/reserved destination never receives a request");
        }
        for (String literal : List.of("1.1.1.1", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111", "2001:4860:4860::8888"))
            check(PosterHttpTransport.publicAddress(InetAddress.getByName(literal)), "public address accepted: " + literal);

        Fixture mixed = new Fixture();
        mixed.dns.put("cdn.example", new InetAddress[]{InetAddress.getByName("1.1.1.1"), InetAddress.getByName("127.0.0.1")});
        mixed.add(302, "https://cdn.example/poster.png", new byte[0]);
        rejects(() -> mixed.read(START, ALLOW), "one private answer invalidates the entire redirect DNS response");
        check(mixed.requests.size() == 1, "mixed DNS result creates no redirected connection");

        for (String name : List.of("localhost", "service.localhost", "printer.local", "intranet")) {
            Fixture local = new Fixture();
            local.add(302, "https://" + name + "/poster.png", new byte[0]);
            rejects(() -> local.read(START, ALLOW), "local DNS name rejected");
            check(local.lookups.equals(List.of("posters.example")), "local redirect name is not resolved");
        }

        Fixture directLan = new Fixture();
        directLan.dns.put("192.168.1.10", new InetAddress[]{InetAddress.getByName("192.168.1.10")});
        directLan.add(200, null, bytes("lan"));
        check(Arrays.equals(directLan.read(URI.create("http://192.168.1.10/poster.png"), ALLOW), bytes("lan")), "explicitly authorized original LAN source remains supported");
        Fixture lanRedirect = new Fixture();
        lanRedirect.dns.put("192.168.1.10", new InetAddress[]{InetAddress.getByName("192.168.1.10")});
        lanRedirect.add(302, "/other.png", new byte[0]);
        rejects(() -> lanRedirect.read(URI.create("http://192.168.1.10/poster.png"), ALLOW), "even same-host redirects into LAN are blocked");
        check(lanRedirect.requests.size() == 1, "original LAN permission does not exempt redirect targets");

        Fixture pinned = new Fixture();
        InetAddress checked = InetAddress.getByName("1.1.1.1");
        pinned.dns.put("cdn.example", new InetAddress[]{checked, InetAddress.getByName("8.8.8.8")});
        pinned.add(302, "https://cdn.example/poster.png", new byte[0]);
        pinned.add(200, null, bytes("pinned"));
        pinned.read(START, ALLOW);
        check(pinned.pinned.get(1).equals(checked) && pinned.lookups.equals(List.of("posters.example", "cdn.example")), "connection receives the checked DNS address with one lookup per hop");
    }

    private static void budgetChecks() throws Exception {
        Fixture large = new Fixture();
        byte[] content = new byte[9 * 1024 * 1024];
        content[content.length - 1] = 42;
        large.add(200, null, content);
        byte[] result = large.read(START, ALLOW);
        check(result.length == content.length && result[result.length - 1] == 42, "content above eight MiB succeeds within configurable budget");

        Fixture overflow = new Fixture();
        Reply dishonest = overflow.add(200, null, new byte[100]);
        dishonest.length = 2;
        rejects(() -> overflow.transport().fetch(START, ALLOW, () -> false, PosterHttpTransport.Budget.defaults(16)), "stream budget independent of Content-Length");
        check(dishonest.readBytes == 17 && overflow.closed == 1, "overflow reads only limit plus one byte and closes");
        Fixture declared = new Fixture();
        Reply tooLarge = declared.add(200, null, new byte[1]);
        tooLarge.length = Integer.MAX_VALUE;
        rejects(() -> declared.read(START, ALLOW), "declared oversized body rejected");
        check(tooLarge.readBytes == 0, "known oversized response body is not read");
        Fixture exact = new Fixture();
        exact.add(200, null, new byte[16]);
        check(exact.transport().fetch(START, ALLOW, () -> false, PosterHttpTransport.Budget.defaults(16)).length == 16, "exact byte budget allowed");
        Fixture encoded = new Fixture();
        encoded.add(200, null, new byte[1]).encoding = "gzip";
        rejects(() -> encoded.read(START, ALLOW), "HTTP compression cannot bypass input accounting");
        for (int status : List.of(201, 204, 300, 304, 305, 401, 403, 404, 500)) {
            Fixture unsuccessful = new Fixture();
            unsuccessful.add(status, null, bytes("not a poster"));
            rejects(() -> unsuccessful.read(START, ALLOW), "non-200 or unsupported redirect status rejected");
        }
        rejects(() -> new PosterHttpTransport.Budget(16, 6, 30_000, 5_000), "cannot raise redirect hard limit");
        rejects(() -> new PosterHttpTransport.Budget(16, 5, 30_001, 5_000), "cannot raise total deadline");
        rejects(() -> new PosterHttpTransport.Budget(16, 5, 30_000, 5_001), "cannot raise I/O timeout");
    }

    private static void cancellationChecks() throws Exception {
        Fixture early = new Fixture();
        rejects(() -> early.transport().fetch(START, ALLOW, () -> true, BUDGET), "pre-cancelled request fails immediately");
        check(early.requests.isEmpty() && early.lookups.isEmpty(), "pre-cancelled request performs no network operation");
        Fixture permission = new Fixture();
        AtomicBoolean cancelled = new AtomicBoolean();
        rejects(() -> permission.transport().fetch(START, (uri, control) -> { cancelled.set(true); return true; }, cancelled::get, BUDGET), "cancellation while permission is pending");
        check(permission.lookups.isEmpty(), "cancelled approval never starts DNS");
        Fixture body = new Fixture();
        cancelled.set(false);
        body.add(200, null, new byte[50]).onRead = () -> cancelled.set(true);
        rejects(() -> body.transport().fetch(START, ALLOW, cancelled::get, BUDGET), "cancellation during body read");
        check(body.closed == 1, "cancelled response closes");
        Fixture timedBody = new Fixture();
        timedBody.add(200, null, new byte[50]).onRead = () -> timedBody.nanos.addAndGet(TimeUnit.SECONDS.toNanos(31));
        rejects(() -> timedBody.read(START, ALLOW), "body cannot outlive total deadline");
        Fixture timedDns = new Fixture();
        timedDns.onResolve = () -> timedDns.nanos.addAndGet(TimeUnit.SECONDS.toNanos(6));
        rejects(() -> timedDns.read(START, ALLOW), "DNS is bounded by I/O budget");
        check(timedDns.requests.isEmpty(), "expired DNS never opens a connection");
        Fixture timedPermission = new Fixture();
        rejects(() -> timedPermission.read(START, (uri, control) -> {
            timedPermission.nanos.addAndGet(TimeUnit.SECONDS.toNanos(31)); return true;
        }), "permission time consumes the same total deadline");
        check(timedPermission.lookups.isEmpty(), "expired permission never resolves the target");
        Fixture readTimeout = new Fixture();
        readTimeout.add(200, null, new byte[1]).readFailure = new SocketTimeoutException("fixture I/O timeout");
        rejects(() -> readTimeout.read(START, ALLOW), "read timeout closes response and propagates");
        check(readTimeout.closed == 1, "read timeout releases connection");
        Thread.currentThread().interrupt();
        try { rejects(() -> new Fixture().read(START, ALLOW), "interrupted loader stops before network"); }
        finally { Thread.interrupted(); }
    }

    private static void errorChecks() throws Exception {
        for (int status : List.of(401, 403, 404, 410, 429, 503)) {
            Fixture fixture = new Fixture();
            fixture.add(status, null, new byte[0]);
            PosterFailure failure = failure(() -> fixture.read(START, ALLOW));
            PosterFailure.Code expected = status == 404 || status == 410 ? PosterFailure.Code.HTTP_NOT_FOUND
                    : status == 401 || status == 403 ? PosterFailure.Code.HTTP_DENIED : PosterFailure.Code.HTTP_STATUS;
            check(failure.code() == expected && Arrays.equals(failure.args(), new Object[]{status}), "HTTP status remains precise and user-facing: " + status);
        }
        Fixture oversized = new Fixture();
        oversized.add(200, null, new byte[1]).length = 100;
        PosterFailure declared = failure(() -> oversized.transport().fetch(START, ALLOW, () -> false, PosterHttpTransport.Budget.defaults(16)));
        check(declared.code() == PosterFailure.Code.DOWNLOAD_BUDGET && Arrays.equals(declared.args(), new Object[]{100L, 16L, false}), "declared download error reports exact bytes and budget");
        Fixture dishonest = new Fixture();
        dishonest.add(200, null, new byte[100]).length = -1;
        PosterFailure streamed = failure(() -> dishonest.transport().fetch(START, ALLOW, () -> false, PosterHttpTransport.Budget.defaults(16)));
        check(streamed.code() == PosterFailure.Code.DOWNLOAD_BUDGET && Arrays.equals(streamed.args(), new Object[]{17L, 16L, true}), "streamed download error accurately marks observed lower bound");
        Fixture unauthorized = new Fixture();
        check(failure(() -> unauthorized.read(START, (uri, control) -> false)).code() == PosterFailure.Code.PERMISSION, "permission denial is distinct from network failure");
        Fixture permissionTimeout = new Fixture();
        PosterFailure preserved = new PosterFailure(PosterFailure.Code.TIMEOUT);
        check(failure(() -> permissionTimeout.read(START, (uri, control) -> { throw preserved; })) == preserved, "authority error category survives the worker boundary");
        Fixture cancellation = new Fixture();
        check(failure(() -> cancellation.transport().fetch(START, ALLOW, () -> true, BUDGET)).code() == PosterFailure.Code.CANCELLED, "cancellation has its own category");
        Fixture readTimeout = new Fixture();
        readTimeout.add(200, null, new byte[1]).readFailure = new SocketTimeoutException("secret URL must not escape");
        PosterFailure timeout = failure(() -> readTimeout.read(START, ALLOW));
        check(timeout.code() == PosterFailure.Code.TIMEOUT && timeout.args().length == 0 && timeout.getCause() == null, "socket timeout excludes secret low-level error text");
        Fixture network = new Fixture();
        network.add(200, null, new byte[1]).readFailure = new IOException("secret token");
        check(failure(() -> network.read(START, ALLOW)).code() == PosterFailure.Code.NETWORK, "I/O errors remain network failures rather than invented HTTP statuses");
        Fixture longUrl = new Fixture();
        check(failure(() -> longUrl.read(URI.create("https://posters.example/" + "x".repeat(2048)), ALLOW)).code() == PosterFailure.Code.INVALID_REFERENCE,
                "overlong URI fails before packet serialization");
        check(longUrl.lookups.isEmpty(), "overlong URI is never resolved");
    }

    private static void loopbackChecks() throws Exception {
        AtomicInteger ambientCalls = new AtomicInteger(), challengeRequests = new AtomicInteger();
        AtomicReference<com.sun.net.httpserver.Headers> receivedHeaders = new AtomicReference<>();
        AtomicReference<String> receivedTarget = new AtomicReference<>();
        CountDownLatch stalled = new CountDownLatch(1), release = new CountDownLatch(1);
        CookieHandler cookies = CookieHandler.getDefault();
        Authenticator authenticator = Authenticator.getDefault();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        var workers = Executors.newCachedThreadPool(task -> { Thread thread = new Thread(task, "poster-loopback-check"); thread.setDaemon(true); return thread; });
        server.setExecutor(workers);
        server.createContext("/signed", exchange -> {
            receivedHeaders.set(exchange.getRequestHeaders()); receivedTarget.set(exchange.getRequestURI().toString());
            byte[] data = bytes("local pixels");
            exchange.getResponseHeaders().add("Set-Cookie", "sensitive=do-not-forward");
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) { output.write(data); }
        });
        server.createContext("/challenge", exchange -> {
            challengeRequests.incrementAndGet();
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"poster-test\"");
            exchange.sendResponseHeaders(401, -1); exchange.close();
        });
        server.createContext("/stall", exchange -> {
            stalled.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            CookieHandler.setDefault(new CookieHandler() {
                @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
                    ambientCalls.incrementAndGet(); return Map.of("Cookie", List.of("sensitive=ambient"));
                }
                @Override public void put(URI uri, Map<String, List<String>> headers) { ambientCalls.incrementAndGet(); }
            });
            Authenticator.setDefault(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    ambientCalls.incrementAndGet(); return new PasswordAuthentication("ambient", "secret".toCharArray());
                }
            });
            String target = "/signed/a%2Fb/./poster.png?sig=a%2Fb%3D&x=1&x=2+3";
            byte[] result = PosterHttpTransport.read(URI.create(base + target), ALLOW, () -> false, BUDGET);
            check(Arrays.equals(result, bytes("local pixels")), "real Apache client reads explicitly approved loopback source");
            check(receivedTarget.get().equals(target), "real wire target preserves signed path and query exactly");
            for (String header : List.of("Cookie", "Authorization", "Proxy-Authorization", "Referer"))
                check(receivedHeaders.get().getFirst(header) == null, "real request excludes " + header);
            rejects(() -> PosterHttpTransport.read(URI.create(base + "/challenge"), ALLOW, () -> false, BUDGET), "real authentication challenge is rejected");
            check(challengeRequests.get() == 1 && ambientCalls.get() == 0, "no ambient CookieHandler or Authenticator and no authentication retry");

            AtomicBoolean cancelled = new AtomicBoolean();
            var pending = workers.submit(() -> {
                try { PosterHttpTransport.read(URI.create(base + "/stall"), ALLOW, cancelled::get, BUDGET); return false; }
                catch (IOException expected) { return true; }
            });
            check(stalled.await(2, TimeUnit.SECONDS), "real stalled request reaches local fixture");
            cancelled.set(true);
            check(pending.get(2, TimeUnit.SECONDS), "cancellation aborts a blocked real header read promptly");
            long started = System.nanoTime();
            rejects(() -> PosterHttpTransport.read(URI.create(base + "/stall"), ALLOW, () -> false,
                    new PosterHttpTransport.Budget(1024, 5, 150, 5000)), "total deadline aborts a real stalled header read");
            check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "total deadline is independent of longer I/O timeout");
        } finally {
            CookieHandler.setDefault(cookies); Authenticator.setDefault(authenticator);
            release.countDown(); server.stop(0); workers.shutdownNow();
        }
    }

    private static final class Fixture {
        final AtomicLong nanos = new AtomicLong();
        final Map<String, InetAddress[]> dns = new HashMap<>();
        final List<String> lookups = new ArrayList<>();
        final List<HttpGet> requests = new ArrayList<>();
        final List<InetAddress> pinned = new ArrayList<>();
        final Queue<Reply> replies = new ArrayDeque<>();
        Runnable onResolve = () -> {};
        int closed;
        Reply add(int status, String location, byte[] body) {
            Reply reply = new Reply(this, status, location, body); replies.add(reply); return reply;
        }
        PosterHttpTransport transport() {
            return new PosterHttpTransport(host -> {
                lookups.add(host); onResolve.run();
                InetAddress[] answer = dns.get(host);
                return answer == null ? new InetAddress[]{InetAddress.getByAddress(new byte[]{1, 1, 1, 1})} : answer;
            }, (request, address, control, budget) -> {
                control.check(); requests.add(request); pinned.add(address);
                Reply reply = replies.poll();
                if (reply == null) throw new AssertionError("Unexpected HTTP request: " + request.getURI());
                return reply;
            }, nanos::get);
        }
        byte[] read(URI initial, PosterHttpTransport.Authority authority) throws IOException {
            return transport().fetch(initial, authority, () -> false, BUDGET);
        }
    }
    private static final class Reply implements PosterHttpTransport.Exchange {
        final Fixture owner;
        final int status;
        final String location;
        final byte[] data;
        long length;
        String encoding;
        Runnable onRead = () -> {};
        IOException readFailure;
        int readBytes;
        Reply(Fixture owner, int status, String location, byte[] data) {
            this.owner = owner; this.status = status; this.location = location; this.data = data; this.length = data.length;
        }
        @Override public int status() { return status; }
        @Override public String location() { return location; }
        @Override public long contentLength() { return length; }
        @Override public String contentEncoding() { return encoding; }
        @Override public InputStream body() {
            ByteArrayInputStream source = new ByteArrayInputStream(data);
            return new InputStream() {
                @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
                @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                    if (readFailure != null) throw readFailure;
                    int got = source.read(bytes, offset, count);
                    if (got > 0) { readBytes += got; onRead.run(); }
                    return got;
                }
            };
        }
        @Override public void close() { owner.closed++; }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void rejects(Throwing action, String message) throws Exception {
        try { action.run(); }
        catch (IOException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static PosterFailure failure(Throwing action) throws Exception {
        try { action.run(); }
        catch (PosterFailure expected) { return expected; }
        throw new AssertionError("Expected a classified poster failure");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
