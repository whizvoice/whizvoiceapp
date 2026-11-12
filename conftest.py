"""pytest hooks for the screen agent tests.

Assertion failures inside run_screen_agent_tests.py save their own screenshot + UI dump
before asserting. Anything else that escapes a test body (an API error from a vision
check, a validation error, a bug in the test) used to leave no trace on disk, because the
console traceback is only visible in the terminal and is gone once the session scrolls.
This hook captures that case: the traceback is written to a file and the tester's
post-mortem screenshot + hierarchy dump are saved alongside it.
"""
import os
import sys
import traceback
from datetime import datetime

sys.path.insert(0, '/Users/ruthgracewong/android_accessibility_tester')

from android_accessibility_tester import capture_failure, find_tester  # noqa: E402

SCREEN_AGENT_OUTPUT_DIR = os.path.join(os.path.dirname(__file__), 'screen_agent_test_output')


def pytest_runtest_makereport(item, call):
    if call.when != "call" or call.excinfo is None:
        return

    os.makedirs(SCREEN_AGENT_OUTPUT_DIR, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    exc_type = call.excinfo.type.__name__

    # 1. Always persist the traceback, assertion or not, so the cause survives the session.
    trace_path = os.path.join(SCREEN_AGENT_OUTPUT_DIR, f"{item.name}_{exc_type}_{stamp}.txt")
    try:
        with open(trace_path, "w") as f:
            f.write("".join(traceback.format_exception(
                call.excinfo.type, call.excinfo.value, call.excinfo.tb)))
        print(f"\nTraceback saved to: {trace_path}")
    except Exception as e:  # never mask the real failure
        print(f"Could not save traceback: {e}")

    # 2. For non-assertion exceptions, also take the post-mortem screenshot + UI dump.
    #    (Assertion paths already saved theirs at the failing step; don't duplicate.)
    if call.excinfo.type is AssertionError:
        return
    tester = find_tester(getattr(item, "funcargs", {}) or {})
    if tester is None:
        return
    try:
        artifacts = capture_failure(tester, item.name, output_dir=SCREEN_AGENT_OUTPUT_DIR,
                                    step_name=f"unhandled_{exc_type}")
        saved = [p for p in artifacts.values() if p]
        if saved:
            print("\nFailure artifacts:\n  " + "\n  ".join(saved))
    except Exception as e:
        print(f"Could not capture failure artifacts: {e}")
