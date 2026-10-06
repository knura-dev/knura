package io.knura.core.ntag424;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/**
 * The NDEF file and SDM file settings for a Knura tap URL:
 * {@code <base>?e=<encrypted PICC data>&c=<MAC>}, the AN12196 layout knurd verifies.
 *
 * <p>The tag stores the URL with {@code 0} placeholders and SDM overwrites them on every read:
 * {@code e} gets 32 hex digits of UID and read counter encrypted under SDMMetaReadKey, {@code c}
 * gets the 16-hex-digit MAC derived from SDMFileReadKey. The MAC input is empty (SDMMACInputOffset
 * equals SDMMACOffset), which is what knurd expects.
 */
public final class TapUrlLayout {
    /** Longest NDEF message this layout writes, so a reader can fetch it in one frame. */
    public static final int MAX_MESSAGE_LENGTH = 128;
    /** FileOption, AccessRights for a factory NDEF file: plain, no SDM, everything free but changes. */
    public static final byte[] FACTORY_FILE_SETTINGS = {0x00, (byte) 0xE0, (byte) 0xEE};

    private static final int PICC_HEX_DIGITS = 32;
    private static final int MAC_HEX_DIGITS = 16;
    /** NLEN (2) + record header (1) + type length (1) + payload length (1) + type 'U' (1) + prefix (1). */
    private static final int URL_OFFSET_IN_FILE = 7;
    private static final byte NDEF_SHORT_URI_HEADER = (byte) 0xD1;
    private static final byte NDEF_TYPE_URI = 0x55;
    private static final byte URI_PREFIX_NONE = 0x00;

    private final String baseUrl;
    private final String template;
    private final int piccDataOffset;
    private final int macOffset;

    private TapUrlLayout(String baseUrl) {
        this.baseUrl = baseUrl;
        String separator = baseUrl.contains("?") ? "&" : "?";
        String prefix = baseUrl + separator + "e=";
        this.template = prefix + "0".repeat(PICC_HEX_DIGITS) + "&c=" + "0".repeat(MAC_HEX_DIGITS);
        this.piccDataOffset = URL_OFFSET_IN_FILE + prefix.length();
        this.macOffset = piccDataOffset + PICC_HEX_DIGITS + "&c=".length();
    }

    /**
     * Layout for tap URLs under {@code baseUrl}, e.g. {@code http://127.0.0.1:8424/tap}. Throws
     * {@link IllegalArgumentException} unless it is an http(s) URL short enough to fit.
     */
    public static TapUrlLayout forBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a valid URL: " + baseUrl, e);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("base URL must start with http:// or https://: " + baseUrl);
        }
        if (uri.getHost() == null || uri.getFragment() != null) {
            throw new IllegalArgumentException("base URL needs a host and no #fragment: " + baseUrl);
        }
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(baseUrl)) {
            throw new IllegalArgumentException("base URL must be plain ASCII: " + baseUrl);
        }
        TapUrlLayout layout = new TapUrlLayout(baseUrl);
        if (layout.ndefMessage().length > MAX_MESSAGE_LENGTH) {
            int spare = MAX_MESSAGE_LENGTH - (layout.ndefMessage().length - baseUrl.length());
            throw new IllegalArgumentException("base URL is too long: at most " + spare + " characters fit");
        }
        return layout;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** The URL as stored on the tag, with {@code 0} placeholders. */
    public String template() {
        return template;
    }

    /** NDEF file contents: 2-byte length, then one short URI record holding the template. */
    public byte[] ndefFile() {
        byte[] message = ndefMessage();
        byte[] file = new byte[2 + message.length];
        file[0] = (byte) (message.length >>> 8);
        file[1] = (byte) message.length;
        System.arraycopy(message, 0, file, 2, message.length);
        return file;
    }

    private byte[] ndefMessage() {
        byte[] url = template.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(NDEF_SHORT_URI_HEADER);
        out.write(1);
        out.write(1 + url.length);
        out.write(NDEF_TYPE_URI);
        out.write(URI_PREFIX_NONE);
        out.writeBytes(url);
        return out.toByteArray();
    }

    /**
     * ChangeFileSettings data for the NDEF file: SDM on with UID and read counter in encrypted PICC
     * data and a MAC over an empty input; anyone may read, only key 0 may write or change settings.
     * Matches the layout of NXP AN12196 section 3.4.
     *
     * @param metaReadKey key slot that encrypts the PICC data
     * @param fileReadKey key slot the MAC derives from
     */
    public byte[] fileSettings(int metaReadKey, int fileReadKey) {
        requireSlot(metaReadKey);
        requireSlot(fileReadKey);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x40); // FileOption: SDM enabled, plain communication
        out.write(0x00); // AccessRights: ReadWrite key 0, Change key 0
        out.write(0xE0); // AccessRights: Read free, Write key 0
        out.write(0xC1); // SDMOptions: UID mirror, read counter mirror, ASCII encoding
        out.write(0xFF); // SDMAccessRights: RFU, SDMCtrRet none
        out.write(metaReadKey << 4 | fileReadKey); // SDMMetaRead, SDMFileRead
        writeOffset(out, piccDataOffset);
        writeOffset(out, macOffset); // SDMMACInputOffset: empty MAC input
        writeOffset(out, macOffset);
        return out.toByteArray();
    }

    /** Offset of the {@code e} value in the NDEF file. */
    public int piccDataOffset() {
        return piccDataOffset;
    }

    /** Offset of the {@code c} value in the NDEF file. */
    public int macOffset() {
        return macOffset;
    }

    /**
     * Decodes the URL in an NDEF message holding one short URI record, as read from a tag.
     * Throws {@link IllegalArgumentException} if the message is anything else.
     */
    public static String decodeUri(byte[] message) {
        if (message.length < 5
                || (message[0] & 0x1F) != 0x11 // SR set, IL clear, TNF well-known
                || message[1] != 1
                || message[3] != NDEF_TYPE_URI) {
            throw new IllegalArgumentException("NDEF message is not a single short URI record");
        }
        int payloadLength = message[2] & 0xFF;
        if (payloadLength < 1 || 4 + payloadLength > message.length) {
            throw new IllegalArgumentException("NDEF URI record is truncated");
        }
        String prefix = switch (message[4]) {
            case 0x00 -> "";
            case 0x01 -> "http://www.";
            case 0x02 -> "https://www.";
            case 0x03 -> "http://";
            case 0x04 -> "https://";
            default -> throw new IllegalArgumentException(
                    String.format("unsupported URI prefix code 0x%02X", message[4]));
        };
        return prefix + new String(message, 5, payloadLength - 1, StandardCharsets.UTF_8);
    }

    private static void writeOffset(ByteArrayOutputStream out, int offset) {
        out.write(offset);
        out.write(offset >>> 8);
        out.write(offset >>> 16);
    }

    private static void requireSlot(int keyNo) {
        if (keyNo < 0 || keyNo > 4) {
            throw new IllegalArgumentException("key number must be 0 to 4, got " + keyNo);
        }
    }
}
