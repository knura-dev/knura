package io.knura.core.ntag424;

import io.knura.core.crypto.AesCmac;
import io.knura.core.crypto.Hex;
import io.knura.core.sdm.SdmVerifier;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A simulated NTAG 424 DNA behind a PC/SC reader, for testing provisioning end to end. It plays
 * the tag's side of EV2 secure messaging with its own code (only CMAC is shared), checks access
 * rights, and fills in SDM mirrors (encrypted PICC data and MAC) on unauthenticated reads.
 *
 * <p>The real-hardware behaviour it mirrors is pinned separately by {@link Ntag424TraceTest}.
 */
public final class FakeNtag424 implements Transceiver {
    private static final byte[] OK = {(byte) 0x91, 0x00};
    private final SecureRandom random = new SecureRandom();

    private final byte[] uid;
    private final byte[][] keys = new byte[5][];
    private final int[] keyVersions = new int[5];
    private byte[] fileSettings = Hex.decode("00E0EE");
    private final byte[] ndef = new byte[Ntag424.NDEF_FILE_SIZE];
    private int readCounter;

    private boolean appSelected;
    private boolean ndefSelected;
    /** The real tag counts a read once per file selection, not per READ BINARY. */
    private boolean countedSinceSelect;
    private int pendingKeyNo = -1;
    private byte[] pendingRndB;
    private Session session;

    /** Throws on the ChangeKey for this slot once, as if the tag left the field. -1 for never. */
    private int failChangeKeySlot = -1;

    public FakeNtag424(byte[] uid) {
        this.uid = uid.clone();
        for (int i = 0; i < keys.length; i++) {
            keys[i] = new byte[16];
        }
        // Factory NDEF file: empty message.
    }

    public byte[] key(int slot) {
        return keys[slot].clone();
    }

    public int keyVersion(int slot) {
        return keyVersions[slot];
    }

    public byte[] fileSettings() {
        return fileSettings.clone();
    }

    public byte[] ndefFile() {
        return ndef.clone();
    }

    public void failOnChangeKey(int slot) {
        failChangeKeySlot = slot;
    }

    @Override
    public byte[] transmit(byte[] c) throws IOException {
        if (c[0] == (byte) 0xFF && c[1] == (byte) 0xCA) {
            return concat(uid, new byte[] {(byte) 0x90, 0x00});
        }
        if (c[0] == 0x00 && c[1] == (byte) 0xA4) {
            return select(Arrays.copyOfRange(c, 5, 7));
        }
        if (c[0] == 0x00 && c[1] == (byte) 0xB0) {
            return readBinary((c[2] & 0xFF) << 8 | (c[3] & 0xFF), c[4] & 0xFF);
        }
        if (c[0] != (byte) 0x90 || !appSelected) {
            return sw(0x6D00);
        }
        int cmd = c[1] & 0xFF;
        byte[] data = c.length > 5 ? Arrays.copyOfRange(c, 5, 5 + (c[4] & 0xFF)) : new byte[0];
        if (cmd != 0xAF) {
            pendingKeyNo = -1;
        }
        return switch (cmd) {
            case 0x71 -> authFirst(data);
            case 0xAF -> authSecond(data);
            case 0x51 -> getCardUid(data);
            case 0xF5 -> getFileSettings(data);
            case 0x8D -> writeData(data);
            case 0x5F -> changeFileSettings(data);
            case 0xC4 -> changeKey(data);
            default -> error(0x911C);
        };
    }

    private byte[] select(byte[] fid) {
        if (Arrays.equals(fid, Hex.decode("E110"))) {
            appSelected = true;
            ndefSelected = false;
            session = null;
            return sw(0x9000);
        }
        if (Arrays.equals(fid, Hex.decode("E104")) && appSelected) {
            ndefSelected = true;
            countedSinceSelect = false;
            return sw(0x9000);
        }
        return sw(0x6A82);
    }

    private byte[] readBinary(int offset, int length) {
        if (!ndefSelected || session != null || offset + length > ndef.length) {
            return sw(0x6982);
        }
        byte[] content = ndef.clone();
        if ((fileSettings[0] & 0x40) != 0) {
            mirror(content);
        }
        return concat(Arrays.copyOfRange(content, offset, offset + length), sw(0x9000));
    }

    /** SDM for the layouts Knura writes: encrypted PICC data and a MAC over an empty input. */
    private void mirror(byte[] content) {
        int options = fileSettings[3] & 0xFF;
        int meta = (fileSettings[5] & 0xF0) >>> 4;
        int file = fileSettings[5] & 0x0F;
        if (options != 0xC1 || meta > 4 || file > 4) {
            throw new UnsupportedOperationException("fake supports only encrypted PICC data with a MAC");
        }
        int piccOffset = offset(6);
        int macInputOffset = offset(9);
        int macOffset = offset(12);
        if (!countedSinceSelect) {
            readCounter++;
            countedSinceSelect = true;
        }
        byte[] picc = new byte[16];
        picc[0] = (byte) 0xC7;
        System.arraycopy(uid, 0, picc, 1, 7);
        picc[8] = (byte) readCounter;
        picc[9] = (byte) (readCounter >>> 8);
        picc[10] = (byte) (readCounter >>> 16);
        byte[] rnd = new byte[5];
        random.nextBytes(rnd);
        System.arraycopy(rnd, 0, picc, 11, 5);
        byte[] encrypted = aes(Cipher.ENCRYPT_MODE, "CBC", keys[meta], new byte[16], picc);
        put(content, piccOffset, Hex.encode(encrypted));
        byte[] macInput = Arrays.copyOfRange(content, macInputOffset, macOffset);
        put(content, macOffset, Hex.encode(SdmVerifier.computeMac(uid, readCounter, macInput, keys[file])));
    }

    private int offset(int at) {
        return (fileSettings[at] & 0xFF) | (fileSettings[at + 1] & 0xFF) << 8 | (fileSettings[at + 2] & 0xFF) << 16;
    }

    private static void put(byte[] content, int at, String ascii) {
        byte[] b = ascii.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, content, at, b.length);
    }

    private byte[] authFirst(byte[] data) {
        session = null;
        int keyNo = data[0];
        if (keyNo < 0 || keyNo > 4) {
            return error(0x9140);
        }
        pendingKeyNo = keyNo;
        pendingRndB = new byte[16];
        random.nextBytes(pendingRndB);
        return concat(aes(Cipher.ENCRYPT_MODE, "CBC", keys[keyNo], new byte[16], pendingRndB), sw(0x91AF));
    }

    private byte[] authSecond(byte[] data) {
        if (pendingKeyNo < 0 || data.length != 32) {
            return error(0x91CA);
        }
        int keyNo = pendingKeyNo;
        pendingKeyNo = -1;
        byte[] key = keys[keyNo];
        byte[] plain = aes(Cipher.DECRYPT_MODE, "CBC", key, new byte[16], data);
        byte[] rndA = Arrays.copyOf(plain, 16);
        byte[] rndBRotated = Arrays.copyOfRange(plain, 16, 32);
        byte[] expected = concat(Arrays.copyOfRange(pendingRndB, 1, 16), new byte[] {pendingRndB[0]});
        if (!Arrays.equals(rndBRotated, expected)) {
            return error(0x91AE);
        }
        byte[] ti = new byte[4];
        random.nextBytes(ti);
        session = new Session(keyNo, key, rndA, pendingRndB, ti);
        byte[] rndARotated = concat(Arrays.copyOfRange(rndA, 1, 16), new byte[] {rndA[0]});
        byte[] answer = concat(ti, rndARotated, new byte[12]);
        return concat(aes(Cipher.ENCRYPT_MODE, "CBC", key, new byte[16], answer), OK);
    }

    private byte[] getCardUid(byte[] data) {
        if (session == null) {
            return error(0x917E);
        }
        if (!session.checkCommandMac(0x51, data)) {
            return error(0x911E);
        }
        return session.fullResponse(concat(uid));
    }

    private byte[] getFileSettings(byte[] data) {
        byte[] settings = concat(new byte[] {0x00}, fileSettings, new byte[] {0x00, 0x01, 0x00});
        if (session == null) {
            return concat(settings, OK);
        }
        if (!session.checkCommandMac(0xF5, data)) {
            return error(0x911E);
        }
        return session.macResponse(settings);
    }

    private byte[] writeData(byte[] data) {
        int write = fileSettings[2] & 0x0F;
        int readWrite = (fileSettings[1] & 0xF0) >>> 4;
        boolean allowed = write == 0xE
                || (session != null && (session.keyNo == write || session.keyNo == readWrite));
        if (!allowed) {
            return error(0x919D);
        }
        int offset = (data[1] & 0xFF) | (data[2] & 0xFF) << 8 | (data[3] & 0xFF) << 16;
        int length = (data[4] & 0xFF) | (data[5] & 0xFF) << 8 | (data[6] & 0xFF) << 16;
        if (data.length != 7 + length || offset + length > ndef.length) {
            return error(0x917E);
        }
        System.arraycopy(data, 7, ndef, offset, length);
        if (session != null) {
            session.ctr++;
        }
        return OK.clone();
    }

    private byte[] changeFileSettings(byte[] data) {
        int change = fileSettings[1] & 0x0F;
        if (session == null || session.keyNo != change) {
            return error(0x919D);
        }
        if (!session.checkCommandMac(0x5F, data)) {
            return error(0x911E);
        }
        fileSettings = session.decryptCommand(Arrays.copyOfRange(data, 1, data.length - 8));
        return session.macResponse(new byte[0]);
    }

    private byte[] changeKey(byte[] data) throws IOException {
        if (session == null || session.keyNo != 0) {
            return error(0x919D);
        }
        if (!session.checkCommandMac(0xC4, data)) {
            return error(0x911E);
        }
        int keyNo = data[0];
        if (keyNo == failChangeKeySlot) {
            failChangeKeySlot = -1;
            session = null;
            throw new IOException("SCARD_W_REMOVED_CARD (simulated)");
        }
        byte[] plain = session.decryptCommand(Arrays.copyOfRange(data, 1, data.length - 8));
        if (keyNo == session.keyNo) {
            keys[keyNo] = Arrays.copyOf(plain, 16);
            keyVersions[keyNo] = plain[16] & 0xFF;
            session = null;
            return OK.clone();
        }
        byte[] newKey = new byte[16];
        for (int i = 0; i < 16; i++) {
            newKey[i] = (byte) (plain[i] ^ keys[keyNo][i]);
        }
        CRC32 crc = new CRC32();
        crc.update(newKey);
        int jam = (int) ~crc.getValue();
        byte[] expected = {(byte) jam, (byte) (jam >>> 8), (byte) (jam >>> 16), (byte) (jam >>> 24)};
        if (!Arrays.equals(expected, Arrays.copyOfRange(plain, 17, 21))) {
            return error(0x911E);
        }
        keys[keyNo] = newKey;
        keyVersions[keyNo] = plain[16] & 0xFF;
        return session.macResponse(new byte[0]);
    }

    private byte[] error(int status) {
        session = null;
        return sw(status);
    }

    private static byte[] sw(int status) {
        return new byte[] {(byte) (status >>> 8), (byte) status};
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] aes(int mode, String chaining, byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/" + chaining + "/NoPadding");
            if (iv == null) {
                c.init(mode, new SecretKeySpec(key, "AES"));
            } else {
                c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            }
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The tag's side of an EV2 session. */
    private static final class Session {
        final int keyNo;
        final byte[] encKey;
        final byte[] macKey;
        final byte[] ti;
        int ctr;

        Session(int keyNo, byte[] key, byte[] rndA, byte[] rndB, byte[] ti) {
            this.keyNo = keyNo;
            this.ti = ti;
            byte[] sv1 = new byte[32];
            byte[] sv2 = new byte[32];
            System.arraycopy(Hex.decode("A55A00010080"), 0, sv1, 0, 6);
            System.arraycopy(Hex.decode("5AA500010080"), 0, sv2, 0, 6);
            for (byte[] sv : new byte[][] {sv1, sv2}) {
                sv[6] = rndA[0];
                sv[7] = rndA[1];
                for (int i = 0; i < 6; i++) {
                    sv[8 + i] = (byte) (rndA[2 + i] ^ rndB[i]);
                }
                System.arraycopy(rndB, 6, sv, 14, 10);
                System.arraycopy(rndA, 8, sv, 24, 8);
            }
            encKey = AesCmac.mac(key, sv1);
            macKey = AesCmac.mac(key, sv2);
        }

        boolean checkCommandMac(int cmd, byte[] data) {
            if (data.length < 8) {
                return false;
            }
            byte[] body = Arrays.copyOf(data, data.length - 8);
            byte[] mac = Arrays.copyOfRange(data, data.length - 8, data.length);
            return Arrays.equals(mac, mac(cmd, ctr, body));
        }

        byte[] decryptCommand(byte[] encrypted) {
            byte[] iv = aes(Cipher.ENCRYPT_MODE, "ECB", encKey, null, ivBlock(0xA5, 0x5A, ctr));
            byte[] plain = aes(Cipher.DECRYPT_MODE, "CBC", encKey, iv, encrypted);
            int end = plain.length - 1;
            while (plain[end] == 0) {
                end--;
            }
            return Arrays.copyOf(plain, end);
        }

        byte[] macResponse(byte[] data) {
            ctr++;
            return concat(data, mac(0x00, ctr, data), OK);
        }

        byte[] fullResponse(byte[] data) {
            ctr++;
            byte[] padded = Arrays.copyOf(data, (data.length / 16 + 1) * 16);
            padded[data.length] = (byte) 0x80;
            byte[] iv = aes(Cipher.ENCRYPT_MODE, "ECB", encKey, null, ivBlock(0x5A, 0xA5, ctr));
            byte[] enc = aes(Cipher.ENCRYPT_MODE, "CBC", encKey, iv, padded);
            return concat(enc, mac(0x00, ctr, enc), OK);
        }

        private byte[] ivBlock(int a, int b, int counter) {
            byte[] block = new byte[16];
            block[0] = (byte) a;
            block[1] = (byte) b;
            System.arraycopy(ti, 0, block, 2, 4);
            block[6] = (byte) counter;
            block[7] = (byte) (counter >>> 8);
            return block;
        }

        private byte[] mac(int first, int counter, byte[] body) {
            byte[] in = concat(new byte[] {(byte) first, (byte) counter, (byte) (counter >>> 8)}, ti, body);
            return AesCmac.truncate(AesCmac.mac(macKey, in));
        }
    }
}
