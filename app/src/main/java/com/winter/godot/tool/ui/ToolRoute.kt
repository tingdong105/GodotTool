package com.winter.godot.tool.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winter.godot.tool.R
import com.winter.godot.tool.data.TaskStatus
import com.winter.godot.tool.feature.compare.CompareScreen
import com.winter.godot.tool.feature.compare.CompareViewModel
import com.winter.godot.tool.feature.compare.ComparisonResultScreen
import com.winter.godot.tool.feature.files.FilePickerScreen
import com.winter.godot.tool.feature.files.FileSortButton
import com.winter.godot.tool.feature.files.StorageAccess
import com.winter.godot.tool.feature.home.HomeScreen
import com.winter.godot.tool.feature.settings.SettingsScreen
import com.winter.godot.tool.feature.tasks.TasksScreen
import com.winter.godot.tool.feature.tasks.TasksSelectAllButton
import com.winter.godot.tool.feature.workspace.WorkspaceScreen
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.ToolDialog
import com.winter.godot.tool.ui.components.ToolIcon
import com.winter.godot.tool.ui.components.toolIconVector
import com.winter.godot.tool.ui.theme.Theme
import com.winter.godot.tool.ui.theme.ThemeTransitionHost
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.runtime.NavProgrammaticEasing
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 保留官方位移与手势几何，缩短切页和手势松开后的收尾。
private val toolPageTransition = object : NavTransition by NavTransitions.MiuixDefault {
    override val motion = NavMotion(
        programmatic = NavSettleSpec.Tween(durationMillis = 260, easing = NavProgrammaticEasing),
        commit = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = 600f),
        cancel = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = 600f),
    )
}

/**
 * 连接页面状态与共享存储权限，所有文件由内置浏览器选择。
 * @param model 界面状态模型。
 */
@Composable
fun ToolRoute(model: ToolViewModel = viewModel(), comparison: CompareViewModel = viewModel()) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val active by model.active.collectAsStateWithLifecycle()
    val logs by model.logs.collectAsStateWithLifecycle()
    val comparisonUi by comparison.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionExplanation by remember { mutableStateOf(false) }
    var pendingComparisonExport by rememberSaveable { mutableStateOf(false) }
    val storageGranted: () -> Unit = {
        if (StorageAccess.granted(context)) {
            if (pendingComparisonExport) { pendingComparisonExport = false; comparison.publish() } else model.navigate(Screen.FILES)
        } else permissionExplanation = true
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        storageGranted()
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        storageGranted()
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val requestFiles: () -> Unit = {
        if (StorageAccess.granted(context)) storageGranted()
        else if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            runCatching { storagePermission.launch(intent) }.onFailure {
                runCatching { storagePermission.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    .onFailure { permissionExplanation = true }
            }
        } else legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val back: () -> Unit = {
        when {
            ui.screen == Screen.COMPARE_RESULT && comparisonUi.searchExpanded -> comparison.expandSearch(false)
            ui.screen == Screen.COMPARE_RESULT -> { comparison.closeResult(); model.back() }
            ui.screen == Screen.COMPARE && comparisonUi.selected.isNotEmpty() -> comparison.clearSelection()
            else -> model.back()
        }
    }
    LaunchedEffect(comparisonUi.viewingId) { if (comparisonUi.viewingId != null) model.navigate(Screen.COMPARE_RESULT) }
    BackHandler(enabled = ui.screen == Screen.COMPARE_RESULT || ui.screen == Screen.COMPARE && comparisonUi.selected.isNotEmpty() ||
        ui.screen == Screen.TASKS && ui.selectedTasks.isNotEmpty(), onBack = back)
    ThemeTransitionHost(settings.theme, { mode -> model.updateSettings(model.settings.value.copy(theme = mode)) }) { prepareTheme, selectTheme, discardTheme ->
        ToolNavHost(model.navEntries, ui, back) { screen, pageUi ->
            val workspaceTitle = pageUi.previewPath?.substringAfterLast('/') ?: tasks.firstOrNull { it.id == pageUi.workspaceId }?.name.orEmpty()
            val page: @Composable (Screen) -> Unit = { destination ->
                when (destination) {
                    Screen.HOME -> HomeScreen(pageUi, settings, tasks.firstOrNull { it.id == active } ?: tasks.firstOrNull(), active != null,
                        model::selectHomeStep, { pendingComparisonExport = false; model.preparePicker("recovery"); model.navigate(Screen.HOME); requestFiles() }, model::followSettings, model::editSelection,
                        {
                            if (StorageAccess.granted(context)) {
                                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                model.recoverChosen()
                            } else { pendingComparisonExport = false; model.preparePicker("recovery"); requestFiles() }
                        }, model::newRecovery,
                        { model.navigate(Screen.HOME); model.navigate(Screen.TASKS) },
                        { id -> model.navigate(Screen.HOME); model.openWorkspace(id) }, logs, model::editSnapshot)
                    Screen.COMPARE -> CompareScreen(comparisonUi, { old ->
                        pendingComparisonExport = false
                        model.preparePicker(if (old) "snapshot-old" else "snapshot-new")
                        model.navigate(Screen.COMPARE)
                        requestFiles()
                    }, comparison::start, comparison::swap, comparison::cancel, comparison::open, comparison::selectHistory, comparison::deleteSelected)
                    Screen.COMPARE_RESULT -> ComparisonResultScreen(comparisonUi, comparison::search, comparison::expandSearch, comparison::filter,
                        comparison::plugins, comparison::loadMore, comparison::wrapLines, comparison::viewMode, {
                            if (StorageAccess.granted(context)) comparison.publish() else { pendingComparisonExport = true; requestFiles() }
                        }, back)
                    Screen.TASKS -> TasksScreen(tasks, active, model::deleteTasks,
                        ui.selectedTasks, ui.deletingTasks, model::selectTask)
                    Screen.SETTINGS -> SettingsScreen(settings, active != null, model::updateSettings, model::clearFinished,
                        onThemeChange = selectTheme, onThemePrepare = prepareTheme, onThemeDismiss = discardTheme)
                    Screen.FILES -> FilePickerScreen(pageUi, active == null || pageUi.pickerPurpose != "recovery", model::browseFiles, model::chooseFile,
                        { model.confirmFiles { old, path -> comparison.select(old, path) } }, model::refreshFiles)
                    Screen.WORKSPACE -> WorkspaceScreen(pageUi, model::preview, active = screen == ui.screen)
                }
                BackHandler(enabled = screen == ui.screen && (ui.previewPath != null || screen == Screen.FILES &&
                    ui.browserPath.isNotEmpty() && ui.browserPath != StorageAccess.root().absolutePath), onBack = model::back)
                BackHandler(enabled = destination == Screen.HOME && ui.screen == Screen.HOME && active == null &&
                    ui.inputFiles.isNotEmpty() && ui.homeStep > 1) { model.selectHomeStep(ui.homeStep - 1) }
                if (destination == ui.screen && permissionExplanation) ToolDialog(stringResource(R.string.storage_permission), { permissionExplanation = false; pendingComparisonExport = false }) {
                    Text(stringResource(R.string.storage_permission_detail))
                    PrimaryButton(stringResource(R.string.authorize), { permissionExplanation = false; requestFiles() }, Modifier.fillMaxWidth())
                }
                if (destination == ui.screen) ui.notice?.let { notice -> ToolDialog(stringResource(R.string.notice), model::dismissNotice) {
                    Text(stringResource(notice))
                    if (ui.noticeDetail.isNotEmpty()) Text(ui.noticeDetail, style = Theme.typography.code)
                    PrimaryButton(stringResource(R.string.confirm), model::dismissNotice, Modifier.fillMaxWidth())
                } }
            }
            if (screen.isRoot()) RootTabs(screen, ui.screen == screen, model::navigate, page)
            else if (screen == Screen.COMPARE_RESULT) page(screen)
            else ToolScaffold(screen, workspaceTitle, model::navigate, { dispatcher?.onBackPressed() ?: back() },
                titleOverride = if (screen == Screen.TASKS && ui.selectedTasks.isNotEmpty()) stringResource(R.string.tasks_selected, ui.selectedTasks.size) else null, actions = {
                if (screen == Screen.TASKS && ui.selectedTasks.isNotEmpty()) {
                    val selectableCount = tasks.count { it.status != TaskStatus.RUNNING && it.id != active }
                    TasksSelectAllButton(ui.selectedTasks.size == selectableCount, !ui.deletingTasks, model::selectAllTasks)
                }
                if (screen == Screen.FILES) FileSortButton(pageUi.browserSort, pageUi.browserReversed, pageUi.browserDirectoryOnly, model::sortFiles, enabled = !pageUi.loading)
            }) { page(screen) }
        }
    }
}

/**
 * 使用 miuix 官方导航容器管理页面转场、条目状态和预测返回。
 * @param backStack 以首页或设置开头的业务返回栈。
 * @param ui 当前页面的业务状态。
 * @param onBack 弹栈或页面内逐层返回。
 * @param content 页面内容，离场时保留最后一份状态。
 */
@Composable
fun ToolNavHost(backStack: NavBackStack, ui: ToolUiState, onBack: () -> Unit, content: @Composable (Screen, ToolUiState) -> Unit) {
    val currentUi by rememberUpdatedState(ui)
    val currentContent by rememberUpdatedState(content)
    val corner = maxOf(rememberNavSystemCornerRadius(), Theme.sizes.corner)
    val effects = NavDisplayEffects(cornerClipRadius = corner, dimAmount = 0.12f,
        backdropColor = MiuixTheme.colorScheme.surface)
    NavDisplay(backStack = backStack, modifier = Modifier.fillMaxSize(), onBack = onBack,
        transition = toolPageTransition, effects = effects) {
        entry<Screen>(contentKey = { if (it.isRoot()) "root" else it.name }) { screen ->
            var snapshot by remember { mutableStateOf(currentUi) }
            if (currentUi.screen == screen) snapshot = currentUi
            Box(Modifier.fillMaxSize().then(if (currentUi.screen == screen) Modifier else Modifier.clearAndSetSemantics {})) {
                currentContent(screen, snapshot)
            }
        }
    }
}

/**
 * 一级页面使用可折叠大标题，子页面使用紧凑顶栏。
 * @param screen 当前页面。
 * @param workspaceName 工程或预览文件名。
 * @param onBack 与系统返回共用导航栈。
 * @param showNavigation 一级分页内部由外层提供固定导航栏。
 * @param actions 二级页面顶栏操作。
 * @param titleOverride 多选等临时状态的顶栏标题。
 */
@Composable
fun ToolScaffold(screen: Screen, workspaceName: String = "", onNavigate: (Screen) -> Unit = {}, onBack: () -> Unit = {}, showNavigation: Boolean = true,
    titleOverride: String? = null, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val root = screen.isRoot()
    val title = titleOverride ?: when (screen) {
        Screen.HOME -> stringResource(R.string.app_name)
        Screen.COMPARE -> stringResource(R.string.compare)
        Screen.COMPARE_RESULT -> stringResource(R.string.compare_result)
        Screen.SETTINGS -> stringResource(R.string.settings)
        Screen.TASKS -> stringResource(R.string.history)
        Screen.FILES -> stringResource(R.string.select_file)
        Screen.WORKSPACE -> workspaceName.ifEmpty { stringResource(R.string.workspace) }
    }
    val pages = rememberSaveableStateHolder()
    key(screen) {
        val scroll = MiuixScrollBehavior(rememberTopAppBarState())
        val width = if (screen == Screen.WORKSPACE) Modifier.fillMaxWidth() else Modifier.widthIn(max = Theme.sizes.contentMax).fillMaxWidth()
        Scaffold(modifier = Modifier.fillMaxSize().imePadding().then(if (root) Modifier.nestedScroll(scroll.nestedScrollConnection) else Modifier), topBar = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                if (root) TopAppBar(title, modifier = width, scrollBehavior = scroll, actions = {
                    if (screen == Screen.HOME) IconButton(onClick = { onNavigate(Screen.TASKS) }, modifier = Modifier.size(Theme.sizes.largeIcon)) {
                        ToolIcon(R.drawable.ic_tasks, description = stringResource(R.string.history))
                    }
                })
                else SmallTopAppBar(title = title, modifier = width, navigationIconPadding = Theme.spacing.xs, actionIconPadding = Theme.spacing.xs,
                    actions = { actions() }, navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(Theme.sizes.largeIcon)) { ToolIcon(R.drawable.ic_back, description = stringResource(R.string.back)) }
                })
            }
        }, bottomBar = {
            if (root && showNavigation) ToolBottomNavigation(screen, onNavigate)
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                Box(width.fillMaxHeight()) {
                    if (root || screen == Screen.FILES || screen == Screen.TASKS) pages.SaveableStateProvider(screen.name) { content() }
                    else content()
                }
            }
        }
    }
}

/**
 * 首页与设置共用的官方底部导航栏。
 * @param screen 当前标签。
 * @param onNavigate 标签点击。
 */
@Composable
fun ToolBottomNavigation(screen: Screen, onNavigate: (Screen) -> Unit) {
    NavigationBar {
        NavigationBarItem(selected = screen == Screen.HOME, onClick = { onNavigate(Screen.HOME) },
            icon = toolIconVector(R.drawable.ic_workbench), label = stringResource(R.string.home))
        NavigationBarItem(selected = screen == Screen.COMPARE, onClick = { onNavigate(Screen.COMPARE) },
            icon = toolIconVector(R.drawable.ic_compare), label = stringResource(R.string.compare))
        NavigationBarItem(selected = screen == Screen.SETTINGS, onClick = { onNavigate(Screen.SETTINGS) },
            icon = toolIconVector(R.drawable.ic_settings), label = stringResource(R.string.settings))
    }
}
