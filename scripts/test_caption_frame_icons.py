from pathlib import Path
import struct
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class CaptionFrameIconTests(unittest.TestCase):
    def test_adaptive_variants_use_monochrome_safe_brand_source(self):
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            root = ET.parse(RES / "mipmap-anydpi-v26" / name).getroot()
            self.assertEqual(root.find("background").get(ANDROID + "drawable"), "@color/ic_launcher_background")
            self.assertEqual(root.find("foreground").get(ANDROID + "drawable"), "@drawable/ic_caption_frame_mark")
            self.assertEqual(root.find("monochrome").get(ANDROID + "drawable"), "@drawable/ic_caption_frame_mark")
        self.assertEqual(ET.parse(MANIFEST).getroot().find("application").get(ANDROID + "icon"), "@mipmap/ic_launcher")
        colors = (RES / "values/ic_launcher_background.xml").read_text(encoding="utf-8")
        self.assertIn("#101010", colors)

    def test_icon_pngs_have_real_expected_dimensions(self):
        for rel, dimensions in (
            ("app/src/main/res/drawable-nodpi/ic_caption_frame_mark.png", (432, 432)),
            ("assets/branding/caption-frame-store-icon-512.png", (512, 512)),
        ):
            payload = (ROOT / rel).read_bytes()
            self.assertEqual(payload[:8], b"\x89PNG\r\n\x1a\n")
            self.assertEqual(struct.unpack(">II", payload[16:24]), dimensions)

    def test_shared_compose_icons_use_outlined_set(self):
        for name in ("MainScreen", "CaptionScreen", "HistoryScreen", "SplitScreenLauncher"):
            text = (ROOT / f"app/src/main/java/com/charles/livecaptionn/ui/{name}.kt").read_text(encoding="utf-8")
            self.assertNotIn("icons.filled.", text)
            self.assertNotIn("Icons.Filled.", text)
            self.assertNotIn("Icons.Default.", text)
            self.assertTrue("icons.outlined." in text or "icons.automirrored.outlined." in text)

    def test_notification_and_overlay_share_one_stroke_style(self):
        service = (ROOT / "app/src/main/java/com/charles/livecaptionn/service/CaptionForegroundService.kt").read_text(encoding="utf-8")
        self.assertIn("setSmallIcon(R.drawable.ic_overlay_captions)", service)
        self.assertIn("addAction(R.drawable.ic_overlay_pause", service)
        self.assertIn("addAction(R.drawable.ic_overlay_close", service)
        for resource in (RES / "drawable").glob("ic_overlay_*.xml"):
            text = resource.read_text(encoding="utf-8")
            self.assertIn('android:strokeWidth="2"', text)
            self.assertNotIn('android:strokeWidth="2.5"', text)


if __name__ == "__main__":
    unittest.main()
