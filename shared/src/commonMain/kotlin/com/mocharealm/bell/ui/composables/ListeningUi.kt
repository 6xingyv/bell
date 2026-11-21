// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.*
import org.jetbrains.compose.resources.stringResource

@Composable
expect fun MusicOutputMenu(
    model: BellStore,
    state: BellState,
    modifier: Modifier = Modifier,
    centerHorizontally: Boolean = true,
    onGroups: () -> Unit,
)

@Composable
fun ListeningAvatar(
    model: BellStore,
    state: BellState,
    userId: String?,
    name: String?,
    size: Dp = 24.dp,
) {
    Box(
        Modifier.size(size)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                blendMode = BlendMode.SrcOver
            }
            .clip(CircleShape)
            .background(Color(0xFF666479)),
        contentAlignment = Alignment.Center,
    ) {
        MusicText(
            name?.take(1).orEmpty(),
            (size.value * .5f).toInt(),
            Color.White,
            FontWeight.SemiBold,
        )
        val url =
            state.session?.let { s ->
                userId?.let { model.repository.avatar(s.copy(user = s.user.copy(id = it)), null) }
            }
        AsyncImage(url, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun ListeningGroupsSheet(
    model: BellStore,
    state: BellState,
    visible: Boolean = true,
    dismiss: () -> Unit,
) {
    val defaultName =
        stringResource(Res.string.default_listening_group_name, state.session?.user?.name.orEmpty())
    var name by remember(state.session?.user?.id) { mutableStateOf(defaultName) }
    LaunchedEffect(visible) { if (visible) model.accept(BellIntent.LoadListeningGroups) }
    val otherGroups = state.listening.groups.filter { it.id != state.listening.group?.id }
    val groupName = stringResource(Res.string.listening_group_name)
    val leave = stringResource(Res.string.leave_listening_group)
    val create = stringResource(Res.string.create_listening_group)
    val join = stringResource(Res.string.join_listening_group)
    val empty = stringResource(Res.string.no_listening_groups)
    MusicListSheet(stringResource(Res.string.listen_together), visible, dismiss) {
        val activeGroup = state.listening.group
        if (activeGroup != null) {
            item(key = "group-name") {
                MusicText(
                    activeGroup.name,
                    13,
                    Color.Gray,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                )
            }
            item(key = "group-members") {
                BellGroup {
                    activeGroup.participants.forEachIndexed { index, participant ->
                        val userId =
                            state.listening.members.firstOrNull { it.name == participant }?.id
                                ?: state.session?.user?.takeIf { it.name == participant }?.id
                                ?: state.listening.entries
                                    .firstOrNull { it.userName == participant }
                                    ?.userId
                        BellRow(
                            participant,
                            leading = { ListeningAvatar(model, state, userId, participant, 28.dp) },
                            showTopSeparator = index > 0,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
            item(key = "leave-group") {
                BellGroup {
                    BellRow(
                        leave,
                        onClick = {
                            model.accept(BellIntent.LeaveListeningGroup)
                            dismiss()
                        },
                        leading = {
                            MusicIcon("rectangle.portrait.and.arrow.right", MusicAccent, 24.dp)
                        },
                    )
                }
            }
        } else {
            item(key = "create-group") {
                BellGroup {
                    BellField(name, { name = it }, groupName)
                    BellRow(
                        create,
                        onClick = {
                            model.accept(BellIntent.CreateListeningGroup(name.trim()))
                            dismiss()
                        },
                        leading = { MusicIcon("plus", MusicAccent, 24.dp) },
                        showTopSeparator = true,
                        enabled = name.isNotBlank() && state.listening.connected,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
            item {
                MusicText(
                    join,
                    13,
                    Color.Gray,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                )
            }
            item(key = "available-groups") {
                BellGroup {
                    if (otherGroups.isEmpty()) BellRow(empty)
                    otherGroups.forEachIndexed { index, group ->
                        BellRow(
                            group.name,
                            group.participants.joinToString("、"),
                            onClick = {
                                model.accept(BellIntent.JoinListeningGroup(group.id))
                                dismiss()
                            },
                            leading = { MusicIcon("shareplay", MusicAccent, 24.dp) },
                            showTopSeparator = index > 0,
                        )
                    }
                }
            }
        }
    }
}
