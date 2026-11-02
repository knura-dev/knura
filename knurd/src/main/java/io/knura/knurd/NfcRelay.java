package io.knura.knurd;

import io.knura.core.claim.ClaimCode;
import io.knura.core.crypto.Hex;
import io.knura.core.keys.TagKeyDerivation;
import io.knura.core.ntag424.Ntag424;
import io.knura.core.ntag424.Ntag424Exception;
import io.knura.core.ntag424.TapUrlLayout;
import io.knura.core.ntag424.Transceiver;
import io.knura.core.provision.TagKeySet;
import io.knura.core.provision.TagProvisioner;
import io.knura.core.sdm.SdmVerificationException;
import io.knura.core.sdm.VerifiedTap;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.System.Logger.Level;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Sets tags up through a phone's NFC instead of a USB reader. The phone app is only a relay:
 * knurd runs the same {@link TagProvisioner} knurctl runs, and each command it would send a reader
 * goes to the phone in the answer to the phone's last request. The phone hands it to the tag and
 * posts the tag's reply back. Tag keys are worked out here from the master key and only reach the
 * tag inside its own encrypted session, so the phone never holds a key.
 *
 * <ul>
 *   <li>{@code POST /v1/nfc/sessions} with {@code {"action":"provision","uid":"04..."}} starts a
 *       session. {@code action} is provision, reset, info or tap; {@code uid} is the 7-byte UID
 *       the phone read; {@code from} is factory, knura or claim, the keys the tag has now
 *       (provision defaults to factory, reset to knura). With claim, {@code claim} holds the code
 *       from the tag's claim card, or the {@code knura://claim?...} link from its QR code.
 *   <li>{@code POST /v1/nfc/sessions/<id>} with {@code {"response":"<hex>"}}, the tag's reply to
 *       the last command, or {@code {"error":"tag lost"}} when the phone couldn't get one.
 * </ul>
 *
 * <p>Both answer {@code {"status":"command","session":...,"command":"<hex>","log":[...]}} with the
 * next command for the tag, {@code {"status":"done",...,"result":{...}}} or {@code
 * {"status":"failed",...,"detail":"..."}}. {@code log} holds the steps taken since the last answer.
 *
 * <p>Only one session runs at a time, and only the token that started it can continue it. It ends
 * when its job finishes or fails, or when the phone doesn't answer within {@code replyWait}. Like
 * knurctl, provisioning changes key 0 last, so a session cut short can simply be run again.
 */
final class NfcRelay {
    private static final System.Logger LOG = System.getLogger("knurd.nfc");
    private static final Pattern UID = Pattern.compile("[0-9A-Fa-f]{14}");
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{22}");
    /** Longest tag reply accepted, in bytes: an NDEF read plus status and MAC fits well inside. */
    private static final int MAX_RESPONSE = 512;

    /** How long a session waits for the phone's next message before it gives up on the tag. */
    static final Duration REPLY_WAIT = Duration.ofSeconds(20);

    /** An answer for the admin API: HTTP status, JSON body and a line for the audit log. */
    record Reply(int status, String json, String detail) {}

    enum Action {
        PROVISION, RESET, INFO, TAP
    }

    private sealed interface Event permits Command, Finished {}

    private record Command(byte[] apdu) implements Event {}

    /** {@code json} is the result object when {@code ok}, else the reason it failed. */
    private record Finished(boolean ok, String json) implements Event {}

    private final TagKeys keys;
    private final String tapBase;
    private final Set<String> tokens;
    private final Duration replyWait;
    private final SecureRandom random = new SecureRandom();
    private Session active; // guarded by this

    /**
     * @param keys must be in master-key mode
     * @param tapBase the tap URL base provisioned tags get, as knurctl's default
     * @param tokens the names of the tokens allowed to use it
     * @param replyWait how long a session waits for the phone before giving up
     */
    NfcRelay(TagKeys keys, String tapBase, Set<String> tokens, Duration replyWait) {
        if (!"master".equals(keys.mode())) {
            throw new IllegalArgumentException("setting tags up through a phone needs master.key");
        }
        TapUrlLayout.forBaseUrl(tapBase); // Fails now, not on the first tag, if it can't go on a tag.
        this.keys = keys;
        this.tapBase = tapBase;
        this.tokens = java.util.concurrent.ConcurrentHashMap.newKeySet();
        this.tokens.addAll(tokens);
        this.replyWait = replyWait;
    }

    boolean allows(String tokenName) {
        return tokenName != null && tokens.contains(tokenName);
    }

    /** Lets one more token set tags up, as when the setup page pairs a phone; no restart needed. */
    void allow(String tokenName) {
        tokens.add(tokenName);
    }

    /** Stops a token setting tags up, as when a phone is removed; no restart needed. */
    void disallow(String tokenName) {
        tokens.remove(tokenName);
    }

    /** The names allowed now. */
    Set<String> allowed() {
        return Set.copyOf(tokens);
    }

    /** The tap URL base tags set up through a phone get. */
    String tapBase() {
        return tapBase;
    }

    static boolean isSessionId(String id) {
        return SESSION_ID.matcher(id).matches();
    }

    /** Starts a session and answers with the first command for the tag. */
    Reply start(String tokenName, Map<String, Object> body) {
        Action action;
        try {
            action = Action.valueOf(text(body, "action", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return bad("action must be provision, reset, info or tap");
        }
        String uidHex = text(body, "uid", "");
        if (!UID.matcher(uidHex).matches()) {
            return bad("uid must be the tag's 7-byte UID in hex (14 digits)");
        }
        String from = text(body, "from", action == Action.RESET ? "knura" : "factory");
        if (!from.equals("factory") && !from.equals("knura") && !from.equals("claim")) {
            return bad("from must be factory, knura or claim");
        }
        byte[] uid = Hex.decode(uidHex);
        ClaimCode claim = null;
        if (from.equals("claim")) {
            if (action != Action.PROVISION && action != Action.RESET) {
                return bad("from claim only works with provision or reset");
            }
            String text = text(body, "claim", "");
            try {
                claim = ClaimCode.parse(text);
            } catch (IllegalArgumentException e) {
                return bad(e.getMessage());
            }
            byte[] cardUid = ClaimCode.linkUid(text);
            if (cardUid != null && !Arrays.equals(cardUid, uid)) {
                claim.destroy();
                return bad("this claim card is for tag " + Hex.encode(cardUid) + ", not " + uidHex);
            }
        }
        byte[] id = new byte[16];
        random.nextBytes(id);
        Session session = new Session(Base64.getUrlEncoder().withoutPadding().encodeToString(id), tokenName, action,
                uid, from, claim);
        synchronized (this) {
            if (active != null) {
                session.forgetClaim();
                return new Reply(409, error("busy", "another phone is setting a tag up; try again when it finishes"),
                        "nfc busy");
            }
            active = session;
        }
        Thread.ofVirtual().name("nfc-" + session.id).start(session::run);
        session.lock.lock();
        try {
            return session.next();
        } finally {
            session.lock.unlock();
        }
    }

    /** Passes the tag's reply on and answers with what comes next. */
    Reply step(String tokenName, String id, Map<String, Object> body) {
        Session session;
        synchronized (this) {
            session = active;
        }
        if (session == null || !session.id.equals(id) || !session.token.equals(tokenName)) {
            return new Reply(404, error("not_found", "no such session; it may have finished or timed out"), null);
        }
        if (!session.lock.tryLock()) {
            return new Reply(409, error("busy", "this session is already answering a request"), "nfc busy");
        }
        try {
            return session.answer(body);
        } finally {
            session.lock.unlock();
        }
    }

    private synchronized void ended(Session session) {
        if (active == session) {
            active = null;
        }
    }

    /** One run of one action on one tag. The job runs on its own thread; the phone drives it. */
    private final class Session implements Transceiver {
        final String id;
        final String token;
        final Action action;
        final byte[] uid;
        final String from;
        /** The claim card's code when {@code from} is claim, else null. Wiped when the job ends. */
        final ClaimCode claim;
        final ReentrantLock lock = new ReentrantLock();
        private final BlockingQueue<Event> toPhone = new LinkedBlockingQueue<>();
        private final BlockingQueue<Object> fromPhone = new LinkedBlockingQueue<>();
        private final List<String> log = new ArrayList<>(); // guarded by itself
        private int logSent; // guarded by lock
        private boolean awaiting; // guarded by lock

        Session(String id, String token, Action action, byte[] uid, String from, ClaimCode claim) {
            this.id = id;
            this.token = token;
            this.action = action;
            this.uid = uid;
            this.from = from;
            this.claim = claim;
        }

        /** The keys the tag has now. */
        TagKeySet current(TagKeySet knura) {
            return switch (from) {
                case "knura" -> knura;
                case "claim" -> claim.keys(uid);
                default -> TagKeySet.factory();
            };
        }

        void forgetClaim() {
            if (claim != null) {
                claim.destroy();
            }
        }

        @Override
        public byte[] transmit(byte[] command) throws IOException {
            toPhone.add(new Command(command.clone()));
            Object reply;
            try {
                reply = fromPhone.poll(replyWait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("stopped");
            }
            if (reply == null) {
                throw new IOException("the phone stopped answering");
            }
            if (reply instanceof String why) {
                throw new IOException("the phone could not reach the tag: " + why);
            }
            return (byte[]) reply;
        }

        void log(String line) {
            synchronized (log) {
                log.add(line);
            }
        }

        void run() {
            Finished end;
            try {
                end = new Finished(true, perform(this));
            } catch (IOException e) {
                end = new Finished(false, e.getMessage());
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "nfc " + action + " failed", e);
                end = new Finished(false, "internal error");
            } finally {
                forgetClaim();
                ended(this);
            }
            toPhone.add(end);
        }

        /** Waits for the job's next command or its end. Called holding {@link #lock}. */
        Reply next() {
            Event event;
            try {
                // The job waits replyWait for the phone; give it a little longer to finish up.
                event = toPhone.poll(replyWait.toMillis() + 5000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event = null;
            }
            String head = "{\"session\":" + Json.quote(id) + ",\"log\":" + newLog();
            String what = "nfc " + action.name().toLowerCase(Locale.ROOT) + " " + Hex.encode(uid);
            if (event == null) {
                return new Reply(504, error("timeout", "the tag job did not answer in time"), what + " timed out");
            }
            return switch (event) {
                case Command c -> {
                    awaiting = true;
                    yield new Reply(200, head + ",\"status\":\"command\",\"command\":\"" + Hex.encode(c.apdu()) + "\"}",
                            null);
                }
                case Finished f when f.ok() -> new Reply(200, head + ",\"status\":\"done\",\"result\":" + f.json() + "}",
                        what + " done");
                case Finished f -> new Reply(200, head + ",\"status\":\"failed\",\"detail\":" + Json.quote(f.json()) + "}",
                        what + " failed: " + f.json());
            };
        }

        /** Takes the phone's reply to the last command. Called holding {@link #lock}. */
        Reply answer(Map<String, Object> body) {
            if (!awaiting) {
                return new Reply(409, error("busy", "this session is not waiting for a tag reply"), null);
            }
            if (body.get("response") instanceof String hex) {
                byte[] response;
                try {
                    response = Hex.decode(hex);
                } catch (IllegalArgumentException e) {
                    return bad("response must be hex");
                }
                if (response.length < 2 || response.length > MAX_RESPONSE) {
                    return bad("response must be 2 to " + MAX_RESPONSE + " bytes");
                }
                fromPhone.add(response);
            } else if (body.get("error") instanceof String why) {
                fromPhone.add(why.length() > 100 ? why.substring(0, 100) : why);
            } else {
                return bad("send the tag's reply as response, or what went wrong as error");
            }
            awaiting = false;
            return next();
        }

        private String newLog() {
            List<String> lines;
            synchronized (log) {
                lines = List.copyOf(log.subList(logSent, log.size()));
                logSent = log.size();
            }
            StringBuilder json = new StringBuilder("[");
            for (String line : lines) {
                json.append(json.length() > 1 ? "," : "").append(Json.quote(line));
            }
            return json.append(']').toString();
        }
    }

    /** Does the session's action, the way the knurctl command of the same name does. */
    private String perform(Session s) throws IOException {
        Ntag424 tag = new Ntag424(s);
        TagProvisioner provisioner = new TagProvisioner(s::log);
        TagKeySet knura = keys.knuraKeys(s.uid).orElseThrow();
        String uidHex = Hex.encode(s.uid);
        try {
            switch (s.action) {
                case PROVISION -> {
                    TagKeySet current = s.current(knura);
                    TagProvisioner.Result r;
                    try {
                        r = provisioner.provision(tag, s.uid, current, knura, TapUrlLayout.forBaseUrl(tapBase));
                    } catch (Ntag424Exception e) {
                        throw explainKeyMismatch(e, s.from);
                    }
                    return "{\"uid\":" + Json.quote(r.uidHex()) + ",\"tapUrl\":" + Json.quote(r.tapUrl())
                            + ",\"counter\":" + r.tap().readCounter() + "}";
                }
                case RESET -> {
                    try {
                        provisioner.reset(tag, s.uid, s.current(knura));
                    } catch (Ntag424Exception e) {
                        throw explainKeyMismatch(e, s.from);
                    }
                    return "{\"uid\":" + Json.quote(uidHex) + "}";
                }
                case INFO -> {
                    String url = null;
                    try {
                        url = TagProvisioner.readTapUrl(tag);
                    } catch (Ntag424Exception e) {
                        s.log("No tap URL: " + e.getMessage());
                    }
                    String keyKind = tryKey0(tag, Ntag424.FACTORY_KEY) ? "factory"
                            : tryKey0(tag, knura.key(0)) ? "knura" : "other";
                    String check = url != null && keyKind.equals("knura") ? check(url, knura) : "";
                    return "{\"uid\":" + Json.quote(uidHex) + ",\"url\":" + Json.quote(url) + ",\"keys\":"
                            + Json.quote(keyKind) + check + "}";
                }
                case TAP -> {
                    String url = TagProvisioner.readTapUrl(tag);
                    return "{\"uid\":" + Json.quote(uidHex) + ",\"url\":" + Json.quote(url) + check(url, knura) + "}";
                }
                default -> throw new IllegalStateException(s.action.toString());
            }
        } finally {
            knura.destroy();
        }
    }

    /** {@code ,"genuine":true,"counter":N} or {@code ,"genuine":false,"why":"..."}. */
    private static String check(String url, TagKeySet keys) {
        try {
            VerifiedTap tap = TagProvisioner.verifyTapUrl(
                    url, keys.key(TagKeyDerivation.SDM_META_READ_KEY), keys.key(TagKeyDerivation.SDM_FILE_READ_KEY));
            return ",\"genuine\":true,\"counter\":" + tap.readCounter();
        } catch (SdmVerificationException e) {
            return ",\"genuine\":false,\"why\":" + Json.quote(e.getMessage());
        }
    }

    private static boolean tryKey0(Ntag424 tag, byte[] key) throws IOException {
        tag.selectApplication();
        try {
            tag.authenticate(0, key);
            return true;
        } catch (Ntag424Exception e) {
            if (e.isAuthenticationError()) {
                return false;
            }
            throw e;
        } finally {
            tag.endSession();
        }
    }

    private static IOException explainKeyMismatch(Ntag424Exception e, String from) {
        if (!e.isAuthenticationError()) {
            return e;
        }
        return new IOException("the tag refused the key: " + switch (from) {
            case "factory" -> "this tag no longer has factory keys. If Knura set it up with this master key, use from knura.";
            case "claim" -> "this tag doesn't open with that claim code. Check the card belongs to this tag;"
                    + " if the tag was already claimed, use from knura.";
            default -> "this tag's key 0 is not the Knura key from this master key. It may have factory keys (try info),"
                        + " another master key, or keys from other software.";
        }, e);
    }

    private static String text(Map<String, Object> body, String name, String fallback) {
        return body.get(name) instanceof String s ? s.trim() : fallback;
    }

    private static Reply bad(String detail) {
        return new Reply(400, error("malformed", detail), null);
    }

    static String error(String status, String detail) {
        return "{\"status\":" + Json.quote(status) + (detail == null ? "" : ",\"detail\":" + Json.quote(detail)) + "}";
    }
}
