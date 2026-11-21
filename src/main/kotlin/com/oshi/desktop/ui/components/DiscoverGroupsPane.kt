package com.oshi.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.oshi.desktop.group.PublicGroupDirectory
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import kotlinx.coroutines.delay

/** What the Discover screen needs from the client — a seam, so it renders without one. */
interface DiscoverGroupsHost {
    fun heard(): List<PublicGroupDirectory.Ad>
    fun meshRunning(): Boolean
    fun startMesh(): Boolean
    fun isMember(groupId: String): Boolean
    /** Join; the window opens the conversation and says what happened. */
    fun join(groupId: String)
    /** Open New chat on its "paste or scan" card, for an invite link or a QR picture. */
    fun openInvite()
}

/**
 * __DESKTOP_GROUP_DISCOVERY_2027__ iOS `DiscoverPublicGroupsView` / Android
 * `DiscoverGroupsScreen`: public groups announced by OSHI devices on the local network,
 * refreshed every 2 s like iOS, each with Join. When the local mesh is off (the window
 * starts without it) the screen says so and offers to start it — nothing is heard otherwise,
 * and a list that stays empty without saying why reads as broken.
 */
@Composable
fun DiscoverGroupsPane(host: DiscoverGroupsHost) {
    var ads by remember { mutableStateOf(host.heard()) }
    var running by remember { mutableStateOf(host.meshRunning()) }
    LaunchedEffect(host) {
        while (true) {
            ads = host.heard()
            running = host.meshRunning()
            delay(2_000)
        }
    }
    ToolScaffold(
        title = t("pc.groups.discover.title"),
        subtitle = t("pc.groups.discover.subtitle"),
        icon = ToolGlyph.SEARCH,
        actions = {
            ToolButton(t("chats.merged.scan_invite"), icon = ToolGlyph.LINK, onClick = host::openInvite)
        },
    ) {
        if (!running) {
            ToolNotice(t("pc.groups.discover.mesh_off"), tone = ToolTone.WARNING, action = {
                ToolButton(t("pc.groups.discover.start_mesh"), icon = ToolGlyph.WIFI, style = ToolButtonStyle.PRIMARY) {
                    running = host.startMesh()
                }
            })
        }
        ToolCard(title = t("discover_how_it_works"), icon = ToolGlyph.INFO) {
            Step("1", t("pc.groups.discover.step1"))
            Step("2", t("discover_step_2"))
            Step("3", t("discover_step_3"))
            ToolHint(t("pc.groups.discover.privacy"))
        }
        if (ads.isEmpty()) {
            ToolCard {
                ToolEmptyState(
                    title = t("discover_no_groups_found"),
                    body = t("pc.groups.discover.empty"),
                    mascot = "peek",
                )
            }
        } else {
            ToolCard(title = t("nav_title_public_groups"), icon = ToolGlyph.PEOPLE, trailing = { ToolPill(ads.size.toString()) }) {
                Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.xxs)) {
                    for (ad in ads) {
                        val member = host.isMember(ad.groupId)
                        ToolRow(
                            title = ad.name,
                            subtitle = listOf(
                                if (ad.memberCount == 1) t("group_member_count_singular") else t("group_member_count", ad.memberCount),
                                t("group_type_public"),
                            ).bidiJoin(),
                            leading = { Monogram(ad.name, ad.groupId, size = 36.dp) },
                        ) {
                            if (member) ToolPill(t("groups.joined"), color = OshiTheme.success)
                            else ToolButton(t("groups.join"), icon = ToolGlyph.PLUS, style = ToolButtonStyle.PRIMARY) { host.join(ad.groupId) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
        ToolPill(n)
        Text(text, style = ToolType.body, color = Ink.strong)
    }
}

