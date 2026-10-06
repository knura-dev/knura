package io.knura.core.ntag424;

import io.knura.core.crypto.AesCmac;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import javax.crypto.Cipher;

/**
 * The NTAG 424 DNA commands Knura needs, over any {@link Transceiver}: ISO select and read,
 * AuthenticateEV2First, GetCardUid, GetFileSettings, ChangeFileSettings, WriteData and ChangeKey.
 *
 * <p>Command layout and secure messaging follow the NT4H2421Gx datasheet and NXP AN12196, and are
 * pinned by tests against byte traces recorded from a real tag on an ACR122U reader. Not thread
 * safe: one instance talks to one tag.
 */
public final class Ntag424 {
    /** NDEF file: the file whose contents phones read and SDM mirrors into. */
    public static final int NDEF_FILE = 0x02;
    /** Size of the NDEF file on NTAG 424 DNA. */
    public static final int NDEF_FILE_SIZE = 256;
    /** Every key on a tag fresh from the factory. */
    public static final byte[] FACTORY_KEY = new byte[16];

    /**
     * SELECT by DF name D2760000850101 (NXP AN12196). Selecting by file ID E110 only works from
     * the card level: once the application is selected, the tag answers 6A82.
     */
    private static final byte[] SELECT_NDEF_APPLICATION = bytes("00A4040C07D276000085010100");
    private static final byte[] SELECT_NDEF_FILE = bytes("00A4000C02E10400");
    private static final int CMD_AUTH_EV2_FIRST = 0x71;
    private static final int CMD_ADDITIONAL_FRAME = 0xAF;
    private static final int CMD_GET_CARD_UID = 0x51;
    private static final int CMD_GET_FILE_SETTINGS = 0xF5;
    private static final int CMD_CHANGE_FILE_SETTINGS = 0x5F;
    private static final int CMD_WRITE_DATA = 0x8D;
    private static final int CMD_CHANGE_KEY = 0xC4;
    private static final int SW_OK = 0x9100;
    private static final int SW_MORE = 0x91AF;
    private static final int SW_ISO_OK = 0x9000;
    /** Data bytes per WriteData frame, well inside what the ACR122U and the tag accept. */
    private static final int WRITE_CHUNK = 128;
    /** Bytes per READ BINARY: a Knura tap URL (NDEF file of at most 130 bytes) takes one read. */
    private static final int READ_CHUNK = 130;

    private final Transceiver transceiver;
    private final Supplier<byte[]> rndASource;
    private SecureSession session;
    private int authKeyNo = -1;

    public Ntag424(Transceiver transceiver) {
        this(transceiver, () -> {
            byte[] rnd = new byte[16];
            new SecureRandom().nextBytes(rnd);
            return rnd;
        });
    }

    /** For tests: {@code rndASource} supplies RndA for each authentication. */
    public Ntag424(Transceiver transceiver, Supplier<byte[]> rndASource) {
        this.transceiver = transceiver;
        this.rndASource = rndASource;
    }

    /** Selects the NDEF application. This also drops any authentication. */
    public void selectApplication() throws IOException {
        endSession();
        iso("SELECT application", SELECT_NDEF_APPLICATION);
    }

    /**
     * Reads the NDEF file the way a phone does: select it and read without authenticating, so the
     * tag fills in SDM mirrors (and counts a read). Selects the application first, which drops
     * any authentication. Returns the NDEF message, without the 2-byte length prefix.
     */
    public byte[] readNdefMessage() throws IOException {
        selectApplication();
        iso("SELECT NDEF file", SELECT_NDEF_FILE);
        byte[] lengthBytes = readBinary(0, 2);
        int length = (lengthBytes[0] & 0xFF) << 8 | (lengthBytes[1] & 0xFF);
        if (length > NDEF_FILE_SIZE - 2) {
            throw new Ntag424Exception("NDEF length " + length + " does not fit the NDEF file");
        }
        // One read for the whole message when it fits: SDM fills the mirrors per read command,
        // so a URL split over two reads could mix two different counters.
        byte[] file = new byte[length + 2];
        for (int done = 0; done < file.length; done += READ_CHUNK) {
            int len = Math.min(READ_CHUNK, file.length - done);
            System.arraycopy(readBinary(done, len), 0, file, done, len);
        }
        return Arrays.copyOfRange(file, 2, file.length);
    }

    private byte[] readBinary(int offset, int length) throws IOException {
        byte[] cmd = {0x00, (byte) 0xB0, (byte) (offset >>> 8), (byte) offset, (byte) length};
        byte[] data = iso("READ BINARY", cmd);
        if (data.length != length) {
            throw new Ntag424Exception("READ BINARY returned " + data.length + " bytes, expected " + length);
        }
        return data;
    }

    /**
     * AuthenticateEV2First with key {@code keyNo}. Throws {@link Ntag424Exception} with {@link
     * Ntag424Exception#isAuthenticationError()} when the key is wrong.
     */
    public void authenticate(int keyNo, byte[] key) throws IOException {
        requireKey(key);
        endSession();
        Response first = native1(CMD_AUTH_EV2_FIRST, new byte[] {(byte) keyNo, 0x00});
        if (first.status != SW_MORE || first.data.length != 16) {
            throw new Ntag424Exception("AuthenticateEV2First", first.status);
        }
        byte[] rndB = SecureSession.aesCbc(Cipher.DECRYPT_MODE, key, new byte[16], first.data);
        byte[] rndA = rndASource.get();
        if (rndA.length != 16) {
            throw new IllegalStateException("RndA must be 16 bytes");
        }
        byte[] proof = SecureSession.aesCbc(
                Cipher.ENCRYPT_MODE, key, new byte[16], SecureSession.concat(rndA, rotateLeft(rndB)));
        Response second = native1(CMD_ADDITIONAL_FRAME, proof);
        if (second.status != SW_OK || second.data.length != 32) {
            throw new Ntag424Exception("AuthenticateEV2First (part 2)", second.status);
        }
        byte[] plain = SecureSession.aesCbc(Cipher.DECRYPT_MODE, key, new byte[16], second.data);
        if (!MessageDigest.isEqual(Arrays.copyOfRange(plain, 4, 20), rotateLeft(rndA))) {
            throw new Ntag424Exception("AuthenticateEV2First: tag's answer does not match (wrong key?)");
        }
        session = new SecureSession(key, rndA, rndB, Arrays.copyOf(plain, SecureSession.TI_LENGTH));
        authKeyNo = keyNo;
        Arrays.fill(rndA, (byte) 0);
        Arrays.fill(rndB, (byte) 0);
    }

    public boolean isAuthenticated() {
        return session != null;
    }

    /** The tag's real 7-byte UID. Needs authentication (the tag answers 917E otherwise). */
    public byte[] getCardUid() throws IOException {
        byte[] uid = fullCommand("GetCardUid", CMD_GET_CARD_UID, new byte[0], new byte[0]);
        if (uid.length != 7) {
            throw new Ntag424Exception("GetCardUid returned " + uid.length + " bytes, expected 7");
        }
        return uid;
    }

    /** Raw file settings (datasheet section 10.7.2); MACed when authenticated, plain otherwise. */
    public byte[] getFileSettings(int fileNo) throws IOException {
        byte[] header = {(byte) fileNo};
        if (session == null) {
            Response r = native1(CMD_GET_FILE_SETTINGS, header);
            check("GetFileSettings", r);
            return r.data;
        }
        return macCommand("GetFileSettings", CMD_GET_FILE_SETTINGS, header);
    }

    /** ChangeFileSettings in full (encrypted) mode; {@code settings} starts at FileOption. */
    public void changeFileSettings(int fileNo, byte[] settings) throws IOException {
        fullCommand("ChangeFileSettings", CMD_CHANGE_FILE_SETTINGS, new byte[] {(byte) fileNo}, settings);
    }

    /**
     * WriteData in plain mode, for files whose communication mode is plain (the NDEF file).
     * Inside a session the command counter still advances.
     */
    public void writeData(int fileNo, int offset, byte[] data) throws IOException {
        for (int done = 0; done < data.length; done += WRITE_CHUNK) {
            int len = Math.min(WRITE_CHUNK, data.length - done);
            int at = offset + done;
            byte[] payload = new byte[7 + len];
            payload[0] = (byte) fileNo;
            payload[1] = (byte) at;
            payload[2] = (byte) (at >>> 8);
            payload[3] = (byte) (at >>> 16);
            payload[4] = (byte) len;
            payload[5] = (byte) (len >>> 8);
            payload[6] = (byte) (len >>> 16);
            System.arraycopy(data, done, payload, 7, len);
            Response r = native1(CMD_WRITE_DATA, payload);
            check("WriteData", r);
            if (session != null) {
                session.acceptPlainResponse();
            }
        }
    }

    /**
     * ChangeKey. Changing the key the session authenticated with ends the session; changing any
     * other key needs that key's current value.
     */
    public void changeKey(int keyNo, byte[] oldKey, byte[] newKey, int version) throws IOException {
        requireKey(newKey);
        if (session == null) {
            throw new IllegalStateException("ChangeKey needs authentication");
        }
        byte[] data;
        if (keyNo == authKeyNo) {
            data = Arrays.copyOf(newKey, 17);
            data[16] = (byte) version;
        } else {
            requireKey(oldKey);
            data = new byte[21];
            for (int i = 0; i < 16; i++) {
                data[i] = (byte) (newKey[i] ^ oldKey[i]);
            }
            data[16] = (byte) version;
            CRC32 crc = new CRC32();
            crc.update(newKey);
            int jamCrc = (int) ~crc.getValue();
            data[17] = (byte) jamCrc;
            data[18] = (byte) (jamCrc >>> 8);
            data[19] = (byte) (jamCrc >>> 16);
            data[20] = (byte) (jamCrc >>> 24);
        }
        byte[] header = {(byte) keyNo};
        byte[] encrypted = session.encrypt(data);
        Arrays.fill(data, (byte) 0);
        byte[] macInput = SecureSession.concat(header, encrypted);
        Response r = native1(CMD_CHANGE_KEY, SecureSession.concat(macInput, session.commandMac(CMD_CHANGE_KEY, macInput)));
        if (keyNo == authKeyNo) {
            // The tag answers without a MAC and drops the session: its key just changed.
            check("ChangeKey", r);
            endSession();
            return;
        }
        acceptMacResponse("ChangeKey", r);
    }

    /** Forgets the session keys. The tag itself drops authentication on the next select. */
    public void endSession() {
        if (session != null) {
            session.destroy();
        }
        session = null;
        authKeyNo = -1;
    }

    private byte[] macCommand(String name, int cmd, byte[] header) throws IOException {
        requireSession(name);
        Response r = native1(cmd, SecureSession.concat(header, session.commandMac(cmd, header)));
        return acceptMacResponse(name, r);
    }

    private byte[] fullCommand(String name, int cmd, byte[] header, byte[] data) throws IOException {
        requireSession(name);
        byte[] payload = data.length == 0 ? header : SecureSession.concat(header, session.encrypt(data));
        Response r = native1(cmd, SecureSession.concat(payload, session.commandMac(cmd, payload)));
        byte[] encrypted = acceptMacResponse(name, r);
        return encrypted.length == 0 ? encrypted : session.decryptResponse(encrypted, name);
    }

    private byte[] acceptMacResponse(String name, Response r) throws IOException {
        if (r.status != SW_OK) {
            endSession();
            throw new Ntag424Exception(name, r.status);
        }
        if (r.data.length < 8) {
            endSession();
            throw new Ntag424Exception(name + ": response too short for its MAC");
        }
        byte[] data = Arrays.copyOf(r.data, r.data.length - 8);
        byte[] mac = Arrays.copyOfRange(r.data, r.data.length - 8, r.data.length);
        try {
            session.acceptResponse(0x00, data, mac, name);
        } catch (Ntag424Exception e) {
            endSession();
            throw e;
        }
        return data;
    }

    private void requireSession(String name) {
        if (session == null) {
            throw new IllegalStateException(name + " needs authentication");
        }
    }

    private Response native1(int cmd, byte[] payload) throws IOException {
        byte[] apdu;
        if (payload.length == 0) {
            apdu = new byte[] {(byte) 0x90, (byte) cmd, 0x00, 0x00, 0x00};
        } else {
            apdu = new byte[6 + payload.length];
            apdu[0] = (byte) 0x90;
            apdu[1] = (byte) cmd;
            apdu[4] = (byte) payload.length;
            System.arraycopy(payload, 0, apdu, 5, payload.length);
        }
        return Response.of(transceiver.transmit(apdu));
    }

    private void check(String name, Response r) throws Ntag424Exception {
        if (r.status != SW_OK) {
            endSession();
            throw new Ntag424Exception(name, r.status);
        }
    }

    private byte[] iso(String name, byte[] apdu) throws IOException {
        Response r = Response.of(transceiver.transmit(apdu));
        if (r.status != SW_ISO_OK) {
            throw new Ntag424Exception(name, r.status);
        }
        return r.data;
    }

    private static byte[] rotateLeft(byte[] in) {
        byte[] out = new byte[in.length];
        System.arraycopy(in, 1, out, 0, in.length - 1);
        out[in.length - 1] = in[0];
        return out;
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != AesCmac.BLOCK_SIZE) {
            throw new IllegalArgumentException("AES-128 key must be 16 bytes");
        }
    }

    private static byte[] bytes(String hex) {
        return io.knura.core.crypto.Hex.decode(hex);
    }

    private record Response(byte[] data, int status) {
        static Response of(byte[] raw) throws Ntag424Exception {
            if (raw == null || raw.length < 2) {
                throw new Ntag424Exception("tag sent no status word");
            }
            int sw = (raw[raw.length - 2] & 0xFF) << 8 | (raw[raw.length - 1] & 0xFF);
            return new Response(Arrays.copyOf(raw, raw.length - 2), sw);
        }
    }
}
