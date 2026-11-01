#!/usr/bin/env python3
"""T-6 埋点纪律门禁（PostToolUse hook）。

规则（harness/references/tech-rules.md T-6）：任何调用 Spring RestClient.builder()/create()
的 .java 文件必须同时挂 TraceContext.interceptor()，否则跨服务链路 traceId 断链。

- 触发：PostToolUse（matcher Write|Edit|MultiEdit），文件已落盘后可读实际内容。
- 判定：先剥 Java 注释（双向防误判），再查 builder/create 与 interceptor。
- 豁免：Elasticsearch 客户端 org.elasticsearch.client.RestClient / co.elastic.clients
  （同名不同类，如 agent-service EsClientConfig）。
- exit 契约：0=pass；2=block（fail-closed，stdout 回喂模型自纠）。
- 基础设施故障（stdin 解析失败 / 文件读不到）→ exit 0，不阻断会话（fail-open 只针对 hook 基建）。
- CLI 模式：python .claude/hooks/check-instrumentation.py --check <file>...
  （/implement Step 4 手工复跑 / 单元测试用）。

仅使用 stdlib（json/os/re/sys），无第三方依赖。
"""

import json
import os
import re
import sys

BLOCK_EXIT = 2
ES_CLIENT_MARKERS = ("org.elasticsearch.client.RestClient", "co.elastic.clients")
BUILDER_PATTERNS = ("RestClient.builder(", "RestClient.create(")
INTERCEPTOR_PATTERN = "TraceContext.interceptor("


def strip_java_comments(src: str) -> str:
    """剥除 /* ... */ 与 // ... 注释。先块后行，行注释只在代码层剥。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.DOTALL)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def check_file(path: str):
    """返回 (ok, message)。message 仅在违规时为违规文件路径。"""
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            raw = fh.read()
    except OSError as exc:
        print(f"[check-instrumentation] warning: cannot read {path}: {exc}", file=sys.stderr)
        return True, ""

    code = strip_java_comments(raw)

    # ES 客户端同名不同类豁免（import 语句即含全限定名，命中即豁免）
    if any(m in code for m in ES_CLIENT_MARKERS):
        return True, ""

    # 只注入/消费 Builder Bean 的文件（无 builder 调用）→ pass
    if not any(p in code for p in BUILDER_PATTERNS):
        return True, ""

    if INTERCEPTOR_PATTERN in code:
        return True, ""

    return False, path


def resolve_candidates(tool_input: dict, cwd: str) -> list:
    """Write/Edit 取 file_path；MultiEdit 遍历 edits[*].file_path。相对路径基于 cwd 展开。"""
    paths = []
    fp = tool_input.get("file_path")
    if isinstance(fp, str) and fp:
        paths.append(fp)
    for edit in tool_input.get("edits") or []:
        if isinstance(edit, dict) and isinstance(edit.get("file_path"), str):
            paths.append(edit["file_path"])
    resolved = []
    for p in paths:
        if os.path.isabs(p):
            resolved.append(os.path.normpath(p))
        else:
            resolved.append(os.path.normpath(os.path.join(cwd, p)))
    return resolved


def block_message(violations) -> str:
    return json.dumps(
        {
            "decision": "block",
            "stopReason": (
                "T-6 埋点纪律违规（fail-closed）：以下文件调用了 Spring "
                "RestClient.builder()/create() 但未挂 TraceContext.interceptor()："
                + "; ".join(violations)
                + "。依据 harness/references/tech-rules.md T-6 + harness/agents/<module>.md 必守规则。"
                "请在 RestClient builder 上补 .requestInterceptor(TraceContext.interceptor())"
                "（Elasticsearch 客户端 org.elasticsearch.client.RestClient / co.elastic.clients 豁免）。"
            ),
        },
        ensure_ascii=False,
    )


def main() -> int:
    argv = sys.argv[1:]
    candidates = []

    if len(argv) >= 2 and argv[0] == "--check":
        candidates = argv[1:]
    else:
        try:
            data = json.load(sys.stdin)
        except Exception as exc:
            print(f"[check-instrumentation] warning: stdin parse failed ({exc}); skipping gate", file=sys.stderr)
            return 0
        if not isinstance(data, dict):
            print("[check-instrumentation] warning: unexpected stdin payload; skipping gate", file=sys.stderr)
            return 0
        cwd = data.get("cwd") or os.getcwd()
        tool_input = data.get("tool_input") or {}
        candidates = resolve_candidates(tool_input, cwd)

    violations = []
    for path in candidates:
        if path.lower().endswith(".java"):
            ok, message = check_file(path)
            if not ok:
                violations.append(message)

    if violations:
        print(block_message(violations))
        return BLOCK_EXIT
    return 0


if __name__ == "__main__":
    sys.exit(main())
