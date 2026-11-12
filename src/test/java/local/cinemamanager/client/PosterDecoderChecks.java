package local.cinemamanager.client;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.IIOImage;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Actual bounded decodes, including large source axes and image payloads exceeding the old 8 MiB limit. */
public final class PosterDecoderChecks {
    private static final long MIB = 1024L * 1024;
    private static final PosterDecoder.Limits DEFAULT = new PosterDecoder.Limits(2048, 16 * MIB, 256 * MIB);
    private static final String LOSSLESS = "UklGRhwAAABXRUJQVlA4TA8AAAAvH8ADAAcQ/Y/+ByKi/wEA";
    private static final String LOSSY = "UklGRkwAAABXRUJQVlA4IEAAAABQAwCdASogABAAPjEUiEKiISEYBAAgAwSgB2APwA/AABEQAAD+96KX//16JSql/ew///wsz+Fmfwsz/4TgAAAA";
    private static final String ANIMATED = "UklGRuQAAABXRUJQVlA4WAoAAAACAAAAHwAADwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAAJWUDhMDwAAAC8fwAMABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAABWUDhMDwAAAC8fwAMAB9D/iP4HIqL/AQBBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAABWUDhMDwAAAC8fwAMABxDR//4HIqL/AQBBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAABWUDhMEAAAAC8fwAMAB9D//kf/AxHR/wA=";
    // Libwebp-encoded real 8192x17 images, retained as tiny deterministic fixtures.
    private static final String WIDE_LOSSLESS = "UklGRioAAABXRUJQVlA4TB4AAAAv/x8EAAcQ/Y/+BwgkbdY///ZH9D+5//f//t9/BQA=";
    private static final String WIDE_LOSSY = "UklGRiQCAABXRUJQVlA4IBgCAADQOgCdASoAIBEAPm02mUmkIyKhIAgAgA2JaW7hd2Ee3AfgAAAT2Ae+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ9YAAD+/7ge///8guWF1yNf//yA/5Af8gP/4+KarTgQAAAAAAAAAAAAAAAaKYEAAAAAAAAAAAAAAAAAAA==";
    private static int checks;

    public static void main(String[] args) throws Exception {
        PosterRetryPolicyChecks.main(new String[0]);
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000); image.setRGB(1, 0, 0x800000ff);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out);
        byte[] small = out.toByteArray();
        PosterDecoder.Decoded decoded = PosterDecoder.decode(small, DEFAULT);
        check(decoded.abgr()[0] == 0xff0000ff && decoded.abgr()[1] == 0x80ff0000, "PNG color and alpha");
        var sixteenBit = javax.imageio.ImageTypeSpecifier.createInterleaved(
                java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB), new int[]{0, 1, 2, 3},
                java.awt.image.DataBuffer.TYPE_USHORT, true, false).createBufferedImage(3, 1);
        sixteenBit.getRaster().setPixel(0, 0, new int[]{65535, 0, 0, 32768});
        out.reset(); ImageIO.write(sixteenBit, "png", out);
        decoded = PosterDecoder.decode(out.toByteArray(), DEFAULT);
        check(decoded.abgr()[0] == 0x800000ff, "16-bit RGBA PNG converts to bounded 8-bit upload pixels");
        sixteenBit.flush();

        for (String fixture : new String[]{LOSSLESS, LOSSY, ANIMATED}) {
            decoded = PosterDecoder.decode(Base64.getDecoder().decode(fixture), DEFAULT);
            check(decoded.width() == 32 && decoded.height() == 16, "WebP dimensions and first animated frame");
            check((decoded.abgr()[0] & 255) > 230, "WebP red channel");
        }
        for (String fixture : new String[]{WIDE_LOSSLESS, WIDE_LOSSY}) {
            decoded = PosterDecoder.decode(Base64.getDecoder().decode(fixture), DEFAULT);
            check(decoded.sourceWidth() == 8192 && decoded.sourceHeight() == 17 && decoded.width() == 2048,
                    "actual 8192 non-square WebP decodes without a full-size BGRA copy");
            check((decoded.abgr()[0] & 255) > 230 && (decoded.abgr()[decoded.abgr().length - 1] & 255) > 230,
                    "large WebP first and last pixel color");
        }
        alphaChecks();

        for (int[] size : new int[][]{{8192, 17}, {17, 8192}, {8192, 1}, {1, 8192}}) {
            byte[] png = png(size[0], size[1], false);
            decoded = PosterDecoder.decode(png, DEFAULT);
            check(decoded.sourceWidth() == size[0] && decoded.sourceHeight() == size[1], "8192 non-square source dimensions retained");
            check(decoded.width() <= 2048 && decoded.height() <= 2048, "8192 source raster subsampled before allocation");
            check((decoded.abgr()[0] & 0xffffff) == 0x332211, "large PNG content decoded");
        }

        // The input is generated scanline by scanline: the test itself never allocates an 8192² raster.
        byte[] square = png(8192, 8192, false);
        decoded = PosterDecoder.decode(square, DEFAULT);
        check(decoded.sourceWidth() == 8192 && decoded.sourceHeight() == 8192
                && decoded.width() == 2048 && decoded.height() == 2048, "actual 8192 square PNG bounded to 16 MiB output");
        check(PosterDecoder.estimateWorkingBytes(square, DEFAULT) < 80 * MIB, "large PNG estimate has no source-sized 256 MiB pixel buffer");

        byte[] largePayload = png(2048, 1536, true);
        check(largePayload.length > 8 * MIB, "valid image pixel payload really exceeds 8 MiB");
        decoded = PosterDecoder.decode(largePayload, DEFAULT);
        check(decoded.sourceWidth() == 2048 && decoded.sourceHeight() == 1536, "valid image beyond former transport limit decodes");
        check(decoded.abgr().length == 2048 * 1536, "large payload retained output");
        largePayload = null; decoded = null;

        byte[] metadata = insertPng(small, chunk("zTXt", new byte[]{'x', 0, 0, 1, 2, 3}));
        check(PosterDecoder.decode(metadata, DEFAULT).abgr()[1] == 0x80ff0000, "compressed metadata never reaches decoder");
        for (String type : new String[]{"iTXt", "iCCP", "tEXt", "eXIf", "vpAg"})
            check(PosterDecoder.decode(insertPng(small, chunk(type, new byte[]{1, 2, 3})), DEFAULT).abgr()[0] == 0xff0000ff,
                    "unused " + type + " is not parsed");
        rejects(() -> PosterDecoder.inspect(insertPng(small, chunk("ABCD", new byte[0]))), "critical");
        rejects(() -> PosterDecoder.inspect(insertPng(small, chunk("gAMA", new byte[5]))), "rendering");
        rejects(() -> PosterDecoder.inspect(insertPng(insertPng(small, chunk("gAMA", new byte[4])), chunk("gAMA", new byte[4]))), "Duplicate");
        byte[] corrupt = small.clone(); corrupt[corrupt.length - 1] ^= 1;
        rejects(() -> PosterDecoder.decode(corrupt, DEFAULT), "checksum");
        rejects(() -> PosterDecoder.decode(Arrays.copyOf(small, small.length - 4), DEFAULT), "Truncated");
        byte[] badPixels = replacePngIdat(small, new byte[]{1, 2, 3, 4});
        rejects(() -> PosterDecoder.decode(badPixels, DEFAULT), null);
        rejects(() -> PosterDecoder.inspect(pngHeader(8193, 10)), "8192");
        rejects(() -> PosterDecoder.inspect(pngHeader(10, 8193)), "8192");
        rejects(() -> PosterDecoder.inspect(pngHeader(0, 10)), "8192");
        rejects(() -> PosterDecoder.decode("<html>fake image url</html>".getBytes(), DEFAULT), "content");
        rejects(() -> PosterDecoder.decode("GIF89a123456789".getBytes(), DEFAULT), "content");

        byte[] tinyWebp = Base64.getDecoder().decode(LOSSLESS);
        byte[] webp8193 = tinyWebp.clone();
        ByteBuffer.wrap(webp8193).order(ByteOrder.LITTLE_ENDIAN).putInt(21, (8193 - 1) | (16 - 1) << 14);
        rejects(() -> PosterDecoder.inspect(webp8193), "8192");
        byte[] webpSquare = tinyWebp.clone();
        ByteBuffer.wrap(webpSquare).order(ByteOrder.LITTLE_ENDIAN).putInt(21, 8191 | 8191 << 14);
        long losslessBytes = PosterDecoder.estimateWorkingBytes(webpSquare, DEFAULT);
        check(losslessBytes > 256 * MIB, "8192-square lossless codec working estimate exceeds default budget");
        rejects(() -> PosterDecoder.decode(webpSquare, DEFAULT), "memory budget");
        byte[] mismatch = Base64.getDecoder().decode(ANIMATED); mismatch[24] = 63;
        // Enlarging the canvas alone is legal. An ANMF that lies outside it is not.
        mismatch[24] = 15;
        rejects(() -> PosterDecoder.inspect(mismatch), "outside canvas");
        byte[] brokenWebp = tinyWebp.clone(); brokenWebp[16] = 127;
        rejects(() -> PosterDecoder.inspect(brokenWebp), "Truncated");
        rejects(() -> PosterDecoder.requireBudget(65 * MIB, 256 * MIB, 192 * MIB, 192 * MIB, 16 * MIB), "memory budget");
        rejects(() -> PosterDecoder.requireBudget(20 * MIB, 256 * MIB, 1024 * MIB, 30 * MIB, 16 * MIB), "memory budget");
        check(PosterDecoder.knownCodecLimit(new org.watermedia.api.codecs.CodecException(
                "VP8L declares 33554432 pixels, bitstream can encode at most 16777216")), "known WM fill ceiling is a codec-limit reason");
        check(!PosterDecoder.knownCodecLimit(new org.watermedia.api.codecs.CodecException(
                "VP8L declares 1024 pixels, bitstream can encode at most 4096")), "other WM data failures remain corrupt-image reasons");

        jpegChecks();
        check(PosterDecoder.decode(square, new PosterDecoder.Limits(4096, 4 * MIB, 256 * MIB)).abgr().length <= MIB,
                "pixel budget enforced independently of texture edge");
        Thread.currentThread().interrupt();
        try { rejects(() -> PosterDecoder.decode(small, DEFAULT), "cancelled"); }
        finally { Thread.interrupted(); }
        System.out.println("PosterDecoder checks passed: " + checks);
    }

    private static void alphaChecks() throws Exception {
        byte[] lossy = Base64.getDecoder().decode(LOSSY);
        for (int filter = 0; filter < 4; filter++) {
            byte[] raw = new byte[512], encoded = new byte[513]; encoded[0] = (byte) (filter << 2);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 32; x++) raw[y * 32 + x] = (byte) ((x * 7 + y * 9) & 255);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 32; x++) {
                int p = y * 32 + x, left = x == 0 ? 0 : raw[p - 1] & 255, above = y == 0 ? 0 : raw[p - 32] & 255;
                int predictor = switch (filter) {
                    case 0 -> 0;
                    case 1 -> x == 0 ? above : left;
                    case 2 -> y == 0 ? left : above;
                    default -> x == 0 ? above : y == 0 ? left : Math.max(0, Math.min(255, left + above - (raw[p - 33] & 255)));
                };
                encoded[p + 1] = (byte) ((raw[p] & 255) - predictor);
            }
            ByteArrayOutputStream chunks = new ByteArrayOutputStream();
            chunks.write(webpChunk("VP8X", new byte[]{16, 0, 0, 0, 31, 0, 0, 15, 0, 0}));
            chunks.write(webpChunk("ALPH", encoded)); chunks.write(lossy, 12, lossy.length - 12);
            ByteArrayOutputStream container = new ByteArrayOutputStream();
            container.write("RIFF".getBytes()); container.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunks.size() + 4).array());
            container.write("WEBP".getBytes()); container.write(chunks.toByteArray());
            PosterDecoder.Decoded result = PosterDecoder.decode(container.toByteArray(), DEFAULT);
            for (int p = 0; p < raw.length; p++) check((result.abgr()[p] >>> 24) == (raw[p] & 255), "WebP ALPH filter " + filter + " pixel " + p);
        }
    }

    private static byte[] webpChunk(String name, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(name.getBytes());
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.length).array()); out.write(body);
        if ((body.length & 1) != 0) out.write(0); return out.toByteArray();
    }

    private static void jpegChecks() throws Exception {
        for (boolean progressive : new boolean[]{false, true}) {
            BufferedImage source = new BufferedImage(8192, 17, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 17; y++) for (int x = 0; x < 8192; x++) source.setRGB(x, y, 0xff0000);
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream); ImageWriteParam param = writer.getDefaultWriteParam();
                param.setProgressiveMode(progressive ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
                writer.write(null, new IIOImage(source, null, null), param);
            } finally { writer.dispose(); source.flush(); }
            byte[] jpeg = out.toByteArray();
            PosterDecoder.Decoded result = PosterDecoder.decode(jpeg, DEFAULT);
            check(result.sourceWidth() == 8192 && result.sourceHeight() == 17 && result.width() == 2048,
                    "actual 8192 JPEG subsampling, progressive=" + progressive);
            check((result.abgr()[0] & 255) > 230, "JPEG color, progressive=" + progressive);
            byte[] oversized = jpeg.clone();
            for (int p = 0; p < oversized.length - 9; p++) if ((oversized[p] & 255) == 255
                    && ((oversized[p + 1] & 255) == 192 || (oversized[p + 1] & 255) == 194)) {
                oversized[p + 7] = 32; oversized[p + 8] = 1; break;
            }
            rejects(() -> PosterDecoder.inspect(oversized), "8192");
            rejects(() -> PosterDecoder.decode(Arrays.copyOf(jpeg, jpeg.length - 2), DEFAULT), null);
        }
    }

    private static byte[] png(int width, int height, boolean noise) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(noise ? Deflater.NO_COMPRESSION : Deflater.BEST_SPEED);
        try (DeflaterOutputStream pixels = new DeflaterOutputStream(compressed, deflater, 8192)) {
            byte[] row = new byte[width * 3 + 1]; Random random = new Random(6419);
            for (int y = 0; y < height; y++) {
                if (noise) { random.nextBytes(row); row[0] = 0; }
                else for (int x = 0; x < width; x++) { row[x * 3 + 1] = 0x11; row[x * 3 + 2] = 0x22; row[x * 3 + 3] = 0x33; }
                pixels.write(row);
            }
        } finally { deflater.end(); }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(pngHeader(width, height)); out.write(chunk("IDAT", compressed.toByteArray())); out.write(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }
    private static byte[] pngHeader(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10});
        out.write(chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2).put(new byte[3]).array()));
        return out.toByteArray();
    }
    private static byte[] chunk(String type, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(ByteBuffer.allocate(4).putInt(body.length).array()); out.write(name); out.write(body);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(body);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array()); return out.toByteArray();
    }
    private static byte[] insertPng(byte[] png, byte[] extra) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(png, 0, 33); out.write(extra); out.write(png, 33, png.length - 33); return out.toByteArray();
    }
    private static byte[] replacePngIdat(byte[] png, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(png, 0, 33); out.write(chunk("IDAT", body)); out.write(chunk("IEND", new byte[0])); return out.toByteArray();
    }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
    private static void rejects(Throwing action, String text) throws Exception {
        try { action.run(); }
        catch (IOException failure) {
            PosterFailure.Code expected = text == null ? PosterFailure.Code.CORRUPT_IMAGE : switch (text) {
                case "8192" -> PosterFailure.Code.DIMENSIONS;
                case "memory budget" -> PosterFailure.Code.MEMORY_BUDGET;
                case "content" -> PosterFailure.Code.UNSUPPORTED_FORMAT;
                case "cancelled" -> PosterFailure.Code.CANCELLED;
                default -> PosterFailure.Code.CORRUPT_IMAGE;
            };
            if (!(failure instanceof PosterFailure typed) || typed.code() != expected)
                throw new AssertionError("Expected " + expected + ": " + failure, failure);
            checks++; return;
        }
        throw new AssertionError("Expected rejection: " + text);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
