import tempfile
import unittest
from pathlib import Path

from update_release_badge import render, update


def release(tag="v0.8.14"):
    return {"tag_name": tag, "draft": False, "prerelease": False, "published_at": "2026-11-24T19:05:02Z",
            "assets": [{"name": f"Nexora-Sessions-Hub-{tag}.apk", "state": "uploaded", "size": 1234}]}


README = ("# Nexora\n\n[![build-and-release](https://example.test/build.svg)](https://example.test/build)\n"
          "[![release](https://img.shields.io/github/v/release/owner/repo?label=release&sort=date)](https://github.com/owner/repo/releases/latest)\n"
          "[![licença](https://example.test/license.svg)](LICENSE)\n\nPastas, perfis e sessões.\n")


class ReleaseBadgeTest(unittest.TestCase):
    def test_dynamic_badge_becomes_the_exact_published_version(self):
        result = render(README, "owner/repo", release())
        self.assertIn("https://img.shields.io/badge/release-v0.8.14-orange", result)
        self.assertIn("https://github.com/owner/repo/releases/tag/v0.8.14", result)
        before = README.splitlines()
        after = result.splitlines()
        self.assertEqual([3], [index for index, pair in enumerate(zip(before, after)) if pair[0] != pair[1]])

    def test_a_new_release_updates_both_label_and_link(self):
        previous = render(README, "owner/repo", release())
        updated = render(previous, "owner/repo", release("v0.8.15"))
        self.assertNotIn("v0.8.14", updated)
        self.assertIn("release-v0.8.15-orange", updated)
        self.assertIn("releases/tag/v0.8.15", updated)

    def test_unchanged_release_is_idempotent(self):
        updated = render(README, "owner/repo", release())
        self.assertEqual(updated, render(updated, "owner/repo", release()))

    def test_draft_prerelease_or_missing_publication_cannot_change_the_badge(self):
        for key, value in [("draft", True), ("prerelease", True), ("published_at", None), ("tag_name", "invalid")]:
            with self.assertRaises(ValueError):
                render(README, "owner/repo", {**release(), key: value})

    def test_release_without_finished_apk_is_rejected(self):
        for assets in [[], [{"name": "Nexora-Sessions-Hub-v0.8.14.apk", "state": "new", "size": 0}]]:
            with self.assertRaises(ValueError):
                render(README, "owner/repo", {**release(), "assets": assets})

    def test_missing_or_duplicate_badge_is_rejected(self):
        for text in ["# no badge", README + README]:
            with self.assertRaises(ValueError):
                render(text, "owner/repo", release())

    def test_file_update_preserves_windows_line_endings_and_unicode(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "README.md"
            path.write_bytes(README.replace("\n", "\r\n").encode("utf-8"))
            self.assertTrue(update(path, "owner/repo", release()))
            self.assertFalse(update(path, "owner/repo", release()))
            data = path.read_bytes()
            self.assertNotIn(b"\n", data.replace(b"\r\n", b""))
            self.assertIn("sessões".encode("utf-8"), data)


if __name__ == "__main__":
    unittest.main()
