#!/usr/bin/env python3
"""whale-mobile 端云协议契约校验器（V2；V1 已随全量重构删除）。

依据 contracts/v1/PROTOCOL.md 与 contracts/v2/PROTOCOL.md（唯一事实源）：

V1（冻结，行为不变）：
1. examples/ 下每个黄金示例必须通过 messages/ 下对应消息的 JSON Schema（draft 2020-12）。
2. 三条硬编码负例必须失败。

V2 增强：
1. 正例双重校验（逐消息 schema + envelope）。
2. 反向检查：messages/ 下每个 schema（除 envelope）必须有同名黄金示例。
3. objects/*.schema.json 必须各有一个 objects/examples/<name>.json 正例并通过。
4. 扩展负例（版本不符 / 删必填 / 错误 dir / ID 格式错误 / V1 状态词混入）。
5. 交叉一致性：error.schema.json 的 code 枚举与 action.receipt.schema.json 的
   error_code 枚举逐字一致；能力枚举满足 query ⊆ action ⊆ hello。

用法：python3 tests/contract_validate.py
退出码：0 = 全部通过；1 = 有失败；2 = 缺依赖。
"""
import copy
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VENDOR_DIR = os.path.join(ROOT, "tests", "_vendor")

if os.path.isdir(VENDOR_DIR):
    sys.path.insert(0, VENDOR_DIR)

try:
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
    return sorted(validator.iter_errors(instance), key=lambda e: list(e.path))


class Reporter:
    def __init__(self):
        self.failures = []
        self.passes = 0

    def ok(self, label):
        self.passes += 1
        print(f"PASS  {label}")

    def fail(self, label):
        self.failures.append(label)


def validate_version(rep, version):
    base = os.path.join(ROOT, "contracts", version)
    messages_dir = os.path.join(base, "messages")
    examples_dir = os.path.join(base, "examples")

    schemas = {}
    for fn in sorted(os.listdir(messages_dir)):
        if fn.endswith(".schema.json"):
            schemas[fn[: -len(".schema.json")]] = load_json(os.path.join(messages_dir, fn))

    # ---------- 正例 ----------
    example_names = sorted(fn[: -len(".json")] for fn in os.listdir(examples_dir)
                           if fn.endswith(".json"))
    for name in example_names:
        label = f"{version}/examples/{name}.json"
        try:
            instance = load_json(os.path.join(examples_dir, name + ".json"))
        except (OSError, json.JSONDecodeError) as e:
            rep.fail(f"{label}: 无法解析 JSON: {e}")
            continue
        schema = schemas.get(name)
        if schema is None:
            rep.fail(f"{label}: 缺少对应 schema messages/{name}.schema.json")
            continue
        errs = check(Draft202012Validator(schema), instance)
        if errs:
            rep.fail(f"{label}: 未通过 messages/{name}.schema.json:\n" + "\n".join(
                f"    - {'/'.join(map(str, e.path)) or '<root>'}: {e.message}" for e in errs))
            continue
        env_errs = check(Draft202012Validator(schemas["envelope"]), instance)
        if env_errs:
            rep.fail(f"{label}: 未通过通用信封 envelope.schema.json")
            continue
        rep.ok(label)

    if version == "v1":
        v1_negatives(rep, schemas, examples_dir)
        return schemas

    # ---------- V2：反向检查 ----------
    for name in schemas:
        if name == "envelope":
            continue
        if name not in example_names:
            rep.fail(f"v2: messages/{name}.schema.json 缺少黄金示例 examples/{name}.json")
        else:
            rep.ok(f"v2 反向检查 {name}")

    # ---------- V2：objects ----------
    objects_dir = os.path.join(base, "objects")
    obj_examples_dir = os.path.join(objects_dir, "examples")
    for fn in sorted(os.listdir(objects_dir)):
        if not fn.endswith(".schema.json"):
            continue
        name = fn[: -len(".schema.json")]
        ex_path = os.path.join(obj_examples_dir, name + ".json")
        label = f"v2/objects/{name}"
        if not os.path.exists(ex_path):
            rep.fail(f"{label}: 缺少正例 objects/examples/{name}.json")
            continue
        try:
            instance = load_json(ex_path)
        except (OSError, json.JSONDecodeError) as e:
            rep.fail(f"{label}: 正例无法解析: {e}")
            continue
        errs = check(Draft202012Validator(load_json(os.path.join(objects_dir, fn))),
                     instance)
        if errs:
            rep.fail(f"{label}: 正例未通过:\n" + "\n".join(
                f"    - {'/'.join(map(str, e.path)) or '<root>'}: {e.message}" for e in errs))
        else:
            rep.ok(label)

    v2_negatives(rep, schemas, examples_dir)
    v2_cross_checks(rep, schemas)
    return schemas


def v1_negatives(rep, schemas, examples_dir):
    negatives = []
    bad_hello = copy.deepcopy(load_json(os.path.join(examples_dir, "device.hello.json")))
    bad_hello["v"] = "2.0"
    bad_hello["payload"]["protocol_version"] = "2.0"
    negatives.append(("neg v1: device.hello v=2.0（未知 major）", "device.hello", bad_hello))

    bad_event = copy.deepcopy(load_json(os.path.join(examples_dir, "sensor.event.json")))
    del bad_event["payload"]["event_id"]
    negatives.append(("neg v1: sensor.event 缺 event_id", "sensor.event", bad_event))

    bad_action = copy.deepcopy(load_json(os.path.join(examples_dir, "action.request.json")))
    del bad_action["payload"]["idempotency_key"]
    negatives.append(("neg v1: action.request 缺 idempotency_key", "action.request", bad_action))

    for label, schema_name, instance in negatives:
        errs = check(Draft202012Validator(schemas[schema_name]), instance)
        if errs:
            rep.ok(f"{label}（按预期拒绝：{errs[0].message[:60]}）")
        else:
            rep.fail(f"{label}: 应被拒绝却通过")


def v2_negatives(rep, schemas, examples_dir):
    def ex(name):
        return load_json(os.path.join(examples_dir, name + ".json"))

    negatives = []

    bad = copy.deepcopy(ex("device.hello"))
    bad["v"] = "1.0"
    bad["payload"]["protocol_version"] = "1.0"
    negatives.append(("neg v2: device.hello v=1.0（错误 major）", "device.hello", bad))

    bad = copy.deepcopy(ex("device.hello"))
    del bad["payload"]["features"]
    negatives.append(("neg v2: device.hello 缺 features", "device.hello", bad))

    bad = copy.deepcopy(ex("action.request"))
    bad["dir"] = "uplink"
    negatives.append(("neg v2: action.request dir=uplink（方向错误）", "action.request", bad))

    bad = copy.deepcopy(ex("action.request"))
    del bad["payload"]["idempotency_key"]
    negatives.append(("neg v2: action.request 缺 idempotency_key", "action.request", bad))

    bad = copy.deepcopy(ex("experience.event"))
    del bad["payload"]["event_id"]
    negatives.append(("neg v2: experience.event 缺 event_id", "experience.event", bad))

    bad = copy.deepcopy(ex("experience.event"))
    bad["payload"]["event_id"] = bad["payload"]["event_id"].replace("evt_", "", 1)
    negatives.append(("neg v2: experience.event event_id 无 evt_ 前缀", "experience.event", bad))

    bad = copy.deepcopy(ex("device.confirm.request"))
    bad["payload"]["device_id"] = "ba4bb14c75917cfc"  # 16 字符短串
    negatives.append(("neg v2: confirm.request device_id 非 32-hex", "device.confirm.request", bad))

    bad = copy.deepcopy(ex("action.receipt"))
    bad["payload"]["status"] = "executed"  # V1 状态词
    negatives.append(("neg v2: action.receipt status=executed（V1 状态词）", "action.receipt", bad))

    bad = copy.deepcopy(ex("device.heartbeat"))
    bad["ts"] = 4102444800001  # 越界
    negatives.append(("neg v2: device.heartbeat ts 越界", "device.heartbeat", bad))

    bad = copy.deepcopy(ex("run.trace.event"))
    bad["payload"]["seq"] = 0
    negatives.append(("neg v2: run.trace.event seq=0", "run.trace.event", bad))

    for label, schema_name, instance in negatives:
        errs = check(Draft202012Validator(schemas[schema_name]), instance)
        if errs:
            rep.ok(f"{label}（按预期拒绝：{errs[0].message[:60]}）")
        else:
            rep.fail(f"{label}: 应被拒绝却通过")


def _cap_enum(schema, *path):
    node = schema
    for key in path:
        node = node[key]
    return node.get("enum")


def v2_cross_checks(rep, schemas):
    # 错误码：error.code 与 action.receipt.error_code 逐字一致
    err_codes = schemas["error"]["properties"]["payload"]["properties"]["code"]["enum"]
    receipt_codes = schemas["action.receipt"]["properties"]["payload"]["properties"]["error_code"]["enum"]
    if err_codes == receipt_codes:
        rep.ok("v2 交叉检查：错误码枚举一致（error ↔ action.receipt）")
    else:
        rep.fail(f"v2 交叉检查失败：错误码不一致 {err_codes} vs {receipt_codes}")

    # 能力子集关系：action ⊆ hello、query ⊆ hello、hello = action ∪ query
    #（screen.* / device.app.list 同时是查询与动作能力——沿用 v1 惯例；
    #  device.output.read / device.status 为仅查询能力，不得作为动作下发）
    hello_caps = set(schemas["device.hello"]["properties"]["payload"]["properties"]
                     ["capabilities"]["items"]["enum"])
    action_caps = set(schemas["action.request"]["properties"]["payload"]["properties"]
                      ["capability"]["enum"])
    query_ops = set(schemas["sensor.query"]["properties"]["payload"]["properties"]
                    ["operation"]["enum"])
    if (action_caps <= hello_caps and query_ops <= hello_caps
            and hello_caps == action_caps | query_ops):
        rep.ok("v2 交叉检查：能力枚举 hello = action ∪ query")
    else:
        rep.fail(f"v2 交叉检查失败：hello-(action∪query)="
                 f"{sorted(hello_caps - action_caps - query_ops)} "
                 f"action-hello={sorted(action_caps - hello_caps)} "
                 f"query-hello={sorted(query_ops - hello_caps)}")

    # hello_ack 收窄集合与 hello 同枚举
    ack_caps = set(schemas["device.hello_ack"]["properties"]["payload"]["properties"]
                   ["accepted_capabilities"]["items"]["enum"])
    if ack_caps == hello_caps:
        rep.ok("v2 交叉检查：hello_ack 能力枚举与 hello 一致")
    else:
        rep.fail("v2 交叉检查失败：hello_ack 能力枚举与 hello 不一致")


def main():
    rep = Reporter()
    schema_counts = {}
    for version in ("v1", "v2"):
        base = os.path.join(ROOT, "contracts", version)
        if not os.path.isdir(base):
            continue
        schemas = validate_version(rep, version)
        schema_counts[version] = len(schemas)

    total = rep.passes + len(rep.failures)
    print()
    if rep.failures:
        print("FAILURES:")
        for f in rep.failures:
            print(" ", f)
    print(f"摘要: {rep.passes}/{total} 通过；schema 数 {schema_counts}")
    return 0 if not rep.failures else 1


if __name__ == "__main__":
    sys.exit(main())
