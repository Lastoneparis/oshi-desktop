package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.Strings
import com.oshi.desktop.i18n.t
import com.oshi.desktop.store.PrivacyPreferences
import com.oshi.desktop.pairing.ContactQr
import com.oshi.desktop.ui.AppearanceSettings
import com.oshi.desktop.ui.ChatListSettings
import com.oshi.desktop.ui.DisplaySettings
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.ThemeMode
import com.oshi.desktop.ui.state.Pane
import com.oshi.desktop.ui.state.Severity
import com.oshi.desktop.ui.state.ShellState

/**
 * __DESKTOP_SETTINGS_2027__ Settings, as a desktop lays it out: the sections down the left,
 * the chosen one on the right. It replaces the single 720-dp column of cards and text links
 * the More tab used to be.
 *
 * Every word is a catalog key, so the page is translated in all 34 locales; Arabic, Hebrew
 * and Persian mirror it (the theme root sets the layout direction).
 */
enum class SettingsSection(val icon: SettingsIcon, val group: Int) {
    PROFILE(SettingsIcon.PROFILE, 0),
    APPEARANCE(SettingsIcon.APPEARANCE, 0),
    NOTIFICATIONS(SettingsIcon.NOTIFICATIONS, 0),
    PRIVACY(SettingsIcon.PRIVACY, 0),
    /** __DESKTOP_APP_LOCK_2027__ passcode lock. */
    SECURITY(SettingsIcon.KEY, 0),
    DISGUISE(SettingsIcon.DISGUISE, 0),
    CONTACTS(SettingsIcon.CONTACTS, 1),
    DEVICES(SettingsIcon.DEVICES, 1),
    BACKUP(SettingsIcon.ARCHIVE, 1),
    ACCOUNT(SettingsIcon.KEY, 1),
    TOOLS(SettingsIcon.ANTENNA, 2),
    /** __DESKTOP_BOT_COMMANDS_2026_11_24__ the bots this account owns, and their `/` commands. */
    BOTS(SettingsIcon.BOT, 2),
    NOT_ON_DESKTOP(SettingsIcon.LAPTOP_OFF, 2),
    /** __DESKTOP_HELP_2027__ questions and answers, support links. */
    HELP(SettingsIcon.HELP, 2),
    ABOUT(SettingsIcon.INFO, 2),
    DELETE(SettingsIcon.DELETE, 3);

    fun title(): String = when (this) {
        PROFILE -> t("desktop.settings.section.profile")
        APPEARANCE -> t("settings.appearance.title")
        NOTIFICATIONS -> t("settings.notifications.title")
        PRIVACY -> t("desktop.settings.section.privacy")
        SECURITY -> t("settings.security.title")
        DISGUISE -> t("disguise.title")
        CONTACTS -> t("desktop.settings.section.contacts")
        DEVICES -> t("devsync.title")
        BACKUP -> t("desktop.settings.section.backup")
        ACCOUNT -> t("desktop.settings.section.account")
        TOOLS -> t("desktop.settings.section.tools")
        BOTS -> t("bot.management_title")
        NOT_ON_DESKTOP -> t("desktop.settings.section.notOnDesktop")
        HELP -> t("settings.help.title")
        ABOUT -> t("settings.about.title")
        DELETE -> t("account.delete.title")
    }

    fun subtitle(): String = when (this) {
        PROFILE -> t("desktop.settings.profile.subtitle")
        APPEARANCE -> t("desktop.settings.appearance.subtitle")
        NOTIFICATIONS -> t("desktop.settings.notifications.subtitle")
        PRIVACY -> t("desktop.settings.privacy.subtitle")
        SECURITY -> t("pc.lock.subtitle")
        DISGUISE -> t("desktop.disguise.subtitle")
        CONTACTS -> t("desktop.settings.contacts.subtitle")
        DEVICES -> t("desktop.settings.devices.subtitle")
        BACKUP -> t("desktop.settings.backup.subtitle")
        ACCOUNT -> t("desktop.settings.account.subtitle")
        TOOLS -> t("desktop.settings.tools.subtitle")
        BOTS -> t("bot.empty_desc")
        NOT_ON_DESKTOP -> t("desktop.settings.notOnDesktop.subtitle")
        HELP -> t("pc.help.subtitle")
        ABOUT -> t("settings.about.tagline")
        DELETE -> t("account.delete.headline")
    }
}

/** Which section is open. App-wide so the menu bar can open Settings at a given page. */
object SettingsNavigation {
    var section: SettingsSection by mutableStateOf(SettingsSection.PROFILE)
}

/**
 * The pages that need the live client. Passed in as slots so the pane itself renders with
 * no account at all (screenshots, tests) — the same seam [DeleteAccountHost] uses.
 */
class SettingsSlots(
    val contacts: @Composable () -> Unit = {},
    val linkedDevices: @Composable () -> Unit = {},
    val disguise: @Composable () -> Unit = {},
    val deleteAccount: @Composable () -> Unit = {},
    /** The recovery key, or null when this machine cannot export one. Called on demand. */
    val recoveryKey: () -> String? = { null },
    val version: String = "",
    /** __DESKTOP_AUTO_UPDATE_2026_11_21__ "Check for updates" (Windows/Linux only). */
    val updates: @Composable () -> Unit = {},
    /** __DESKTOP_BOT_COMMANDS_2026_11_24__ Settings → Bots (needs the live client). */
    val bots: @Composable () -> Unit = { BotsPage(emptyList(), emptyList(), BotsActions()) },
)

class SettingsActions(
    val onShow: (Pane) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onExportMessages: () -> Unit = {},
    val onImportMessages: () -> Unit = {},
    val onSetNickname: (String?) -> Unit = {},
    val onPullNickname: () -> Unit = {},
    val onPushNickname: () -> Unit = {},
    /** __PROFILE_PHOTOS_DESKTOP_2027__ */
    val onSetPhoto: (() -> Unit)? = null,
    val onRemovePhoto: (() -> Unit)? = null,
    val onExportCallDiagnostics: () -> Unit = {},
    val onDeliveryReceipts: (Boolean) -> Unit = {},
    val onReadReceipts: (Boolean) -> Unit = {},
    val onTypingIndicators: (Boolean) -> Unit = {},
    val onSyncPush: () -> Unit = {},
    val onSyncPull: () -> Unit = {},
)

@Composable
fun SettingsPane(
    state: ShellState,
    slots: SettingsSlots,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(OshiTheme.paneGround)) {
        // 1024 px window → a 232-dp column; it grows to 288 on a wide screen, never more.
        val sidebarWidth = when {
            maxWidth < 1100.dp -> 232.dp
            maxWidth < 1600.dp -> 264.dp
            else -> 288.dp
        }
        Row(Modifier.fillMaxSize()) {
            SettingsSidebar(state, Modifier.width(sidebarWidth).fillMaxHeight())
            Box(Modifier.width(Metrics.hairline).fillMaxHeight().background(OshiTheme.separator))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                SettingsContent(SettingsNavigation.section, state, slots, actions)
            }
        }
    }
}

// ---------------------------------------------------------------------------- sidebar

@Composable
private fun SettingsSidebar(state: ShellState, modifier: Modifier) {
    Column(
        modifier
            .background(OshiTheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.lg),
    ) {
        Text(
            t("settings.title"),
            style = OshiTheme.typography.headlineMedium,
            color = Ink.strong,
            modifier = Modifier.padding(horizontal = OshiTheme.sm),
        )
        Spacer(Modifier.height(OshiTheme.lg))
        // Who this is — the phone's settings open on the same card.
        val (source, hovered) = rememberRowInteraction()
        Row(
            Modifier
                .fillMaxWidth()
                .clip(OshiTheme.radiusMd)
                .background(
                    if (SettingsNavigation.section == SettingsSection.PROFILE) OshiTheme.brand.copy(alpha = 0.12f)
                    else if (hovered.value) OshiTheme.surface else Color.Transparent,
                )
                .focusRing(OshiTheme.radiusMd)
                .clickable(source, null) { SettingsNavigation.section = SettingsSection.PROFILE }
                .padding(OshiTheme.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupAvatar(state.selfPictureBase64, state.displayName, state.selfAddress, 40.dp)
            Spacer(Modifier.width(OshiTheme.md))
            Column(Modifier.weight(1f)) {
                Text(
                    state.displayName,
                    style = OshiTheme.typography.titleSmall,
                    color = Ink.strong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.selfAddressShort,
                    // A key is not prose: keep "3q2+7w…" in that order in Arabic too.
                    style = OshiTheme.typography.bodySmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                    color = Ink.soft,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(OshiTheme.md))
        var lastGroup = 0
        for (section in SettingsSection.entries) {
            if (section == SettingsSection.PROFILE) continue
            if (section.group != lastGroup) {
                Spacer(Modifier.height(OshiTheme.sm))
                Hairline(Modifier.padding(horizontal = OshiTheme.sm))
                Spacer(Modifier.height(OshiTheme.sm))
                lastGroup = section.group
            }
            SidebarItem(section, selected = SettingsNavigation.section == section) {
                SettingsNavigation.section = section
            }
        }
    }
}

@Composable
private fun SidebarItem(section: SettingsSection, selected: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    val danger = section == SettingsSection.DELETE
    val tint = if (danger) OshiTheme.danger else OshiTheme.brand
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(OshiTheme.radiusSm)
            .background(
                when {
                    selected -> tint.copy(alpha = 0.12f)
                    hovered.value -> OshiTheme.surface
                    else -> Color.Transparent
                },
            )
            .focusRing(OshiTheme.radiusSm)
            .clickable(source, null, onClick = onClick)
            .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconBadge(section.icon, tint, 28.dp)
        Spacer(Modifier.width(OshiTheme.md))
        Text(
            section.title(),
            style = OshiTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (danger) OshiTheme.danger else Ink.strong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------- pages

@Composable
private fun SettingsContent(section: SettingsSection, state: ShellState, slots: SettingsSlots, actions: SettingsActions) {
    when (section) {
        // These three draw their own frame (they are also opened outside Settings).
        SettingsSection.DISGUISE -> slots.disguise()
        SettingsSection.DELETE -> slots.deleteAccount()
        else -> SettingsPageFrame {
            SettingsPageHeader(section.icon, section.title(), section.subtitle())
            when (section) {
                SettingsSection.PROFILE -> ProfilePage(state, actions)
                SettingsSection.APPEARANCE -> AppearanceSettingsContent()
                SettingsSection.NOTIFICATIONS -> NotificationsPage()
                SettingsSection.PRIVACY -> PrivacyPage(state, actions)
                SettingsSection.SECURITY -> SecuritySettingsContent()
                SettingsSection.CONTACTS -> slots.contacts()
                SettingsSection.DEVICES -> slots.linkedDevices()
                SettingsSection.BACKUP -> BackupPage(state, slots, actions)
                SettingsSection.ACCOUNT -> AccountSettingsContent(state, actions)
                SettingsSection.TOOLS -> ToolsPage(actions)
                SettingsSection.BOTS -> slots.bots()
                SettingsSection.NOT_ON_DESKTOP -> NotOnDesktopPage()
                SettingsSection.HELP -> HelpPage(actions.onOpenUrl, initiallyOpen = HelpNavigation.open)
                SettingsSection.ABOUT -> AboutPage(slots, actions)
                else -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------- profile

@Composable
private fun ProfilePage(state: ShellState, actions: SettingsActions) {
    var showQr by remember { mutableStateOf(false) }
    SettingsCard {
        SettingsCardBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // __PROFILE_PHOTOS_DESKTOP_2027__ the picture contacts see; click to change it.
                OwnPhotoAvatar(state.selfPictureBase64, state.displayName, state.selfAddress, Metrics.avatarLarge, actions.onSetPhoto, actions.onRemovePhoto)
                Spacer(Modifier.width(OshiTheme.lg))
                Column(Modifier.weight(1f)) {
                    Text(state.displayName, style = OshiTheme.typography.titleLarge, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xxs))
                    Text(state.selfAddressShort, style = OshiTheme.typography.bodySmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr), color = Ink.soft, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(OshiTheme.lg))
            SettingsButtonRow {
                actions.onSetPhoto?.let { f -> SettingsButton(SettingsIcon.PROFILE, t("profile.photo.change")) { f() } }
                SettingsButton(SettingsIcon.COPY, t("settings.qr.copyAddress")) { actions.onCopy(state.selfAddress) }
                SettingsButton(SettingsIcon.QR, if (showQr) t("desktop.settings.profile.hideQr") else t("settings.identity.showQR")) {
                    showQr = !showQr
                }
            }
            if (showQr && state.selfAddress.isNotBlank()) {
                Spacer(Modifier.height(OshiTheme.lg))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(OshiTheme.radiusMd).background(Color.White).padding(OshiTheme.sm)) {
                        QrCode(ContactQr.deepLinkFor(state.selfAddress), 168.dp)
                    }
                    Spacer(Modifier.width(OshiTheme.lg))
                    Text(t("settings.qr.scanHint"), style = OshiTheme.typography.bodyMedium, color = Ink.soft, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    NicknameCard(state, actions)
}

@Composable
private fun NicknameCard(state: ShellState, actions: SettingsActions) {
    var typed by remember(state.ownNickname) { mutableStateOf(state.ownNickname.orEmpty()) }
    SettingsCard(
        title = t("desktop.nickname.title"),
        footer = if (state.nicknameBroadcasts) t("desktop.nickname.body") else t("desktop.nickname.withheld"),
    ) {
        SettingsCardBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .glassField()
                        .clip(OshiTheme.radiusMd)
                        .background(OshiTheme.surface)
                        .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
                ) {
                    if (typed.isEmpty()) {
                        Text(t("desktop.nickname.placeholder"), style = OshiTheme.typography.bodyLarge, color = Ink.soft)
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = typed,
                        // Same cap as both phones' Settings field; the wire sanitizer caps again.
                        onValueChange = { v -> typed = if (v.codePointCount(0, v.length) > 48) typed else v },
                        singleLine = true,
                        textStyle = OshiTheme.typography.bodyLarge.copy(color = Ink.strong),
                        cursorBrush = SolidColor(OshiTheme.brand),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(OshiTheme.md))
                SettingsButton(
                    SettingsIcon.CHECK,
                    t("common.save"),
                    primary = true,
                    enabled = !state.busy && typed.trim() != state.ownNickname.orEmpty(),
                ) { actions.onSetNickname(typed.trim().ifEmpty { null }) }
            }
            state.profileNotice?.let { SettingsNotice(it.text, it.severity == Severity.ERROR) }
        }
        Hairline()
        SettingsCardBody {
            Text(t("desktop.nickname.sync.hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.md))
            SettingsButtonRow {
                SettingsButton(SettingsIcon.IMPORT, t("desktop.nickname.pull"), enabled = !state.busy) { actions.onPullNickname() }
                SettingsButton(SettingsIcon.EXPORT, t("desktop.nickname.push"), enabled = !state.busy) { actions.onPushNickname() }
            }
        }
    }
}

// ---------------------------------------------------------------- appearance

@Composable
fun AppearanceSettingsContent() {
    val appearance = AppearanceSettings.current
    SettingsCard(title = t("settings.appearance.theme")) {
        SettingsCardBody {
            SegmentedControl(
                options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                selected = appearance.mode,
                label = { mode ->
                    when (mode) {
                        ThemeMode.SYSTEM -> t("settings.appearance.theme.system")
                        ThemeMode.LIGHT -> t("settings.appearance.theme.light")
                        ThemeMode.DARK -> t("settings.appearance.theme.dark")
                    }
                },
                onSelect = { AppearanceSettings.setMode(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(OshiTheme.sm))
            Text(
                when (appearance.mode) {
                    ThemeMode.SYSTEM -> t(
                        "settings.appearance.theme.system.description",
                        if (OshiTheme.systemDark) t("settings.appearance.theme.dark") else t("settings.appearance.theme.light"),
                    )
                    ThemeMode.LIGHT -> t("settings.appearance.theme.light.description")
                    ThemeMode.DARK -> t("settings.appearance.theme.dark.description")
                },
                style = OshiTheme.typography.bodySmall,
                color = Ink.soft,
            )
        }
        Hairline()
        SettingsToggleRow(
            SettingsIcon.EYE,
            t("settings.appearance.oledBlack"),
            t("settings.appearance.oledBlack.description"),
            checked = appearance.oledBlack,
        ) { AppearanceSettings.setOledBlack(it) }
        Hairline()
        // __GLASS_ALL_THEMES__ Light, Dark and Extra dark alike; the switch shows what the
        // current look draws (its default until touched).
        SettingsToggleRow(
            SettingsIcon.APPEARANCE,
            t("appearance_glass_effect"),
            t("appearance_glass_effect_footer"),
            checked = appearance.glassOn(OshiTheme.systemDark),
        ) { AppearanceSettings.setGlass(it) }
    }

    LanguageCard()

    SettingsCard(title = t("settings.appearance.fontSize")) {
        SettingsCardBody {
            SegmentedControl(
                options = DisplaySettings.TEXT_SCALES,
                selected = DisplaySettings.textScale,
                label = { scale ->
                    when (scale) {
                        0.9f -> t("desktop.settings.textSize.small")
                        1f -> t("desktop.settings.textSize.default")
                        1.15f -> t("desktop.settings.textSize.large")
                        else -> t("desktop.settings.textSize.larger")
                    }
                },
                onSelect = { DisplaySettings.changeTextScale(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(OshiTheme.md))
            Text(t("settings.appearance.fontSize.preview"), style = OshiTheme.typography.bodyLarge, color = Ink.strong)
        }
    }

    SettingsCard(title = t("settings.appearance.wallpaper"), footer = t("desktop.settings.wallpaper.footer")) {
        SettingsCardBody { WallpaperSwatches() }
    }

    SettingsCard(title = t("desktop.settings.appearance.chats")) {
        SettingsToggleRow(
            SettingsIcon.TEXT,
            t("chats.merged.setting_title"),
            t("chats.merged.setting_desc"),
            checked = ChatListSettings.merged,
        ) { ChatListSettings.setMerged(it) }
        SettingsDivider()
        val animated by com.oshi.desktop.ui.MotionPreference.animatedReactions
        SettingsToggleRow(
            SettingsIcon.SMILE,
            t("desktop.reactions.animated"),
            t("desktop.reactions.animated.body"),
            checked = animated,
        ) { com.oshi.desktop.ui.MotionPreference.setAnimatedReactions(it) }
    }
}

/**
 * Every language the catalogs carry, each in its OWN name, plus "System". A grid rather than
 * a menu: someone who cannot read the current language can still spot theirs at a glance.
 */
@Composable
private fun LanguageCard() {
    val tags = remember { Strings.availableTags.sortedBy { DisplaySettings.nativeName(it).lowercase() } }
    val current = DisplaySettings.languageOverride
    SettingsCard(title = t("desktop.settings.language.title"), footer = t("desktop.settings.language.footer")) {
        SettingsCardBody {
            BoxWithConstraints {
                val perRow = when {
                    maxWidth < 420.dp -> 2
                    maxWidth < 600.dp -> 3
                    else -> 4
                }
                val cells = listOf<String?>(null) + tags
                Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                    for (row in cells.chunked(perRow)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                            for (tag in row) {
                                LanguageChip(
                                    label = if (tag == null) {
                                        t("desktop.settings.language.system", DisplaySettings.nativeName(
                                            Strings.resolve(DisplaySettings.systemTag, Strings.availableTags).firstOrNull() ?: "en",
                                        ))
                                    } else DisplaySettings.nativeName(tag),
                                    selected = tag == current,
                                    modifier = Modifier.weight(1f),
                                ) { DisplaySettings.setLanguage(tag) }
                            }
                            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        modifier
            .heightIn(min = 34.dp)
            .clip(OshiTheme.radiusSm)
            .background(
                when {
                    selected -> OshiTheme.brand.copy(alpha = 0.16f)
                    hovered.value -> OshiTheme.surface
                    else -> Color.Transparent
                },
            )
            .focusRing(OshiTheme.radiusSm)
            .clickable(source, null, onClick = onClick)
            .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = OshiTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) OshiTheme.brand else Ink.strong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) SettingsIconView(SettingsIcon.CHECK, OshiTheme.brand, 14.dp)
    }
}

@Composable
private fun WallpaperSwatches() {
    val chosen = DisplaySettings.defaultWallpaper
    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
        for (paper in WallpaperId.entries) {
            val (source, hovered) = rememberRowInteraction()
            Column(
                Modifier
                    .width(84.dp)
                    .clip(OshiTheme.radiusMd)
                    .focusRing(OshiTheme.radiusMd)
                    .clickable(source, null) { DisplaySettings.changeDefaultWallpaper(paper) }
                    .padding(OshiTheme.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(width = 64.dp, height = 84.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            if (paper == chosen) 2.dp else Metrics.hairline,
                            if (paper == chosen) OshiTheme.brand else OshiTheme.separator,
                            RoundedCornerShape(10.dp),
                        ),
                ) {
                    WallpaperBackdrop(paper, Modifier.fillMaxSize())
                    if (paper == WallpaperId.NONE) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            SettingsIconView(SettingsIcon.IMAGE, Ink.soft, 20.dp)
                        }
                    }
                }
                Spacer(Modifier.height(OshiTheme.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (paper == chosen) {
                        SettingsIconView(SettingsIcon.CHECK, OshiTheme.brand, 12.dp)
                        Spacer(Modifier.width(OshiTheme.xxs))
                    }
                    Text(
                        wallpaperName(paper),
                        style = OshiTheme.typography.labelMedium,
                        color = if (paper == chosen) OshiTheme.brand else if (hovered.value) Ink.strong else Ink.soft,
                        fontWeight = if (paper == chosen) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

fun wallpaperName(paper: WallpaperId): String = when (paper) {
    WallpaperId.NONE -> t("desktop.settings.wallpaper.none")
    WallpaperId.PAPER -> t("desktop.settings.wallpaper.paper")
    WallpaperId.VIOLET -> t("desktop.settings.wallpaper.violet")
    WallpaperId.BLUSH -> t("desktop.settings.wallpaper.blush")
    WallpaperId.INK -> t("desktop.settings.wallpaper.ink")
}

// ---------------------------------------------------------------- notifications

@Composable
private fun NotificationsPage() {
    val prefs = remember {
        com.oshi.desktop.store.NotificationPreferences(
            java.io.File(com.oshi.desktop.store.DesktopPaths.dataDir, com.oshi.desktop.store.NotificationPreferences.FILE_NAME),
        )
    }
    var values by remember { mutableStateOf(runCatching { prefs.load() }.getOrDefault(com.oshi.desktop.store.NotificationPreferences.Values())) }
    fun change(next: com.oshi.desktop.store.NotificationPreferences.Values) {
        runCatching { prefs.save(next) }
        values = runCatching { prefs.load() }.getOrDefault(next)
    }
    // __NOTIFICATION_OPTIONS_2027__ iOS's Alerts section: Enable Notifications, Sound.
    SettingsCard(title = t("settings.notifications.section.alerts"), footer = t("settings.notifications.enable.footer")) {
        SettingsToggleRow(SettingsIcon.NOTIFICATIONS, t("settings.notifications.enable"), null, checked = values.enabled) { want ->
            change(values.copy(enabled = want))
        }
        if (values.enabled) {
            SettingsDivider()
            SettingsToggleRow(SettingsIcon.ALERT, t("settings.notifications.sound"), null, checked = values.sound) { want ->
                change(values.copy(sound = want))
            }
        }
    }
    SettingsCard(title = t("settings.notifications.section.messages"), footer = t("desktop.notifications.body")) {
        SettingsToggleRow(
            SettingsIcon.EYE,
            t("desktop.notifications.preview"),
            t("settings.notifications.preview.footer"),
            checked = values.preview,
        ) { want -> change(values.copy(preview = want)) }
    }

    var atLogin by remember { mutableStateOf(runCatching { com.oshi.desktop.ui.LoginItem.isEnabled() }.getOrDefault(false)) }
    var problem by remember { mutableStateOf<String?>(null) }
    SettingsCard(title = t("desktop.background.title"), footer = t("desktop.background.body")) {
        SettingsToggleRow(
            SettingsIcon.POWER,
            t("desktop.background.login"),
            null,
            checked = atLogin,
        ) { want ->
            problem = com.oshi.desktop.ui.LoginItem.setEnabled(want)
            atLogin = com.oshi.desktop.ui.LoginItem.isEnabled()
        }
        problem?.let {
            SettingsCardBody { SettingsNotice(t("desktop.background.login.failed", it), error = true) }
        }
    }
}

// ---------------------------------------------------------------- privacy

@Composable
private fun PrivacyPage(state: ShellState, actions: SettingsActions) {
    val privacy = PrivacyPreferences.values
    SettingsCard(title = t("settings.privacy.section.messages")) {
        SettingsToggleRow(
            SettingsIcon.CHECK,
            t("desktop.settings.privacy.delivery"),
            t("desktop.settings.privacy.delivery.detail"),
            checked = state.deliveryReceiptsEnabled,
        ) { actions.onDeliveryReceipts(it) }
        SettingsDivider()
        SettingsToggleRow(
            SettingsIcon.EYE,
            t("settings.privacy.readReceipts"),
            t("desktop.settings.privacy.read.detail"),
            checked = state.readReceiptsEnabled,
        ) { actions.onReadReceipts(it) }
        SettingsDivider()
        SettingsToggleRow(
            SettingsIcon.TEXT,
            t("settings.privacy.typing"),
            null,
            checked = state.typingIndicatorsEnabled,
        ) { actions.onTypingIndicators(it) }
        SettingsDivider()
        // __DESKTOP_PRIVACY_PARITY_2027__ iOS PrivacySettingsView's MESSAGES section.
        SettingsToggleRow(
            SettingsIcon.COPY,
            t("settings.privacy.blockForwardCopy"),
            t("settings.privacy.blockForwardCopy.footer"),
            checked = privacy.blockForwardCopy,
        ) { on -> PrivacyPreferences.update { it.copy(blockForwardCopy = on) } }
    }
    // __DESKTOP_PRIVACY_PARITY_2027__ iOS VISIBILITY section: Last Seen, Profile Photo.
    SettingsCard(title = t("settings.privacy.section.visibility"), footer = t("settings.privacy.visibility.footer")) {
        SettingsToggleRow(
            SettingsIcon.CLOCK,
            t("settings.privacy.lastSeen"),
            t("pc.privacy.last_seen.detail"),
            checked = privacy.lastSeen,
        ) { on -> PrivacyPreferences.update { it.copy(lastSeen = on) } }
        SettingsDivider()
        SettingsToggleRow(
            SettingsIcon.PROFILE,
            t("settings.privacy.profilePhoto"),
            t("pc.privacy.photo.detail"),
            checked = privacy.profilePhotoVisible,
        ) { on -> PrivacyPreferences.update { it.copy(profilePhotoVisible = on) } }
    }
    // __DESKTOP_PRIVACY_PARITY_2027__ iOS CONTACTS section: Calls from Contacts Only.
    SettingsCard(title = t("settings.privacy.section.contacts"), footer = t("pc.privacy.calls.footer")) {
        SettingsToggleRow(
            SettingsIcon.CONTACTS,
            t("settings.privacy.callsFromContactsOnly"),
            t("settings.privacy.callsFromContactsOnly.description"),
            checked = privacy.callsFromContactsOnly,
        ) { on -> PrivacyPreferences.update { it.copy(callsFromContactsOnly = on) } }
    }
    PrivacyRelayCard()
    SettingsCard(title = t("desktop.settings.privacy.more")) {
        SettingsLinkRow(SettingsIcon.DISGUISE, t("disguise.title"), t("desktop.disguise.subtitle")) {
            SettingsNavigation.section = SettingsSection.DISGUISE
        }
        SettingsDivider()
        SettingsLinkRow(SettingsIcon.TEXT, t("desktop.settings.tools.covert"), t("desktop.settings.tools.covert.detail")) {
            actions.onShow(Pane.COVERT)
        }
    }
    SettingsCard {
        Row(Modifier.padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md), verticalAlignment = Alignment.Top) {
            SettingsIconBadge(SettingsIcon.PRIVACY, OshiTheme.success)
            Spacer(Modifier.width(OshiTheme.md))
            Column {
                Text(t("desktop.account.fact.plaintext.title"), style = OshiTheme.typography.bodyLarge, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(t("desktop.account.fact.plaintext.body"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
        }
    }
}

/**
 * __OHTTP_DESKTOP_2026_11_10__ "Privacy relay (beta)" — the phones' keys (`settings.privacy.*`)
 * and the desktop's own footer: on desktop media goes through the relay too. OFF by default;
 * greyed while the build carries no relay URL.
 */
@Composable
internal fun PrivacyRelayCard() {
    var available by remember { mutableStateOf(com.oshi.desktop.net.DesktopOhttp.isAvailable()) }
    var draft by remember { mutableStateOf(com.oshi.desktop.net.DesktopOhttp.customRelayUrl()) }
    var invalid by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(com.oshi.desktop.net.DesktopOhttp.isEnabled()) }
    SettingsCard(title = t("settings.privacy.section.network"), footer = t("desktop.account.ohttp.footer")) {
        SettingsToggleRow(
            SettingsIcon.GLOBE,
            t("settings.privacy.ohttp.title"),
            t("settings.privacy.ohttp.subtitle"),
            checked = available && enabled,
            enabled = available,
        ) { want ->
            com.oshi.desktop.net.DesktopOhttp.setEnabled(want)
            enabled = com.oshi.desktop.net.DesktopOhttp.isEnabled()
        }
        Hairline()
        SettingsCardBody {
            Text(t("settings.privacy.ohttp.address"), style = OshiTheme.typography.bodyLarge, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(t("settings.privacy.ohttp.address.hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(OshiTheme.radiusMd)
                        .background(OshiTheme.surface)
                        .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
                ) {
                    if (draft.isEmpty()) Text("https://", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                    androidx.compose.foundation.text.BasicTextField(
                        value = draft,
                        onValueChange = { draft = it; invalid = false },
                        singleLine = true,
                        textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(OshiTheme.brand),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(OshiTheme.md))
                SettingsButton(SettingsIcon.CHECK, t("save")) {
                    if (com.oshi.desktop.net.DesktopOhttp.setRelayUrl(draft)) {
                        available = com.oshi.desktop.net.DesktopOhttp.isAvailable()
                        enabled = com.oshi.desktop.net.DesktopOhttp.isEnabled()
                    } else invalid = true
                }
            }
            if (invalid) SettingsNotice(t("settings.privacy.ohttp.address.invalid"), error = true)
            if (!available) {
                Spacer(Modifier.height(OshiTheme.xs))
                Text(t("settings.privacy.ohttp.address.not_set"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
        }
    }
}

// ---------------------------------------------------------------- backup

@Composable
private fun BackupPage(state: ShellState, slots: SettingsSlots, actions: SettingsActions) {
    var revealed by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }
    SettingsCard(title = t("settings.backup.title")) {
        Row(Modifier.padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md), verticalAlignment = Alignment.Top) {
            SettingsIconBadge(SettingsIcon.ALERT, OshiTheme.warning)
            Spacer(Modifier.width(OshiTheme.md))
            Column(Modifier.weight(1f)) {
                Text(t("settings.backup.warning.title"), style = OshiTheme.typography.bodyLarge, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(t("settings.backup.warning.description"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
        }
        Hairline()
        SettingsCardBody {
            val key = revealed
            if (key == null) {
                SettingsButtonRow {
                    SettingsButton(SettingsIcon.KEY, t("settings.backup.revealKey")) {
                        val k = slots.recoveryKey()
                        revealed = k
                        missing = k == null
                    }
                }
                if (missing) SettingsNotice(t("settings.backup.noRecoveryKey"), error = true)
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.clip(OshiTheme.radiusMd).background(Color.White).padding(OshiTheme.sm)) {
                        QrCode(key, 168.dp)
                    }
                    Spacer(Modifier.width(OshiTheme.lg))
                    Column(Modifier.weight(1f)) {
                        Text(t("settings.backup.scanToRestore"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                        Spacer(Modifier.height(OshiTheme.sm))
                        MonoBlock(key)
                        Spacer(Modifier.height(OshiTheme.sm))
                        SettingsButtonRow {
                            SettingsButton(SettingsIcon.COPY, t("settings.backup.copyKey")) { actions.onCopy(key) }
                            SettingsButton(SettingsIcon.EYE, t("desktop.settings.backup.hideKey")) { revealed = null }
                        }
                    }
                }
            }
        }
    }

    SettingsCard(title = t("desktop.export.card.title"), footer = t("desktop.export.card.body")) {
        SettingsCardBody {
            SettingsButtonRow {
                SettingsButton(SettingsIcon.EXPORT, t("export.messages"), primary = true, enabled = !state.busy) { actions.onExportMessages() }
                SettingsButton(SettingsIcon.IMPORT, t("settings.data.importMessages"), enabled = !state.busy) { actions.onImportMessages() }
            }
            state.backupNotice?.let { SettingsNotice(it.text, it.severity == Severity.ERROR) }
        }
    }

    SettingsCard {
        SettingsLinkRow(SettingsIcon.CLOCK, t("settings.calls.diaglog.title"), t("settings.calls.diaglog.subtitle")) {
            if (!state.busy) actions.onExportCallDiagnostics()
        }
    }
}

// ---------------------------------------------------------------- account

/**
 * The account as this machine holds it. Also the body of the stand-alone Account pane the
 * menu bar opens, so the two can never disagree.
 */
@Composable
fun AccountSettingsContent(state: ShellState, actions: SettingsActions) {
    SettingsCard {
        SettingsValueRow(t("settings.profile.name"), state.displayName)
        Hairline()
        SettingsValueRow(t("desktop.settings.account.address"), state.selfAddress, mono = true) { actions.onCopy(state.selfAddress) }
        Hairline()
        SettingsValueRow(t("portal.app.relay"), state.relayUrl, mono = true)
        Hairline()
        SettingsValueRow(
            t("desktop.settings.account.gate"),
            when (com.oshi.desktop.ui.state.gateDisplay(state.gateOpen, state.gateAnswered)) {
                com.oshi.desktop.ui.state.GateDisplay.OPEN -> t("desktop.settings.account.gate.open")
                com.oshi.desktop.ui.state.GateDisplay.ASKING -> t("connection.connecting")
                com.oshi.desktop.ui.state.GateDisplay.CLOSED -> t("desktop.settings.account.gate.closed")
            },
        )
    }
    SettingsCard(title = t("desktop.settings.account.sync"), footer = t("desktop.settings.account.sync.detail")) {
        SettingsCardBody {
            SettingsButtonRow {
                SettingsButton(SettingsIcon.EXPORT, t("desktop.settings.account.sync.push"), enabled = !state.busy) { actions.onSyncPush() }
                SettingsButton(SettingsIcon.IMPORT, t("desktop.settings.account.sync.pull"), enabled = !state.busy) { actions.onSyncPull() }
            }
            state.syncNotice?.let { SettingsNotice(it.text, it.severity == Severity.ERROR) }
        }
    }
    SettingsCard {
        Row(Modifier.padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md), verticalAlignment = Alignment.Top) {
            SettingsIconBadge(SettingsIcon.POWER, OshiTheme.warning)
            Spacer(Modifier.width(OshiTheme.md))
            Column {
                Text(t("desktop.account.fact.noPush.title"), style = OshiTheme.typography.bodyLarge, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(t("desktop.account.fact.noPush.body"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
        }
    }
}

// ---------------------------------------------------------------- tools

@Composable
private fun ToolsPage(actions: SettingsActions) {
    SettingsCard {
        SettingsLinkRow(SettingsIcon.TEXT, t("desktop.settings.tools.covert"), t("desktop.settings.tools.covert.detail")) { actions.onShow(Pane.COVERT) }
        SettingsDivider()
        SettingsLinkRow(SettingsIcon.CLOCK, t("desktop.settings.tools.scheduled"), t("desktop.settings.tools.scheduled.detail")) { actions.onShow(Pane.SCHEDULED) }
        SettingsDivider()
        SettingsLinkRow(SettingsIcon.ANTENNA, t("settings.section.longRange"), t("desktop.settings.tools.radio.detail")) { actions.onShow(Pane.LORA) }
        SettingsDivider()
        SettingsLinkRow(SettingsIcon.DEVICES, t("desktop.settings.tools.peers"), t("desktop.settings.tools.peers.detail")) { actions.onShow(Pane.PEERS) }
        SettingsDivider()
        // __DESKTOP_GROUP_DISCOVERY_2027__
        SettingsLinkRow(SettingsIcon.DISCOVER, t("pc.groups.discover.title"), t("pc.groups.discover.menu")) { actions.onShow(Pane.DISCOVER_GROUPS) }
        SettingsDivider()
        SettingsLinkRow(SettingsIcon.LINK, t("mail.title"), t("desktop.settings.tools.mail.detail")) { actions.onShow(Pane.MAIL) }
    }
}

// ---------------------------------------------------------------- not on a computer

/** What the phones do that a computer cannot, each with the reason. Not "not built yet". */
// Literal keys (not built from an id) so the catalog audit can see every one of them.
val NOT_ON_DESKTOP: List<Triple<SettingsIcon, () -> String, () -> String>> = listOfNotNull(
    Triple(SettingsIcon.NOTIFICATIONS, { t("desktop.settings.notOnDesktop.push") }, { t("desktop.settings.notOnDesktop.push.why") }),
    Triple(SettingsIcon.ANTENNA, { t("desktop.settings.notOnDesktop.bluetoothMesh") }, { t("desktop.settings.notOnDesktop.bluetoothMesh.why") }),
    Triple(SettingsIcon.ANTENNA, { t("desktop.settings.notOnDesktop.loraBluetooth") }, { t("desktop.settings.notOnDesktop.loraBluetooth.why") }),
    // __DESKTOP_1_6_PARITY__ "a check-in" is no longer here: the safety check-in works on a
    // computer, and so does sending a place picked on the map. What still cannot is LIVE.
    Triple(SettingsIcon.GLOBE, { t("desktop.settings.notOnDesktop.liveLocation") }, { t("desktop.settings.notOnDesktop.liveLocation.why") }),
    // __SCREEN_CAPTURE_PROTECTION_2026_11_20__ Windows has the switch now (Settings › Security).
    if (com.oshi.desktop.store.ScreenCapturePreference.supported) null
    else Triple(SettingsIcon.EYE, { t("desktop.settings.notOnDesktop.screenshots") }, { t("desktop.settings.notOnDesktop.screenshots.why") }),
    Triple(SettingsIcon.CLOCK, { t("desktop.settings.notOnDesktop.watch") }, { t("desktop.settings.notOnDesktop.watch.why") }),
    Triple(SettingsIcon.NOTIFICATIONS, { t("desktop.settings.notOnDesktop.callScreen") }, { t("desktop.settings.notOnDesktop.callScreen.why") }),
    Triple(SettingsIcon.TEXT, { t("desktop.settings.notOnDesktop.keyboards") }, { t("desktop.settings.notOnDesktop.keyboards.why") }),
)

@Composable
private fun NotOnDesktopPage() {
    SettingsCard {
        NOT_ON_DESKTOP.forEachIndexed { i, (icon, title, why) ->
            if (i > 0) SettingsDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md), verticalAlignment = Alignment.Top) {
                SettingsIconBadge(icon, Ink.soft)
                Spacer(Modifier.width(OshiTheme.md))
                Column(Modifier.weight(1f)) {
                    Text(title(), style = OshiTheme.typography.bodyLarge, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xxs))
                    Text(why(), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- about

@Composable
private fun AboutPage(slots: SettingsSlots, actions: SettingsActions) {
    SettingsCard {
        SettingsCardBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.oshi.desktop.mascot.OshiMascot.named("wave")?.let {
                    MascotImage(it, 64.dp, playing = rememberMascotPlaying(it, hovered = false, playOnAppear = true))
                    Spacer(Modifier.width(OshiTheme.lg))
                }
                Column {
                    Text(t("identity.title"), style = OshiTheme.typography.titleLarge, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xxs))
                    Text("${t("settings.version")} ${slots.version}", style = OshiTheme.typography.bodySmall, color = Ink.soft)
                }
            }
        }
    }
    slots.updates()
    SettingsCard(title = t("settings.help.title")) {
        SettingsLinkRow(SettingsIcon.GLOBE, t("settings.about.website"), null) { actions.onOpenUrl("https://oshi-messenger.com") }
        SettingsDivider()
        SettingsLinkRow(SettingsIcon.INFO, t("settings.help.contact"), null) { actions.onOpenUrl(t("settings.about.support")) }
        SettingsDivider()
        SettingsLinkRow(SettingsIcon.LINK, t("settings.about.sourceCode"), null) { actions.onOpenUrl("https://github.com/Lastoneparis/oshi-desktop") }
    }
    SettingsCard(title = t("settings.about.section.legal")) {
        SettingsLinkRow(SettingsIcon.PRIVACY, t("settings.help.privacy"), null) { actions.onOpenUrl(t("settings.about.privacy")) }
        SettingsDivider()
        SettingsLinkRow(SettingsIcon.TEXT, t("settings.help.terms"), null) { actions.onOpenUrl(t("settings.about.terms")) }
    }
    SettingsCard {
        SettingsLinkRow(SettingsIcon.ALERT, t("desktop.settings.about.ledger"), t("desktop.settings.about.ledger.detail")) {
            actions.onShow(Pane.LIMITS)
        }
    }
}
