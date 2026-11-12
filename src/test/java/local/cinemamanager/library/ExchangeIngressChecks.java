package local.cinemamanager.library;

/** Exercises bounded queued work independently of Minecraft and networking. */
public final class ExchangeIngressChecks {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        ExchangeIngress ingress = new ExchangeIngress(2, 3, 4, 1000);
        Object a = new Object(), b = new Object();
        var first = ingress.acquire(a, 0); var second = ingress.acquire(a, 0);
        check(first != null && second != null && ingress.acquire(a, 0) == null, "connection cannot overfill server queue");
        var third = ingress.acquire(b, 0);
        check(third != null && ingress.acquire(b, 0) == null, "global pending work remains bounded");
        first.close(); first.close();
        var fourth = ingress.acquire(a, 0);
        check(fourth != null && ingress.acquire(b, 0) == null, "idempotent permit close never undercounts global work");
        fourth.close(); second.close(); third.close();
        var last = ingress.acquire(a, 0); check(last != null, "fourth admitted packet in window"); last.close();
        check(ingress.acquire(a, 0) == null && ingress.acquire(a, 999) == null, "closing queued work does not bypass packet rate");
        try (var reset = ingress.acquire(a, 1000)) { check(reset != null, "new window permits work again"); }
        try (var backward = ingress.acquire(a, -1)) { check(backward != null, "clock rollover resets the window safely"); }
        ExchangeIngress transfer = new ExchangeIngress(2, 16, 256, 1000);
        for (int i = 0; i < 200; i++) try (var permit = transfer.acquire(a, 0)) { check(permit != null, "one maximum-size stop-and-wait upload is not self-rate-limited"); }
        System.out.println("PASS: " + checks + " exchange ingress assertions; queue, rate, release and transfer bounds.");
    }
}
