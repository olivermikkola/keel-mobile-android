package com.keelmobile.android.ui

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun KeelAppRoot(
    controller: KeelController,
    onAttachFileClick: () -> Unit,
    onImportModelClick: () -> Unit,
    onDictateClick: () -> Unit,
    onSpeakText: (String) -> Unit,
    onSharePreview: (PreviewPayload) -> Unit
) {
    val state by controller.state.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = state.route == KeelRoute.CHAT,
        drawerContent = {
            SidebarDrawerContent(
                state = state,
                onNewChat = { preset ->
                    controller.startNewChat(preset)
                    scope.launch { drawerState.close() }
                },
                onSelectTask = { id ->
                    controller.selectTask(id)
                    scope.launch { drawerState.close() }
                },
                onDeleteTask = { id -> controller.deleteTask(id) },
                onNavigate = { route ->
                    controller.navigate(route)
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        when (state.route) {
            KeelRoute.CHAT -> ChatScreen(
                state = state,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenModels = { controller.navigate(KeelRoute.MODELS) },
                onNewChat = { controller.startNewChat(null) },
                onSelectMode = { controller.setMode(it) },
                onSend = { controller.sendMessage(it) },
                onStop = { controller.stopGeneration() },
                onToggleThinking = { controller.toggleThinking() },
                onAttachFileClick = onAttachFileClick,
                onDictateClick = onDictateClick,
                onSpeakText = onSpeakText,
                onApplyProposal = { controller.applyProposal(it) },
                onDiscardProposal = { controller.discardProposal(it) },
                onPreviewProposal = { controller.previewProposal(it) },
                onResolveApproval = { controller.resolvePendingApproval(it) },
                onDismissBanner = { controller.dismissBanner() }
            )
            KeelRoute.MODELS -> ModelsScreen(
                state = state,
                onBack = { controller.navigate(KeelRoute.CHAT) },
                onSelectModel = { controller.modelLibrary.selectModel(it) },
                onDownloadModel = { controller.modelLibrary.startDownload(it) },
                onPauseDownload = { controller.modelLibrary.pauseDownload() },
                onDiscardPartial = { controller.modelLibrary.discardPartial(it) },
                onDeleteModel = { controller.modelLibrary.deleteInstalled(it) },
                onImportLocalFile = onImportModelClick,
                onImportHfLink = { controller.importHuggingFaceLink(it) }
            )
            KeelRoute.LIBRARY -> LibraryScreen(
                items = state.libraryItems,
                onBack = { controller.navigate(KeelRoute.CHAT) },
                onOpenItem = { controller.previewLibraryFile(it) }
            )
            KeelRoute.CUSTOMIZE -> CustomizeScreen(
                state = state,
                onBack = { controller.navigate(KeelRoute.CHAT) },
                onSaveInstructions = { controller.saveCustomInstructions(it) },
                onSetMemoryEnabled = { controller.setMemoryEnabled(it) },
                onForgetFact = { controller.forgetMemoryFact(it) },
                onClearAllMemory = { controller.clearAllMemory() }
            )
            KeelRoute.CONNECTIONS -> ConnectionsScreen(
                state = state,
                onBack = { controller.navigate(KeelRoute.CHAT) },
                onSaveGitHubToken = { controller.saveGitHubToken(it) },
                onSaveBraveKey = { controller.saveBraveKey(it) },
                onToggleConnector = { controller.toggleConnector(it) }
            )
            KeelRoute.ABOUT -> AboutScreen(
                systemInfo = state.systemInfo,
                onBack = { controller.navigate(KeelRoute.CHAT) }
            )
        }
    }

    state.preview?.let { payload ->
        OfflinePreviewDialog(
            payload = payload,
            onDismiss = { controller.closePreview() },
            onShare = onSharePreview
        )
    }
}
