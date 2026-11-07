#!/usr/bin/env python3
"""跨仓 V2 契约漂移校验（issue #46）。

逐文件比较两个 checkout 的 ``contracts/v2/`` 约定范围：

- ``PROTOCOL.md``
- ``messages/*.schema.json``
- ``objects/*.schema.json``
- ``examples/*.json``
- ``objects/examples/*.json``

canonical source 是 **whale-mobile**（``contracts/v2/README.md`` 与
``docs/devices.md`` 已声明）。任一侧多文件、缺文件或内容不同都算漂移。
旧 V1 已退役，不再参与比较。

用法::

    python3 scripts/contract_sync_check.py --core /path/whale-core \
        --mobile /path/whale-mobile
    python3 scripts/contract_sync_check.py --a /path/a --b /path/b

CI 里先按同一批次 ref checkout 两个仓到本地路径再调用本脚本。
"""

from __future__ import annotations

import argparse
import filecmp
import sys
from pathlib import Path

SCOPE_FILES = ("PROTOCOL.md",)
SCOPE_GLOBS = (
    "messages/*.schema.json",
    "objects/*.schema.json",
    "examples/*.json",
    "objects/examples/*.json",
)


def collect(root: Path) -> "dict[str, Path]":
    base = root / "contracts" / "v2"
    found: dict[str, Path] = {}
    for name in SCOPE_FILES:
        path = base / name
        if path.is_file():
            found[name] = path
    for pattern in SCOPE_GLOBS:
        for path in sorted(base.glob(pattern)):
            found[str(path.relative_to(base))] = path
    return found


def compare(a_root: Path, b_root: Path) -> "tuple[list[str], int]":
    a_files = collect(a_root)
    b_files = collect(b_root)
    problems: list[str] = []
    total = len(set(a_files) | set(b_files))
    for rel in sorted(set(a_files) | set(b_files)):
        in_a, in_b = rel in a_files, rel in b_files
        tag = f"contracts/v2/{rel}"
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
            "\\n处置：先在 canonical（whale-mobile）落定变更，再整体同步到 "
            "whale-core 镜像；不要两侧分别改。"
        )
        return 1
    print(f"ok: contracts/v2 范围内 {total} 个文件两侧一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
