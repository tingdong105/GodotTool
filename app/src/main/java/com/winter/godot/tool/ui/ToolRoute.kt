package com.winter.godot.tool.ui

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winter.godot.tool.R
import com.winter.godot.tool.data.TaskStatus
import com.winter.godot.tool.data.isOutputAvailable
import com.winter.godot.tool.feature.backup.BackupAccess
import com.winter.godot.tool.feature.backup.BackupDialogs
import com.winter.godot.tool.feature.backup.BackupScope
import com.winter.godot.tool.feature.backup.BackupViewModel
import com.winter.godot.tool.feature.compare.CompareScreen
import com.winter.godot.tool.feature.compare.CompareViewModel
import com.winter.godot.tool.feature.compare.ComparisonResultScreen
import com.winter.godot.tool.feature.compare.RuleGroup
import com.winter.godot.tool.feature.files.FilePickerScreen
import com.winter.godot.tool.feature.files.FileSortButton
import com.winter.godot.tool.feature.files.StorageAccess
import com.winter.godot.tool.feature.home.HomeScreen
import com.winter.godot.tool.feature.settings.ComparisonRulesScreen
import com.winter.godot.tool.feature.settings.ComparisonRulesSortButton
import com.winter.godot.tool.feature.settings.SettingsScreen
import com.winter.godot.tool.feature.settings.about.AboutScreen
import com.winter.godot.tool.feature.settings.about.AboutViewModel
import com.winter.godot.tool.feature.settings.about.LicensesScreen
import com.winter.godot.tool.feature.tasks.TasksClearButton
import com.winter.godot.tool.feature.tasks.TasksScreen
import com.winter.godot.tool.feature.tasks.TasksSelectAllButton
import com.winter.godot.tool.feature.workspace.WorkspaceScreen
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.ToolDialog
import com.winter.godot.tool.ui.components.ToolIcon
import com.winter.godot.tool.ui.components.toolIconVector
import com.winter.godot.tool.ui.startup.StartupHost
import com.winter.godot.tool.ui.startup.StartupViewModel
import com.winter.godot.tool.ui.theme.Theme
import com.winter.godot.tool.ui.theme.ThemeTransitionHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
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
 * @param startup 当前进程一次性的启动动画状态。
 */
@Composable
fun ToolRoute(model: ToolViewModel = viewModel(), comparison: CompareViewModel = viewModel(), about: AboutViewModel = viewModel(),
    startup: StartupViewModel? = null, backup: BackupViewModel = viewModel()) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val active by model.active.collectAsStateWithLifecycle()
    val logs by model.logs.collectAsStateWithLifecycle()
    val comparisonUi by comparison.ui.collectAsStateWithLifecycle()
    val aboutUi by about.ui.collectAsStateWithLifecycle()
    val backupUi by backup.ui.collectAsStateWithLifecycle()
    val backupBusy by BackupAccess.busy.collectAsStateWithLifecycle()
    val backupEnabled = active == null && !comparisonUi.busy && !comparisonUi.rulesLoading && !comparisonUi.rulesSaving &&
        !comparisonUi.historyLoading && !comparisonUi.opening && !comparisonUi.filtering && !comparisonUi.loadingMore &&
        !comparisonUi.publishing && !ui.deletingTasks && !backupBusy && !backupUi.inspecting
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val latestTask = tasks.firstOrNull { it.id == active } ?: tasks.firstOrNull()
    val outputPath = remember(latestTask?.summary) {
        runCatching { JSONObject(latestTask?.summary.orEmpty()).optString("outputPath") }.getOrDefault("")
    }
    var outputCheckVersion by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { outputCheckVersion++ }
    val availableOutputPath by produceState("", latestTask?.id, latestTask?.status, outputPath, ui.screen, outputCheckVersion) {
        value = ""
        if (ui.screen == Screen.HOME && latestTask?.status in setOf(TaskStatus.SUCCEEDED, TaskStatus.PARTIAL)) {
            value = withContext(Dispatchers.IO) { outputPath.takeIf(::isOutputAvailable).orEmpty() }
        }
    }
    var permissionExplanation by remember { mutableStateOf(false) }
    var pendingComparisonExport by rememberSaveable { mutableStateOf(false) }
    var pendingBackupAction by rememberSaveable { mutableStateOf<String?>(null) }
    var aboutLinkFailed by rememberSaveable { mutableStateOf(false) }
    val openAboutLink: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: android.content.ActivityNotFoundException) {
            aboutLinkFailed = true
        } catch (_: SecurityException) {
            aboutLinkFailed = true
        }
    }
    val storageGranted: () -> Unit = {
        if (StorageAccess.granted(context)) {
            val backupAction = pendingBackupAction
            pendingBackupAction = null
            when {
                backupAction == "export" -> backup.chooseBackup()
                backupAction == "restore" -> { model.preparePicker("backup-restore"); model.navigate(Screen.FILES) }
                pendingComparisonExport -> { pendingComparisonExport = false; comparison.publish() }
                else -> model.navigate(Screen.FILES)
            }
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
    val activeRuleGroup = ui.screen.comparisonRuleGroup()
    val back: () -> Unit = {
        when {
            ui.screen == Screen.COMPARE_RESULT && comparisonUi.searchExpanded -> comparison.expandSearch(false)
            ui.screen == Screen.COMPARE_RESULT -> { comparison.closeResult(); model.back() }
            ui.screen == Screen.COMPARE && comparisonUi.selected.isNotEmpty() -> comparison.clearSelection()
            activeRuleGroup != null && comparisonUi.ruleList(activeRuleGroup).selectedIds.isNotEmpty() -> comparison.clearRuleSelection(activeRuleGroup)
            else -> model.back()
        }
    }
    LaunchedEffect(comparisonUi.viewingId) { if (comparisonUi.viewingId != null) model.navigate(Screen.COMPARE_RESULT) }
    BackHandler(enabled = ui.screen == Screen.COMPARE_RESULT || ui.screen == Screen.COMPARE && comparisonUi.selected.isNotEmpty() ||
        activeRuleGroup != null && comparisonUi.ruleList(activeRuleGroup).selectedIds.isNotEmpty() ||
        ui.screen == Screen.TASKS && ui.selectedTasks.isNotEmpty(), onBack = back)
    ThemeTransitionHost(settings.theme, { mode -> model.updateSettings(model.settings.value.copy(theme = mode)) }) { prepareTheme, selectTheme, discardTheme ->
        StartupHost(startup) {
            ToolNavHost(model.navEntries, ui, back, interceptBack = ui.screen == Screen.COMPARE_RESULT && comparisonUi.searchExpanded ||
                    backupUi.running || backupUi.choosing || backupUi.confirming || backupUi.inspecting) { screen, pageUi ->
                val ruleGroup = screen.comparisonRuleGroup()
                val ruleList = ruleGroup?.let(comparisonUi::ruleList)
                val workspaceTitle = pageUi.previewPath?.substringAfterLast('/') ?: tasks.firstOrNull { it.id == pageUi.workspaceId }?.name.orEmpty()
                val page: @Composable (Screen) -> Unit = { destination ->
                    when (destination) {
                        Screen.HOME -> HomeScreen(pageUi, settings, latestTask, active != null,
                            model::selectHomeStep, { pendingComparisonExport = false; model.preparePicker("recovery"); model.navigate(Screen.HOME); requestFiles() }, model::followSettings, model::editSelection,
                            {
                                if (StorageAccess.granted(context)) {
                                    if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    model.recoverChosen()
                                } else { pendingComparisonExport = false; model.preparePicker("recovery"); requestFiles() }
                            }, model::newRecovery,
                            { model.navigate(Screen.HOME); model.navigate(Screen.TASKS) },
                            { path ->
                                scope.launch {
                                    if (!withContext(Dispatchers.IO) { isOutputAvailable(path) }) {
                                        outputCheckVersion++
                                        Toast.makeText(context, R.string.output_unavailable, Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(context.getString(R.string.copy_path), path)))
                                    Toast.makeText(context, R.string.copy_success, Toast.LENGTH_SHORT).show()
                                }
                            }, logs, model::editSnapshot, outputAvailable = availableOutputPath.isNotBlank() && availableOutputPath == outputPath)
                        Screen.COMPARE -> CompareScreen(comparisonUi, { old ->
                            pendingComparisonExport = false
                            model.preparePicker(if (old) "snapshot-old" else "snapshot-new")
                            model.navigate(Screen.COMPARE)
                            requestFiles()
                        }, comparison::start, comparison::cancel, comparison::open, comparison::selectHistory, comparison::deleteSelected, comparison::finishProgress)
                        Screen.COMPARE_RESULT -> ComparisonResultScreen(comparisonUi, comparison::search, comparison::expandSearch, comparison::filter,
                            comparison::plugins, comparison::loadMore, comparison::wrapLines, comparison::viewMode, {
                                if (StorageAccess.granted(context)) comparison.publish() else { pendingComparisonExport = true; requestFiles() }
                            }, back, active = destination == ui.screen)
                        Screen.TASKS -> TasksScreen(tasks, active, model::deleteTasks,
                            ui.selectedTasks, ui.deletingTasks, model::selectTask)
                        Screen.SETTINGS -> SettingsScreen(settings, model::updateSettings,
                            onThemeChange = selectTheme, onThemePrepare = prepareTheme, onThemeDismiss = discardTheme,
                            onAbout = { model.navigate(Screen.ABOUT) }, onComparisonRules = { model.navigate(Screen.COMPARISON_RULES) },
                            onComparisonFocusRules = { model.navigate(Screen.COMPARISON_FOCUS_RULES) },
                            backupEnabled = backupEnabled, onBackup = {
                                if (StorageAccess.granted(context)) backup.chooseBackup()
                                else { pendingBackupAction = "export"; requestFiles() }
                            }, onRestore = {
                                if (StorageAccess.granted(context)) { model.preparePicker("backup-restore"); model.navigate(Screen.FILES) }
                                else { pendingBackupAction = "restore"; requestFiles() }
                            })
                        Screen.COMPARISON_RULES, Screen.COMPARISON_FOCUS_RULES -> {
                            val group = requireNotNull(destination.comparisonRuleGroup())
                            ComparisonRulesScreen(comparisonUi, { original, rule -> comparison.saveRule(group, original, rule) },
                                { comparison.deleteRules(group, it) }, comparison::loadRules, { comparison.selectRule(group, it) }, group)
                        }
                        Screen.ABOUT -> AboutScreen(aboutUi, screen == ui.screen, back, {
                            openAboutLink(AboutViewModel.SOURCE_URL)
                        }, { model.navigate(Screen.LICENSES) }, onDeveloper = { openAboutLink(AboutViewModel.DEVELOPER_URL) })
                        Screen.LICENSES -> {
                            LaunchedEffect(Unit) { about.loadLicenses() }
                            LicensesScreen(aboutUi, about::loadLicenses)
                        }
                        Screen.FILES -> FilePickerScreen(pageUi, active == null || pageUi.pickerPurpose != "recovery", model::browseFiles, model::chooseFile,
                            { model.confirmFiles(onSnapshot = { old, path -> comparison.select(old, path) }, onBackup = backup::inspect) }, model::refreshFiles)
                        Screen.WORKSPACE -> WorkspaceScreen(pageUi, model::preview, active = screen == ui.screen)
                    }
                    BackHandler(enabled = screen == ui.screen && (ui.previewPath != null || screen == Screen.FILES &&
                        ui.browserPath.isNotEmpty() && ui.browserPath != StorageAccess.root().absolutePath), onBack = model::back)
                    BackHandler(enabled = destination == Screen.HOME && ui.screen == Screen.HOME && active == null &&
                        ui.inputFiles.isNotEmpty() && ui.homeStep > 1) { model.selectHomeStep(ui.homeStep - 1) }
                    // 在设置页的弹窗宿主内注册。
                    if (destination == Screen.SETTINGS && destination == ui.screen) {
                        BackupDialogs(backupUi, backup::select, backup::review, backup::dismissChoice, backup::revise,
                            onConfirm = {
                                backup.confirm({ model.active.value == null && !model.ui.value.deletingTasks && comparison.backupReady() }) { scopes ->
                                    if (BackupScope.SETTINGS in scopes) model.reloadSettings()
                                    comparison.reloadAfterRestore(scopes)
                                }
                            }, onCancel = backup::cancel, onFinished = backup::finish, onNotice = backup::dismissNotice)
                    }
                    if (destination == ui.screen && permissionExplanation) ToolDialog(stringResource(R.string.storage_permission), { permissionExplanation = false; pendingComparisonExport = false; pendingBackupAction = null }) {
                        Text(stringResource(R.string.storage_permission_detail))
                        PrimaryButton(stringResource(R.string.authorize), { permissionExplanation = false; requestFiles() }, Modifier.fillMaxWidth())
                    }
                    if (destination == ui.screen) ui.notice?.let { notice -> ToolDialog(stringResource(R.string.notice), model::dismissNotice) {
                        Text(stringResource(notice))
                        if (ui.noticeDetail.isNotEmpty()) Text(ui.noticeDetail, style = Theme.typography.code)
                        PrimaryButton(stringResource(R.string.confirm), model::dismissNotice, Modifier.fillMaxWidth())
                    } }
                    if (destination == Screen.ABOUT && destination == ui.screen && aboutLinkFailed) {
                        ToolDialog(stringResource(R.string.notice), { aboutLinkFailed = false }) {
                            Text(stringResource(R.string.about_link_failed))
                            PrimaryButton(stringResource(R.string.confirm), { aboutLinkFailed = false }, Modifier.fillMaxWidth())
                        }
                    }
                }
                if (screen.isRoot()) RootTabs(screen, ui.screen == screen, model::navigate, settings.floatingBottomBar, settings.liquidGlass, page)
                else if (screen == Screen.COMPARE_RESULT || screen == Screen.ABOUT) page(screen)
                else ToolScaffold(screen, workspaceTitle, model::navigate, { dispatcher?.onBackPressed() ?: back() },
                    titleOverride = when {
                        screen == Screen.TASKS && ui.selectedTasks.isNotEmpty() -> stringResource(R.string.tasks_selected, ui.selectedTasks.size)
                        ruleList != null && ruleList.selectedIds.isNotEmpty() -> stringResource(R.string.tasks_selected, ruleList.selectedIds.size)
                        else -> null
                    }, actions = {
                    if (screen == Screen.TASKS) {
                        val selectableCount = tasks.count { it.status != TaskStatus.RUNNING && it.id != active }
                        if (ui.selectedTasks.isNotEmpty()) {
                            TasksSelectAllButton(ui.selectedTasks.size == selectableCount, !ui.deletingTasks, model::selectAllTasks)
                        } else {
                            TasksClearButton(active == null && !ui.deletingTasks && !backupBusy && selectableCount > 0, model::clearFinished)
                        }
                    }
                    if (screen == Screen.FILES) FileSortButton(pageUi.browserSort, pageUi.browserReversed, pageUi.browserDirectoryOnly, model::sortFiles, enabled = !pageUi.loading)
                    if (ruleGroup != null && ruleList != null) {
                        val enabled = !comparisonUi.rulesLoading && !comparisonUi.rulesLoadFailed && !comparisonUi.rulesSaving
                        if (ruleList.selectedIds.isNotEmpty()) TasksSelectAllButton(
                            ruleList.selectedIds.size == comparisonUi.rules.count { it.exclude == ruleGroup.exclude }, enabled,
                            { comparison.selectAllRules(ruleGroup) })
                        ComparisonRulesSortButton(ruleList.sort, ruleList.reversed, enabled,
                            { order, reversed -> comparison.sortRules(ruleGroup, order, reversed) })
                    }
                }) { page(screen) }
            }
        }
    }
}

/**
 * 使用 miuix 官方导航容器管理页面转场、条目状态和预测返回。
 * @param backStack 以首页或设置开头的业务返回栈。
 * @param ui 当前页面的业务状态。
 * @param onBack 弹栈或页面内逐层返回。
 * @param interceptBack 页面正在消费返回时禁用页面拖动退出。
 * @param content 页面内容，离场时保留最后一份状态。
 */
@Composable
fun ToolNavHost(backStack: NavBackStack, ui: ToolUiState, onBack: () -> Unit, interceptBack: Boolean = false,
    content: @Composable (Screen, ToolUiState) -> Unit) {
    val currentUi by rememberUpdatedState(ui)
    val currentContent by rememberUpdatedState(content)
    val corner = maxOf(rememberNavSystemCornerRadius(), Theme.sizes.corner)
    val effects = NavDisplayEffects(cornerClipRadius = corner, dimAmount = 0.12f,
        backdropColor = MiuixTheme.colorScheme.surface)
    NavDisplay(backStack = backStack, modifier = Modifier.fillMaxSize(), onBack = onBack,
        transition = toolPageTransition, effects = effects) {
        entry<Screen>(contentKey = { if (it.isRoot()) "root" else it.name },
            swipeDismiss = if (interceptBack) NavSwipeDirection.None else null) { screen ->
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
 * @param floatingNavigation 是否将底部避让交由一级列表处理。
 */
@Composable
fun ToolScaffold(screen: Screen, workspaceName: String = "", onNavigate: (Screen) -> Unit = {}, onBack: () -> Unit = {}, showNavigation: Boolean = true,
    titleOverride: String? = null, actions: @Composable () -> Unit = {}, floatingNavigation: Boolean = false, content: @Composable () -> Unit) {
    val root = screen.isRoot()
    val title = titleOverride ?: when (screen) {
        Screen.HOME -> stringResource(R.string.app_name)
        Screen.COMPARE -> stringResource(R.string.compare)
        Screen.COMPARE_RESULT -> stringResource(R.string.compare_result)
        Screen.SETTINGS -> stringResource(R.string.settings)
        Screen.COMPARISON_RULES -> stringResource(R.string.compare_filter_rules)
        Screen.COMPARISON_FOCUS_RULES -> stringResource(R.string.compare_focus_rules)
        Screen.ABOUT -> stringResource(R.string.about_software)
        Screen.LICENSES -> stringResource(R.string.licenses)
        Screen.TASKS -> stringResource(R.string.history)
        Screen.FILES -> stringResource(R.string.select_file)
        Screen.WORKSPACE -> workspaceName.ifEmpty { stringResource(R.string.workspace) }
    }
    val pages = rememberSaveableStateHolder()
    val tablet = ToolDevice.isPad
    val tabletLandscape = tablet && ToolDevice.isLandscape
    val pageInsets = if (tablet) WindowInsets.safeDrawing else WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val scaffold: @Composable (Boolean) -> Unit = { wide ->
        CompositionLocalProvider(LocalWideContent provides wide) {
            key(screen) {
                val scroll = MiuixScrollBehavior(rememberTopAppBarState())
                val width = when {
                    screen == Screen.WORKSPACE -> Modifier.fillMaxWidth()
                    wide -> Modifier.widthIn(max = Theme.sizes.wideContentMax).fillMaxWidth()
                    else -> Modifier.widthIn(max = Theme.sizes.contentMax).fillMaxWidth()
                }
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
                }, contentWindowInsets = if (root && floatingNavigation)
                    pageInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    else pageInsets,
                bottomBar = {
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
    }
    // 固定子页面的组合位置。
    BoxWithConstraints(Modifier.fillMaxSize().then(if (tablet)
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)) else Modifier)) {
        val minimumWidth = if (screen == Screen.HOME) Theme.sizes.navigationBreakpoint else Theme.sizes.tabletBreakpoint
        scaffold(root && tabletLandscape && maxWidth >= minimumWidth)
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

/**
 * 获取规则设置页面所属的分组。
 * 使用示例：Screen.COMPARISON_RULES.comparisonRuleGroup()。
 * @return 规则分组，其它页面为空。
 */
private fun Screen.comparisonRuleGroup(): RuleGroup? = when (this) {
    Screen.COMPARISON_RULES -> RuleGroup.FILTER
    Screen.COMPARISON_FOCUS_RULES -> RuleGroup.FOCUS
    else -> null
}
