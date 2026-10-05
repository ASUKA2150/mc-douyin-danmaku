#!/usr/bin/env bash
# 开发者自检：编译并运行 DecodeCheck（不需要 Gradle / Minecraft）
#
# 用法（在仓库根目录执行）：
#   bash tools/selfcheck/run.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CORE_DIR="${REPO_ROOT}/common/src/main/java"
SRC_DIR="${SCRIPT_DIR}/src"
OUT_DIR="${SCRIPT_DIR}/out"

# 找 JDK：优先 JAVA_HOME，其次 PATH
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

echo "使用 JDK: ${JAVAC}"

# 参与自检的源文件（只挑不依赖 Gson 真实现的那几个）
SOURCES=(
    "${SRC_DIR}/com/google/gson/JsonElement.java"
    "${SRC_DIR}/com/google/gson/JsonArray.java"
    "${SRC_DIR}/com/google/gson/JsonObject.java"
    "${SRC_DIR}/com/google/gson/JsonParser.java"
    "${SRC_DIR}/com/google/gson/Gson.java"
    "${SRC_DIR}/com/google/gson/GsonBuilder.java"
    "${SRC_DIR}/DecodeCheck.java"
    "${CORE_DIR}/com/douyindanmaku/core/DanmakuLog.java"
    "${CORE_DIR}/com/douyindanmaku/core/FileLog.java"
    "${CORE_DIR}/com/douyindanmaku/core/LogFile.java"
    "${CORE_DIR}/com/douyindanmaku/core/config/DanmakuConfig.java"
    "${CORE_DIR}/com/douyindanmaku/core/model/DanmakuMessage.java"
    "${CORE_DIR}/com/douyindanmaku/core/model/DanmakuEvent.java"
    "${CORE_DIR}/com/douyindanmaku/core/model/RoomStats.java"
    "${CORE_DIR}/com/douyindanmaku/core/model/StatsProbe.java"
    "${CORE_DIR}/com/douyindanmaku/core/proto/ProtobufReader.java"
    "${CORE_DIR}/com/douyindanmaku/core/douyin/DouyinProtocol.java"
    "${CORE_DIR}/com/douyindanmaku/core/text/DanmakuFormatter.java"
    "${CORE_DIR}/com/douyindanmaku/core/text/DanmakuFilter.java"
    "${CORE_DIR}/com/douyindanmaku/core/text/NumberText.java"
    "${CORE_DIR}/com/douyindanmaku/core/text/LikeCounter.java"
)

for file in "${SOURCES[@]}"; do
    if [[ ! -f "${file}" ]]; then
        echo "缺少源文件：${file}" >&2
        exit 1
    fi
done

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

echo "正在编译…"
"${JAVAC}" -encoding UTF-8 -d "${OUT_DIR}" "${SOURCES[@]}"

echo "正在运行自检…"
"${JAVA}" -Dfile.encoding=UTF-8 -cp "${OUT_DIR}" DecodeCheck
