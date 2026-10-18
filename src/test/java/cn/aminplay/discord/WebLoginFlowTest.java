package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Synthetic authority + loopback Discord only. No real Gateway, credentials, registrations or account changes. */
public final class WebLoginFlowTest {
    private static final String GUILD = "111111111111111111", APP = "222222222222222222";
    private static final String USER = "333333333333333333", OTHER = "444444444444444444";
    private static final String ID = "555555555555555555", TOKEN = "synthetic_interaction_token";
    private static final String CODE = "A".repeat(43), LEGACY = "AMC-ABCD-EFGH-JKMN";
    private static int passed, failed;
    private static final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private static volatile String fault = "";

    record Call(String method, String path, JsonObject body) {}

    static final class Host implements WebLoginFlow.Host {
        final DiscordRest rest;
        BotConfig cfg;
        String app = APP, actor, interaction;
        int claims;
        boolean throwSecret;
        Map<String, Object> result = Map.of("status", "ok", "code", CODE, "expiresInSeconds", 120);
        final Set<String> issuedInteractions = new HashSet<>();
        boolean rejectReplay;

        Host(DiscordRest rest, boolean enabled) {
            this.rest = rest;
            cfg = config(enabled);
        }

        @Override public BotConfig cfg() { return cfg; }
        @Override public String applicationId() { return app; }
        @Override public DiscordRest rest() { return rest; }
        @Override public Map<String, Object> claim(String actor, String interaction) {
            claims++;
            this.actor = actor;
            this.interaction = interaction;
            if (throwSecret) throw new IllegalStateException(CODE + " " + TOKEN);
            if (rejectReplay && !issuedInteractions.add(interaction)) return Map.of("status", "rejected");
            return result;
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Call call = new Call(exchange.getRequestMethod(), exchange.getRequestURI().toString(), JsonParser.parseString(body).getAsJsonObject());
            calls.add(call);
            boolean fail = fault.equals("defer") && call.path.endsWith("/callback") || fault.equals("edit") && call.method.equals("PATCH");
            int status = fail ? 403 : call.path.endsWith("/callback") ? 204 : 200;
            byte[] response = (fail ? "{\"message\":\"" + CODE + " " + TOKEN + "\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : response.length);
            if (status != 204) exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(), "synthetic_bot_token", "http://127.0.0.1:" + server.getAddress().getPort());
            definitionsAndConfig();
            reflectionCompatibility();
            identityExtraction();

            Host disabled = new Host(rest, false);
            run(disabled, event());
            check(disabled.claims == 0 && content().contains("尚未开启") && !content().contains(CODE), "default disabled never calls authority");
            privateOnly("disabled");

            Host ok = new Host(rest, true);
            JsonObject request = event();
            request.getAsJsonObject("member").addProperty("nick", OTHER);
            request.getAsJsonObject("member").getAsJsonObject("user").addProperty("username", OTHER);
            JsonObject unrelated = new JsonObject();
            unrelated.addProperty("id", OTHER);
            request.add("user", unrelated);
            request.getAsJsonObject("data").add("resolved", unrelated.deepCopy());
            run(ok, request);
            check(ok.claims == 1 && USER.equals(ok.actor) && ID.equals(ok.interaction), "only stable member.user.id and exact interaction ID reach authority");
            check(content().contains(CODE) && content().contains("120 秒") && content().contains("只能使用一次"), "private code explains lifetime and single use");
            check(!content().contains(LEGACY) && !content().contains("/key ") && !content().contains("http"), "no binding-key reuse or code URL");
            privateOnly("success even with no DM capability");

            for (String status : List.of("disabled", "unavailable", "unbound", "rejected", "rate-limited", "unknown")) {
                Host denied = new Host(rest, true);
                denied.result = Map.of("status", status, "code", CODE, "message", TOKEN);
                run(denied, event());
                check(denied.claims == 1 && !content().contains(CODE) && !content().contains(TOKEN), "authority status is fail-closed: " + status);
                privateOnly(status);
            }

            Host replay = new Host(rest, true);
            replay.rejectReplay = true;
            run(replay, event());
            check(content().contains(CODE), "initial authority response is delivered");
            run(replay, event());
            check(replay.claims == 2 && !content().contains(CODE) && content().contains("失效"), "server replay denial does not replay prior code");

            Host providerError = new Host(rest, true);
            providerError.throwSecret = true;
            String output = quietRun(providerError, event());
            check(!content().contains(CODE) && !content().contains(TOKEN) && output.isEmpty(), "provider exception never leaks code/token or stacktrace");
            privateOnly("provider error");

            Host deferError = new Host(rest, true);
            fault = "defer";
            output = quietRun(deferError, event());
            check(deferError.claims == 0 && calls.size() == 1 && output.isEmpty(), "failed ephemeral defer issues no code and has no fallback/log");
            check(calls.get(0).body.getAsJsonObject("data").get("flags").getAsInt() == 64, "failed defer was still private");

            Host editError = new Host(rest, true);
            fault = "edit";
            output = quietRun(editError, event());
            check(editError.claims == 1 && calls.size() == 2 && output.isEmpty(), "failed private edit has no public/DM retry or exception log");
            privateOnly("failed edit");
            fault = "";

            JsonObject dm = event();
            dm.remove("guild_id");
            dm.add("user", dm.getAsJsonObject("member").getAsJsonObject("user").deepCopy());
            dm.remove("member");
            Host dmHost = new Host(rest, true);
            run(dmHost, dm);
            check(dmHost.claims == 0 && !content().contains(CODE), "DM command cannot issue a code and cannot trigger channel fallback");
            privateOnly("DM rejected");

            for (Consumer<JsonObject> mutate : invalidIdentityCases()) {
                Host host = new Host(rest, true);
                JsonObject invalid = event();
                mutate.accept(invalid);
                run(host, invalid);
                check(host.claims == 0 && !content().contains(CODE), "invalid identity/command never reaches authority");
                privateOnly("invalid identity");
            }
            for (Consumer<JsonObject> mutate : invalidEnvelopeCases()) {
                Host host = new Host(rest, true);
                JsonObject invalid = event();
                mutate.accept(invalid);
                run(host, invalid);
                check(host.claims == 0 && calls.isEmpty(), "invalid envelope cannot issue code or alter REST route");
            }
            badProviderResults();
        } finally {
            server.stop(0);
        }
        System.out.println("WebLoginFlowTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void definitionsAndConfig() {
        check(!BotConfig.load(new YamlConfiguration()).webLoginEnabled, "missing config is disabled");
        check(!config(false).webLoginEnabled && config(true).webLoginEnabled, "explicit gate only");
        JsonArray defaultCommands = Interactions.definitions().get(0).getAsJsonObject().getAsJsonArray("options");
        JsonArray enabledCommands = Interactions.definitions(true).get(0).getAsJsonObject().getAsJsonArray("options");
        Set<String> names = new HashSet<>();
        for (var item : defaultCommands) names.add(item.getAsJsonObject().get("name").getAsString());
        check(!names.contains(WebLoginFlow.COMMAND) && names.contains("key") && names.contains("lookup"), "default definitions preserve legacy onboarding and omit weblogin");
        check(enabledCommands.size() == defaultCommands.size() + 1 && enabledCommands.size() <= 25, "enabled definition adds exactly one command within limit");
        JsonObject newCommand = null;
        for (var item : enabledCommands) if (item.getAsJsonObject().get("name").getAsString().equals(WebLoginFlow.COMMAND)) newCommand = item.getAsJsonObject();
        check(newCommand != null && !newCommand.has("options") && !newCommand.has("default_member_permissions"), "new command takes no target identity or admin permission");
        check(!Interactions.commandSummary().contains("网页登录码") && Interactions.commandSummary(true).contains("网页登录码"), "summary follows default-off registration gate");
        check(config(false).intents() == config(true).intents(), "no additional Gateway intent");
    }

    /** Old API deliberately lacks claimWebLogin. */
    public static class OldApi {
        public Map<String, Object> claim(String id, String name) { return Map.of("status", "new", "key", LEGACY); }
        public List<Map<String, Object>> lookup(String query) { return List.of(); }
        public Map<String, Object> banDiscord(String id, String reason, String by, long until) { return Map.of(); }
        public Map<String, Object> unbanDiscord(String id, String by) { return Map.of(); }
        public Map<String, Object> resolvePlayer(String name) { return Map.of(); }
        public boolean isVerified(String id) { return false; }
        public Map<String, Object> confirmLogin(String token, String id, boolean yes) { return Map.of(); }
        public void setEventSink(Consumer<Map<String, Object>> sink) {}
        public Map<String, Object> status() { return Map.of(); }
    }

    public static final class NewApi extends OldApi {
        String actor, interaction;
        public Map<String, Object> claimWebLogin(String actor, String interaction) {
            this.actor = actor;
            this.interaction = interaction;
            return Map.of("status", "unavailable");
        }
    }

    private static void reflectionCompatibility() throws Exception {
        Constructor<HubAuthKeys> constructor = HubAuthKeys.class.getDeclaredConstructor(Class.class, Object.class);
        constructor.setAccessible(true);
        HubAuthKeys old = constructor.newInstance(OldApi.class, new OldApi());
        check(old.claim(USER, "synthetic").get("key").equals(LEGACY), "old API legacy key flow remains callable");
        check(old.claimWebLogin(USER, ID).get("status").equals("unavailable"), "missing additive method fails closed without breaking discovery");
        NewApi api = new NewApi();
        HubAuthKeys modern = constructor.newInstance(NewApi.class, api);
        check(modern.claimWebLogin(USER, ID).get("status").equals("unavailable") && USER.equals(api.actor) && ID.equals(api.interaction), "reflection passes only trusted IDs and preserves server readiness denial");
    }

    private static void identityExtraction() {
        check(WebLoginFlow.isCommand(event()) && USER.equals(WebLoginFlow.actor(event(), GUILD)), "recognizes guild subcommand and stable member ID");
        JsonObject key = event();
        key.getAsJsonObject("data").getAsJsonArray("options").get(0).getAsJsonObject().addProperty("name", "key");
        check(!WebLoginFlow.isCommand(key), "legacy key command is not intercepted");
        check(!WebLoginFlow.isCommand(null) && !WebLoginFlow.isCommand(new JsonObject()), "routing handles missing data without logging payload");
    }

    private static List<Consumer<JsonObject>> invalidIdentityCases() {
        return List.of(
                e -> e.addProperty("guild_id", OTHER),
                e -> e.remove("member"),
                e -> e.getAsJsonObject("member").remove("user"),
                e -> e.getAsJsonObject("member").getAsJsonObject("user").addProperty("id", "nickname"),
                e -> e.getAsJsonObject("member").getAsJsonObject("user").addProperty("id", 333333333333333333L),
                e -> e.getAsJsonObject("member").getAsJsonObject("user").addProperty("bot", true),
                e -> e.getAsJsonObject("member").getAsJsonObject("user").addProperty("system", true),
                e -> e.getAsJsonObject("member").getAsJsonObject("user").addProperty("bot", "false"),
                e -> e.getAsJsonObject("data").addProperty("name", "weblogin"),
                e -> e.getAsJsonObject("data").getAsJsonArray("options").get(0).getAsJsonObject().addProperty("type", 2),
                e -> {
                    JsonObject target = new JsonObject();
                    target.addProperty("name", "discord_id"); target.addProperty("type", 3); target.addProperty("value", OTHER);
                    JsonArray targets = new JsonArray(); targets.add(target);
                    e.getAsJsonObject("data").getAsJsonArray("options").get(0).getAsJsonObject().add("options", targets);
                }
        );
    }

    private static List<Consumer<JsonObject>> invalidEnvelopeCases() {
        return List.of(
                e -> e.addProperty("application_id", OTHER),
                e -> e.remove("application_id"),
                e -> e.addProperty("id", "555555555555555555/../../../channels"),
                e -> e.addProperty("token", "token/../../channels/" + CODE),
                e -> e.remove("token"),
                e -> e.addProperty("type", 3),
                e -> e.addProperty("type", "2")
        );
    }

    private static void badProviderResults() {
        List<Map<String, Object>> malformed = new ArrayList<>();
        malformed.add(Map.of("status", "ok", "code", LEGACY, "expiresInSeconds", 120));
        malformed.add(Map.of("status", "ok", "code", CODE + "` @everyone", "expiresInSeconds", 120));
        malformed.add(Map.of("status", "ok", "code", CODE));
        for (Object seconds : List.of(0, -1, 121, 1.5, Double.NaN, "120")) malformed.add(Map.of("status", "ok", "code", CODE, "expiresInSeconds", seconds));
        malformed.add(Map.of("status", "ok", "code", CODE, "expiresInSeconds", Double.POSITIVE_INFINITY));
        malformed.add(Map.of("status", "ok"));
        malformed.add(Map.of());
        malformed.add(null);
        for (Map<String, Object> result : malformed) check(!WebLoginFlow.reply(result).contains(CODE) && !WebLoginFlow.reply(result).contains(LEGACY), "malformed/missing result cannot expose a secret");
    }

    private static BotConfig config(boolean enabled) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("discord.guild-id", GUILD);
        cfg.set("web-login.enabled", enabled);
        return BotConfig.load(cfg);
    }

    private static JsonObject event() {
        return JsonParser.parseString("{\"type\":2,\"id\":\"" + ID + "\",\"token\":\"" + TOKEN + "\",\"application_id\":\"" + APP + "\",\"guild_id\":\"" + GUILD
                + "\",\"member\":{\"user\":{\"id\":\"" + USER + "\",\"username\":\"synthetic\"}},\"data\":{\"name\":\"minmin\",\"options\":[{\"type\":1,\"name\":\"weblogin\"}]}}").getAsJsonObject();
    }

    private static void run(Host host, JsonObject event) {
        calls.clear();
        new WebLoginFlow(host).handle(event);
    }

    private static String quietRun(Host host, JsonObject event) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture); System.setErr(capture);
            run(host, event);
        } finally {
            System.setOut(out); System.setErr(err);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String content() {
        return calls.size() < 2 ? "" : calls.getLast().body.get("content").getAsString();
    }

    private static void privateOnly(String label) {
        boolean ok = calls.size() == 2;
        if (ok) {
            Call defer = calls.getFirst(), edit = calls.getLast();
            ok = defer.method.equals("POST") && defer.path.equals("/interactions/" + ID + "/" + TOKEN + "/callback")
                    && defer.body.get("type").getAsInt() == 5 && defer.body.getAsJsonObject("data").get("flags").getAsInt() == 64
                    && edit.method.equals("PATCH") && edit.path.equals("/webhooks/" + APP + "/" + TOKEN + "/messages/@original")
                    && edit.body.get("flags").getAsInt() == 64 && edit.body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty()
                    && !defer.path.contains(CODE) && !edit.path.contains(CODE);
        }
        check(ok, "only ephemeral original, no DM/public endpoint: " + label);
    }

    private static void check(boolean condition, String name) {
        if (condition) passed++;
        else { failed++; System.out.println("FAIL " + name); }
    }
}
