package top.harcochen.dsh.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import top.harcochen.dsh.DshJson;

/** A retained Host PTY attachment. Input is discarded across every disconnect. */
public final class DshRuntimeTerminal implements AutoCloseable {
    private record Input(JsonObject command, int bytes, int cost) {}

    private final DshRemoteService remote;
    private final ExecutorService inputExecutor;
    private final Supplier<Boolean> current;
    private final Consumer<JsonObject> sink;
    private final String session;
    private final String id;
    private final JsonObject limits;
    private final boolean legacy;
    private final String attachmentId = UUID.randomUUID().toString();
    private DshRemoteService.FeatureStream retain;
    private DshRemoteService.FeatureStream attach;
    private boolean attached;
    private boolean ended;
    private boolean writable;
    private long outputSequence = -1;
    private long sequence;
    private long acknowledged;
    private int bytes;
    private int buffered;
    private int inFlight;
    private int window = Integer.MAX_VALUE;
    private int overhead;
    private final Deque<Input> queue = new ArrayDeque<>();
    private final Map<Long, Input> pending = new LinkedHashMap<>();

    public DshRuntimeTerminal(
            DshRemoteService remote,
            ExecutorService inputExecutor,
            Supplier<Boolean> current,
            Consumer<JsonObject> sink,
            String session,
            String id,
            JsonObject limits) {
        this.remote = remote;
        this.inputExecutor = inputExecutor;
        this.current = current;
        this.sink = sink;
        this.session = session;
        this.id = id;
        this.limits = limits;
        legacy = !limits.has("maxBufferedBytes");
    }

    public static JsonObject environment(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw invalid("Invalid terminal environment");
        JsonObject limits = value.getAsJsonObject();
        if (DshJson.strictString(limits, "cwd") == null)
            throw invalid("Invalid terminal directory");
        for (String key : new String[] {"maxInputBytes", "maxCols", "maxRows", "scrollback"}) {
            long number = natural(limits.get(key));
            if (number < (key.equals("scrollback") ? 0 : 1) || number > Integer.MAX_VALUE)
                throw invalid("Invalid terminal limits");
        }
        if (limits.has("maxBufferedBytes")
                && (natural(limits.get("maxBufferedBytes")) < 1
                        || natural(limits.get("maxBufferedBytes")) > Integer.MAX_VALUE))
            throw invalid("Invalid terminal queue limit");
        return limits.deepCopy();
    }

    public static JsonObject info(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw invalid("Invalid terminal metadata");
        JsonObject info = value.getAsJsonObject();
        if (DshJson.strictString(info, "id") == null
                || DshJson.string(info, "id").isBlank()
                || DshJson.strictString(info, "title") == null
                || DshJson.strictString(info, "cwd") == null
                || natural(info.get("cols")) < 1
                || natural(info.get("rows")) < 1
                || !java.util.Set.of("running", "exited", "failed")
                        .contains(DshJson.stringOr(info, "state", ""))
                || !info.has("exitCode")
                || (!info.get("exitCode").isJsonNull() && !integer(info.get("exitCode")))
                || (info.has("error") && DshJson.strictString(info, "error") == null))
            throw invalid("Invalid terminal metadata");
        return info.deepCopy();
    }

    public synchronized void start() {
        if (ended || retain != null) return;
        JsonObject args = args(false);
        retain =
                remote.duplexFeature(
                        "terminal/retain",
                        args,
                        frame -> {
                            synchronized (this) {
                                if (ended || !current.get()) return;
                                if (!"retained".equals(DshJson.string(frame, "type"))) {
                                    fail("Invalid terminal retention acknowledgement");
                                    return;
                                }
                                if (attached) return;
                                attached = true;
                                attach =
                                        remote.duplexFeature(
                                                legacy ? "terminal/follow" : "terminal/attach",
                                                args(true),
                                                this::frame,
                                                this::terminal);
                            }
                        },
                        this::terminal);
    }

    public synchronized void write(String data) {
        if (data == null || data.isEmpty()) return;
        JsonObject command = new JsonObject();
        command.addProperty("type", "write");
        command.addProperty("data", data);
        send(command, data.getBytes(StandardCharsets.UTF_8).length);
    }

    public synchronized void resize(int cols, int rows) {
        JsonObject command = new JsonObject();
        command.addProperty("type", "resize");
        command.addProperty("cols", Math.max(1, Math.min(cols, limits.get("maxCols").getAsInt())));
        command.addProperty("rows", Math.max(1, Math.min(rows, limits.get("maxRows").getAsInt())));
        send(command, 0);
    }

    private void send(JsonObject command, int count) {
        if (!writable || ended || !current.get()) return;
        command.addProperty("sequence", sequence + 1);
        int cost = command.toString().getBytes(StandardCharsets.UTF_8).length + overhead;
        int maximum =
                Math.min(
                        16 * 1024 * 1024,
                        limits.get(legacy ? "maxInputBytes" : "maxBufferedBytes").getAsInt());
        if ((long) bytes + count > limits.get("maxInputBytes").getAsInt()
                || (long) buffered + cost > maximum
                || cost > window) {
            fail("Terminal input exceeds the Runtime limit. Send smaller chunks.");
            return;
        }
        sequence++;
        bytes += count;
        buffered += cost;
        queue.addLast(new Input(command, count, cost));
        flush();
    }

    private synchronized void flush() {
        while (!ended && writable && !queue.isEmpty()) {
            Input next = queue.peekFirst();
            if ((long) inFlight + next.cost > window) return;
            queue.removeFirst();
            long seq = next.command.get("sequence").getAsLong();
            pending.put(seq, next);
            inFlight += next.cost;
            if (!legacy) attach.send(next.command);
            else
                inputExecutor.execute(
                        () -> {
                            synchronized (this) {
                                if (ended || !writable || !current.get()) return;
                            }
                            try {
                                JsonObject args = args(true);
                                String type = DshJson.string(next.command, "type");
                                if ("write".equals(type))
                                    args.add("data", next.command.get("data"));
                                else {
                                    args.add("cols", next.command.get("cols"));
                                    args.add("rows", next.command.get("rows"));
                                }
                                remote.callFeature("terminal/" + type, args);
                                synchronized (this) {
                                    if (!ended) acknowledge(seq, null);
                                }
                            } catch (RuntimeException error) {
                                fail(DshJson.message(error));
                            }
                        });
        }
    }

    private synchronized void frame(JsonObject frame) {
        if (ended || !current.get()) {
            close();
            return;
        }
        try {
            String type = DshJson.string(frame, "type");
            JsonObject view = new JsonObject();
            if (outputSequence < 0) {
                if (!"snapshot".equals(type)
                        || natural(frame.get("sequence")) < 0
                        || DshJson.strictString(frame, "screen") == null)
                    throw invalid("Terminal omitted its screen snapshot");
                if (frame.has("inputWindow")) {
                    if (!frame.get("inputWindow").isJsonObject())
                        throw invalid("Invalid terminal input window");
                    JsonObject input = frame.getAsJsonObject("inputWindow");
                    long max = natural(input.get("maxBytes")),
                            extra = natural(input.get("frameOverheadBytes"));
                    if (max < 1 || max > Integer.MAX_VALUE || extra < 0 || extra > 1_000_000)
                        throw invalid("Invalid terminal input window");
                    window = (int) max;
                    overhead = (int) extra;
                }
                outputSequence = frame.get("sequence").getAsLong();
                view.addProperty("type", "snapshot");
                view.addProperty("data", DshJson.string(frame, "screen"));
                view.add("scrollback", limits.get("scrollback"));
                applyState(frame, view);
            } else if ("output".equals(type)
                    && natural(frame.get("sequence")) == outputSequence + 1
                    && DshJson.strictString(frame, "data") != null) {
                outputSequence++;
                view.addProperty("type", "output");
                view.add("data", frame.get("data"));
            } else if ("state".equals(type)) {
                view.addProperty("type", "state");
                applyState(frame, view);
            } else if ("ack".equals(type) && !legacy) {
                String refusal = DshJson.strictString(frame, "refusal");
                if (frame.has("refusal")
                        && !"read-only".equals(refusal)
                        && !"not-running".equals(refusal))
                    throw invalid("Invalid terminal input refusal");
                acknowledge(natural(frame.get("sequence")), refusal);
                return;
            } else throw invalid("Terminal output has an invalid frame or sequence gap");
            view.addProperty("writable", writable);
            sink.accept(view);
            flush();
        } catch (RuntimeException invalid) {
            fail(DshJson.message(invalid));
        }
    }

    private void applyState(JsonObject frame, JsonObject view) {
        JsonObject info = info(frame.get("info"));
        if (!id.equals(DshJson.string(info, "id")))
            throw invalid("Runtime terminal identity changed");
        if (!legacy
                && (!frame.has("writable")
                        || !frame.get("writable").isJsonPrimitive()
                        || !frame.getAsJsonPrimitive("writable").isBoolean()))
            throw invalid("Invalid terminal input ownership");
        writable =
                "running".equals(DshJson.string(info, "state"))
                        && (legacy
                                ? attachmentId.equals(DshJson.string(info, "controllerId"))
                                : DshJson.bool(frame, "writable", false));
        view.addProperty(
                "message",
                "DSH · "
                        + DshJson.string(info, "title")
                        + (writable ? "" : " · " + DshJson.string(info, "state") + " / read only"));
    }

    private synchronized void acknowledge(long seq, String refusal) {
        Input sent = pending.remove(seq);
        if (seq != acknowledged + 1 || sent == null)
            throw invalid("Invalid terminal input acknowledgement");
        acknowledged = seq;
        bytes -= sent.bytes;
        buffered -= sent.cost;
        inFlight -= sent.cost;
        if (refusal != null) {
            fail("Terminal input control was revoked: " + refusal);
            return;
        }
        flush();
    }

    private JsonObject args(boolean agent) {
        JsonObject args = new JsonObject();
        args.addProperty(agent ? "agentId" : "sessionId", session);
        args.addProperty("id", id);
        if (agent && legacy) args.addProperty("attachmentId", attachmentId);
        return args;
    }

    private void terminal(JsonObject error) {
        fail(
                error == null
                        ? "Runtime terminal attachment ended. Reopen to reconnect."
                        : DshJson.stringOr(error, "message", "Runtime terminal disconnected"));
    }

    private synchronized void fail(String message) {
        if (ended) return;
        close();
        JsonObject view = new JsonObject();
        view.addProperty("type", "state");
        view.addProperty("writable", false);
        view.addProperty("message", message);
        sink.accept(view);
    }

    @Override
    public synchronized void close() {
        ended = true;
        writable = false;
        queue.clear();
        pending.clear();
        if (attach != null) attach.close();
        if (retain != null) retain.close();
    }

    private static long natural(JsonElement value) {
        return integer(value) && value.getAsDouble() >= 0 ? value.getAsLong() : -1;
    }

    private static boolean integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            return false;
        double n = value.getAsDouble();
        return Double.isFinite(n) && Math.abs(n) <= 9_007_199_254_740_991d && n == Math.rint(n);
    }

    private static DshRemoteException invalid(String message) {
        return DshRemoteException.protocol("terminal", message, null);
    }
}
