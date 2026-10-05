#!/usr/bin/env bash
# 浏览器旁观模式 · 开发自检
#
# 用法（在仓库根目录执行，需要先跑一次 ./gradlew :fabric:compileJava）：
#   bash tools/cdpcheck/run.sh                      # 接线对照实验（约 10 秒）
#   bash tools/cdpcheck/run.sh douyin               # 真实抖音测试（约 60 秒）
#   bash tools/cdpcheck/run.sh --browser /path/msedge
#   bash tools/cdpcheck/run.sh douyin --browser /path/msedge 123456789
#
# 不指定 --browser 时走模组自己的探测逻辑（ChromeFinder），
# 顺便可以确认「浏览器自动探测在你机器上找得对不对」。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUT_DIR="${SCRIPT_DIR}/out"

# ---------- 解析参数 ----------
MODE="local"
BROWSER=""
ROOM=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        douyin)     MODE="douyin"; shift ;;
        --browser)  BROWSER="${2:-}"; shift 2 ;;
        *)          ROOM="$1"; shift ;;
    esac
done

# ---------- 找 JDK ----------
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/javac" ]]; then
    JAVAC="${JAVA_HOME}/bin/javac"
    JAVA="${JAVA_HOME}/bin/java"
elif command -v javac >/dev/null 2>&1; then
    JAVAC="$(command -v javac)"
    JAVA="$(command -v java)"
else
    echo "找不到 javac。请安装 JDK 21，或设置 JAVA_HOME 环境变量。" >&2
    exit 1
fi

# ---------- 找模组编译产物 ----------
CLASSES_DIR="${REPO_ROOT}/fabric/build/classes/java/main"
if [[ ! -d "${CLASSES_DIR}" ]]; then
    echo "找不到编译产物：${CLASSES_DIR}" >&2
    echo "请先在仓库根目录执行：./gradlew :fabric:compileJava" >&2
    exit 1
fi

# ---------- 找 Gson ----------
GSON_JAR="$(find "${HOME}/.gradle/caches/modules-2/files-2.1/com.google.code.gson" \
    -name 'gson-*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | head -n 1)"
if [[ -z "${GSON_JAR}" ]]; then
    echo "在 Gradle 缓存里找不到 gson jar。请先成功构建一次模组。" >&2
    exit 1
fi

# ---------- 决定跑哪个实验 ----------
if [[ "${MODE}" == "douyin" ]]; then
    PROBE="CdpDouyinProbe"
else
    PROBE="CdpLocalProbe"
fi

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

echo "实验程序 : ${PROBE}"
echo "使用 JDK : ${JAVAC}"
echo "正在编译…"

"${JAVAC}" -encoding UTF-8 -cp "${CLASSES_DIR}:${GSON_JAR}" -d "${OUT_DIR}" \
    "${SCRIPT_DIR}/src/com/douyindanmaku/core/chrome/${PROBE}.java"

# ---------- 组参数 ----------
PROBE_ARGS=()
[[ -n "${BROWSER}" ]] && PROBE_ARGS+=("${BROWSER}")
[[ "${MODE}" == "douyin" && -n "${ROOM}" ]] && PROBE_ARGS+=("${ROOM}")

if [[ "${MODE}" == "douyin" ]]; then
    echo "正在运行真实抖音测试（会弹出浏览器窗口，约 60 秒）…"
else
    echo "正在运行接线对照实验（会弹出浏览器窗口，约 10 秒）…"
fi

"${JAVA}" -Dfile.encoding=UTF-8 \
    -cp "${OUT_DIR}:${CLASSES_DIR}:${GSON_JAR}" \
    "com.douyindanmaku.core.chrome.${PROBE}" ${PROBE_ARGS[@]+"${PROBE_ARGS[@]}"}
