#!/usr/bin/env python3
"""Run Android-independent Kotlin tests using explicitly supplied local compiler/JUnit/Gson jars.

No SDK, Gradle or network downloads are performed. This does not build the APK.
All generated classes and reports are placed under --output.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/android/app/src/main/kotlin/com/wangfanlab/whale"
TEST = ROOT / "app/android/app/src/test/kotlin/com/wangfanlab/whale"
SOURCES = (
    "transport/v2/ProtocolV2.kt", "transport/v2/SeqCounter.kt", "transport/v2/Backoff.kt",
    "transport/v2/OutputPages.kt", "transport/v2/IdentityRegistration.kt", "modules/agent2/ObserveOptions.kt",
    "runtime/RecoveryPolicy.kt", "runtime/SensorQueryDispatch.kt", "runtime/UiEventHub.kt",
    "modules/recording/RecordingOptionsPolicy.kt",
    "transport/v2/ConnectionGeneration.kt",
    "modules/glasses/GlassesLogic.kt", "modules/voip/VoipLogic.kt", "modules/notificationread/NotificationReadLogic.kt",
)
TESTS = (
    "transport/v2/UlidAndIdsV2Test.kt", "transport/v2/SeqCounterV2Test.kt", "transport/v2/BackoffTest.kt",
    "transport/v2/EnvelopeCodecV2Test.kt", "transport/v2/OutputPagesTest.kt", "transport/v2/IdentityRegistrationTest.kt",
    "modules/agent2/ObserveOptionsTest.kt", "runtime/RecoveryPolicyTest.kt", "runtime/NodeRuntimeLogicTest.kt",
    "modules/recording/RecordingOptionsPolicyTest.kt",
    "transport/v2/ConnectionGenerationTest.kt",
    "modules/glasses/GlassesLogicTest.kt", "modules/voip/VoipLogicTest.kt", "modules/notificationread/NotificationReadLogicTest.kt",
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tools", type=Path, required=True, help="local Kotlin 1.9.24 compiler dependencies and JUnit 4.13.2 jars")
    parser.add_argument("--gson", type=Path, required=True, help="project Gson 2.11.0 jar")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    if not args.java:
        parser.error("Java runtime is required")
    jars = [args.tools / name for name in ("kotlin-compiler-embeddable-1.9.24.jar",
        "kotlin-stdlib-1.9.24.jar", "junit-4.13.2.jar", "hamcrest-core-1.3.jar")]
    if not args.gson.is_file() or any(not jar.is_file() for jar in jars):
        parser.error("required local jars are missing; supply compiler dependencies, JUnit and Gson explicitly")
    output = args.output.resolve()
    classes = output / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    report_path = output / "report.json"
    report_path.write_text(json.dumps({"status": "running"}) + "\n", encoding="utf-8")
    classpath = os.pathsep.join(str(path.resolve()) for path in sorted(args.tools.glob("*.jar")))
    classpath += os.pathsep + str(args.gson.resolve())
    source_paths = [MAIN / name for name in SOURCES] + [TEST / name for name in TESTS]
    test_classes = []
    for path in (TEST / name for name in TESTS):
        text = path.read_text(encoding="utf-8")
        package = re.search(r"^package\s+([\w.]+)", text, re.M).group(1)
        test_classes += [package + "." + name for name in re.findall(r"^class\s+(\w+)", text, re.M)]
    commands = [
        [args.java, "-Dfile.encoding=UTF-8", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
         "-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-classpath", classpath,
         "-d", str(classes), *(str(path) for path in source_paths)],
        [args.java, "-Dfile.encoding=UTF-8", "-cp", str(classes) + os.pathsep + classpath,
         "org.junit.runner.JUnitCore", *test_classes],
    ]
    for name, command in zip(("compile", "tests"), commands):
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
        text = result.stdout + result.stderr
        (output / (name + ".log")).write_text(text, encoding="utf-8")
        if result.returncode:
            report_path.write_text(json.dumps({"status": "failed", "stage": name,
                "exit_code": result.returncode}) + "\n", encoding="utf-8")
            print(text[-6000:])
            return result.returncode
    count = re.search(r"OK \((\d+) tests?\)", text)
    report = {"status": "passed" if count else "failed", "passed": int(count.group(1)) if count else None, "classes": test_classes,
              "scope": "Android-independent JVM; not an APK build"}
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 0 if count else 1


if __name__ == "__main__":
    raise SystemExit(main())
