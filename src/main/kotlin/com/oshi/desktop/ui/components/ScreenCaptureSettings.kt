package com.oshi.desktop.ui.components

import androidx.compose.runtime.Composable
import com.oshi.desktop.i18n.t
import com.oshi.desktop.store.ScreenCapturePreference
import com.oshi.desktop.store.StrictMode

/**
 * __SCREEN_CAPTURE_PROTECTION_2026_11_20__ Settings › Security › "Screenshot protection".
 * On by default, like Android. Shown only where it can be honoured (Windows); elsewhere the
 * "not on a computer" page says why.
 */
@Composable
fun ScreenCaptureSettingsCard() {
    if (!ScreenCapturePreference.supported) return
    SettingsCard(title = t("onboarding_feature_screenshot_title"), footer = t("desktop.capture.footer")) {
        SettingsToggleRow(
            SettingsIcon.EYE,
            t("onboarding_feature_screenshot_title"),
            if (StrictMode.enabled) t("desktop.strict.forced.on") else t("desktop.capture.detail"),
            checked = ScreenCapturePreference.effective,
            // __STRICT_MODE_2026_11_22__ forced while strict mode is on; the person's own value returns after.
            enabled = !StrictMode.enabled,
        ) { ScreenCapturePreference.set(it) }
    }
}
