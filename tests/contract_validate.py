#!/usr/bin/env python3
"""whale-mobile V1 协议契约校验器。

依据 contracts/v1/PROTOCOL.md（唯一事实源）：
1. examples/ 下每个黄金示例必须通过 messages/ 下对应消息的 JSON Schema（draft 2020-12）。
2. 负例必须失败：
   - 未知 major 版本（v="2.0"）的 device.hello 必须被拒（§2/§8）；
   - sensor.event 缺 event_id 必须失败（幂等键，§4）；
   - action.request 缺 idempotency_key 必须失败（§6）。

用法：python3 tests/contract_validate.py
退出码：0 = 全部通过；1 = 有失败。
"""
import copy
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MESSAGES_DIR = os.path.join(ROOT, "contracts", "v1", "messages")
EXAMPLES_DIR = os.path.join(ROOT, "contracts", "v1", "examples")
VENDOR_DIR = os.path.join(ROOT, "tests", "_vendor")

if os.path.isdir(VENDOR_DIR):
    sys.path.insert(0, VENDOR_DIR)

try:
    import jsonschema
    from jsonschema import Draft202012Validator
except ImportError:
    print("FATAL: 需要 jsonschema 库。请执行：\n"
          "  pip install --target tests/_vendor jsonschema\n"
          "或在虚拟环境中安装 jsonschema 后运行。", file=sys.stderr)
    sys.exit(2)


def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def check(validator, instance):
    """返回错误列表（空列表 = 通过）。"""
    return sorted(validator.iter_errors(instance), key=lambda e: list(e.path))


def main():
    failures = []
    passes = 0

    schemas = {}
    for fn in sorted(os.listdir(MESSAGES_DIR)):
        if fn.endswith(".schema.json"):
            name = fn[: -len(".schema.json")]
            schemas[name] = load_json(os.path.join(MESSAGES_DIR, fn))

    # ---------- 正例：examples/ 全部通过对应 schema ----------
    for fn in sorted(os.listdir(EXAMPLES_DIR)):
        if not fn.endswith(".json"):
            continue
        name = fn[: -len(".json")]
        path = os.path.join(EXAMPLES_DIR, fn)
        label = f"examples/{fn}"
        try:
            instance = load_json(path)
        except (OSError, json.JSONDecodeError) as e:
            failures.append(f"{label}: 无法解析 JSON: {e}")
            continue
        schema = schemas.get(name)
        if schema is None:
            failures.append(f"{label}: 缺少对应 schema messages/{name}.schema.json")
            continue
        errs = check(Draft202012Validator(schema), instance)
        if errs:
            failures.append(f"{label}: 未通过 messages/{name}.schema.json:\n" + "\n".join(
                f"    - {'/'.join(map(str, e.path)) or '<root>'}: {e.message}" for e in errs))
        else:
            # 同时应通过通用信封 schema（payload 结构由具体 schema 管）
            env_errs = check(Draft202012Validator(schemas["envelope"]), instance)
            if env_errs:
                failures.append(f"{label}: 未通过通用信封 envelope.schema.json:\n" + "\n".join(
                    f"    - {'/'.join(map(str, e.path)) or '<root>'}: {e.message}" for e in env_errs))
            else:
                passes += 1
                print(f"PASS  {label}")

    # ---------- 负例 ----------
    negatives = []

    # 1. 未知 major 版本 v=2.0 的 device.hello 必须失败（§2/§8）
    bad_hello = copy.deepcopy(load_json(os.path.join(EXAMPLES_DIR, "device.hello.json")))
    bad_hello["v"] = "2.0"
    bad_hello["payload"]["protocol_version"] = "2.0"
    negatives.append(("neg: device.hello v=2.0（未知 major）", "device.hello", bad_hello))

    # 2. sensor.event 缺 event_id 必须失败（§4 幂等键）
    bad_event = copy.deepcopy(load_json(os.path.join(EXAMPLES_DIR, "sensor.event.json")))
    del bad_event["payload"]["event_id"]
    negatives.append(("neg: sensor.event 缺 event_id", "sensor.event", bad_event))

    # 3. action.request 缺 idempotency_key 必须失败（§6）
    bad_action = copy.deepcopy(load_json(os.path.join(EXAMPLES_DIR, "action.request.json")))
    del bad_action["payload"]["idempotency_key"]
    negatives.append(("neg: action.request 缺 idempotency_key", "action.request", bad_action))

    for label, schema_name, instance in negatives:
        errs = check(Draft202012Validator(schemas[schema_name]), instance)
        if errs:
            passes += 1
            print(f"PASS  {label}（按预期拒绝：{errs[0].message}）")
        else:
            failures.append(f"{label}: 应被拒绝却通过了 messages/{schema_name}.schema.json")

    # ---------- 摘要 ----------
    total = passes + len(failures)
    print()
    if failures:
        print("FAILURES:")
        for f in failures:
            print(" ", f)
    print(f"摘要: {passes}/{total} 通过（正例 {len([x for x in os.listdir(EXAMPLES_DIR) if x.endswith('.json')])} 条 + 负例 {len(negatives)} 条），"
          f"schema {len(schemas)} 个")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
