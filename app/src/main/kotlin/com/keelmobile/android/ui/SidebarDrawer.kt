package com.keelmobile.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keelmobile.android.ui.theme.KeelMark
import com.keelmobile.android.ui.theme.LocalKeelColors
import com.keelmobile.harness.AppPreset

@Composable
fun SidebarDrawerContent(
    state: KeelUiState,
    onNewChat: (AppPreset?) -> Unit,
    onOpenLocalFolder: () -> Unit,
    onSelectTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onNavigate: (KeelRoute) -> Unit
) {
    val colors = LocalKeelColors.current
    var search by remember { mutableStateOf("") }
    val filteredTasks = remember(state.tasks, search) {
        if (search.isBlank()) state.tasks
        else state.tasks.filter { t ->
            t.title.contains(search, ignoreCase = true) ||
                t.messages.any { it.text.contains(search, ignoreCase = true) }
        }
    }

    ModalDrawerSheet(
        drawerContainerColor = colors.background,
        drawerContentColor = colors.textPrimary,
        modifier = Modifier.fillMaxHeight().widthIn(max = 300.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeelMark(modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Keel Mobile",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search chats…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                DrawerItem("New chat", Icons.Default.Add, false) { onNewChat(null) }
            }
            item {
                DrawerItem("Open local folder", Icons.Default.FolderOpen, false) { onOpenLocalFolder() }
            }

            item { SectionHeader("Apps") }
            items(AppPreset.entries) { preset ->
                DrawerItem(
                    label = preset.title,
                    icon = presetIcon(preset),
                    selected = state.route == KeelRoute.CHAT && state.activeTask.appPreset == preset,
                    onClick = { onNewChat(preset) }
                )
            }

            item { SectionHeader("Workspace") }
            item {
                DrawerItem("Library (${state.libraryItems.size})", Icons.Default.Folder, state.route == KeelRoute.LIBRARY) {
                    onNavigate(KeelRoute.LIBRARY)
                }
            }
            item {
                DrawerItem("Scheduled (${state.scheduledTasks.size})", Icons.Default.Schedule, state.route == KeelRoute.SCHEDULED) {
                    onNavigate(KeelRoute.SCHEDULED)
                }
            }
            item {
                DrawerItem("Models", Icons.Default.Memory, state.route == KeelRoute.MODELS) {
                    onNavigate(KeelRoute.MODELS)
                }
            }
            item {
                DrawerItem("Customize", Icons.Default.Tune, state.route == KeelRoute.CUSTOMIZE) {
                    onNavigate(KeelRoute.CUSTOMIZE)
                }
            }
            item {
                DrawerItem("Connections", Icons.Default.Hub, state.route == KeelRoute.CONNECTIONS) {
                    onNavigate(KeelRoute.CONNECTIONS)
                }
            }
            item {
                DrawerItem("About & Privacy", Icons.Default.Info, state.route == KeelRoute.ABOUT) {
                    onNavigate(KeelRoute.ABOUT)
                }
            }

            item {
                HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(vertical = 8.dp))
                SectionHeader("Recent chats")
            }

            items(filteredTasks, key = { it.id }) { task ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = task.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        selected = state.route == KeelRoute.CHAT && task.id == state.activeTaskId,
                        onClick = { onSelectTask(task.id) },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = colors.surfaceRaised,
                            unselectedContainerColor = Color.Transparent,
                            selectedTextColor = colors.accent,
                            unselectedTextColor = colors.textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (state.tasks.size > 1) {
                        IconButton(onClick = { onDeleteTask(task.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete chat", tint = colors.textMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val colors = LocalKeelColors.current
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = colors.textMuted,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun DrawerItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalKeelColors.current
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = colors.surfaceRaised,
            unselectedContainerColor = Color.Transparent,
            selectedIconColor = colors.accent,
            selectedTextColor = colors.accent,
            unselectedIconColor = colors.textMuted,
            unselectedTextColor = colors.textPrimary
        )
    )
}

private fun presetIcon(preset: AppPreset): ImageVector = when (preset) {
    AppPreset.WEBSITES -> Icons.Default.Language
    AppPreset.SHEETS -> Icons.Default.TableChart
    AppPreset.DOCS -> Icons.Default.Description
    AppPreset.STUDY -> Icons.Default.School
    AppPreset.SLIDES -> Icons.Default.Slideshow
}
