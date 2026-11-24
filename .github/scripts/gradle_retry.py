"""Retry Maven Central HTTP 429 downloads, without retrying failed tests/builds."""

import os
import re
import subprocess
import sys
import time
from pathlib import Path


MAVEN_RATE_LIMIT = re.compile(
    r"Could not (?:GET|HEAD).*https://(?:repo\.maven\.apache\.org|repo1\.maven\.org)/"
    r".*Received status code 429\b",
    re.IGNORECASE,
)


def attempt(command):
    limited = False
    with subprocess.Popen(
        command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    ) as process:
        for line in process.stdout:
            print(line, end="", flush=True)
            limited = limited or bool(MAVEN_RATE_LIMIT.search(line))
        return process.wait(), limited


def run(command, execute=attempt, sleep=time.sleep):
    for number in range(1, 4):
        code, limited = execute(command)
        if code == 0 or not limited or number == 3:
            return code
        delay = 20 * number
        print(
            f"Maven Central respondeu HTTP 429. Nova tentativa {number + 1}/3 em {delay}s.",
            flush=True,
        )
        sleep(delay)
    raise AssertionError("Unreachable")


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    try:
        sys.exit(run([str(wrapper), *sys.argv[1:]]))
    except KeyboardInterrupt:
        sys.exit(130)
