package local.cinemamanager.client;

import org.watermedia.api.codecs.readers.webp.lossless.VP8LosslessDecoder;
import org.watermedia.api.codecs.readers.webp.lossy.VP8LossyDecoder;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStreamImpl;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.CRC32;

/** Still-image decoding. No player, audio engine, GL context, disk cache or native image is created. */
public final class PosterDecoder {
    public static final int MAX_SOURCE_SIDE = 8192;
    public static final int MAX_CONTAINER_CHUNKS = 4096;
    private static final long MIB = 1024L * 1024;

    /** Working bytes include encoded input, codec scratch, an up-to-16-bit raster, ABGR and upload pixels. */
    public record Limits(int maxTextureSide, long maxTextureBytes, long maxWorkingBytes) {
        public Limits {
            if (maxTextureSide < 1 || maxTextureSide > MAX_SOURCE_SIDE || maxTextureBytes < 4
                    || maxTextureBytes > Integer.MAX_VALUE || maxWorkingBytes < 1)
                throw new IllegalArgumentException("Invalid poster decoding budgets");
        }
    }
    /** The panel uses sourceWidth/sourceHeight for aspect; width/height are the bounded texture. */
    public record Decoded(int width, int height, int sourceWidth, int sourceHeight, int[] abgr) {}
    public record Info(String format, int width, int height) {}

    private record Part(int offset, int length) {}
    private record Frame(int width, int height, int x, int y, int offset, int length,
                         boolean lossless, int alphaOffset, int alphaLength) {}
    private record Header(String format, int width, int height, List<Part> parts,
                          long jpegCoefficientBytes, Frame frame) {}
    private record Plan(int width, int height, int sample, long bytes) {}

    private PosterDecoder() {}

    public static Info inspect(byte[] bytes) throws IOException {
        Header header = header(bytes);
        return new Info(header.format, header.width, header.height);
    }

    /** An allocation estimate, not an allocation; useful for reporting why a valid image exceeds policy. */
    public static long estimateWorkingBytes(byte[] bytes, Limits limits) throws IOException {
        Header header = header(bytes);
        return plan(bytes, header, limits).bytes;
    }

    public static Decoded decode(byte[] bytes, Limits limits) throws IOException {
        interrupted();
        Header header = header(bytes); // Every canvas and subframe is checked before codec construction.
        Plan plan = plan(bytes, header, limits);
        PosterRetryPolicy.Heap heap = PosterRetryPolicy.Heap.current();
        long reserve = PosterRetryPolicy.decodeReserve(heap.maximum());
        requireBudget(plan.bytes, limits.maxWorkingBytes, heap.maximum(), heap.available(), reserve);
        try {
            return header.frame == null ? decodeJdk(bytes, header, plan) : decodeWebp(bytes, header, plan);
        } catch (PosterFailure failure) {
            throw failure;
        } catch (IOException failure) {
            interrupted();
            if (knownCodecLimit(failure)) throw new PosterFailure(PosterFailure.Code.CODEC_LIMIT);
            throw new PosterFailure(PosterFailure.Code.CORRUPT_IMAGE);
        } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
            interrupted();
            throw new PosterFailure(PosterFailure.Code.CORRUPT_IMAGE);
        }
    }

    static boolean knownCodecLimit(IOException failure) {
        // WM's zero-bit fill safety cap is narrower than its normal 64 MP source-axis limit.
        // Never turn arbitrary codec exceptions into a resource-limit diagnosis.
        if (!(failure instanceof org.watermedia.api.codecs.CodecException) || failure.getMessage() == null) return false;
        var match = java.util.regex.Pattern.compile("^VP8L declares ([0-9]+) pixels, bitstream can encode at most 16777216$")
                .matcher(failure.getMessage());
        if (!match.matches()) return false;
        try { return Long.parseLong(match.group(1)) > 16777216L; }
        catch (NumberFormatException ignored) { return false; }
    }

    static void requireBudget(long required, long configured, long maxHeap, long available, long reserve) throws IOException {
        long effective = PosterRetryPolicy.decodeBudget(configured, maxHeap, available, reserve);
        if (required > effective)
            throw new PosterFailure(PosterFailure.Code.MEMORY_BUDGET, required, effective);
    }

    private static Plan plan(byte[] bytes, Header h, Limits limits) {
        int sample = Math.max(1, (Math.max(h.width, h.height) + limits.maxTextureSide - 1) / limits.maxTextureSide);
        int width, height;
        do {
            width = (h.width + sample - 1) / sample;
            height = (h.height + sample - 1) / sample;
            if ((long) width * height * 4 <= limits.maxTextureBytes) break;
            sample++;
        } while (true);
        // JDK PNG retains scanlines, not a source-sized pixel image; JPEG baseline uses MCU rows.
        // Progressive JPEG additionally retains source-sized 16-bit DCT coefficient arrays.
        long scratch = 8 * MIB + (long) h.width * 256 + h.jpegCoefficientBytes;
        if (h.frame != null) {
            Frame f = h.frame;
            long pixels = (long) f.width * f.height;
            if (f.lossless) scratch += losslessEstimate(f.width, f.height, f.length);
            else {
                long padded = ((f.width + 15L) / 16 * 16) * ((f.height + 15L) / 16 * 16);
                // VP8LossyDecoder: YUV420 planes (1.5 P), three int arrays per macroblock,
                // token/probability state and row scratch. Round up to 2 P plus fixed scratch.
                scratch += padded * 2;
                if (f.alphaOffset >= 0) {
                    scratch += pixels;
                    if ((u8(bytes, f.alphaOffset) & 3) == 1)
                        scratch += losslessEstimate(f.width, f.height, f.alphaLength - 1);
                }
            }
        }
        return new Plan(width, height, sample, bytes.length + scratch + 16L * width * height);
    }

    private static long losslessEstimate(int width, int height, int encodedBytes) {
        long pixels = (long) width * height;
        // VP8LDecoder: 4 P main pixels; a bundled palette can overlap another <= 2 P;
        // predictor/color/entropy images each have <= ceil(W/4)*ceil(H/4) ints.
        // 8 P plus edge-row slack covers those buffers and palette conversion scratch.
        // HuffmanTable retains up to 12 bytes/symbol + lookup arrays. Five tables/group
        // have <= 3136 symbols including the maximum 2048-entry color cache: 48 KiB/group.
        // WM permits at most 4096 groups, at most one per entropy pixel, and >=20 encoded
        // bits/group. This intentionally conservative bound can reject complex large WebP.
        long groups = Math.max(1, Math.min(4096, Math.min(((width + 3L) / 4) * ((height + 3L) / 4),
                (Math.max(0, encodedBytes) * 8L + 7) / 20)));
        return pixels * 8 + (width + height) * 32L + groups * 48 * 1024;
    }

    private static Decoded decodeJdk(byte[] bytes, Header h, Plan plan) throws IOException {
        ImageReader reader = null;
        // Only stock JDK readers have the allocation behavior admitted by plan().
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(h.format);
        String expected = h.format.equals("PNG") ? "com.sun.imageio.plugins.png.PNGImageReader"
                : "com.sun.imageio.plugins.jpeg.JPEGImageReader";
        while (readers.hasNext()) {
            ImageReader candidate = readers.next();
            if (candidate.getClass().getName().equals(expected)) { reader = candidate; break; }
            candidate.dispose();
        }
        if (reader == null) throw new PosterFailure(PosterFailure.Code.UNAVAILABLE);
        BufferedImage image = null;
        try (PartsInput input = new PartsInput(bytes, h.parts)) {
            reader.setInput(input, false, true);
            if (reader.getWidth(0) != h.width || reader.getHeight(0) != h.height)
                throw new IOException("Poster dimensions changed during decoding");
            String[] warning = {null};
            reader.addIIOReadWarningListener((ignored, message) -> warning[0] = message);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(plan.sample, plan.sample, 0, 0);
            // Explicit bounded destination prevents the reader choosing a full-size raster.
            image = reader.getImageTypes(0).next().createBufferedImage(plan.width, plan.height);
            param.setDestination(image);
            reader.read(0, param);
            if (warning[0] != null) throw new IOException("Damaged poster image: " + warning[0]);
            interrupted();
            int[] pixels = new int[plan.width * plan.height];
            for (int y = 0; y < plan.height; y++) {
                interrupted();
                image.getRGB(0, y, plan.width, 1, pixels, y * plan.width, plan.width);
            }
            for (int i = 0; i < pixels.length; i++) pixels[i] = abgr(pixels[i]);
            return new Decoded(plan.width, plan.height, h.width, h.height, pixels);
        } finally {
            if (image != null) image.flush();
            reader.dispose();
        }
    }

    private static Decoded decodeWebp(byte[] bytes, Header h, Plan plan) throws IOException {
        Frame f = h.frame;
        int[] source = null;
        byte[] alpha = null;
        if (f.lossless) {
            // Direct int[] path avoids WEBPReader's source-sized heap/direct BGRA copies.
            source = VP8LosslessDecoder.decode(slice(bytes, f.offset + 5, f.length - 5), f.width, f.height);
        } else {
            source = VP8LossyDecoder.decode(slice(bytes, f.offset, f.length), f.width, f.height, new boolean[1]);
            if (f.alphaOffset >= 0) alpha = decodeAlpha(bytes, f);
        }
        int[] pixels = new int[plan.width * plan.height];
        for (int y = 0; y < plan.height; y++) {
            interrupted();
            int sy = Math.min(h.height - 1, y * plan.sample) - f.y;
            if (sy < 0 || sy >= f.height) continue;
            for (int x = 0; x < plan.width; x++) {
                int sx = Math.min(h.width - 1, x * plan.sample) - f.x;
                if (sx < 0 || sx >= f.width) continue;
                int pixel = abgr(source[sy * f.width + sx]);
                if (alpha != null) pixel = (alpha[sy * f.width + sx] & 255) << 24 | pixel & 0x00ffffff;
                pixels[y * plan.width + x] = pixel;
            }
        }
        return new Decoded(plan.width, plan.height, h.width, h.height, pixels);
    }

    private static byte[] decodeAlpha(byte[] bytes, Frame f) throws IOException {
        int flags = u8(bytes, f.alphaOffset), compression = flags & 3, filter = flags >> 2 & 3;
        byte[] alpha = new byte[f.width * f.height];
        if (compression == 0) System.arraycopy(bytes, f.alphaOffset + 1, alpha, 0, alpha.length);
        else {
            int[] argb = VP8LosslessDecoder.decode(slice(bytes, f.alphaOffset + 1, f.alphaLength - 1), f.width, f.height);
            for (int i = 0; i < alpha.length; i++) alpha[i] = (byte) (argb[i] >> 8);
        }
        for (int y = 0; filter != 0 && y < f.height; y++) {
            interrupted();
            for (int x = 0; x < f.width; x++) {
                int p = y * f.width + x;
                int left = x > 0 ? alpha[p - 1] & 255 : 0;
                int above = y > 0 ? alpha[p - f.width] & 255 : 0;
                int prediction = switch (filter) {
                    case 1 -> x > 0 ? left : above;
                    case 2 -> y > 0 ? above : left;
                    default -> x == 0 ? above : y == 0 ? left : clamp(left + above - (alpha[p - f.width - 1] & 255));
                };
                alpha[p] = (byte) ((alpha[p] & 255) + prediction);
            }
        }
        return alpha;
    }

    private static Header header(byte[] bytes) throws IOException {
        try { return parseHeader(bytes); }
        catch (PosterFailure failure) { throw failure; }
        catch (IOException | IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw new PosterFailure(PosterFailure.Code.CORRUPT_IMAGE);
        }
    }

    private static Header parseHeader(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new PosterFailure(PosterFailure.Code.UNSUPPORTED_FORMAT);
        if (bytes.length < 12) {
            if (ascii(bytes, 0, "\211PNG") || ascii(bytes, 0, "\377\330") || ascii(bytes, 0, "RIFF"))
                throw new PosterFailure(PosterFailure.Code.CORRUPT_IMAGE);
            throw new PosterFailure(PosterFailure.Code.UNSUPPORTED_FORMAT);
        }
        if (u8(bytes, 0) == 137 && ascii(bytes, 1, "PNG\r\n\032\n")) return png(bytes);
        if (u8(bytes, 0) == 255 && u8(bytes, 1) == 216) return jpeg(bytes);
        if (ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) return webp(bytes);
        throw new PosterFailure(PosterFailure.Code.UNSUPPORTED_FORMAT);
    }

    private static Header png(byte[] b) throws IOException {
        if (b.length < 33 || be32(b, 8) != 13 || !ascii(b, 12, "IHDR")) throw new IOException("Invalid PNG header");
        int width = be32(b, 16), height = be32(b, 20);
        bounds(width, height);
        int depth = u8(b, 24), color = u8(b, 25);
        boolean validDepth = switch (color) {
            case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 2, 4, 6 -> depth == 8 || depth == 16;
            case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            default -> false;
        };
        if (!validDepth || u8(b, 26) != 0 || u8(b, 27) != 0 || u8(b, 28) > 1) throw new IOException("Invalid PNG pixel layout");
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(0, 8));
        java.util.Set<String> singletons = new java.util.HashSet<>();
        boolean data = false, afterData = false;
        int chunks = 0;
        for (int p = 8; p < b.length;) {
            interrupted();
            if (++chunks > MAX_CONTAINER_CHUNKS || p + 12L > b.length) throw new IOException("Invalid PNG chunk count or header");
            long lengthLong = be32(b, p) & 0xffffffffL;
            if (p + 12L + lengthLong > b.length) throw new IOException("Truncated PNG chunk");
            int length = (int) lengthLong;
            String type = new String(b, p + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            for (int i = p + 4; i < p + 8; i++) if (!(b[i] >= 'A' && b[i] <= 'Z' || b[i] >= 'a' && b[i] <= 'z'))
                throw new IOException("Invalid PNG chunk type");
            CRC32 crc = new CRC32(); crc.update(b, p + 4, length + 4);
            if ((int) crc.getValue() != be32(b, p + 8 + length)) throw new IOException("PNG chunk checksum mismatch");
            int maxLength = switch (type) {
                case "IHDR" -> 13;
                case "PLTE" -> 768;
                case "IEND" -> 0;
                case "tRNS" -> 256;
                case "bKGD" -> 6;
                case "gAMA", "cICP" -> 4;
                case "sRGB" -> 1;
                case "acTL" -> 8;
                case "fcTL" -> 26;
                default -> Integer.MAX_VALUE;
            };
            if (length > maxLength) throw new IOException("Invalid PNG rendering chunk");
            if (maxLength != Integer.MAX_VALUE && !type.equals("fcTL") && !singletons.add(type))
                throw new IOException("Duplicate PNG rendering chunk");
            if (type.equals("IHDR") && p != 8) throw new IOException("Duplicate PNG image header");
            if (type.equals("fcTL")) {
                if (length != 26) throw new IOException("Invalid APNG frame");
                int w = be32(b, p + 12), h = be32(b, p + 16); bounds(w, h);
                long x = be32(b, p + 20) & 0xffffffffL, y = be32(b, p + 24) & 0xffffffffL;
                if (x + w > width || y + h > height) throw new IOException("APNG frame outside canvas");
            }
            if (type.equals("IDAT")) {
                if (afterData) throw new IOException("Non-consecutive PNG image chunks");
                data = true;
            } else if (data) afterData = true;
            boolean keep = type.equals("IHDR") || type.equals("PLTE") || type.equals("tRNS") || type.equals("IDAT") || type.equals("IEND");
            if (keep) parts.add(new Part(p, length + 12));
            else if ((u8(b, p + 4) & 32) == 0) throw new IOException("Unknown critical PNG chunk");
            if (type.equals("IEND")) {
                if (!data || length != 0 || p + 12 != b.length) throw new IOException("Invalid PNG end chunk");
                return new Header("PNG", width, height, parts, 0, null);
            }
            p += length + 12;
        }
        throw new IOException("Missing PNG end chunk");
    }

    private static Header jpeg(byte[] b) throws IOException {
        List<Part> parts = new ArrayList<>(); parts.add(new Part(0, 2));
        int width = 0, height = 0, count = 0;
        long coefficients = 0;
        boolean entropy = false, scan = false;
        for (int p = 2; p < b.length;) {
            interrupted();
            if (entropy) {
                int start = p;
                while (p + 1 < b.length) {
                    if (u8(b, p) != 255) { p++; continue; }
                    int next = u8(b, p + 1);
                    if (next == 0 || next >= 208 && next <= 215) { p += 2; continue; }
                    if (next == 255) { p++; continue; }
                    break;
                }
                if (p > start) parts.add(new Part(start, p - start));
                entropy = false;
            }
            int start = p;
            if (p >= b.length || u8(b, p++) != 255) throw new IOException("Invalid JPEG marker");
            while (p < b.length && u8(b, p) == 255) p++;
            if (p >= b.length) throw new IOException("Truncated JPEG marker");
            int marker = u8(b, p++);
            if (++count > MAX_CONTAINER_CHUNKS) throw new IOException("Too many JPEG segments");
            if (marker == 217) {
                if (width == 0 || !scan) throw new IOException("Incomplete JPEG image");
                parts.add(new Part(start, p - start));
                return new Header("JPEG", width, height, parts, coefficients, null);
            }
            if (marker == 0 || marker == 216 || marker >= 208 && marker <= 215 || marker == 1)
                throw new IOException("Unexpected JPEG marker");
            if (p + 2 > b.length) throw new IOException("Truncated JPEG segment");
            int length = u8(b, p) << 8 | u8(b, p + 1);
            if (length < 2 || p + (long) length > b.length) throw new IOException("Truncated JPEG segment");
            if (marker >= 192 && marker <= 207 && marker != 196 && marker != 200 && marker != 204) {
                if (width != 0 || (marker != 192 && marker != 193 && marker != 194) || length < 8 || u8(b, p + 2) != 8)
                    throw new IOException("Unsupported JPEG frame header");
                height = u8(b, p + 3) << 8 | u8(b, p + 4);
                width = u8(b, p + 5) << 8 | u8(b, p + 6); bounds(width, height);
                int components = u8(b, p + 7);
                if ((components != 1 && components != 3 && components != 4) || length != 8 + components * 3)
                    throw new IOException("Invalid JPEG components");
                int maxH = 1, maxV = 1;
                for (int c = 0; c < components; c++) {
                    int sampling = u8(b, p + 9 + c * 3), hs = sampling >> 4, vs = sampling & 15;
                    if (hs < 1 || hs > 4 || vs < 1 || vs > 4) throw new IOException("Invalid JPEG sampling");
                    maxH = Math.max(maxH, hs); maxV = Math.max(maxV, vs);
                }
                if (marker == 194) for (int c = 0; c < components; c++) {
                    int sampling = u8(b, p + 9 + c * 3);
                    long blocksX = (width + maxH * 8L - 1) / (maxH * 8L) * (sampling >> 4);
                    long blocksY = (height + maxV * 8L - 1) / (maxV * 8L) * (sampling & 15);
                    coefficients += blocksX * blocksY * 128; // 64 signed 16-bit DCT values/block.
                }
            }
            // Strip arbitrary APP/COM content, including ICC and EXIF, before JDK metadata code.
            // Retain the fixed Adobe color transform marker for CMYK/YCCK interpretation.
            boolean keep = !(marker >= 224 && marker <= 239 || marker == 254)
                    || marker == 238 && length == 14 && ascii(b, p + 2, "Adobe");
            if (keep) parts.add(new Part(start, p + length - start));
            if (marker == 218) { if (width == 0) throw new IOException("JPEG scan before frame"); entropy = true; scan = true; }
            p += length;
        }
        throw new IOException("Missing JPEG end marker");
    }

    private static Header webp(byte[] b) throws IOException {
        long endLong = (le32(b, 4) & 0xffffffffL) + 8;
        if (endLong != b.length || endLong < 20) throw new IOException("Invalid WebP container size");
        int width = 0, height = 0, alphaOffset = -1, alphaLength = 0;
        boolean extended = false, animated = false, animControl = false;
        Frame first = null;
        int[] chunks = {0};
        for (int p = 12; p < b.length;) {
            int length = webpChunk(b, p, b.length, chunks), body = p + 8;
            if (ascii(b, p, "VP8X")) {
                if (p != 12 || length != 10 || (u8(b, body) & 0xc1) != 0) throw new IOException("Invalid WebP extended header");
                extended = true; animated = (u8(b, body) & 2) != 0;
                width = le24(b, body + 4) + 1; height = le24(b, body + 7) + 1; bounds(width, height);
            } else if (ascii(b, p, "ANIM")) {
                if (!animated || animControl || length != 6 || first != null) throw new IOException("Invalid WebP animation control");
                animControl = true;
            } else if (ascii(b, p, "ANMF")) {
                if (!animated || !animControl || length < 16) throw new IOException("Invalid WebP animation frame");
                int x = le24(b, body) * 2, y = le24(b, body + 3) * 2;
                int w = le24(b, body + 6) + 1, h = le24(b, body + 9) + 1; bounds(w, h);
                if ((long) x + w > width || (long) y + h > height || (u8(b, body + 15) & ~3) != 0)
                    throw new IOException("WebP animation frame outside canvas");
                Frame frame = webpFrame(b, body + 16, body + length, w, h, x, y, chunks);
                if (first == null) first = frame; // A poster always uses exactly the first frame.
            } else if (ascii(b, p, "ALPH")) {
                if (!extended || animated || first != null || alphaOffset >= 0) throw new IOException("Invalid WebP alpha chunk");
                alphaOffset = body; alphaLength = length;
            } else if (ascii(b, p, "VP8 ") || ascii(b, p, "VP8L")) {
                if (animated || first != null) throw new IOException("Duplicate WebP image bitstream");
                first = bitstream(b, p, length, 0, 0, alphaOffset, alphaLength);
                if (extended && (width != first.width || height != first.height)) throw new IOException("WebP frame and canvas dimensions disagree");
                if (!extended) { width = first.width; height = first.height; }
            }
            p = body + length + (length & 1);
        }
        if (first == null) throw new IOException("Missing WebP image bitstream");
        return new Header("WebP", width, height, List.of(), 0, first);
    }

    private static Frame webpFrame(byte[] b, int start, int end, int width, int height, int x, int y, int[] chunks) throws IOException {
        Frame frame = null;
        int alphaOffset = -1, alphaLength = 0;
        for (int p = start; p < end;) {
            int length = webpChunk(b, p, end, chunks), body = p + 8;
            if (ascii(b, p, "ALPH")) {
                if (frame != null || alphaOffset >= 0) throw new IOException("Duplicate WebP frame alpha");
                alphaOffset = body; alphaLength = length;
            } else if (ascii(b, p, "VP8 ") || ascii(b, p, "VP8L")) {
                if (frame != null) throw new IOException("Duplicate WebP frame bitstream");
                frame = bitstream(b, p, length, x, y, alphaOffset, alphaLength);
                if (frame.width != width || frame.height != height) throw new IOException("WebP subframe dimensions disagree");
            } else throw new IOException("Unexpected WebP animation subchunk");
            p = body + length + (length & 1);
        }
        if (frame == null) throw new IOException("Missing WebP frame bitstream");
        return frame;
    }

    private static Frame bitstream(byte[] b, int p, int length, int x, int y, int alphaOffset, int alphaLength) throws IOException {
        int body = p + 8, width, height;
        boolean lossless = ascii(b, p, "VP8L");
        if (lossless) {
            if (length < 5 || u8(b, body) != 47 || alphaOffset >= 0) throw new IOException("Invalid WebP lossless header");
            int bits = le32(b, body + 1);
            if ((bits >>> 29) != 0) throw new IOException("Unsupported WebP lossless version");
            width = (bits & 16383) + 1; height = (bits >>> 14 & 16383) + 1;
        } else {
            if (length < 10 || (u8(b, body) & 1) != 0 || !ascii(b, body + 3, "\235\001\052"))
                throw new IOException("Invalid WebP lossy header");
            width = (u8(b, body + 6) | u8(b, body + 7) << 8) & 16383;
            height = (u8(b, body + 8) | u8(b, body + 9) << 8) & 16383;
        }
        bounds(width, height);
        if (alphaOffset >= 0) {
            if (alphaLength < 1) throw new IOException("Invalid WebP alpha header");
            int flags = u8(b, alphaOffset);
            if ((flags & 0xc0) != 0 || (flags & 3) > 1 || (flags >> 4 & 3) > 1)
                throw new IOException("Invalid WebP alpha layout");
            if ((flags & 3) == 0 && alphaLength != 1L + (long) width * height)
                throw new IOException("Truncated WebP alpha plane");
        }
        return new Frame(width, height, x, y, body, length, lossless, alphaOffset, alphaLength);
    }

    private static int webpChunk(byte[] b, int p, int end, int[] count) throws IOException {
        interrupted();
        if (++count[0] > MAX_CONTAINER_CHUNKS || p + 8L > end) throw new IOException("Invalid WebP chunk count or header");
        long length = le32(b, p + 4) & 0xffffffffL;
        if (p + 8L + length + (length & 1) > end) throw new IOException("Truncated WebP chunk");
        return (int) length;
    }

    private static void bounds(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE)
            throw new PosterFailure(PosterFailure.Code.DIMENSIONS, width, height, MAX_SOURCE_SIDE);
    }
    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new PosterFailure(PosterFailure.Code.CANCELLED);
    }
    private static int abgr(int argb) { return argb & 0xff00ff00 | (argb >>> 16 & 255) | (argb & 255) << 16; }
    private static int clamp(int value) { return Math.min(255, Math.max(0, value)); }
    private static int u8(byte[] b, int p) { return b[p] & 255; }
    private static int be32(byte[] b, int p) { return u8(b, p) << 24 | u8(b, p + 1) << 16 | u8(b, p + 2) << 8 | u8(b, p + 3); }
    private static int le24(byte[] b, int p) { return u8(b, p) | u8(b, p + 1) << 8 | u8(b, p + 2) << 16; }
    private static int le32(byte[] b, int p) { return le24(b, p) | u8(b, p + 3) << 24; }
    private static boolean ascii(byte[] b, int p, String value) {
        if (p < 0 || p + (long) value.length() > b.length) return false;
        for (int i = 0; i < value.length(); i++) if (u8(b, p + i) != value.charAt(i)) return false;
        return true;
    }
    private static ByteBuffer slice(byte[] b, int offset, int length) {
        return ByteBuffer.wrap(b, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Virtual concatenation strips metadata without duplicating the encoded image or caching it on disk. */
    private static final class PartsInput extends ImageInputStreamImpl {
        private final byte[] bytes;
        private final List<Part> parts;
        private final long[] starts;
        private final long length;
        PartsInput(byte[] bytes, List<Part> parts) {
            this.bytes = bytes; this.parts = parts; starts = new long[parts.size() + 1];
            long total = 0;
            for (int i = 0; i < parts.size(); i++) { starts[i] = total; total += parts.get(i).length; }
            starts[parts.size()] = total; length = total;
        }
        private int part() {
            int lo = 0, hi = parts.size();
            while (lo + 1 < hi) { int mid = (lo + hi) >>> 1; if (starts[mid] <= streamPos) lo = mid; else hi = mid; }
            return lo;
        }
        @Override public int read() throws IOException {
            checkClosed(); interrupted(); bitOffset = 0;
            if (streamPos >= length) return -1;
            int i = part();
            return u8(bytes, parts.get(i).offset + (int) (streamPos++ - starts[i]));
        }
        @Override public int read(byte[] target, int offset, int count) throws IOException {
            checkClosed(); interrupted(); java.util.Objects.checkFromIndexSize(offset, count, target.length); bitOffset = 0;
            if (count == 0) return 0;
            if (streamPos >= length) return -1;
            int total = 0;
            while (count > 0 && streamPos < length) {
                int i = part(), local = (int) (streamPos - starts[i]);
                Part part = parts.get(i);
                int n = Math.min(count, part.length - local);
                System.arraycopy(bytes, part.offset + local, target, offset, n);
                count -= n; offset += n; total += n; streamPos += n;
            }
            return total;
        }
        @Override public long length() { return length; }
    }
}
