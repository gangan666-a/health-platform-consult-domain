#!/usr/bin/env python3
"""T-8 模型切换单一事实源：门禁 + 渲染（PostToolUse / SessionStart hook）。

规则（harness/references/tech-rules.md T-8）：升级/兜底模型只有一个事实源
`.claude/llm-switch.json`；由本 hook 幂等渲染为 `agent-service/src/main/resources/llm-models.properties`，
再经 `spring.config.import=optional:classpath:llm-models.properties` 被 Spring 消费。
LLM 升级链（AbstractSubAgent）与视觉升级链（GlmVisionAnalyzer）读同一份渲染结果，
改 JSON 一行即切模型，无需改 Java。

- 触发：
  ① PostToolUse（matcher Write|Edit|MultiEdit）：仅当编辑的是 `.claude/llm-switch.json` 才动作，
     渲染 + 校验；校验失败 exit 2 阻断（fail-closed，stdout 回喂模型自纠）。其它文件 → 直接放行。
  ② SessionStart：幂等对齐渲染（内容不同才写），恒 exit 0（SessionStart 不能 block）。
- 校验违规：JSON 非法 / role 未知或重复 / name·baseUrl·model·escalationModel 为空 /
            出现停用模型名 deepseek-chat、deepseek-reasoner。
- 扫描面收敛：只扫 JSON 与渲染产物，不扫 .java（避免测试 fixture 里的历史名误报）。
- exit 契约：0=pass；2=block（fail-closed，stdout 回喂模型自纠）。
- 基础设施故障（stdin 解析失败 / 文件读不到）→ exit 0，不阻断会话（fail-open 只针对 hook 基建）。
- CLI 模式：
    python .claude/hooks/sync-llm-model.py --check [json]   # 只校验，返回 0/2
    python .claude/hooks/sync-llm-model.py --render         # 手工重渲染

仅使用 stdlib（json/os/sys），无第三方依赖。路径以脚本位置锚定（.claude/hooks/ → 仓库根），
不依赖 cwd，也不受 Claude 启动目录影响。
"""

import json
import os
import sys

BLOCK_EXIT = 2
RETIRED_MODELS = ("deepseek-chat", "deepseek-reasoner")
VALID_ROLES = ("chat", "vision")

HOOK_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(HOOK_DIR))
SOURCE_JSON = os.path.join(REPO_ROOT, ".claude", "llm-switch.json")
OUTPUT_PROPERTIES = os.path.join(
    REPO_ROOT, "agent-service", "src", "main", "resources", "llm-models.properties"
)


def warn(msg: str) -> None:
    print(f"[sync-llm-model] {msg}", file=sys.stderr)


def find_retired(text: str) -> list:
    return [m for m in RETIRED_MODELS if m in text]


def render_properties(data: dict):
    """把 providers 渲染成 properties 文本。返回 (content, errors)；errors 非空时 content 为 None。"""
    errors = []
    providers = data.get("providers")
    if not isinstance(providers, list) or not providers:
        return None, ["providers 缺失或为空列表"]

    by_role = {}
    for idx, provider in enumerate(providers):
        if not isinstance(provider, dict):
            errors.append(f"providers[{idx}] 不是对象")
            continue
        role = provider.get("role")
        if role not in VALID_ROLES:
            errors.append(f"providers[{idx}].role 非法（应为 chat|vision）：{role!r}")
            continue
        if role in by_role:
            errors.append(f"role 重复：{role}")
            continue
        for key in ("name", "baseUrl", "model", "escalationModel"):
            if not str(provider.get(key) or "").strip():
                errors.append(f"providers[{idx}]({role}).{key} 为空")
        by_role[role] = provider

    for role in VALID_ROLES:
        if role not in by_role:
            errors.append(f"缺少 role={role} 的供应商")

    if errors:
        return None, errors

    chat = by_role["chat"]
    vision = by_role["vision"]
    lines = [
        "# GENERATED from .claude/llm-switch.json — DO NOT EDIT（改 JSON 后由 sync-llm-model hook 重新渲染）",
        f"spring.ai.openai.base-url={chat['baseUrl'].strip()}",
        f"spring.ai.openai.chat.options.model={chat['model'].strip()}",
        f"agent.llm.primary-models[0]={chat['model'].strip()}",
        f"agent.llm.primary-models[1]={chat['escalationModel'].strip()}",
        f"agent.llm.escalation-model={chat['escalationModel'].strip()}",
        f"agent.report.vision.base-url={vision['baseUrl'].strip()}",
        f"agent.report.vision.model={vision['model'].strip()}",
        f"agent.report.vision.fallback-model={vision['escalationModel'].strip()}",
        "",
    ]
    return "\n".join(lines), []


def load_and_validate(path: str):
    """读取 + 校验 + 渲染。返回 (content, errors)。"""
    try:
        with open(path, encoding="utf-8") as fh:
            raw = fh.read()
    except OSError as exc:
        return None, [f"无法读取 {path}: {exc}"]

    errors = list()
    hits = find_retired(raw)
    if hits:
        errors.append("JSON 含停用模型名：" + ", ".join(hits))

    try:
        data = json.loads(raw)
    except Exception as exc:
        return None, errors + [f"JSON 解析失败：{exc}"]
    if not isinstance(data, dict):
        return None, errors + ["JSON 顶层不是对象"]

    content, render_errors = render_properties(data)
    errors += render_errors
    if content is not None:
        hits = find_retired(content)
        if hits:
            errors.append("渲染产物含停用模型名：" + ", ".join(hits))
    return content, errors


def write_if_changed(path: str, content: str) -> bool:
    """确定性写入：内容一致则不落盘（幂等，避免脏 git；强制 \\n 防 Windows CRLF）。"""
    try:
        with open(path, encoding="utf-8") as fh:
            current = fh.read()
    except OSError:
        current = None
    if current == content:
        return False
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(content)
    return True


def block_message(errors: list) -> str:
    return json.dumps(
        {
            "decision": "block",
            "stopReason": (
                "T-8 模型切换单一事实源违规（fail-closed）："
                + "；".join(errors)
                + "。依据 harness/references/tech-rules.md T-8。"
                "请修 .claude/llm-switch.json：每个角色恰好一个供应商，role 取 chat|vision，"
                "model/escalationModel 填在用模型（升级模型默认 deepseek-v4-pro），"
                "停用名 deepseek-chat/deepseek-reasoner 必须替换。"
            ),
        },
        ensure_ascii=False,
    )


def is_source_file(path: str, cwd: str) -> bool:
    if not path:
        return False
    base = cwd or REPO_ROOT
    resolved = path if os.path.isabs(path) else os.path.join(base, path)
    return os.path.normcase(os.path.normpath(resolved)) == os.path.normcase(
        os.path.normpath(SOURCE_JSON)
    )


def edited_paths(tool_input: dict, cwd: str) -> list:
    paths = []
    fp = tool_input.get("file_path")
    if isinstance(fp, str) and fp:
        paths.append(fp)
    for edit in tool_input.get("edits") or []:
        if isinstance(edit, dict) and isinstance(edit.get("file_path"), str):
            paths.append(edit["file_path"])
    return [is_source_file(p, cwd) for p in paths]


def render_and_report() -> bool:
    """渲染并对齐。返回是否写入（发生变更）。"""
    content, errors = load_and_validate(SOURCE_JSON)
    if errors:
        warn("渲染跳过（校验未通过）：" + "；".join(errors))
        return False
    return write_if_changed(OUTPUT_PROPERTIES, content)


def main() -> int:
    argv = sys.argv[1:]

    if argv and argv[0] == "--check":
        target = argv[1] if len(argv) >= 2 else SOURCE_JSON
        _content, errors = load_and_validate(target)
        if errors:
            print(block_message(errors))
            return BLOCK_EXIT
        return 0

    if argv and argv[0] == "--render":
        _content, errors = load_and_validate(SOURCE_JSON)
        if errors:
            print(block_message(errors))
            return BLOCK_EXIT
        write_if_changed(OUTPUT_PROPERTIES, _content)
        return 0

    try:
        data = json.load(sys.stdin)
    except Exception as exc:
        warn(f"stdin parse failed ({exc}); skipping gate")
        return 0
    if not isinstance(data, dict):
        warn("unexpected stdin payload; skipping gate")
        return 0

    cwd = data.get("cwd") or REPO_ROOT
    event = data.get("hook_event_name") or ""
    tool_input = data.get("tool_input") or {}

    if event == "PostToolUse":
        if not any(edited_paths(tool_input, cwd)):
            return 0  # 非事实源文件的编辑 → 放行
        content, errors = load_and_validate(SOURCE_JSON)
        if errors:
            print(block_message(errors))
            return BLOCK_EXIT
        write_if_changed(OUTPUT_PROPERTIES, content)
        return 0

    # SessionStart 及其它事件：仅对齐渲染，恒放行
    if render_and_report():
        warn("llm-models.properties 已按 llm-switch.json 重新渲染（重启服务生效）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
