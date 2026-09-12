package io.legado.app.ui.replace.edit

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.ActivityReplaceEditBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 编辑替换规则
 */
class ReplaceEditActivity :
    VMBaseActivity<ActivityReplaceEditBinding, ReplaceEditViewModel>(),
    KeyboardToolPop.CallBack {

    companion object {

        private const val PREVIEW_DEBOUNCE_MILLIS = 250L

        fun startIntent(
            context: Context,
            id: Long = -1,
            pattern: String? = null,
            isRegex: Boolean = false,
            scope: String? = null,
            bookName: String? = null,
            bookSource: String? = null
        ): Intent {
            val intent = Intent(context, ReplaceEditActivity::class.java)
            intent.putExtra("id", id)
            intent.putExtra("pattern", pattern)
            intent.putExtra("isRegex", isRegex)
            intent.putExtra("scope", scope)
            intent.putExtra("bookName", bookName)
            intent.putExtra("bookSource", bookSource)
            return intent
        }

    }

    override val binding by viewBinding(ActivityReplaceEditBinding::inflate)
    override val viewModel by viewModels<ReplaceEditViewModel>()

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    private var previewJob: Job? = null
    private var updatingView = false
    private var updatingScopeChecks = false
    private var curBookName: String? = null
    private var curBookSource: String? = null
    private var selectedPattern: String? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            upReplaceView(it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.replace_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val view = window.decorView.findFocus()
            if (view is EditText) {
                result.data?.getStringExtra("text")?.let {
                    view.setText(it)
                }
                result.data?.getIntExtra("cursorPosition", -1)?.takeIf { it in 0 ..< view.text.length }?.let {
                    view.setSelection(it)
                }
            } else {
                toastOnUi(R.string.focus_lost_on_textbox)
            }
        }
    }
    private fun onFullEditClicked() {
        val view = window.decorView.findFocus()
        if (view is EditText && view !== binding.etPreviewOutput) {
            val hint = findParentTextInputLayout(view)?.hint?.toString()
            val currentText = view.text.toString()
            val intent = Intent(this, CodeEditActivity::class.java).apply {
                putExtra("text", currentText)
                putExtra("title", hint)
                putExtra("cursorPosition", view.selectionStart)
            }
            textEditLauncher.launch(intent)
        }
        else {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_fullscreen_edit -> onFullEditClicked()
            R.id.menu_save -> {
                val rule = getReplaceRule()
                viewModel.save(rule) {
                    viewModel.saveSample(rule.id, binding.etPreviewInput.text.toString())
                    setResult(RESULT_OK)
                    finish()
                }
            }

            R.id.menu_copy_rule -> sendToClip(GSON.toJson(getReplaceRuleForExport()))
            R.id.menu_paste_rule -> viewModel.pasteRule {
                upReplaceView(it)
            }
        }
        return true
    }

    override fun onDestroy() {
        previewJob?.cancel()
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    private fun initView() {
        binding.ivHelp.setOnClickListener {
            showHelp("regexHelp")
        }
        binding.ivGroupDropdown.setOnClickListener {
            showGroupSelectMenu()
        }
        curBookName = intent.getStringExtra("bookName")
        curBookSource = intent.getStringExtra("bookSource")
        if (curBookName == null && curBookSource == null) {
            binding.flexCurScope.isVisible = false
        } else {
            binding.cbCurBook.isVisible = curBookName != null
            binding.cbCurSource.isVisible = curBookSource != null
            binding.cbCurBook.setOnCheckedChangeListener { _, _ -> onCurScopeCheckChanged() }
            binding.cbCurSource.setOnCheckedChangeListener { _, _ -> onCurScopeCheckChanged() }
            binding.etScope.doAfterTextChanged {
                if (!updatingView && !updatingScopeChecks) {
                    syncCurScopeChecks()
                }
            }
        }
        binding.etPreviewOutput.apply {
            keyListener = null
            showSoftInputOnFocus = false
            isCursorVisible = false
            setTextIsSelectable(true)
        }
        binding.etPreviewInput.doAfterTextChanged { text ->
            if (updatingView) return@doAfterTextChanged
            val value = text?.toString().orEmpty()
            val normalized = ReplacePreview.normalizeSample(value)
            if (value != normalized) {
                updatingView = true
                try {
                    binding.etPreviewInput.setText(normalized)
                    binding.etPreviewInput.setSelection(normalized.length)
                } finally {
                    updatingView = false
                }
                toastOnUi(getString(R.string.replace_preview_truncated, ReplacePreview.MAX_SAMPLE_LENGTH))
            }
            schedulePreview()
        }
        binding.etName.doAfterTextChanged { schedulePreview() }
        selectedPattern = intent.getStringExtra("pattern")?.takeIf { it.isNotBlank() }
        binding.rbPresetLineStart.setOnClickListener {
            selectedPattern?.let {
                binding.etReplaceRule.setText("\\s$it.*")
            }
        }
        binding.rbPresetLineContains.setOnClickListener {
            selectedPattern?.let {
                binding.etReplaceRule.setText(".*$it.*")
            }
        }
        binding.rbPresetNone.setOnClickListener {
            selectedPattern?.let {
                binding.etReplaceRule.setText(it)
            }
        }
        binding.cbUseRegex.setOnCheckedChangeListener { _, _ ->
            upPatternPresetVisibility()
            schedulePreview()
        }
        upPatternPresetVisibility()
        binding.etReplaceRule.doAfterTextChanged { schedulePreview() }
        binding.etReplaceTo.doAfterTextChanged { schedulePreview() }
        binding.etTimeout.doAfterTextChanged { schedulePreview() }
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    private fun showGroupSelectMenu() {
        Coroutine.async {
            appDb.replaceRuleDao.allGroups()
        }.onSuccess { groups ->
            if (groups.isEmpty()) {
                toastOnUi(getString(R.string.empty))
                return@onSuccess
            }
            popupActionMenu(this@ReplaceEditActivity) {
                groups.forEach { group ->
                    item(group, group)
                }
            }.show(binding.ivGroupDropdown) { group ->
                binding.etGroup.setText(group)
                binding.etGroup.setSelection(group.length)
            }
        }
    }

    private fun upPatternPresetVisibility() {
        binding.flexPatternPreset.isVisible =
            binding.cbUseRegex.isChecked && selectedPattern != null
    }

    /**
     * 替换范围与"替换当前小说/书源"复选框双向同步：
     * 复选框勾选时在替换范围填入对应值(书名或书源URL)，取消勾选则移除该值。
     */
    private fun scopeTokens(): List<String> {
        return binding.etScope.text?.toString()
            ?.split(";")
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?: emptyList()
    }

    private fun syncCurScopeChecks() {
        updatingScopeChecks = true
        try {
            val tokens = scopeTokens()
            curBookName?.let { binding.cbCurBook.isChecked = it in tokens }
            curBookSource?.let { binding.cbCurSource.isChecked = it in tokens }
        } finally {
            updatingScopeChecks = false
        }
    }

    private fun onCurScopeCheckChanged() {
        if (updatingView || updatingScopeChecks) {
            return
        }
        val tokens = scopeTokens().toMutableList()
        curBookName?.let { name ->
            if (binding.cbCurBook.isChecked) {
                if (!tokens.contains(name)) {
                    tokens.add(name)
                }
            } else {
                tokens.remove(name)
            }
        }
        curBookSource?.let { source ->
            if (binding.cbCurSource.isChecked) {
                if (!tokens.contains(source)) {
                    tokens.add(source)
                }
            } else {
                tokens.remove(source)
            }
        }
        val newScope = if (tokens.isEmpty()) {
            ""
        } else {
            tokens.joinToString(";") + ";"
        }
        binding.etScope.setText(newScope)
        binding.etScope.setSelection(newScope.length)
    }

    private fun upReplaceView(replaceRule: ReplaceRule) = binding.run {
        updatingView = true
        try {
            etName.setText(replaceRule.name)
            etGroup.setText(replaceRule.group)
            etReplaceRule.setText(replaceRule.pattern)
            cbUseRegex.isChecked = replaceRule.isRegex
            etReplaceTo.setText(replaceRule.replacement)
            cbScopeTitle.isChecked = replaceRule.scopeTitle
            cbScopeSource.isChecked = replaceRule.scopeSource
            cbScopeContent.isChecked = replaceRule.scopeContent
            etScope.setText(replaceRule.scope)
            syncCurScopeChecks()
            etExcludeScope.setText(replaceRule.excludeScope)
            etTimeout.setText(replaceRule.timeoutMillisecond.toString())
            val editingRuleId = viewModel.replaceRule?.id ?: replaceRule.id
            etPreviewInput.setText(
                ReplacePreview.normalizeSample(
                    replaceRule.previewText ?: viewModel.sampleFor(editingRuleId)
                )
            )
            etPreviewInput.setSelection(etPreviewInput.text?.length ?: 0)
        } finally {
            updatingView = false
        }
        schedulePreview()
    }

    private fun schedulePreview() {
        if (updatingView) return
        previewJob?.cancel()
        val sample = ReplacePreview.normalizeSample(binding.etPreviewInput.text.toString())
        val rule = getReplaceRule().copy()
        previewJob = lifecycleScope.launch {
            delay(PREVIEW_DEBOUNCE_MILLIS)
            val result = try {
                Result.success(ReplacePreview.apply(rule, sample))
            } catch (error: CancellationException) {
                throw error
            } catch (error: StackOverflowError) {
                Result.failure(error)
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (!isActive) return@launch
            result.onSuccess {
                binding.tilPreviewOutput.error = null
                binding.etPreviewOutput.setText(it)
            }.onFailure {
                binding.etPreviewOutput.setText(sample)
                binding.tilPreviewOutput.error = getString(
                    when ((it as? ReplacePreviewException)?.reason) {
                        ReplacePreviewException.Reason.TIMEOUT -> R.string.replace_preview_timeout
                        ReplacePreviewException.Reason.CONTEXT_UNAVAILABLE ->
                            R.string.replace_preview_context_unavailable
                        ReplacePreviewException.Reason.JS_EVALUATION ->
                            R.string.replace_preview_js_error
                        null -> R.string.replace_preview_error
                    }
                )
            }
        }
    }

    private fun getReplaceRule(): ReplaceRule = binding.run {
        val replaceRule: ReplaceRule = viewModel.replaceRule ?: ReplaceRule()
        replaceRule.name = etName.text.toString()
        replaceRule.group = etGroup.text.toString()
        replaceRule.pattern = etReplaceRule.text.toString()
        replaceRule.isRegex = cbUseRegex.isChecked
        replaceRule.replacement = etReplaceTo.text.toString()
        replaceRule.scopeTitle = cbScopeTitle.isChecked
        replaceRule.scopeSource = cbScopeSource.isChecked
        replaceRule.scopeContent = cbScopeContent.isChecked
        replaceRule.scope = etScope.text.toString()
        replaceRule.excludeScope = etExcludeScope.text.toString()
        replaceRule.timeoutMillisecond = etTimeout.text.toString().toLongOrNull() ?: 3000L
        return replaceRule
    }

    private fun getReplaceRuleForExport(): ReplaceRule {
        return getReplaceRule().also { rule ->
            rule.previewText = ReplacePreview.normalizeSample(
                binding.etPreviewInput.text.toString()
            ).takeIf { it.isNotEmpty() }
        }
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem("正则教程", "regexHelp")
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "regexHelp" -> showHelp("regexHelp")
        }
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        val view = window?.decorView?.findFocus()
        if (view is EditText && view !== binding.etPreviewOutput) {
            var start = view.selectionStart
            var end = view.selectionEnd
            if (start > end) {
                val temp = start
                start = end
                end = temp
            }
            //获取EditText的文字
            val edit = view.editableText
            if (start < 0 || start >= edit.length) {
                edit.append(text)
            } else {
                //光标所在位置插入文字
                edit.replace(start, end, text)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onUndoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText && editText !== binding.etPreviewOutput) {
            editText.onTextContextMenuItem(android.R.id.undo)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRedoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText && editText !== binding.etPreviewOutput) {
            editText.onTextContextMenuItem(android.R.id.redo)
        }
    }

}
