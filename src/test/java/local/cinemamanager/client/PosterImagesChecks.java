package local.cinemamanager.client;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;

/** Headless, in-memory codec checks. Never opens a network connection, a window or a player. */
public final class PosterImagesChecks {
    private static int checks;
    private static final String WEBP = "UklGRhwAAABXRUJQVlA4TA8AAAAvH8ADAAcQ/Y/+ByKi/wEA";
    private static final String LOSSY_WEBP = "UklGRkwAAABXRUJQVlA4IEAAAABQAwCdASogABAAPjEUiEKiISEYBAAgAwSgB2APwA/AABEQAAD+96KX//16JSql/ew///wsz+Fmfwsz/4TgAAAA";
    private static final String ANIMATED_WEBP = "UklGRuQAAABXRUJQVlA4WAoAAAACAAAAHwAADwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAAJWUDhMDwAAAC8fwAMABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAABWUDhMDwAAAC8fwAMAB9D/iP4HIqL/AQBBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAABWUDhMDwAAAC8fwAMABxDR//4HIqL/AQBBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAABWUDhMEAAAAC8fwAMAB9D//kf/AxHR/wA=";
    private static final String APNG = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAQCAYAAAB3AH1ZAAAACGFjVEwAAAAEAAAAAHzNZtAAAAAaZmNUTAAAAAAAAAAgAAAAEAAAAAAAAAAAAAEABQAAXdzFBgAAACZJREFUeJztzkEBAAAMgkC2/p01hh8owF0gDPvlXIAAAQIECBAAUJTlAh5GTHP7AAAAGmZjVEwAAAABAAAAIAAAABAAAAAAAAAAAAABAAUAAMavL9IAAAAqZmRBVAAAAAJ4nO3OQQEAAAyCQLb+nTWGHyjAHSEM++VcgAABAgQIEABQk+YCHuAmPmoAAAAaZmNUTAAAAAMAAAAgAAAAEAAAAAAAAAAAAAEABQAAKzn8OwAAACpmZEFUAAAABHic7c5BAQAADIJAtv6dNYYfKMAdJAz75VyAAAECBAgQAFCS5wIeweVtaAAAABpmY1RMAAAABQAAACAAAAAQAAAAAAAAAAAAAQAFAADG845BAAAAKmZkQVQAAAAGeJztzkEBAAAMgkC2/p01hh8owF1CGPbLuQABAgQIECAAoJtPAx1Xn0ZzAAAAAElFTkSuQmCC";

    public static void main(String[] args) throws Exception {
        BufferedImage pngImage = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        pngImage.setRGB(0, 0, 0xffff0000);
        pngImage.setRGB(1, 0, 0x800000ff);
        byte[] png = image(pngImage, "png");
        PosterImages.Decoded decoded = PosterImages.decode(png);
        check(decoded.width() == 2 && decoded.height() == 1, "PNG dimensions");
        check(decoded.abgr()[0] == 0xff0000ff, "PNG red is converted from BGRA to NativeImage ABGR");
        check(decoded.abgr()[1] == 0x80ff0000, "PNG blue and alpha are retained");
        metadataChecks(png);

        BufferedImage jpegImage = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) jpegImage.setRGB(x, y, 0xff0000);
        byte[] jpeg = image(jpegImage, "jpeg");
        decoded = PosterImages.decode(jpeg);
        check(decoded.width() == 8 && decoded.height() == 8, "JPEG dimensions");
        check((decoded.abgr()[0] & 255) > 230 && (decoded.abgr()[0] >>> 16 & 255) < 20, "JPEG requests packed BGRA and retains red");
        for (String encoded : new String[]{WEBP, LOSSY_WEBP, ANIMATED_WEBP, APNG}) {
            decoded = PosterImages.decode(Base64.getDecoder().decode(encoded));
            check(decoded.width() == 32 && decoded.height() == 16 && decoded.abgr().length == 512, "WebP/APNG decodes exactly one canvas frame");
        }
        byte[] largePng = png.clone();
        ByteBuffer.wrap(largePng).putInt(16, 8193);
        rejects(() -> PosterImages.dimensions(largePng), "oversized PNG rejected before codec construction");
        byte[] zeroPng = png.clone();
        ByteBuffer.wrap(zeroPng).putInt(20, 0);
        rejects(() -> PosterImages.dimensions(zeroPng), "zero PNG height rejected");
        byte[] duplicatePng = new byte[png.length + 25];
        System.arraycopy(png, 0, duplicatePng, 0, 33);
        System.arraycopy(png, 8, duplicatePng, 33, 25);
        System.arraycopy(png, 33, duplicatePng, 58, png.length - 33);
        rejects(() -> PosterImages.dimensions(duplicatePng), "duplicate PNG header cannot bypass dimension preflight");
        byte[] largeJpeg = jpeg.clone();
        for (int p = 0; p < largeJpeg.length - 9; p++) if ((largeJpeg[p] & 255) == 255 && (largeJpeg[p + 1] & 255) == 192) {
            largeJpeg[p + 7] = 32;
            largeJpeg[p + 8] = 1;
            break;
        }
        rejects(() -> PosterImages.dimensions(largeJpeg), "oversized JPEG rejected before codec construction");
        byte[] largeWebp = Base64.getDecoder().decode(ANIMATED_WEBP);
        largeWebp[24] = 0;
        largeWebp[25] = 32; // VP8X stores width minus one.
        rejects(() -> PosterImages.dimensions(largeWebp), "oversized WebP canvas rejected before codec construction");
        byte[] badWebp = Base64.getDecoder().decode(WEBP);
        badWebp[16] = 127;
        rejects(() -> PosterImages.dimensions(badWebp), "truncated WebP chunk rejected");
        rejects(() -> PosterImages.decode(new byte[PosterImages.MAX_INPUT_BYTES + 1]), "input budget is enforced independently of headers");
        check(PosterImages.readBounded(new ByteArrayInputStream(png)).length == png.length, "bounded reader retains valid body");
        rejects(() -> PosterImages.readBounded(new ByteArrayInputStream(new byte[1025]),1024,()->false), "oversized body rejected without trusting Content-Length");
        rejects(() -> PosterImages.decode("<html>not an image</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "HTML is rejected without invoking a media player");
        rejects(() -> PosterImages.decode("GIF89a1234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), "unsupported format is explicit");

        check(PosterImages.source("https://example.com/poster.png?size=large").uri().getScheme().equals("https"), "custom HTTPS host accepted by client syntax checks");
        check(PosterImages.source("http://192.168.1.10/poster.png").uri().getHost().equals("192.168.1.10"), "LAN poster hosting is not arbitrarily blocked");
        check(PosterImages.source("posters/title.png").uri().getScheme().equals("file"), "relative local file remains a client-local file");
        check(PosterImages.source("file:///tmp/title.png").uri().getScheme().equals("file"), "explicit client-local file accepted syntactically");
        check(PosterImages.source("resource:mycinema:textures/poster.png").resource().getNamespace().equals("mycinema"), "resource-pack reference supported");
        rejects(() -> PosterImages.source("https://username:secret@example.com/poster.png"), "credentials in URI rejected");
        rejects(() -> PosterImages.source("file://remote/share/poster.png"), "network file share rejected consistently with WF5");
        rejects(() -> PosterImages.source("resource:mycinema:../private.png"), "resource traversal rejected");
        rejects(() -> PosterImages.source("water://video-id"), "media resolver protocol never creates a player");
        rejects(() -> PosterImages.source("https://example.com/poster.png\n"), "control characters rejected before trimming");
        System.out.println("PosterImages checks passed: " + checks);
    }

    private static void metadataChecks(byte[] png) throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            byte[] repeated = new byte[8192];
            Arrays.fill(repeated, (byte) 'a');
            for (int i = 0; i < 128; i++) deflater.write(repeated); // Encode 1 MiB once; never inflate it.
        }
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        text.write("Comment\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        text.write(compressed.toByteArray());
        byte[] manyTexts = pngChunks(png, pngChunk("zTXt", text.toByteArray()), 300);
        byte[] sanitized = PosterImages.sanitize(manyTexts);
        check(manyTexts.length < PosterImages.MAX_INPUT_BYTES, "many compressed text chunks fit below the transport cap");
        check(Arrays.equals(sanitized, png), "300 zTXt chunks are removed byte-for-byte without decompressing any string");
        // Only the already-verified sanitized bytes reach the decoder in this regression test.
        check(PosterImages.decode(sanitized).abgr()[0] == 0xff0000ff, "metadata stripping preserves poster pixels");
        for (String type : new String[]{"iTXt", "iCCP", "tEXt", "eXIf", "vpAg"}) {
            byte[] invalidMetadata = pngChunks(png, pngChunk(type, new byte[]{1, 2, 3}), 1);
            check(Arrays.equals(PosterImages.sanitize(invalidMetadata), png), type + " is never parsed by WATERMedia");
            check(PosterImages.decode(invalidMetadata).abgr()[1] == 0x80ff0000, type + " cannot change the retained pixels");
        }
        byte[] tooMany = pngChunks(png, pngChunk("tEXt", new byte[0]), PosterImages.MAX_CONTAINER_CHUNKS);
        rejects(() -> PosterImages.sanitize(tooMany), "PNG chunk count is bounded before metadata parsing");
        byte[] unknownCritical = pngChunks(png, pngChunk("ABCD", new byte[0]), 1);
        rejects(() -> PosterImages.sanitize(unknownCritical), "unknown critical PNG chunks remain errors");
        byte[] oversizedGamma = pngChunks(png, pngChunk("gAMA", new byte[5]), 1);
        rejects(() -> PosterImages.sanitize(oversizedGamma), "retained rendering metadata has fixed size bounds");
        byte[] gamma = pngChunk("gAMA", new byte[]{0, 0, (byte) 177, (byte) 143});
        rejects(() -> PosterImages.sanitize(pngChunks(png, gamma, 2)), "singleton rendering metadata cannot accumulate");

        IndexColorModel palette = new IndexColorModel(8, 2, new byte[]{(byte) 255, 0}, new byte[]{0, 0},
                new byte[]{0, (byte) 255}, new byte[]{(byte) 255, 64});
        BufferedImage indexed = new BufferedImage(2, 1, BufferedImage.TYPE_BYTE_INDEXED, palette);
        indexed.getRaster().setSample(0, 0, 0, 0);
        indexed.getRaster().setSample(1, 0, 0, 1);
        PosterImages.Decoded indexedResult = PosterImages.decode(image(indexed, "png"));
        check(indexedResult.abgr()[0] == 0xff0000ff && indexedResult.abgr()[1] == 0x40ff0000,
                "PNG PLTE palette and tRNS alpha survive metadata sanitization");

        byte[] animated = Base64.getDecoder().decode(ANIMATED_WEBP);
        byte[] extended = webpChunks(animated, "XMP ", "<dc:creator><rdf:li>unused</rdf:li></dc:creator>".getBytes(java.nio.charset.StandardCharsets.UTF_8), 20);
        check(Arrays.equals(PosterImages.sanitize(extended), animated), "WebP XMP is stripped without parsing author lists");
        for (String type : new String[]{"ICCP", "EXIF"}) {
            byte[] withProfile = webpChunks(animated, type, new byte[]{1, 2, 3}, 2);
            check(Arrays.equals(PosterImages.sanitize(withProfile), animated), "WebP " + type + " is stripped with RIFF size repaired");
        }
        byte[] manyWebpChunks = webpChunks(animated, "XMP ", new byte[0], PosterImages.MAX_CONTAINER_CHUNKS);
        rejects(() -> PosterImages.sanitize(manyWebpChunks), "WebP chunk count is bounded before metadata parsing");
    }

    private static byte[] pngChunk(String type, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(ByteBuffer.allocate(4).putInt(body.length).array());
        out.write(name);
        out.write(body);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(body);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        return out.toByteArray();
    }
    private static byte[] pngChunks(byte[] png, byte[] chunk, int copies) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, 33); // Between IHDR and IDAT.
        for (int i = 0; i < copies; i++) out.write(chunk);
        out.write(png, 33, png.length - 33);
        return out.toByteArray();
    }
    private static byte[] webpChunks(byte[] webp, String type, byte[] body, int copies) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(webp, 0, 30); // After the extended VP8X canvas, before ANIM/ANMF.
        for (int i = 0; i < copies; i++) {
            out.write(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.write(ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(body.length).array());
            out.write(body);
            if ((body.length & 1) != 0) out.write(0);
        }
        out.write(webp, 30, webp.length - 30);
        byte[] result = out.toByteArray();
        ByteBuffer.wrap(result).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, result.length - 8);
        result[20] |= 0x2c; // Exercise clearing only the discarded metadata feature flags.
        return result;
    }

    private static byte[] image(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) throw new IOException("Missing test image writer");
        return out.toByteArray();
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void rejects(Throwing action, String message) throws Exception {
        try { action.run(); }
        catch (IOException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
