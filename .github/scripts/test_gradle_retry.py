import contextlib
import io
import unittest
from unittest.mock import Mock

from gradle_retry import MAVEN_RATE_LIMIT, attempt, run


class GradleRetryTest(unittest.TestCase):
    def invoke(self, outcomes):
        execute = Mock(side_effect=outcomes)
        sleep = Mock()
        with contextlib.redirect_stdout(io.StringIO()):
            code = run(["gradlew", "test"], execute=execute, sleep=sleep)
        return code, execute, sleep

    def test_success_does_not_repeat_or_sleep(self):
        code, execute, sleep = self.invoke([(0, False)])
        self.assertEqual(0, code)
        self.assertEqual(1, execute.call_count)
        sleep.assert_not_called()

    def test_temporary_429_retries_then_succeeds(self):
        code, execute, sleep = self.invoke([(1, True), (0, False)])
        self.assertEqual(0, code)
        self.assertEqual(2, execute.call_count)
        sleep.assert_called_once_with(20)

    def test_persistent_rate_limit_stops_after_three_attempts(self):
        code, execute, sleep = self.invoke([(1, True), (1, True), (7, True)])
        self.assertEqual(7, code)
        self.assertEqual(3, execute.call_count)
        self.assertEqual([20, 40], [call.args[0] for call in sleep.call_args_list])

    def test_real_build_failure_is_never_retried(self):
        for outcomes in [[(1, False)], [(1, True), (2, False)]]:
            code, execute, _ = self.invoke(outcomes)
            self.assertEqual(outcomes[-1][0], code)
            self.assertEqual(len(outcomes), execute.call_count)

    def test_only_central_download_rate_limits_are_recognized(self):
        self.assertTrue(MAVEN_RATE_LIMIT.search(
            "> Could not GET 'https://repo.maven.apache.org/maven2/org/robolectric/runtime.jar'. "
            "Received status code 429 from server: Too Many Requests"))
        for line in ["Test failed: expected status code 429", "Received status code 503",
                     "Could not GET 'https://unrelated.test/file'. Received status code 429"]:
            self.assertFalse(MAVEN_RATE_LIMIT.search(line))

    def test_real_child_output_is_classified_and_exit_code_preserved(self):
        import sys
        command = [sys.executable, "-c", "print(\"Could not GET 'https://repo.maven.apache.org/maven2/file'. Received status code 429\"); raise SystemExit(6)"]
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual((6, True), attempt(command))


if __name__ == "__main__":
    unittest.main()
