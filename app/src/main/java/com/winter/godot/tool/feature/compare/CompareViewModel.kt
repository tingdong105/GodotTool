package com.winter.godot.tool.feature.compare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tencent.mmkv.MMKV
import com.winter.godot.tool.R
import com.winter.godot.tool.feature.files.StorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 已校验且缓存的快照输入。
 * @author Tingdong
 * @date 2026-11-25
 */
data class SnapshotInput(val name: String, val cache: File, val header: SnapshotHeader)

/**
 * 对比记录与结果阅读的单向状态。
 * @author Tingdong
 * @date 2026-11-25
 */
data class CompareUiState(
    val before: SnapshotInput? = null, val after: SnapshotInput? = null,
    val loadingBefore: Boolean = false, val loadingAfter: Boolean = false, val running: Boolean = false, val processed: Int = 0,
    val histories: List<ComparisonRecord> = emptyList(), val selected: Set<String> = emptySet(), val historyLoading: Boolean = true,
    val record: ComparisonRecord? = null, val viewingId: String? = null, val opening: Boolean = false, val result: ComparisonResult? = null,
    val rows: List<ComparisonRow> = emptyList(), val totalRows: Int = 0,
    val includePlugins: Boolean = false, val query: String = "", val searchExpanded: Boolean = false, val filter: ChangeKind? = null, val focusOnly: Boolean = false,
    val filtering: Boolean = false, val counts: List<Int> = List(ChangeKind.entries.size) { 0 },
    val stars: Set<String> = emptySet(), val rules: List<ComparisonRule> = emptyList(), val rulesSaving: Boolean = false,
    val publishing: Boolean = false, val error: Int? = null,
    val matchedCounts: List<Int> = List(ChangeKind.entries.size) { 0 },
    val loadingMore: Boolean = false, val loadMoreFailed: Boolean = false, val wrapLines: Boolean = true,
    val viewMode: ComparisonMode = ComparisonMode.METHODS,
) {
    val busy: Boolean get() = running || loadingBefore || loadingAfter
    val hasMore: Boolean get() = rows.size < totalRows
}

/**
 * 管理快照、持久记录与连续滚动阅读。
 * @author Tingdong
 * @date 2026-11-25
 */
class CompareViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val cacheId = saved.get<String>("comparisonCache")?.takeIf { it.matches(Regex("[0-9a-f-]{36}")) }
        ?: UUID.randomUUID().toString().also { saved["comparisonCache"] = it }
    private val cache = File(application.cacheDir, "comparison-$cacheId")
    private val history = ComparisonHistory(File(application.filesDir, "comparison-history"), StorageAccess.diffRoot(),
        { path, change -> SnapshotReasonText.line(application, path, change) })
    private val rulesFile = File(application.filesDir, "comparison-rules.json")
    private val readerPreferences = MMKV.mmkvWithID("comparison-reader-v1", MMKV.SINGLE_PROCESS_MODE)
    private val mutableUi = MutableStateFlow(CompareUiState(includePlugins = saved["comparisonPlugins"] ?: false,
        wrapLines = readerPreferences.decodeBool("wrap-lines", true)))
    val ui = mutableUi.asStateFlow()
    private var beforeJob: Job? = null
    private var afterJob: Job? = null
    private var compareJob: Job? = null
    private var filterJob: Job? = null
    private var moreJob: Job? = null
    private var resultIndex: ComparisonIndex? = null
    private var historyJob: Job? = null
    private var openJob: Job? = null
    private var ruleJob: Job? = null
    private var starJob: Job? = null
    private var publishJob: Job? = null

    init {
        val restoredRecord = saved.get<String>("comparisonRecord")
        for (old in listOf(true, false)) {
            val source = saved.get<String>(if (old) "oldSnapshot" else "newSnapshot")
            if (source != null) select(old, source, restoring = true)
        }
        refreshHistory()
        viewModelScope.launch {
            val rules = withContext(Dispatchers.IO) { runCatching { ComparisonRules.load(rulesFile) }.getOrDefault(emptyList()) }
            mutableUi.update { it.copy(rules = rules) }
            if (restoredRecord != null) open(restoredRecord)
        }
    }

    /**
     * 校验并缓存 diff.gt 或 ZIP 内的快照。
     * @param old 是否为旧版。
     * @param path 共享存储中的输入。
     * @param restoring 是否优先使用会话缓存。
     */
    fun select(old: Boolean, path: String, restoring: Boolean = false) {
        if (old) beforeJob?.cancel() else afterJob?.cancel()
        closeResult()
        val prefix = if (old) "old" else "new"
        val previous = if (old) ui.value.before else ui.value.after
        val restoredName = saved.get<String>("${prefix}SnapshotCache")
        mutableUi.update { if (old) it.copy(loadingBefore = true, error = null) else it.copy(loadingAfter = true, error = null) }
        val job = viewModelScope.launch {
            var imported: File? = null
            try {
                val selected = withContext(Dispatchers.IO) {
                    val context = coroutineContext
                    check(cache.isDirectory || cache.mkdirs())
                    val restored = restoredName?.let { File(cache, it) }?.takeIf { restoring && it.canonicalFile.parentFile == cache.canonicalFile && it.isFile }
                    val destination = File(cache, "${UUID.randomUUID()}.gt"); imported = destination
                    val source = restored ?: File(path).canonicalFile.also {
                        require(it.toPath().startsWith(StorageAccess.root().canonicalFile.toPath())) { "SNAPSHOT_UNAVAILABLE" }
                    }
                    SnapshotIO.open(source).use { cursor ->
                        SnapshotIO.write(destination, cursor.header, sequence {
                            while (true) { context.ensureActive(); yield(cursor.next() ?: break) }
                        }) { context.ensureActive() }
                        SnapshotInput(File(path).name, destination, cursor.header)
                    }
                }
                saved["${prefix}Snapshot"] = path; saved["${prefix}SnapshotCache"] = selected.cache.name
                mutableUi.update { if (old) it.copy(before = selected, loadingBefore = false) else it.copy(after = selected, loadingAfter = false) }
                imported = null
                withContext(Dispatchers.IO) { previous?.cache?.delete() }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableUi.update { if (old) it.copy(loadingBefore = false, error = errorResource(error)) else it.copy(loadingAfter = false, error = errorResource(error)) } }
            finally { withContext(NonCancellable + Dispatchers.IO) { imported?.delete() } }
        }
        if (old) beforeJob = job else afterJob = job
    }

    /**
     * 交换版本方向及其恢复引用。
     */
    fun swap() {
        if (ui.value.busy) return
        for (suffix in listOf("Snapshot", "SnapshotCache")) {
            val a = saved.get<String>("old$suffix"); saved["old$suffix"] = saved.get<String>("new$suffix"); saved["new$suffix"] = a
        }
        mutableUi.update { it.copy(before = it.after, after = it.before, error = null) }
    }

    /**
     * 比较、保存私有记录并发布公开报告。
     */
    fun start() {
        val state = ui.value
        if (state.busy) return
        val old = state.before ?: return; val new = state.after ?: return
        closeResult()
        mutableUi.update { it.copy(running = true, processed = 0, error = null) }
        compareJob = viewModelScope.launch {
            var output: File? = null
            try {
                val persisted = withContext(Dispatchers.IO) {
                    val context = coroutineContext
                    val file = File(cache, "${UUID.randomUUID()}.diff"); output = file
                    val result = SnapshotDiff.compare(old.cache, new.cache, file, { context.ensureActive() }) { count ->
                        if (count % 32 == 0) mutableUi.update { it.copy(processed = count) }
                    }
                    val record = history.save(result, old.header.name.ifBlank { old.name }, new.header.name.ifBlank { new.name }, emptyList()) { context.ensureActive() }
                    try { history.publish(record) { context.ensureActive() } }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { record }
                }
                mutableUi.update { it.copy(running = false) }; open(persisted.id)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableUi.update { it.copy(running = false, error = errorResource(error)) } }
            finally { withContext(NonCancellable + Dispatchers.IO) { output?.delete() }; refreshHistory() }
        }
    }

    /**
     * 取消计算，保留已提交记录和已完成输入。
     */
    fun cancel() {
        beforeJob?.cancel(); afterJob?.cancel(); compareJob?.cancel()
        mutableUi.update { it.copy(running = false, loadingBefore = false, loadingAfter = false) }
    }

    /**
     * 刷新独立的历史列表。
     */
    fun refreshHistory() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { history.list() }
                mutableUi.update { it.copy(histories = entries, historyLoading = false, selected = it.selected.intersect(entries.map { record -> record.id }.toSet())) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableUi.update { it.copy(historyLoading = false, error = R.string.compare_history_failed) } }
        }
    }

    /**
     * 打开不依赖原快照的私有记录。
     * @param id 记录标识。
     */
    fun open(id: String) {
        openJob?.cancel(); filterJob?.cancel(); moreJob?.cancel(); resultIndex = null
        saved["comparisonRecord"] = id
        mutableUi.update { it.copy(viewingId = id, opening = true, result = null, record = null, rows = emptyList(), totalRows = 0,
            counts = List(ChangeKind.entries.size) { 0 }, matchedCounts = List(ChangeKind.entries.size) { 0 }, filtering = false,
            loadingMore = false, loadMoreFailed = false, query = "", searchExpanded = false, filter = null, focusOnly = false,
            viewMode = ComparisonMode.METHODS, error = null) }
        openJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val context = coroutineContext
                    val opened = history.open(id) { context.ensureActive() }
                    val record = opened.first
                    // 旧路径的报告在打开时另存到新路径，保留原文件。
                    val migrated = if (!record.published && record.publicName.isNotEmpty() && StorageAccess.granted(getApplication<Application>())) {
                        try { history.publish(record) { context.ensureActive() } }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { record }
                    } else record
                    (migrated to opened.second) to history.stars(id)
                }
                mutableUi.update { it.copy(record = loaded.first.first, result = loaded.first.second, stars = loaded.second, opening = false) }
                refreshHistory()
                filterResults()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableUi.update { it.copy(opening = false, error = R.string.compare_history_failed) } }
        }
    }

    /**
     * 关闭阅读页，保留历史文件。
     */
    fun closeResult() {
        openJob?.cancel(); filterJob?.cancel(); moreJob?.cancel(); resultIndex = null; saved["comparisonRecord"] = null
        mutableUi.update { it.copy(viewingId = null, record = null, result = null, rows = emptyList(), totalRows = 0,
            opening = false, filtering = false, loadingMore = false, loadMoreFailed = false, error = null) }
    }

    /**
     * 切换一条历史的选中状态。
     * @param id 记录标识。
     */
    fun selectHistory(id: String) { mutableUi.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) } }

    /**
     * 退出历史多选模式。
     */
    fun clearSelection() { mutableUi.update { it.copy(selected = emptySet()) } }

    /**
     * 删除所选私有记录，公开报告保留。
     */
    fun deleteSelected() {
        val ids = ui.value.selected
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { val context = coroutineContext; history.delete(ids) { context.ensureActive() } }
                mutableUi.update { it.copy(selected = emptySet()) }; refreshHistory()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableUi.update { it.copy(error = R.string.compare_delete_failed) }; refreshHistory() }
        }
    }

    /**
     * 重试保存当前记录的公开报告。
     */
    fun publish() {
        val record = ui.value.record ?: return
        if (ui.value.publishing) return
        mutableUi.update { it.copy(publishing = true) }
        publishJob = viewModelScope.launch {
            try {
                val published = withContext(Dispatchers.IO) { val context = coroutineContext; history.publish(record) { context.ensureActive() } }
                mutableUi.update { if (it.record?.id == record.id) it.copy(record = published, publishing = false) else it.copy(publishing = false) }
                refreshHistory()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableUi.update { it.copy(publishing = false, error = R.string.compare_publish_failed) } }
        }
    }

    /**
     * 搜索并定位首个匹配结果。
     * @param value 关键词。
     */
    fun search(value: String) { mutableUi.update { it.copy(query = value.take(256)) }; filterResults() }

    /**
     * 展开或收起顶栏搜索。
     * @param expanded 是否显示搜索框。
     */
    fun expandSearch(expanded: Boolean) {
        mutableUi.update { it.copy(searchExpanded = expanded, query = if (expanded) it.query else "") }
        if (!expanded) filterResults()
    }

    /**
     * 切换全部、增加、修改或删除。
     * @param kind 空值为全部。
     */
    fun filter(kind: ChangeKind?) { mutableUi.update { it.copy(filter = kind) }; filterResults() }

    /**
     * 更新插件显示范围。
     * @param value 是否包含插件。
     */
    fun plugins(value: Boolean) { saved["comparisonPlugins"] = value; mutableUi.update { it.copy(includePlugins = value) }; filterResults() }

    /**
     * 切换关注模式。
     * @param value 是否仅看关注。
     */
    fun focus(value: Boolean) { mutableUi.update { it.copy(focusOnly = value) }; filterResults() }

    /**
     * 保存文本换行偏好，不重新读取对比结果。
     * @param value 是否自动换行。
     */
    fun wrapLines(value: Boolean) {
        readerPreferences.encode("wrap-lines", value)
        mutableUi.update { it.copy(wrapLines = value) }
    }

    /**
     * 切换独立的结果范围，保留当前增删改分类与搜索。
     * @param mode 方法或字段模式。
     */
    fun viewMode(mode: ComparisonMode) {
        // TODO: 接入规则和关心数据后启用关心模式。
        if (mode == ComparisonMode.FOCUS || mode == ui.value.viewMode) return
        mutableUi.update { it.copy(viewMode = mode) }
        filterResults()
    }

    /**
     * 接近列表末尾时追加下一批方法，失败后允许点击重试。
     */
    fun loadMore() {
        val state = ui.value
        val index = resultIndex ?: return
        if (state.filtering || state.loadingMore || !state.hasMore) return
        val start = state.rows.size
        mutableUi.update { it.copy(loadingMore = true, loadMoreFailed = false) }
        moreJob = viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { val context = coroutineContext; ComparisonResults.read(index, start) { context.ensureActive() } }
                if (resultIndex === index) mutableUi.update { it.copy(rows = it.rows + rows, loadingMore = false) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (resultIndex === index) mutableUi.update { it.copy(loadingMore = false, loadMoreFailed = true) } }
        }
    }

    /**
     * 持久切换一个方法的手动关注。
     * @param row 方法行。
     */
    fun star(row: ComparisonRow) {
        val id = ui.value.record?.id ?: return
        val key = ComparisonResults.starKey(row.path, row.change)
        val values = ui.value.stars.let { if (key in it) it - key else it + key }
        mutableUi.update { it.copy(stars = values) }
        val previous = starJob
        starJob = viewModelScope.launch {
            try { previous?.join(); withContext(Dispatchers.IO) { history.stars(id, values) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableUi.update { it.copy(error = R.string.compare_save_failed) } }
        }
        filterResults()
    }

    /**
     * 添加或移除排除、关注规则。
     * @param rule 待处理规则。
     * @param remove 是否移除。
     */
    fun rule(rule: ComparisonRule, remove: Boolean = false) {
        if (ui.value.rulesSaving) return
        val normalized = try { ComparisonRules.validate(rule) } catch (_: Exception) { mutableUi.update { it.copy(error = R.string.compare_rule_invalid) }; return }
        val next = if (remove) ui.value.rules - normalized else (ui.value.rules + normalized).distinct()
        mutableUi.update { it.copy(rulesSaving = true) }
        ruleJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ComparisonRules.save(rulesFile, next) }
                mutableUi.update { it.copy(rules = next, rulesSaving = false, error = null) }; filterResults()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableUi.update { it.copy(rulesSaving = false, error = R.string.compare_rule_invalid) } }
        }
    }

    /**
     * 重建筛选索引并读取首批结果，后续滚动复用索引。
     */
    private fun filterResults() {
        filterJob?.cancel(); moreJob?.cancel(); resultIndex = null
        val state = ui.value; val result = state.result ?: return
        mutableUi.update { it.copy(filtering = true, rows = emptyList(), totalRows = 0, loadingMore = false, loadMoreFailed = false, error = null) }
        filterJob = viewModelScope.launch {
            try {
                val filtered = withContext(Dispatchers.IO) {
                    val context = coroutineContext
                    // 关注与规则暂时停止应用，保留私有配置供后续恢复入口。
                    val index = ComparisonResults.index(result, state.query, state.filter, state.includePlugins, state.viewMode) { context.ensureActive() }
                    index to ComparisonResults.read(index, 0) { context.ensureActive() }
                }
                resultIndex = filtered.first
                mutableUi.update { it.copy(rows = filtered.second, totalRows = filtered.first.total, counts = filtered.first.counts,
                    matchedCounts = filtered.first.matchedCounts, filtering = false) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableUi.update { it.copy(filtering = false, error = R.string.compare_read_failed) } }
        }
    }

    /**
     * 映射可操作的错误提示。
     * @param error 原始错误。
     * @return 本地化资源。
     */
    private fun errorResource(error: Exception): Int = when (error.message) {
        "SNAPSHOT_MISSING" -> R.string.compare_snapshot_missing
        "SNAPSHOT_INCOMPATIBLE" -> R.string.compare_incompatible
        "SNAPSHOT_DIFFERENT_APP" -> R.string.compare_different_app
        "SNAPSHOT_SIZE_LIMIT", "SNAPSHOT_ENTRY_LIMIT", "SNAPSHOT_METHOD_LIMIT", "COMPARISON_SIZE_LIMIT" -> R.string.compare_too_large
        "SNAPSHOT_UNAVAILABLE" -> R.string.compare_unavailable
        "COMPARISON_SAVE_FAILED", "COMPARISON_NO_SPACE" -> R.string.compare_save_failed
        else -> R.string.compare_read_failed
    }

    /**
     * 回收会话缓存，持久历史独立保留。
     */
    override fun onCleared() {
        val jobs = listOfNotNull(beforeJob, afterJob, compareJob, filterJob, moreJob, historyJob, openJob, ruleJob, starJob, publishJob)
        jobs.forEach { it.cancel() }
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanup.launch {
            try {
                jobs.joinAll()
                if (cache.canonicalFile.parentFile == getApplication<Application>().cacheDir.canonicalFile && cache.name == "comparison-$cacheId") cache.deleteRecursively()
            } finally { cleanup.cancel() }
        }
        super.onCleared()
    }
}
