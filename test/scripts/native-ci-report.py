#!/usr/bin/env python3
#
# Copyright 1999-2026 Alibaba Group Holding Ltd.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Judges the test reports of a Native CI run, for the report stage of native-ci.sh.

A run succeeds only if every suite that the scenario expects completed and nothing but known failures
failed. A stage that dies before it writes a report leaves no report behind, so the absence of a
report counts as a failure here, whatever the reports of the other stages say.

Usage:
    native-ci-report.py judge --root DIR --baseline FILE... [--suites] [--suite full|core]
        [--core-openapi A,B] [--java-sdk A,B] [--native A,B] [--restart-report FILE]
    native-ci-report.py selftest
"""

import argparse
import fnmatch
import glob
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

# The steps that the restart check has to report as passed, in this order. A report that stops
# early, or that lacks the step of the snapshots, fails the run: without the snapshots the data may
# come back through the replay of the Raft log, and nothing is learned about loading a snapshot.
RESTART_STEPS = (
    "seed",
    "snapshots saved after the seed",
    "stop",
    "start after the restart",
    "snapshots loaded by the restart",
    "data after the restart",
)

# Test modules whose classes every suite run has to report.
MODULE_OPENAPI = "openapi-test"
MODULE_JAVA_SDK = "java-sdk-test"
MODULE_MAINTAINER = "maintainer-sdk-test"
MODULE_NATIVE = "native-test"


def split(value):
    return [item for item in (value or "").split(",") if item]


def read_reports(root):
    """The test cases of every failsafe report below root.

    Returns the cases as (module, class, method, kind, message) and the set of (module, class) that
    have a report file, whether or not it holds a test.
    """
    cases, reported = [], set()
    pattern = os.path.join(root, "test", "*", "target", "failsafe-reports", "TEST-*.xml")
    for path in sorted(glob.glob(pattern)):
        module = path.split(os.sep)[-4]
        file_class = os.path.basename(path)[len("TEST-"):-len(".xml")].split("$")[0].split(".")[-1]
        reported.add((module, file_class))
        for case in ET.parse(path).getroot().iter("testcase"):
            name = case.get("classname").split("$")[0].split(".")[-1]
            kind, message = "passed", ""
            if case.find("skipped") is not None:
                kind = "skipped"
            for failure in ("failure", "error"):
                item = case.find(failure)
                if item is not None:
                    kind, message = "failed", (item.get("message") or "").replace("\n", " ")[:200]
            cases.append((module, name, case.get("name"), kind, message))
    return cases, reported


def concrete_classes(root, module):
    """The integration test classes that a suite runs from the sources of a test module.

    An abstract class is not run, and neither is a class that is disabled as a whole.
    """
    names = set()
    source = os.path.join(root, "test", module, "src", "test", "java")
    for directory, _, files in os.walk(source):
        for file in files:
            if not file.endswith("ITCase.java"):
                continue
            name = file[:-len(".java")]
            with open(os.path.join(directory, file), encoding="utf-8", errors="replace") as handle:
                text = handle.read()
            head = text.split("class " + name)[0]
            if re.search(r"\babstract\s+class\s+" + name + r"\b", text) or re.search(r"@Disabled\b", head):
                continue
            names.add(name)
    return names


def completeness(root, cases, reported, args):
    """What a scenario expected to run and did not: one line each."""
    executed = {}
    for module, name, _, kind, _ in cases:
        if kind != "skipped":
            executed[(module, name)] = executed.get((module, name), 0) + 1
    modules = {}
    for (module, _), count in executed.items():
        modules[module] = modules.get(module, 0) + count

    missing = []

    def must_run(module, names):
        for name in sorted(names):
            if executed.get((module, name), 0) == 0:
                state = "skipped" if (module, name) in reported else "no report"
                missing.append("%s: %s has no executed test (%s)" % (module, name, state))

    def must_report(module, names):
        for name in sorted(names):
            if (module, name) not in reported:
                missing.append("%s: %s has no report" % (module, name))

    if args.native:
        must_run(MODULE_NATIVE, split(args.native))
    if args.suites:
        must_run(MODULE_JAVA_SDK, split(args.java_sdk))
        if args.suite == "core":
            must_run(MODULE_OPENAPI, split(args.core_openapi))
        else:
            must_report(MODULE_OPENAPI, concrete_classes(root, MODULE_OPENAPI))
        must_report(MODULE_MAINTAINER, concrete_classes(root, MODULE_MAINTAINER))
        for module in (MODULE_OPENAPI, MODULE_JAVA_SDK, MODULE_MAINTAINER):
            if modules.get(module, 0) == 0:
                missing.append("%s: no test was executed" % module)
    return missing


def judge_restart(path, expected):
    """Problems of the report of the restart check, one line each."""
    if not os.path.isfile(path):
        # The environment writes the report before it ends, so a missing one means that it ended in
        # the middle of the check, or before it.
        return ["the restart check did not run (no %s)" % os.path.basename(path)]
    with open(path, encoding="utf-8") as handle:
        lines = [line.rstrip("\n") for line in handle if line.strip()]
    problems = ["failed step: %s" % line for line in lines if line.startswith("FAIL")]
    passed = [line[len("PASS  "):] for line in lines if line.startswith("PASS  ")]
    for step in expected:
        if not any(line == step or line.startswith(step + ":") for line in passed):
            problems.append("step did not pass or did not run: %s" % step)
    return problems


def judge(args):
    root = args.root
    baseline = []
    for baseline_file in args.baseline:
        with open(baseline_file, encoding="utf-8") as handle:
            baseline += [line.strip() for line in handle if line.strip() and not line.startswith("#")]
    cases, reported = read_reports(root)
    executed = [case for case in cases if case[3] != "skipped"]
    failed = [(("%s.%s" % (case[1], case[2])), case[4]) for case in cases if case[3] == "failed"]
    known = [item for item in failed if any(fnmatch.fnmatch(item[0], pattern) for pattern in baseline)]
    new = [item for item in failed if item not in known]
    status = 0

    print("%d tests executed, %d failed (%d are known failures of the JVM server in this setup)"
          % (len(executed), len(failed), len(known)))
    by_class = {}
    for name, message in new:
        by_class.setdefault(name.split(".")[0], []).append((name, message))
    for test_class, items in sorted(by_class.items()):
        print("NEW FAILURES in %s: %d" % (test_class, len(items)))
        for name, message in items[:3]:
            print("    %s: %s" % (name, message))
    if not executed or new:
        status = 1

    print("\n==> Completeness of the suites")
    missing = completeness(root, cases, reported, args)
    for line in missing[:20]:
        print("    INCOMPLETE %s" % line)
    if len(missing) > 20:
        print("    ... and %d more" % (len(missing) - 20))
    print("    %s" % ("every expected suite completed" if not missing else "%d expected results are missing" % len(missing)))
    if missing:
        status = 1

    if args.restart_report:
        print("\n==> Restart check")
        problems = judge_restart(args.restart_report, RESTART_STEPS)
        if os.path.isfile(args.restart_report):
            with open(args.restart_report, encoding="utf-8") as handle:
                sys.stdout.write(handle.read())
        for problem in problems:
            print("    PROBLEM %s" % problem)
        if problems:
            status = 1
    return status


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


def report_xml(module, root, classname, cases):
    """A failsafe report: cases is a list of (method, kind) with kind passed, skipped or failed."""
    body = ""
    for method, kind in cases:
        inner = {"passed": "", "skipped": "<skipped/>", "failed": '<failure message="boom"/>'}[kind]
        body += '<testcase classname="%s" name="%s">%s</testcase>' % (classname, method, inner)
    write(os.path.join(root, "test", module, "target", "failsafe-reports", "TEST-%s.xml" % classname),
          "<testsuite>%s</testsuite>" % body)


def selftest():
    failures = []

    def expect(name, condition):
        if not condition:
            failures.append(name)

    with tempfile.TemporaryDirectory() as root:
        baseline = os.path.join(root, "baseline.txt")
        write(baseline, "# known\nKnownITCase.slow\n")
        for module, name, abstract in ((MODULE_OPENAPI, "AlphaITCase", False), (MODULE_OPENAPI, "BaseITCase", True),
                                       (MODULE_OPENAPI, "OffITCase", False), (MODULE_MAINTAINER, "BetaITCase", False)):
            head = "@Disabled\n" if name == "OffITCase" else ""
            write(os.path.join(root, "test", module, "src", "test", "java", name + ".java"),
                  "%spublic %sclass %s {}\n" % (head, "abstract " if abstract else "", name))
        report_xml(MODULE_OPENAPI, root, "p.AlphaITCase", [("a", "passed")])
        report_xml(MODULE_MAINTAINER, root, "p.BetaITCase", [("b", "passed")])
        report_xml(MODULE_JAVA_SDK, root, "p.SdkITCase", [("s", "passed")])
        report_xml(MODULE_NATIVE, root, "p.SmokeITCase", [("n", "passed"), ("m", "skipped")])
        restart = os.path.join(root, "restart.txt")
        write(restart, "".join("PASS  %s\n" % step for step in RESTART_STEPS))

        def run(**overrides):
            options = dict(root=root, baseline=[baseline], suites=True, suite="full", core_openapi="", java_sdk="SdkITCase",
                           native="SmokeITCase", restart_report=restart)
            options.update(overrides)
            with open(os.devnull, "w") as sink:
                stdout, sys.stdout = sys.stdout, sink
                try:
                    return judge(argparse.Namespace(**options))
                finally:
                    sys.stdout = stdout

        expect("a complete run passes", run() == 0)
        expect("a class that is disabled or abstract is not expected", run() == 0)
        expect("a class of a list without a report fails", run(java_sdk="SdkITCase,GoneITCase") == 1)
        expect("a scenario class that only skipped fails", run(native="SmokeITCase,MissITCase") == 1)
        expect("a missing restart report fails", run(restart_report=os.path.join(root, "none.txt")) == 1)
        write(os.path.join(root, "short.txt"), "PASS  seed\nPASS  stop\nPASS  start after the restart\nPASS  data after the restart\n")
        expect("a restart report without the snapshot steps fails", run(restart_report=os.path.join(root, "short.txt")) == 1)
        write(os.path.join(root, "bad.txt"), "".join("PASS  %s\n" % s for s in RESTART_STEPS) + "FAIL  something\n")
        expect("a failed restart step fails", run(restart_report=os.path.join(root, "bad.txt")) == 1)
        report_xml(MODULE_OPENAPI, root, "p.KnownITCase", [("slow", "failed")])
        expect("a known failure passes", run() == 0)
        report_xml(MODULE_OPENAPI, root, "p.OtherITCase", [("x", "failed")])
        expect("a new failure fails", run() == 1)
        os.remove(os.path.join(root, "test", MODULE_OPENAPI, "target", "failsafe-reports", "TEST-p.OtherITCase.xml"))
        os.remove(os.path.join(root, "test", MODULE_MAINTAINER, "target", "failsafe-reports", "TEST-p.BetaITCase.xml"))
        expect("a suite class without a report fails", run() == 1)
        expect("a run without suites does not ask for them", run(suites=False, restart_report=None) == 0)
        report_xml(MODULE_MAINTAINER, root, "p.BetaITCase", [("b", "passed")])
        expect("the run passes again once the report is back", run() == 0)
        report_xml(MODULE_OPENAPI, root, "p.CoreITCase", [("c", "skipped")])
        expect("a core class that only skipped fails", run(suite="core", core_openapi="CoreITCase") == 1)
        report_xml(MODULE_OPENAPI, root, "p.CoreITCase", [("c", "passed")])
        expect("a core class that ran passes", run(suite="core", core_openapi="CoreITCase") == 0)
    if failures:
        for name in failures:
            print("SELFTEST FAILED: %s" % name, file=sys.stderr)
        return 1
    print("selftest passed")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("selftest")
    judge_parser = commands.add_parser("judge")
    judge_parser.add_argument("--root", required=True)
    judge_parser.add_argument("--baseline", action="append", default=[])
    judge_parser.add_argument("--suites", action="store_true", help="the run has the suites of the test modules")
    judge_parser.add_argument("--suite", choices=("full", "core"), default="full")
    judge_parser.add_argument("--core-openapi", default="")
    judge_parser.add_argument("--java-sdk", default="")
    judge_parser.add_argument("--native", default="", help="classes of native-test that have to run")
    judge_parser.add_argument("--restart-report")
    args = parser.parse_args()
    if args.command == "selftest":
        return selftest()
    return judge(args)


if __name__ == "__main__":
    sys.exit(main())
