package io.legado.app.ui.replace

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.ActivityReplaceRuleBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.sourceSharePassphraseButton
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 替换规则管理
 */
class ReplaceRuleActivity : VMBaseActivity<ActivityReplaceRuleBinding, ReplaceRuleViewModel>(),
    SearchView.OnQueryTextListener,
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    ReplaceRuleAdapter.CallBack {
    override val binding by viewBinding(ActivityReplaceRuleBinding::inflate)
    override val viewModel by viewModels<ReplaceRuleViewModel>()
    private val importRecordKey = "replaceRuleRecordKey"
    private val adapter by lazy { ReplaceRuleAdapter(this, this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var groups = arrayListOf<String>()
    private var groupMenu: SubMenu? = null
    private var replaceRuleFlowJob: Job? = null
    private var dataInit = false
    private var currentViewMode = ReplaceRuleAdapter.ViewMode.LIST
    private val itemTouchCallback by lazy {
        ItemTouchCallback(adapter).apply {
            isCanDrag = true
        }
    }
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportReplaceRuleDialog(it))
    }
    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
            }
        }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportReplaceRuleDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            alert(R.string.export_success) {
                if (url.isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                    sourceSharePassphraseButton(
                        layoutInflater,
                        url,
                        SourceSharePassphrase.Type.REPLACE_RULE,
                    )
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(url)
                }
                customView { alertBinding.root }
                okButton {
                    sendToClip(url)
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        adapter.sortByName = AppConfig.replaceRuleSort == 1
        initRecyclerView()
        initSearchView()
        initSelectActionView()
        initTabViewMode()
        initFabScroll()
        observeReplaceRuleData()
        observeGroupData()
        observeGroupOrder()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.replace_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        groupMenu = menu.findItem(R.id.menu_group)?.subMenu
        menu.findItem(R.id.menu_manual_replace_rule)?.isChecked = AppConfig.manualReplaceRule
        // 排序菜单是 checkableBehavior="single" 的互斥组，
        // 这类 item 的 setChecked(false) 会走 setExclusiveItemChecked 反而把自己勾上，
        // 所以只能给选中的那一项 setChecked(true)，由互斥组自动取消另一项。
        val sortItemId = if (AppConfig.replaceRuleSort == 1) {
            R.id.menu_sort_by_name
        } else {
            R.id.menu_sort_by_time
        }
        menu.findItem(sortItemId)?.isChecked = true
        upGroupMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        val dragSelectTouchHelper: DragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        // When this page is opened, it is in selection mode
        dragSelectTouchHelper.activeSlideSelect()

        // Note: need judge selection first, so add ItemTouchHelper after it.
        // 名称排序下顺序由名称决定，不允许拖拽
        itemTouchCallback.isCanDrag = AppConfig.replaceRuleSort == 0
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    /**
     * 悬浮滚动按钮：列表内容超过一屏可滚动时显示，回到顶部/跳到底部
     */
    private fun initFabScroll() {
        binding.ivFabToTop.setOnClickListener {
            binding.recyclerView.smoothScrollToPosition(0)
        }
        binding.ivFabToBottom.setOnClickListener {
            val adapter = binding.recyclerView.adapter ?: return@setOnClickListener
            binding.recyclerView.smoothScrollToPosition(adapter.itemCount - 1)
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                upFabScrollButtons()
            }
        })
        binding.recyclerView.post {
            upFabScrollButtons()
        }
    }

    private fun upFabScrollButtons() {
        binding.ivFabToTop.isVisible = binding.recyclerView.canScrollVertically(-1)
        binding.ivFabToBottom.isVisible = binding.recyclerView.canScrollVertically(1)
    }

    private fun initTabViewMode() {
        binding.tabViewMode.setSelectedTabIndicatorColor(accentColor)
        binding.tabViewMode.setTabTextColors(primaryTextColor, accentColor)
        binding.tabViewMode.isTabIndicatorFullWidth = false
        binding.tabViewMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val mode = when (tab.position) {
                    1 -> ReplaceRuleAdapter.ViewMode.GROUP
                    2 -> ReplaceRuleAdapter.ViewMode.SCOPE
                    else -> ReplaceRuleAdapter.ViewMode.LIST
                }
                currentViewMode = mode
                itemTouchCallback.isCanDrag = mode == ReplaceRuleAdapter.ViewMode.LIST &&
                    AppConfig.replaceRuleSort == 0
                adapter.setViewMode(mode)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    /**
     * 切换替换规则排序模式
     * 0 = 添加时间排序（默认，按 sortOrder 手动顺序）
     * 1 = 名称自然排序（此时不支持拖拽排序）
     */
    private fun setReplaceRuleSort(sort: Int) {
        if (AppConfig.replaceRuleSort == sort) return
        AppConfig.replaceRuleSort = sort
        adapter.sortByName = sort == 1
        invalidateOptionsMenu()
        itemTouchCallback.isCanDrag = sort == 0 &&
                currentViewMode == ReplaceRuleAdapter.ViewMode.LIST
        observeReplaceRuleData(searchView.query?.toString())
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.replace_purify_search)
        searchView.setOnQueryTextListener(this)
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            adapter.selectAll()
        } else {
            adapter.revertSelection()
        }
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton { viewModel.delSelection(adapter.selection) }
            noButton()
        }
    }

    private fun initSelectActionView() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.replace_rule_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun observeReplaceRuleData(searchKey: String? = null) {
        dataInit = false
        replaceRuleFlowJob?.cancel()
        replaceRuleFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> {
                    appDb.replaceRuleDao.flowAll()
                }

                searchKey == getString(R.string.enabled) -> {
                    appDb.replaceRuleDao.flowEnabled()
                }

                searchKey == getString(R.string.disabled) -> {
                    appDb.replaceRuleDao.flowDisabled()
                }

                searchKey == getString(R.string.no_group) -> {
                    appDb.replaceRuleDao.flowNoGroup()
                }

                searchKey.startsWith("group:") -> {
                    val groupName = searchKey.substringAfter("group:")
                    appDb.replaceRuleDao.flowGroupSearch(groupName)
                }

                else -> {
                    appDb.replaceRuleDao.flowSearch("%$searchKey%")
                }
            }.catch {
                AppLog.put("替换规则管理界面更新数据出错", it)
            }.flowOn(IO).conflate().collect {
                if (dataInit) {
                    setResult(RESULT_OK)
                }
                val sorted = if (adapter.sortByName) {
                    it.sortedWith { a, b -> a.name.cnCompare(b.name) }
                } else {
                    it
                }
                adapter.setRules(sorted)
                dataInit = true
                delay(100)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.upResumed(true)
    }

    override fun onPause() {
        adapter.upResumed(false)
        super.onPause()
    }

    private fun observeGroupData() {
        lifecycleScope.launch {
            appDb.replaceRuleDao.flowGroups().collect {
                groups.clear()
                groups.addAll(viewModel.applySavedGroupOrder(it))
                upGroupMenu()
            }
        }
    }

    /**
     * 分组管理弹框里拖拽排序后 Room 的 flowGroups 不会重新发射，
     * 这里订阅排序结果，立即刷新分组 tab 与分组菜单的顺序。
     */
    private fun observeGroupOrder() {
        lifecycleScope.launch {
            viewModel.groupOrder.collect { order ->
                adapter.setGroupOrder(order)
                if (groups.isNotEmpty()) {
                    val reordered = viewModel.applySavedGroupOrder(groups)
                    groups.clear()
                    groups.addAll(reordered)
                    upGroupMenu()
                }
            }
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_replace_rule ->
                editActivity.launch(ReplaceEditActivity.startIntent(this))

            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_sort_by_time -> setReplaceRuleSort(0)
            R.id.menu_sort_by_name -> setReplaceRuleSort(1)
            R.id.menu_manual_replace_rule -> {
                AppConfig.manualReplaceRule = !AppConfig.manualReplaceRule
                item.isChecked = AppConfig.manualReplaceRule
                setResult(RESULT_OK)
                invalidateOptionsMenu()
            }
            R.id.menu_enabled_group -> {
                searchView.setQuery(getString(R.string.enabled), true)
            }

            R.id.menu_disabled_group -> {
                searchView.setQuery(getString(R.string.disabled), true)
            }
            R.id.menu_del_selection -> viewModel.delSelection(adapter.selection)
            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_qr -> qrCodeResult.launch()
            R.id.menu_help -> showHelp("replaceRuleHelp")
            R.id.menu_group_null -> {
                searchView.setQuery(getString(R.string.no_group), true)
            }

            else -> if (item.groupId == R.id.replace_group) {
                searchView.setQuery("group:${item.title}", true)
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.enableSelection(adapter.selection)
            R.id.menu_disable_selection -> viewModel.disableSelection(adapter.selection)
            R.id.menu_add_group -> selectionAddToGroups()
            R.id.menu_remove_group -> selectionRemoveFromGroups()
            R.id.menu_top_sel -> viewModel.topSelect(adapter.selection)
            R.id.menu_bottom_sel -> viewModel.bottomSelect(adapter.selection)
            R.id.menu_export_selection -> exportResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    "exportReplaceRule.json",
                    GSON.toJson(ReplacePreviewConfig.withSamples(adapter.selection)).toByteArray(),
                    "application/json"
                )
            }
        }
        return false
    }

    @SuppressLint("InflateParams")
    private fun selectionAddToGroups() {
        alert(titleResource = R.string.add_group) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.setHint(R.string.group_name)
                editView.setFilterValues(groups.toList())
                editView.dropDownHeight = 180.dpToPx()
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    if (it.isNotEmpty()) {
                        viewModel.selectionAddToGroups(adapter.selection, it)
                    }
                }
            }
            cancelButton()
        }
    }

    @SuppressLint("InflateParams")
    private fun selectionRemoveFromGroups() {
        alert(titleResource = R.string.remove_group) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.setHint(R.string.group_name)
                editView.setFilterValues(groups.toList())
                editView.dropDownHeight = 180.dpToPx()
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    if (it.isNotEmpty()) {
                        viewModel.selectionRemoveFromGroups(adapter.selection, it)
                    }
                }
            }
            cancelButton()
        }
    }

    private fun upGroupMenu() = groupMenu?.transaction { menu ->
        menu.removeGroup(R.id.replace_group)
        groups.forEach {
            menu.add(R.id.replace_group, Menu.NONE, Menu.NONE, it)
        }
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(titleResource = R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(
                        ImportReplaceRuleDialog(it)
                    )
                }
            }
            cancelButton()
        }
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        adapter.setSearchActive(!newText.isNullOrBlank())
        observeReplaceRuleData(newText)
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        Coroutine.async { ContentProcessor.upReplaceRules() }
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(
            adapter.selection.size,
            adapter.getItems().size
        )
        upFabScrollButtons()
    }

    override fun update(vararg rule: ReplaceRule) {
        setResult(RESULT_OK)
        viewModel.update(*rule)
    }

    override fun delete(rule: ReplaceRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.name)
            noButton()
            yesButton {
                setResult(RESULT_OK)
                viewModel.delete(rule)
            }
        }
    }

    override fun edit(rule: ReplaceRule) {
        setResult(RESULT_OK)
        editActivity.launch(ReplaceEditActivity.startIntent(this, rule.id))
    }

    override fun toTop(rule: ReplaceRule) {
        setResult(RESULT_OK)
        viewModel.toTop(rule)
    }

    override fun toBottom(rule: ReplaceRule) {
        setResult(RESULT_OK)
        viewModel.toBottom(rule)
    }

    override fun upOrder() {
        setResult(RESULT_OK)
        viewModel.upOrder()
    }

    override fun deleteSection(title: String, rules: List<ReplaceRule>) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + title)
            noButton()
            yesButton {
                setResult(RESULT_OK)
                viewModel.delSelection(rules)
            }
        }
    }
}
