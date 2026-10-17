"""A scroll anchor may leave or enter the finger's gesture rectangle."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))
from adb_controller import AndroidController


class ScrollProgressTests(unittest.TestCase):
    def test_anchor_crossing_gesture_boundary_still_proves_progress(self):
        phone = AndroidController(None, "/unused")
        area = {"x": 100, "y": 800, "width": 800, "height": 300}
        for old_y, new_y in ((900, 500), (1300, 900)):
            with self.subTest(old_y=old_y):
                before = [{"type": "TextView", "text": "Stable row", "rect": [200, old_y, 400, 60]}]
                after = [{"type": "TextView", "text": "Stable row", "rect": [200, new_y, 400, 60]}]
                self.assertTrue(phone.scroll_progress(before, after, area, "up"))

    def test_unrelated_motion_outside_gesture_area_is_not_progress(self):
        phone = AndroidController(None, "/unused")
        area = {"x": 100, "y": 800, "width": 800, "height": 300}
        before = [{"type": "TextView", "text": "Ticker", "rect": [200, 100, 400, 60]}]
        after = [{"type": "TextView", "text": "Ticker", "rect": [200, 80, 400, 60]}]
        self.assertFalse(phone.scroll_progress(before, after, area, "up"))
