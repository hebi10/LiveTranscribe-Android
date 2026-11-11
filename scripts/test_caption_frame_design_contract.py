"""UI-GAP-01: pin the approved CAPTION FRAME roles without changing UI at runtime."""
import json
import re
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TOKENS_PATH = ROOT / "docs/ui/caption-frame-tokens-v1.json"
THEME_PATH = ROOT / "app/src/main/java/com/charles/livecaptionn/ui/theme/CaptionFrameTheme.kt"
OVERLAY_THEME_PATH = ROOT / "app/src/main/java/com/charles/livecaptionn/overlay/OverlayTheme.kt"
OVERLAY_METRICS_PATH = ROOT / "app/src/main/java/com/charles/livecaptionn/overlay/OverlayChromeLayout.kt"
THEME_MODE_PATH = ROOT / "app/src/main/java/com/charles/livecaptionn/settings/AppThemeMode.kt"
ACTIVITY_PATH = ROOT / "app/src/main/java/com/charles/livecaptionn/MainActivity.kt"
LAUNCHER_DIR = ROOT / "app/src/main/res/mipmap-anydpi-v26"


def material_colors(source, scheme):
    name = "CaptionFrameLightColors" if scheme == "light" else "CaptionFrameDarkColors"
    ctor = "lightColorScheme" if scheme == "light" else "darkColorScheme"
    block = re.search(
        rf"internal val {name}\s*=\s*{ctor}\((.*?)\n\)",
        source,
        flags=re.DOTALL,
    )
    if block is None:
        raise AssertionError(f"Missing {name} Material 3 color scheme")
    return {
        role: "#" + value.upper()
        for role, value in re.findall(
            r"\b(\w+)\s*=\s*Color\(0xFF([0-9a-fA-F]{6})\)",
            block.group(1),
        )
    }


class CaptionFrameDesignContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tokens = json.loads(TOKENS_PATH.read_text(encoding="utf-8"))

    def test_color_roles_match_existing_material_theme(self):
        source = THEME_PATH.read_text(encoding="utf-8")
        for mode in ("light", "dark"):
            with self.subTest(mode=mode):
                actual = material_colors(source, mode)
                for role, expected in self.tokens["palette"][mode].items():
                    self.assertEqual(actual.get(role), expected, f"{mode}.{role}")
        self.assertIn("if (darkTheme) CaptionFrameDarkColors else CaptionFrameLightColors", source)

    def test_theme_selection_and_monochrome_launcher(self):
        mode = THEME_MODE_PATH.read_text(encoding="utf-8")
        activity = ACTIVITY_PATH.read_text(encoding="utf-8")
        self.assertRegex(mode, r"SYSTEM,\s*LIGHT,\s*DARK")
        self.assertIn("useDarkColors(isSystemInDarkTheme())", activity)
        symbol = self.tokens["iconPolicy"]["launcher"]
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            launcher = (LAUNCHER_DIR / name).read_text(encoding="utf-8")
            for role in ("foreground", "monochrome"):
                self.assertIn(
                    f"<{role} android:drawable=\"@drawable/{symbol}\"",
                    launcher,
                    f"Missing {role} in {name}",
                )

    def test_overlay_remains_dark_and_control_targets_stay_usable(self):
        overlay = OVERLAY_THEME_PATH.read_text(encoding="utf-8")
        metrics = OVERLAY_METRICS_PATH.read_text(encoding="utf-8")
        self.assertIn("Color.rgb(16, 16, 16), Color.WHITE", overlay)
        self.assertIn("Color.BLACK, Color.WHITE", overlay)
        values = {
            "TOUCH_DP": "overlayTouch",
            "MINIMIZED_HEIGHT_DP": "overlayMinimizedHeight",
            "LABELED_CONTROL_MIN_WIDTH_DP": "overlayLabeledControlMinWidth",
        }
        for kotlin_role, token_role in values.items():
            match = re.search(rf"\bconst val {kotlin_role}\s*=\s*(\d+)", metrics)
            self.assertIsNotNone(match, kotlin_role)
            self.assertEqual(int(match.group(1)), self.tokens["spacingDp"][token_role])

    def test_future_spacings_and_typography_targets_are_regular(self):
        spacing = self.tokens["spacingDp"]
        self.assertEqual(spacing["grid"], 4)
        self.assertTrue(all(v % spacing["grid"] == 0 for v in (
            spacing["screenHorizontal"], spacing["section"], spacing["content"],
            spacing["controlGap"], spacing["minTouch"],
            spacing["overlayTouch"], spacing["overlayMinimizedHeight"],
            spacing["overlayLabeledControlMinWidth"],
        )))
        self.assertGreaterEqual(spacing["minTouch"], 48)
        typography = self.tokens["typographySp"]
        self.assertEqual(typography["titleLarge"], 22)
        self.assertEqual(typography["titleMedium"], 16)
        self.assertEqual(typography["bodyMedium"], 14)
        self.assertEqual(typography["bodySmall"], 12)
        self.assertEqual(typography["labelLarge"], 14)
        self.assertTrue(self.tokens["iconPolicy"]["adaptiveMonochrome"])


if __name__ == "__main__":
    unittest.main()
