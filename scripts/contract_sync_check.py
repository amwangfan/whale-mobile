#!/usr/bin/env python3
"""跨仓契约漂移校验（issue #46）。

逐文件比较两个 checkout 的 ``contracts/v1/`` 约定范围：

- ``PROTOCOL.md``
- ``messages/*.schema.json``
- ``examples/*.json``

canonical source 是 **whale-mobile**（``contracts/v1/README.md`` 与
``docs/devices.md`` 已声明）；本脚本只做双向逐文件对比，不区分方向——
任一侧多了文件、缺了文件或内容不同都算漂移，输出逐文件差异并以退出码 1
失败。README.md 等仓内元文档不在校验范围内。

用法::

    python3 scripts/contract_sync_check.py --core /path/whale-core \
        --mobile /path/whale-mobile
    python3 scripts/contract_sync_check.py --a /path/a --b /path/b  # 通用

CI 里先按 ref checkout 两个仓到本地路径再调用本脚本；合并时序造成的
假失败由流程约束（先更新 canonical，再同步 mirror，最后以同一批次 refs
联合验收），脚本本身不做任何豁免。
"""

from __future__ import annotations

import argparse
import filecmp
import sys
from pathlib import Path

SCOPE_FILES = ("PROTOCOL.md",)
SCOPE_GLOBS = ("messages/*.schema.json", "examples/*.json")
# v2 起增加 objects 合同目录（schema + 正例）
SCOPE_GLOBS_V2 = SCOPE_GLOBS + ("objects/*.schema.json", "objects/examples/*.json")
VERSIONS = ("v1", "v2")  # v1 目录已删除的版本里自动跳过


def collect(root: Path, version: str) -> "dict[str, Path]":
    base = root / "contracts" / version
    found: dict[str, Path] = {}
    if not base.is_dir():
        return found
    for name in SCOPE_FILES:
        p = base / name
        if p.is_file():
            found[name] = p
    globs = SCOPE_GLOBS_V2 if version != "v1" else SCOPE_GLOBS
    for pattern in globs:
        for p in sorted(base.glob(pattern)):
            found[str(p.relative_to(base))] = p
    return found


def compare(a_root: Path, b_root: Path) -> "tuple[list[str], int]":
    problems: list[str] = []
    total = 0
    for version in VERSIONS:
        a_files = collect(a_root, version)
        b_files = collect(b_root, version)
        if not a_files and not b_files:
            continue  # 两侧都没有该版本（如旧 core 尚无 v2 镜像）不算漂移
        total += len(set(a_files) | set(b_files))
        for rel in sorted(set(a_files) | set(b_files)):
            in_a, in_b = rel in a_files, rel in b_files
            tag = f"contracts/{version}/{rel}"
            if in_a and not in_b:
                problems.append(f"ONLY-IN-A  {tag}")
            elif in_b and not in_a:
                problems.append(f"ONLY-IN-B  {tag}")
            elif not filecmp.cmp(a_files[rel], b_files[rel], shallow=False):
                problems.append(f"DIFFERS    {tag}")
    return problems, total


def main(argv: "list[str] | None" = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--core", help="whale-core checkout 路径")
    parser.add_argument("--mobile", help="whale-mobile checkout 路径")
    parser.add_argument("--a", help="通用：A 侧 checkout 路径")
    parser.add_argument("--b", help="通用：B 侧 checkout 路径")
    args = parser.parse_args(argv)

    a = args.a or args.core
    b = args.b or args.mobile
    if not a or not b:
        parser.error("需要 --core/--mobile 或 --a/--b")
    a_root, b_root = Path(a), Path(b)
    for label, root in (("A", a_root), ("B", b_root)):
        if not (root / "contracts" / "v2").is_dir():
            print(f"error: {label} 侧 {root} 缺少 contracts/v2/", file=sys.stderr)
            return 2

    problems, total = compare(a_root, b_root)
    if problems:
        print(f"contract drift detected ({len(problems)} 处)：")
        for line in problems:
            print(f"  {line}")
        print(
            "\n处置：先在 canonical（whale-mobile）落定变更，再整体同步到 "
            "whale-core 镜像；不要两侧分别改。"
        )
        return 1
    print(f"ok: contracts v1+v2 范围内 {total} 个文件两侧一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
