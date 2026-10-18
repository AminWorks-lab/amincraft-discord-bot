#!/usr/bin/env bash
# 离线构建 AminPlayDiscord：用工作区自带的 JDK 21 和 Paper API，不下载任何东西。
# 用法：bash build.sh <build编号>    产物：build-<N>/AminPlayDiscord.jar
set -euo pipefail
cd "$(dirname "$0")"
N="${1:?需要构建编号，例如 bash build.sh 1}"
OUT="build-$N"
[ -e "$OUT" ] && { echo "$OUT 已存在，请换一个新编号"; exit 1; }
TC="${TC:?请把 TC 设成工具链目录：含 jdk/ 和 deps/（Paper API 1.21.11、Adventure、Gson 等 jar）}"
JDK="$TC/jdk/bin"
CP=$(find "$TC/deps/" -name '*.jar' \( -path '*papermc/paper*' -o -path '*kyori*' -o -path '*google/code/gson*' -o -path '*jspecify*' -o -path '*jetbrains/annotations*' -o -path '*bungeecord-chat*' -o -path '*google/guava*' -o -path '*snakeyaml*' \) \
  ! -name 'paper-api-1.21.11-R0.1-20260511.115010-91.jar' | tr '\n' ';')
mkdir -p "$OUT/classes" "$OUT/test-classes"
find src/main/java -name '*.java' > "$OUT/main-sources.txt"
"$JDK/javac.exe" --release 21 -g -encoding UTF-8 -J-Duser.language=en -Xlint:all,-classfile,-serial,-processing -cp "$CP" -d "$OUT/classes" @"$OUT/main-sources.txt" 2>&1 | tee "$OUT/javac-main.log"
cp src/main/resources/* "$OUT/classes/"
find src/test/java -name '*.java' > "$OUT/test-sources.txt"
"$JDK/javac.exe" -J-Duser.language=en --release 21 -encoding UTF-8 -cp "$CP;$OUT/classes" -d "$OUT/test-classes" @"$OUT/test-sources.txt" 2>&1 | tee "$OUT/javac-test.log"
# 全部单元测试（假 Discord / 假 MiniGameHub，不连网）。每个测试都必须打印「X: N passed, 0 failed」
TESTS="BotRulesTest NotifierTest ReplayPosterTest CollectorTest KeyFlowTest PlotDeskTest PostingTest PromoTest MatchChatTest FeedbackPosterTest HighlightPosterTest SayBridgeTest PublicPingTest WebLoginFlowTest"
: > "$OUT/unit-tests.log"
for T in $TESTS; do
  "$JDK/java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP;$OUT/classes;$OUT/test-classes" "cn.aminplay.discord.$T" | tee -a "$OUT/unit-tests.log"
  grep -q "^$T: [0-9]* passed, 0 failed" "$OUT/unit-tests.log" || { echo "单元测试失败：$T"; exit 1; }
done
! grep ' passed, ' "$OUT/unit-tests.log" | grep -vq ' 0 failed' || { echo "单元测试失败"; exit 1; }
"$JDK/jar.exe" --create --file "$OUT/AminPlayDiscord.jar" -C "$OUT/classes" .
sha1sum "$OUT/AminPlayDiscord.jar" | tee "$OUT/sha1.txt"
