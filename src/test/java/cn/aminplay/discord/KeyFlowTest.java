package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 2026-10-07 auth-gate：领取资格、回复文字（只有本人可见 + 私信备份）、Mojang 解析、/minmin 指令定义。 */
public final class KeyFlowTest {
    private static int passed, failed;

    public static void main(String[] args) {
        long now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli();
        // snowflake for 2026-10-01 (6 days old) and 2025-01-01
        String young = String.valueOf((Instant.parse("2026-10-01T12:00:00Z").toEpochMilli() - 1420070400000L) << 22);
        String old = String.valueOf((Instant.parse("2025-01-01T00:00:00Z").toEpochMilli() - 1420070400000L) << 22);
        check(KeyRules.created(old) == Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), "雪花时间");
        check(KeyRules.ineligible(young, "2026-01-01T00:00:00+00:00", now, 7, 1).contains("还要等 1 天"), "账号不满 7 天");
        check(KeyRules.ineligible(old, "2026-10-07T00:00:00.000000+00:00", now, 7, 1).contains("加入本 Discord 服务器不满 1 天"), "入群不满 1 天");
        check(KeyRules.ineligible(old, "2026-10-01T00:00:00.000000+00:00", now, 7, 1) == null, "满足条件");
        check(KeyRules.ineligible(young, null, now, 0, 0) == null, "门槛 0 = 不限制");
        check(KeyRules.ineligible(old, null, now, 7, 1) != null, "没有入群时间 → 拒绝（防私信里用）");
        check(KeyRules.ineligible("abc", null, now, 7, 1) != null, "坏 id");

        String key = "AMC-ABCD-EFGH-JKMN";
        String r1 = KeyRules.claimReply(Map.of("status", "new", "key", key), true);
        check(r1.contains(key) && r1.contains("/key " + key) && r1.contains("/register") && r1.contains("已私信"), "新密钥 + 私信成功");
        String r2 = KeyRules.claimReply(Map.of("status", "resend", "key", key), false);
        check(r2.contains(key) && r2.contains("同一个密钥") && r2.contains("私信备份没发出去"), "重发 + 私信关闭也照样给密钥");
        check(!KeyRules.claimReply(Map.of("status", "new", "key", key), null).contains("私信"), "不发私信时不提");
        check(KeyRules.claimReply(Map.of("status", "bound", "name", "crackA"), null).contains("crackA"), "已绑定");
        check(!KeyRules.claimReply(Map.of("status", "bound", "name", "crackA"), null).contains("AMC-"), "已绑定不再给密钥");
        check(KeyRules.claimReply(Map.of("status", "banned", "reason", "x"), null).contains("封禁"), "封禁");
        check(KeyRules.claimReply(Map.of("status", "disabled"), null).contains("没有开启"), "关闭");
        check(KeyRules.dmCopy(key).contains(key), "私信备份");
        check(KeyRules.loginConfirm("crackA", 120).contains("有人用 **crackA** 登录") && KeyRules.loginConfirm("crackA", 120).contains("是你吗"), "登录确认文字");
        String d = KeyRules.describe(Map.of("discordId", "1", "bound", true, "name", "crackA", "boundAt", 1000L, "lastSeen", 0L,
                "history", List.of("old"), "ipHashes", List.of("a", "b"), "banned", true, "banReason", "griefing", "banSource", "discord"));
        check(d.contains("crackA") && d.contains("曾用名 old") && d.contains("IP 指纹 2 个") && d.contains("已封禁"), "查询一行");

        String[] p = MojangLookup.parse("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}");
        check(p != null && p[0].equals("069a79f4-44e9-4726-a5be-fca90e38aaf5") && p[1].equals("Notch"), "Mojang 解析");
        check(MojangLookup.parse("{}") == null && MojangLookup.parse("not json") == null, "Mojang 坏数据");
        check(MojangLookup.uuidOf("bad name!") == null, "坏名字不查");

        // /minmin definition still valid by Discord rules: ≤25 subcommands, names lower-case ≤32, required options first
        JsonArray defs = Interactions.definitions();
        JsonObject root = defs.get(0).getAsJsonObject();
        JsonArray subs = root.getAsJsonArray("options");
        check(subs.size() <= 25, "子指令 ≤ 25（" + subs.size() + "）");
        Set<String> names = new HashSet<>();
        boolean orderOk = true, namesOk = true;
        for (JsonElement e : subs) {
            JsonObject s = e.getAsJsonObject();
            String n = s.get("name").getAsString();
            namesOk &= n.matches("[a-z0-9_-]{1,32}") && names.add(n) && s.get("description").getAsString().length() <= 100;
            if (s.has("options")) {
                boolean seenOptional = false;
                for (JsonElement o : s.getAsJsonArray("options")) {
                    JsonObject x = o.getAsJsonObject();
                    if (x.get("type").getAsInt() <= 2) continue;
                    boolean req = x.has("required") && x.get("required").getAsBoolean();
                    if (req && seenOptional) orderOk = false;
                    if (!req) seenOptional = true;
                    namesOk &= x.get("description").getAsString().length() <= 100;
                }
            }
        }
        check(namesOk, "名字/说明长度");
        check(orderOk, "必填选项在前");
        check(names.contains("key") && names.contains("lookup"), "有 领取密钥 / 查询");
        JsonObject ban = null;
        for (JsonElement e : subs) if (e.getAsJsonObject().get("name").getAsString().equals("ban")) ban = e.getAsJsonObject();
        boolean member = false;
        for (JsonElement o : ban.getAsJsonArray("options")) if (o.getAsJsonObject().get("name").getAsString().equals("member") && o.getAsJsonObject().get("type").getAsInt() == 6) member = true;
        check(member, "封禁 可以选 Discord 成员");

        // 2026-10-11 account-link: 「关联正版账号」 answers
        check(KeyRules.premiumReply(Map.of("status", "sent", "name", "AminDoll")).contains("确认关联"), "正版关联：已弹窗");
        check(KeyRules.premiumReply(Map.of("status", "offline")).contains("先进服"), "正版关联：不在线");
        check(KeyRules.premiumReply(Map.of("status", "not-premium", "name", "x")).contains("学习版"), "正版关联：不是正版");
        check(KeyRules.premiumReply(Map.of("status", "has-other", "other", "Old")).contains("Old"), "正版关联：已有别的正版号");
        check(KeyRules.premiumReply(Map.of("status", "error")).contains("稍后"), "正版关联：出错");
        check(KeyRules.premiumResult("AminDoll", "ok").contains("同一份"), "正版关联结果：成功");
        check(KeyRules.premiumResult("AminDoll", "denied").contains("拒绝"), "正版关联结果：拒绝");
        boolean linkCmd = false;
        for (JsonElement c : Interactions.definitions().get(0).getAsJsonObject().getAsJsonArray("options")) if (c.getAsJsonObject().get("name").getAsString().equals("linkpremium")) linkCmd = true;
        check(linkCmd, "有 /minmin 关联正版");

        System.out.println("KeyFlowTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(boolean ok, String name) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
