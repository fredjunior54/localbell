# LocalBell Android Design Rules

## Platform and anchor

Use Material You (Android Expressive) for this phone-first Android 14+ Compose app. Keep dynamic wallpaper colors on by default and honor the system light or dark setting. The design targets balanced density and platform ripple feedback.

## Theme tokens

- `Color.kt`: Use `MaterialTheme.colorScheme` and pair each surface with its `on*` role. The Material 3 library supplies complete light and dark schemes, including surface containers, and supplies the fallback when dynamic color is disabled.
- `Type.kt`: Choose Material typography roles, not inline sizes or typefaces. The app uses the Android sans-serif family (system Roboto where available); it does not bundle Roboto Flex. The scale uses 12, 14, 16, 22, 28, 32, and 48 sp.
- `Shape.kt`: Use the five Material shape roles at 4, 8, 12, 16, and 28 dp. Prefer large shapes on cards and the standard Material shape on buttons.
- `Spacing.kt`: Use `MaterialTheme.spacing` for spacing and minimum touch sizes. Its scale is 4, 8, 12, 16, 20, 24, 32, 40, 48, and 56 dp.
- `Theme.kt`: Apply the shared Material color, text, shape, and spacing tokens at the app root.

## Component rules

- Prefer filled cards, Material dialogs, and outlined Material Symbols for new UI. Do not substitute symbols for product artwork or change existing icons outside the scope of a screen update.
- Do not add ad hoc dimensions, typography, or colors, decorative gradients, filler text, or animations without a UI purpose. Use Material component motion defaults or named Material motion tokens rather than inline durations.
- Use sentence case and English string resources for user-facing text. Label icon-only actions for TalkBack and mark decorative images as such.
- Preserve edge-to-edge system insets and predictive back. Interactive targets must be at least 48 dp. Show empty, loading, and error states for asynchronous content.
- Preview new or updated surfaces in light and dark modes. Check contrast at 4.5:1 for text and 3:1 for non-text controls; verify representative device palettes and do not assume that arbitrary wallpaper-derived combinations meet contrast.

## Verification

From `app/`, run `./gradlew detektDesign lint spotlessCheck testDebugUnitTest`. Perform the token audit when changing the theme and the UI audit when changing a screen. Existing screens are not restyled by this adoption; apply the rules to new and changed UI.
