import contextlib
import io
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import Mock

from cleanup_releases import cleanup, list_releases, plan


def releases(count):
    base = datetime(2026, 1, 1, tzinfo=timezone.utc)
    return [{"id": index + 1, "tag_name": f"v0.1.{index}", "draft": False, "prerelease": False,
             "published_at": (base + timedelta(days=index)).isoformat()}
            for index in range(count)]


class CleanupReleasesTest(unittest.TestCase):
    def test_ten_or_fewer_are_preserved(self):
        for count in [0, 1, 10]:
            retained, obsolete = plan(releases(count))
            self.assertEqual(count, len(retained))
            self.assertEqual([], obsolete)

    def test_retains_newest_by_publication_date_regardless_of_api_order_or_tag_version(self):
        source = releases(18)
        source[-1]["tag_name"] = "v0.0.1"
        retained, obsolete = plan(list(reversed(source)))
        self.assertEqual(list(range(18, 8, -1)), [item["id"] for item in retained])
        self.assertEqual(list(range(1, 9)), [item["id"] for item in obsolete])

    def test_drafts_and_unpublished_releases_are_not_counted_or_deleted(self):
        source = releases(12)
        source[0]["draft"] = True
        source[-1]["published_at"] = None
        source[1]["prerelease"] = True
        retained, obsolete = plan(source)
        self.assertEqual(10, len(retained))
        self.assertEqual([], obsolete)

    def test_all_pages_are_read_before_planning(self):
        source = releases(101)
        api = Mock(side_effect=[source[:100], source[100:]])
        self.assertEqual(source, list_releases("owner/repo", api))
        self.assertEqual("repos/owner/repo/releases?per_page=100&page=2", api.call_args.args[1])

    def test_invalid_metadata_or_keep_cannot_produce_a_deletion_plan(self):
        for count in [0, -1]:
            with self.assertRaises(ValueError):
                plan(releases(20), count)
        source = releases(12)
        for invalid in [source + [source[0]], source + [{**source[0], "id": 99, "published_at": "invalid"}]]:
            with self.assertRaises(ValueError):
                plan(invalid)

    def test_dry_run_performs_no_deletes(self):
        api = Mock(return_value=releases(18))
        with contextlib.redirect_stdout(io.StringIO()):
            result = cleanup("owner/repo", api=api)
        self.assertEqual(8, result["obsolete"])
        self.assertEqual([], result["deleted"])
        self.assertEqual(1, api.call_count)
        self.assertEqual("GET", api.call_args.args[0])

    def test_apply_deletes_only_old_release_endpoints_and_preserves_tags(self):
        source = releases(12)
        calls = []
        def api(method, endpoint):
            calls.append((method, endpoint))
            if "?" in endpoint:
                return source
            return source[int(endpoint.rsplit("/", 1)[1]) - 1]
        with contextlib.redirect_stdout(io.StringIO()):
            result = cleanup("owner/repo", apply=True, api=api)
        self.assertEqual(["v0.1.0", "v0.1.1"], result["deleted"])
        self.assertEqual([("DELETE", "repos/owner/repo/releases/1"), ("DELETE", "repos/owner/repo/releases/2")],
                         [call for call in calls if call[0] == "DELETE"])

    def test_release_changed_since_listing_is_preserved(self):
        source = releases(11)
        api = Mock(side_effect=[source, {**source[0], "draft": True}])
        with contextlib.redirect_stdout(io.StringIO()):
            result = cleanup("owner/repo", apply=True, api=api)
        self.assertEqual([], result["deleted"])
        self.assertEqual(2, api.call_count)


if __name__ == "__main__":
    unittest.main()
