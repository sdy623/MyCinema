package local.cinemamanager.client;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.MessageConstraints;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.impl.conn.DefaultRoutePlanner;
import org.apache.http.impl.conn.DefaultSchemePortResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static local.cinemamanager.client.PosterFailure.Code.*;

/**
 * Bounded HTTP image bytes only. This class has no Minecraft, GUI, decoder or server dependency.
 * Each request needs a fresh caller-supplied server authorization. Only the explicitly approved
 * initial URL may resolve to a LAN address; every redirect destination must be publicly routable.
 */
public final class PosterHttpTransport {
    public static final int DEFAULT_MAX_BYTES = 64 * 1024 * 1024;
    public static final int MAX_REDIRECTS = 5;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_HEADER_LINE_LENGTH = 8192;
    private static final ThreadPoolExecutor STAGES = stages();
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();
    private static final PosterHttpTransport DEFAULT = new PosterHttpTransport(
            InetAddress::getAllByName, PosterHttpTransport::openApache, System::nanoTime);

    /** May wait for a server reply, but must use control when doing so. Runs on a bounded worker. */
    @FunctionalInterface public interface Authority {
        boolean allow(URI target, Control control) throws IOException;
    }

    /** Guards resources, not image validity. The decoder has its own allocation budget. */
    public record Budget(int maxBytes, int maxRedirects, int totalTimeoutMillis, int ioTimeoutMillis) {
        public Budget {
            if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE - 8 || maxRedirects < 0 || maxRedirects > MAX_REDIRECTS
                    || totalTimeoutMillis < 1 || totalTimeoutMillis > 30_000
                    || ioTimeoutMillis < 1 || ioTimeoutMillis > 5_000)
                throw new IllegalArgumentException("Invalid poster HTTP resource budget");
        }
        public static Budget defaults(int maxBytes) { return new Budget(maxBytes, MAX_REDIRECTS, 30_000, 5_000); }
        public static Budget defaults() { return defaults(DEFAULT_MAX_BYTES); }
    }

    /** Shared cancellation/deadline for permission, DNS, connection, headers and body. */
    public static final class Control {
        private final BooleanSupplier cancelled;
        private final LongSupplier clock;
        private final Thread owner;
        private final long deadline;

        private Control(BooleanSupplier cancelled, LongSupplier clock, int timeoutMillis) {
            this.cancelled = cancelled;
            this.clock = clock;
            this.owner = Thread.currentThread();
            this.deadline = clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        }
        public void check() throws IOException {
            if (owner.isInterrupted() || Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
                throw new PosterFailure(CANCELLED);
            if (deadline - clock.getAsLong() <= 0) throw new PosterFailure(TIMEOUT);
        }
        public int remainingMillis() throws IOException {
            check();
            long nanos = deadline - clock.getAsLong();
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(nanos)));
        }
    }

    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    @FunctionalInterface interface Connector {
        Exchange open(HttpGet request, InetAddress pinnedAddress, Control control, Budget budget) throws IOException;
    }
    interface Exchange extends AutoCloseable {
        int status();
        String location() throws IOException;
        long contentLength();
        String contentEncoding();
        InputStream body() throws IOException;
        @Override void close() throws IOException;
    }
    private final Resolver resolver;
    private final Connector connector;
    private final LongSupplier clock;

    /** Package-private injection point for deterministic fixtures; production always uses Apache. */
    PosterHttpTransport(Resolver resolver, Connector connector, LongSupplier clock) {
        this.resolver = Objects.requireNonNull(resolver);
        this.connector = Objects.requireNonNull(connector);
        this.clock = Objects.requireNonNull(clock);
    }

    public static byte[] read(URI initial, Authority authority, BooleanSupplier cancelled, int maxBytes) throws IOException {
        return read(initial, authority, cancelled, Budget.defaults(maxBytes));
    }
    public static byte[] read(URI initial, Authority authority, BooleanSupplier cancelled, Budget budget) throws IOException {
        return DEFAULT.fetch(initial, authority, cancelled, budget);
    }

    byte[] fetch(URI initial, Authority authority, BooleanSupplier cancelled, Budget budget) throws IOException {
        Objects.requireNonNull(authority, "Every HTTP poster requires a server authority");
        Objects.requireNonNull(budget);
        Control control = new Control(Objects.requireNonNull(cancelled), clock, budget.totalTimeoutMillis());
        try { return fetch(initial, authority, budget, control); }
        catch (PosterFailure known) { throw known; }
        catch (IOException failure) {
            control.check(); // An abort can surface as an ordinary socket exception.
            if (failure instanceof SocketTimeoutException || failure instanceof ConnectTimeoutException)
                throw new PosterFailure(TIMEOUT);
            throw new PosterFailure(NETWORK);
        }
    }

    private byte[] fetch(URI initial, Authority authority, Budget budget, Control control) throws IOException {
        URI current = validate(initial);
        Set<String> visited = new HashSet<>();
        for (int redirects = 0; ; redirects++) {
            control.check();
            if (!visited.add(loopKey(current))) throw new PosterFailure(REDIRECT_LOOP);
            URI requested = current;
            if (!stage(() -> authority.allow(requested, control), control, control.remainingMillis()))
                throw new PosterFailure(PERMISSION);
            control.check();
            String host = dnsHost(current);
            if (redirects > 0 && localName(host)) throw new PosterFailure(REDIRECT_DISALLOWED);
            InetAddress[] addresses = stage(() -> resolver.resolve(host), control, budget.ioTimeoutMillis());
            if (addresses == null || addresses.length == 0) throw new UnknownHostException("Poster host has no addresses");
            for (InetAddress address : addresses) {
                if (address == null || (redirects > 0 && !publicAddress(address)))
                    throw new PosterFailure(REDIRECT_DISALLOWED);
            }
            // The HTTP library receives this checked address, never another hostname lookup.
            InetAddress pinnedAddress = addresses[0];
            control.check();
            HttpGet request = request(current, control, budget);
            try (Exchange response = connector.open(request, pinnedAddress, control, budget)) {
                control.check();
                int status = response.status();
                if (redirect(status)) {
                    if (redirects >= budget.maxRedirects()) throw new PosterFailure(REDIRECT_LIMIT, budget.maxRedirects());
                    URI target = redirectTarget(current, response.location());
                    if (current.getScheme().equals("https") && target.getScheme().equals("http"))
                        throw new PosterFailure(REDIRECT_DISALLOWED);
                    current = target;
                    continue;
                }
                if (status != 200) throw new PosterFailure(status == 404 || status == 410 ? HTTP_NOT_FOUND
                        : status == 401 || status == 403 ? HTTP_DENIED : HTTP_STATUS, status);
                if (response.contentLength() > budget.maxBytes())
                    throw new PosterFailure(DOWNLOAD_BUDGET, response.contentLength(), (long) budget.maxBytes(), false);
                String encoding = response.contentEncoding();
                if (encoding != null && !encoding.isBlank() && !encoding.equalsIgnoreCase("identity"))
                    throw new PosterFailure(UNSUPPORTED_FORMAT);
                // Exchange.close aborts before closing streams, so rejected content is never drained.
                return bounded(response.body(), budget.maxBytes(), control);
            } finally {
                // This is also used by the scheduled watchdog during a blocked socket read.
                request.abort();
            }
        }
    }

    private static URI validate(URI uri) throws IOException {
        if (uri == null) throw new PosterFailure(INVALID_REFERENCE);
        String raw = uri.toString();
        if (raw.length() > MAX_URL_LENGTH || raw.chars().anyMatch(c -> c <= 32 || c == 127))
            throw new PosterFailure(INVALID_REFERENCE);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                || uri.isOpaque() || uri.getRawUserInfo() != null || uri.getHost() == null
                || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getHost().contains("%"))
            throw new PosterFailure(INVALID_REFERENCE);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isBlank()) throw new PosterFailure(INVALID_REFERENCE);
        // Rebuild using raw components: URI's multi-argument constructor would double-escape signatures.
        String path = uri.getRawPath();
        String normalized = scheme.toLowerCase(Locale.ROOT) + "://" + host
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                + (path == null || path.isEmpty() ? "/" : path)
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        try { return URI.create(normalized); }
        catch (IllegalArgumentException invalid) { throw new PosterFailure(INVALID_REFERENCE); }
    }

    private static URI redirectTarget(URI source, String location) throws IOException {
        if (location == null || location.isBlank() || location.length() > MAX_URL_LENGTH
                || location.chars().anyMatch(c -> c <= 32 || c == 127))
            throw new PosterFailure(REDIRECT_DISALLOWED);
        try {
            URI relative = URI.create(location);
            // java.net.URI.resolve("?x") incorrectly drops the current final path segment for HTTP.
            if (location.startsWith("?")) {
                String base = source.toString();
                int query = base.indexOf('?');
                return validate(URI.create((query < 0 ? base : base.substring(0, query)) + location));
            }
            return validate(source.resolve(relative));
        } catch (IllegalArgumentException | PosterFailure invalid) { throw new PosterFailure(REDIRECT_DISALLOWED); }
    }

    private static String loopKey(URI uri) {
        int port = uri.getPort() < 0 ? (uri.getScheme().equals("https") ? 443 : 80) : uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + ":" + port + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }
    private static String dnsHost(URI uri) {
        String host = uri.getHost();
        return host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    }
    private static boolean localName(String host) {
        return host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.equals("local") || host.indexOf('.') < 0 && host.indexOf(':') < 0;
    }
    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** Conservative global-unicast check, including IPv4-mapped IPv6 and tunneling ranges. */
    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a == 100 && c >= 64 && c <= 127)
                    && !(a == 169 && c == 254) && !(a == 172 && c >= 16 && c <= 31)
                    && !(a == 192 && (c == 168 || c == 0 && (d == 0 || d == 2) || c == 88 && d == 99))
                    && !(a == 198 && (c == 18 || c == 19 || c == 51 && d == 100))
                    && !(a == 203 && c == 0 && d == 113);
        }
        if (b.length != 16 || (b[0] & 0xe0) != 0x20) return false; // Currently allocated IPv6 global unicast.
        int second = b[1] & 255, third = b[2] & 255, fourth = b[3] & 255;
        return !(b[0] == 0x20 && second == 1 && (third < 2 || third == 0x0d && fourth == 0xb8))
                && !(b[0] == 0x20 && second == 2) // 6to4 can embed a private IPv4 destination.
                && !(b[0] == 0x3f && second == 0xff && (third & 0xf0) == 0); // Documentation.
    }

    private static byte[] bounded(InputStream body, int maxBytes, Control control) throws IOException {
        // A doubling buffer can momentarily hold old + new capacity, then another final copy.
        // Fixed segments keep the peak to payload + exact returned array + one small segment.
        List<byte[]> completed = new ArrayList<>();
        byte[] segment = new byte[Math.min(64 * 1024, maxBytes)];
        int used = 0, total = 0;
        for (;;) {
            control.check();
            if (total == maxBytes) {
                int extra = body.read();
                control.check();
                if (extra >= 0) throw new PosterFailure(DOWNLOAD_BUDGET, (long) total + 1, (long) maxBytes, true);
                return assemble(completed, segment, used, total, control);
            }
            if (used == segment.length) {
                completed.add(segment);
                segment = new byte[Math.min(64 * 1024, maxBytes - total)];
                used = 0;
            }
            int amount = body.read(segment, used, segment.length - used);
            control.check();
            if (amount < 0) return assemble(completed, segment, used, total, control);
            used += amount;
            total += amount;
        }
    }

    private static byte[] assemble(List<byte[]> completed, byte[] last, int used, int total, Control control) throws IOException {
        control.check();
        byte[] result = new byte[total];
        int position = 0;
        for (byte[] segment : completed) {
            control.check();
            System.arraycopy(segment, 0, result, position, segment.length);
            position += segment.length;
        }
        System.arraycopy(last, 0, result, position, used);
        control.check();
        return result;
    }

    private static <T> T stage(Callable<T> work, Control control, int timeoutMillis) throws IOException {
        control.check();
        long deadline = control.clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Future<T> future;
        try { future = STAGES.submit(work); }
        catch (RejectedExecutionException busy) { throw new PosterFailure(UNAVAILABLE); }
        try {
            for (;;) {
                control.check();
                long left = Math.min(deadline - control.clock.getAsLong(), TimeUnit.MILLISECONDS.toNanos(control.remainingMillis()));
                if (left <= 0) throw new PosterFailure(TIMEOUT);
                try {
                    T result = future.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                    control.check();
                    if (deadline - control.clock.getAsLong() <= 0)
                        throw new PosterFailure(TIMEOUT);
                    return result;
                } catch (TimeoutException pending) { /* Poll cancellation without blocking the render thread. */ }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PosterFailure(CANCELLED);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            throw new PosterFailure(NETWORK);
        } finally {
            future.cancel(true);
            if (future instanceof Runnable queued) STAGES.remove(queued);
        }
    }

    private static HttpGet request(URI uri, Control control, Budget budget) throws IOException {
        int timeout = Math.min(budget.ioTimeoutMillis(), control.remainingMillis());
        HttpGet request = new HttpGet(uri);
        request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).setAuthenticationEnabled(false)
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES).setContentCompressionEnabled(false).setNormalizeUri(false)
                .setConnectTimeout(timeout).setSocketTimeout(timeout).setConnectionRequestTimeout(timeout).build());
        request.setHeader("Accept", "image/*");
        request.setHeader("Accept-Encoding", "identity");
        request.setHeader("User-Agent", "MyCinema-StaticPoster");
        request.setHeader("Connection", "close");
        return request;
    }

    /** The only production HTTP implementation; Apache performs HTTP parsing and verified TLS. */
    private static Exchange openApache(HttpGet request, InetAddress address, Control control, Budget budget) throws IOException {
        String host = dnsHost(request.getURI());
        DnsResolver pinnedDns = requested -> {
            String compared = requested.startsWith("[") && requested.endsWith("]")
                    ? requested.substring(1, requested.length() - 1) : requested;
            if (!compared.equalsIgnoreCase(host)) throw new UnknownHostException("Unexpected poster host");
            return new InetAddress[]{address};
        };
        var sockets = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", SSLConnectionSocketFactory.getSocketFactory()).build();
        var manager = new BasicHttpClientConnectionManager(sockets, null, DefaultSchemePortResolver.INSTANCE, pinnedDns);
        manager.setConnectionConfig(ConnectionConfig.custom().setMessageConstraints(MessageConstraints.custom()
                .setMaxHeaderCount(64).setMaxLineLength(MAX_HEADER_LINE_LENGTH).build()).build());
        manager.setSocketConfig(SocketConfig.custom().setSoTimeout(Math.min(budget.ioTimeoutMillis(), control.remainingMillis())).build());
        CloseableHttpClient client = HttpClients.custom().setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .disableRedirectHandling().disableCookieManagement().disableAuthCaching()
                .disableAutomaticRetries().disableContentCompression().disableConnectionState().build();
        ScheduledFuture<?> watch = WATCHDOG.scheduleAtFixedRate(() -> {
            try { control.check(); }
            catch (IOException cancelled) { request.abort(); }
        }, 0, 50, TimeUnit.MILLISECONDS);
        try {
            control.check();
            CloseableHttpResponse response = client.execute(request);
            return new Exchange() {
                @Override public int status() { return response.getStatusLine().getStatusCode(); }
                @Override public String location() throws IOException {
                    Header[] locations = response.getHeaders("Location");
                    if (locations.length != 1) throw new PosterFailure(REDIRECT_DISALLOWED);
                    return locations[0].getValue();
                }
                @Override public long contentLength() { return response.getEntity() == null ? 0 : response.getEntity().getContentLength(); }
                @Override public String contentEncoding() {
                    Header encoding = response.getFirstHeader("Content-Encoding");
                    return encoding == null ? null : encoding.getValue();
                }
                @Override public InputStream body() throws IOException {
                    HttpEntity entity = response.getEntity();
                    return entity == null ? InputStream.nullInputStream() : entity.getContent();
                }
                @Override public void close() throws IOException {
                    watch.cancel(false);
                    request.abort(); // Never drain an untrusted redirect body on close.
                    try { response.close(); } finally { client.close(); }
                }
            };
        } catch (IOException | RuntimeException failure) {
            watch.cancel(false);
            request.abort();
            client.close();
            throw failure;
        }
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }
    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> daemon(task, "MyCinema-poster-cancel"));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    private static ThreadPoolExecutor stages() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), task -> daemon(task, "MyCinema-poster-permission-dns"));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
