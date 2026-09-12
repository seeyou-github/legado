package io.legado.app.ui.replace

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.ItemReplaceRuleBinding
import io.legado.app.databinding.ItemReplaceRuleSectionBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import splitties.views.onLongClick


class ReplaceRuleAdapter(
    private val context: Context,
    var callBack: CallBack
) :
    RecyclerView.Adapter<ItemViewHolder>(),
    ItemTouchCallback.Callback {

    enum class ViewMode {
        LIST,
        GROUP,
        SCOPE,
    }

    private val layoutInflater = LayoutInflater.from(context)

    private var viewMode = ViewMode.LIST
    private var searchActive = false
    private var rawRules: List<ReplaceRule> = emptyList()
    private var displayItems: List<DisplayItem> = emptyList()
    private val selected = linkedSetOf<ReplaceRule>()
    private val expandedSections = linkedSetOf<String>()
    private val collapsedInSearch = linkedSetOf<String>()
    private var diffJob: Coroutine<*>? = null
    private var listUpdateVersion = 0L
    private var isResumed = false
    private val movedItems = linkedSetOf<ReplaceRule>()

    val selection: List<ReplaceRule>
        get() = rawRules.filter { it in selected }

    fun getItems(): List<ReplaceRule> = rawRules.toList()

    fun setViewMode(mode: ViewMode) {
        if (viewMode == mode) return
        viewMode = mode
        submitDisplayItems()
    }

    fun setSearchActive(active: Boolean) {
        if (searchActive == active) return
        searchActive = active
        if (active) {
            collapsedInSearch.clear()
        } else {
            expandedSections.clear()
        }
        submitDisplayItems()
    }

    fun setRules(rules: List<ReplaceRule>) {
        rawRules = rules
        submitDisplayItems()
    }

    fun selectAll() {
        rawRules.forEach {
            selected.add(it)
        }
        notifySelectionChanged()
    }

    fun revertSelection() {
        rawRules.forEach {
            if (it in selected) {
                selected.remove(it)
            } else {
                selected.add(it)
            }
        }
        notifySelectionChanged()
    }

    private fun notifySelectionChanged() {
        notifyItemRangeChanged(0, itemCount, Bundle().apply {
            putString("selected", null)
        })
        callBack.upCountView()
    }

    private fun submitDisplayItems() {
        val version = ++listUpdateVersion
        diffJob?.cancel()
        diffJob = null
        val newItems = buildDisplayItems()
        val oldItems = displayItems
        if (!isResumed || oldItems.isEmpty() || newItems.isEmpty()) {
            displayItems = newItems
            notifyDataSetChanged()
            callBack.upCountView()
            return
        }
        val callback = object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size

            override fun getNewListSize() = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].key == newItems[newItemPosition].key
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return when {
                    oldItems[oldItemPosition] is DisplayItem.Section &&
                            newItems[newItemPosition] is DisplayItem.Section -> {
                        val old = oldItems[oldItemPosition] as DisplayItem.Section
                        val new = newItems[newItemPosition] as DisplayItem.Section
                        old.expanded == new.expanded
                                && sectionRulesEquals(old.data.rules, new.data.rules)
                    }

                    oldItems[oldItemPosition] is DisplayItem.Rule &&
                            newItems[newItemPosition] is DisplayItem.Rule -> {
                        ruleContentEquals(
                            (oldItems[oldItemPosition] as DisplayItem.Rule).rule,
                            (newItems[newItemPosition] as DisplayItem.Rule).rule,
                        )
                    }

                    else -> false
                }
            }

            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
                val old = oldItems[oldItemPosition] as? DisplayItem.Rule ?: return null
                val new = newItems[newItemPosition] as? DisplayItem.Rule ?: return null
                val payload = Bundle()
                if (old.rule.name != new.rule.name
                    || old.rule.group != new.rule.group
                ) {
                    payload.putBoolean("upName", true)
                }
                if (old.rule.isEnabled != new.rule.isEnabled) {
                    payload.putBoolean("enabled", new.rule.isEnabled)
                }
                if (payload.isEmpty) {
                    return null
                }
                return payload
            }
        }
        diffJob = Coroutine.async {
            val diffResult = DiffUtil.calculateDiff(callback, true)
            ensureActive()
            withContext(Dispatchers.Main) {
                ensureActive()
                if (version != listUpdateVersion) return@withContext
                displayItems = newItems
                diffResult.dispatchUpdatesTo(this@ReplaceRuleAdapter)
                callBack.upCountView()
            }
        }
    }

    private fun buildDisplayItems(): List<DisplayItem> {
        return when (viewMode) {
            ViewMode.LIST -> rawRules.map { DisplayItem.Rule(null, it) }
            else -> buildSections().flatMap { data ->
                val expanded = isSectionExpanded(data.key)
                mutableListOf<DisplayItem>(
                    DisplayItem.Section(data, expanded)
                ).apply {
                    if (expanded) {
                        data.rules.forEach {
                            add(DisplayItem.Rule(data.key, it))
                        }
                    }
                }
            }
        }
    }

    private fun isSectionExpanded(key: String): Boolean {
        return if (searchActive) {
            key !in collapsedInSearch
        } else {
            key in expandedSections
        }
    }

    private fun buildSections(): List<SectionData> {
        val noGroupTitle = context.getString(R.string.no_group)
        val globalRulesTitle = context.getString(R.string.replace_scope_global_rules)
        return when (viewMode) {
            ViewMode.GROUP -> rawRules
                .flatMap { rule ->
                    val ruleGroups = rule.group?.splitNotBlank(AppPattern.splitGroupRegex).orEmpty()
                    if (ruleGroups.isEmpty()) {
                        listOf(SectionData("group:", noGroupTitle, 0, listOf(rule)))
                    } else {
                        ruleGroups.map { SectionData("group:$it", it, 1, listOf(rule)) }
                    }
                }
                .groupBy({ it.key }, { it })
                .map { (_, groupItems) ->
                    SectionData(
                        groupItems.first().key,
                        groupItems.first().title,
                        groupItems.first().rank,
                        groupItems.flatMap { it.rules }.distinctBy(ReplaceRule::id),
                    )
                }
                .sortedWith(compareBy({ it.rank }, { it.title }))

            else -> rawRules
                .map { scopeSection(it, globalRulesTitle) to it }
                .groupBy({ it.first.key }, { it })
                .map { (_, items) ->
                    val section = items.first().first
                    SectionData(
                        section.key,
                        section.title,
                        section.rank,
                        items.map { it.second }.distinctBy(ReplaceRule::id),
                    )
                }
                .sortedWith(compareBy({ it.rank }, { it.title }))
        }
    }

    private fun scopeSection(rule: ReplaceRule, globalRulesTitle: String): SectionData {
        val scope = rule.scope.orEmpty().trim()
        if (scope.isBlank()) {
            return SectionData("scope:global", globalRulesTitle, 20, listOf(rule))
        }
        val tokens = scope.split(";", ",", "\n", "；", "，")
            .map(String::trim)
            .filter(String::isNotBlank)
            .ifEmpty { listOf(scope) }
        val title = tokens.joinToString(" | ")
        val rank = if (tokens.any { it.contains("://") }) 0 else 10
        return SectionData(
            "scope:${tokens.joinToString("|")}",
            title,
            rank,
            listOf(rule),
        )
    }

    private fun sectionRulePositions(sectionKey: String): List<Int> {
        return displayItems.mapIndexedNotNull { index, item ->
            if (item is DisplayItem.Rule && item.sectionKey == sectionKey) index else null
        }
    }

    private fun ruleAt(position: Int): ReplaceRule? {
        return (displayItems.getOrNull(position) as? DisplayItem.Rule)?.rule
    }

    private fun ruleContentEquals(a: ReplaceRule, b: ReplaceRule): Boolean {
        return a.name == b.name
                && a.group == b.group
                && a.isEnabled == b.isEnabled
    }

    private fun sectionRulesEquals(a: List<ReplaceRule>, b: List<ReplaceRule>): Boolean {
        if (a.size != b.size) return false
        a.forEachIndexed { index, rule ->
            if (!ruleContentEquals(rule, b[index])) return false
        }
        return true
    }

    override fun getItemCount() = displayItems.size

    override fun getItemViewType(position: Int): Int {
        return if (displayItems[position] is DisplayItem.Section) TYPE_SECTION else TYPE_RULE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val binding = if (viewType == TYPE_SECTION) {
            ItemReplaceRuleSectionBinding.inflate(layoutInflater, parent, false)
        } else {
            ItemReplaceRuleBinding.inflate(layoutInflater, parent, false)
        }
        return ItemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) = Unit

    override fun onBindViewHolder(
        holder: ItemViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        when (val item = displayItems.getOrNull(position)) {
            is DisplayItem.Section -> bindSection(
                holder.binding as ItemReplaceRuleSectionBinding,
                item
            )

            is DisplayItem.Rule -> bindRule(
                holder.binding as ItemReplaceRuleBinding,
                item,
                payloads
            )

            else -> Unit
        }
    }

    private fun bindSection(binding: ItemReplaceRuleSectionBinding, item: DisplayItem.Section) {
        binding.run {
            val allSelected = item.data.rules.isNotEmpty() && item.data.rules.all {
                it in selected
            }
            val allEnabled = item.data.rules.isNotEmpty() && item.data.rules.all {
                it.isEnabled
            }
            cbSection.isChecked = allSelected
            viewStateDot.background?.setTint(
                ContextCompat.getColor(
                    context,
                    if (allEnabled) R.color.success else R.color.error
                )
            )
            tvTitle.text = item.data.title
            tvCount.text = context.getString(
                R.string.replace_scope_rule_count,
                item.data.rules.size
            )
            ivArrow.animate().rotation(if (item.expanded) 0f else -90f).setDuration(150).start()
            root.setOnClickListener {
                toggleSection(item.data.key)
            }
            root.onLongClick {
                callBack.deleteSection(item.data.title, item.data.rules)
            }
            cbSection.setOnClickListener {
                item.data.rules.forEach {
                    if (allSelected) {
                        selected.remove(it)
                    } else {
                        selected.add(it)
                    }
                }
                sectionRulePositions(item.data.key).forEach {
                    notifyItemChanged(it, Bundle().apply {
                        putString("selected", null)
                    })
                }
                notifyItemChanged(holderPositionOf(binding), Bundle().apply {
                    putString("selected", null)
                })
                callBack.upCountView()
            }
        }
    }

    private fun holderPositionOf(binding: ViewBinding): Int {
        return (binding.root.parent as? RecyclerView)?.getChildAdapterPosition(binding.root)
            ?: RecyclerView.NO_POSITION
    }

    private fun bindRule(
        binding: ItemReplaceRuleBinding,
        item: DisplayItem.Rule,
        payloads: MutableList<Any>
    ) {
        val rule = item.rule
        binding.run {
            if (payloads.isEmpty()) {
                root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
                cbName.text = rule.getDisplayNameGroup()
                swtEnabled.isChecked = rule.isEnabled
                cbName.isChecked = rule in selected
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "selected" -> cbName.isChecked = rule in selected
                            "upName" -> cbName.text = rule.getDisplayNameGroup()
                            "enabled" -> swtEnabled.isChecked = rule.isEnabled
                        }
                    }
                }
            }
            swtEnabled.setOnUserCheckedChangeListener { isChecked ->
                ruleAt(holderLayoutPositionOf(binding))?.let {
                    it.isEnabled = isChecked
                    callBack.update(it)
                }
            }
            ivEdit.setOnClickListener {
                ruleAt(holderLayoutPositionOf(binding))?.let {
                    callBack.edit(it)
                }
            }
            cbName.setOnClickListener {
                ruleAt(holderLayoutPositionOf(binding))?.let {
                    if (cbName.isChecked) {
                        selected.add(it)
                    } else {
                        selected.remove(it)
                    }
                }
                callBack.upCountView()
            }
            ivMenuMore.setOnClickListener {
                showMenu(ivMenuMore, holderLayoutPositionOf(binding))
            }
        }
    }

    private fun holderLayoutPositionOf(binding: ViewBinding): Int {
        return (binding.root.parent as? RecyclerView)?.getChildViewHolder(binding.root)
            ?.layoutPosition ?: RecyclerView.NO_POSITION
    }

    private fun showMenu(view: View, position: Int) {
        val item = ruleAt(position) ?: return
        popupActionMenu(context) {
            item(context.getString(R.string.to_top), "top")
            item(context.getString(R.string.to_bottom), "bottom")
            item(context.getString(R.string.delete), "delete")
            danger("delete")
        }.show(view) { action ->
            when (action) {
                "top" -> callBack.toTop(item)
                "bottom" -> callBack.toBottom(item)
                "delete" -> {
                    callBack.delete(item)
                    selected.remove(item)
                }
            }
        }
    }

    private fun toggleSection(key: String) {
        if (searchActive) {
            if (key in collapsedInSearch) {
                collapsedInSearch.remove(key)
            } else {
                collapsedInSearch.add(key)
            }
        } else {
            if (key in expandedSections) {
                expandedSections.remove(key)
            } else {
                expandedSections.add(key)
            }
        }
        submitDisplayItems()
    }

    fun upResumed(resumed: Boolean) {
        if (!isResumed) {
            diffJob?.cancel()
            diffJob = null
        }
        isResumed = resumed
    }

    //ItemTouchCallback
    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val srcItem = ruleAt(srcPosition)
        val targetItem = ruleAt(targetPosition)
        if (srcItem != null && targetItem != null) {
            if (srcItem.order == targetItem.order) {
                callBack.upOrder()
            } else {
                val srcOrder = srcItem.order
                srcItem.order = targetItem.order
                targetItem.order = srcOrder
                movedItems.add(srcItem)
                movedItems.add(targetItem)
            }
            val swapped = displayItems.toMutableList()
            swapped[srcPosition] = displayItems[targetPosition]
            swapped[targetPosition] = displayItems[srcPosition]
            displayItems = swapped
            notifyItemMoved(srcPosition, targetPosition)
        }
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if (movedItems.isNotEmpty()) {
            callBack.update(*movedItems.toTypedArray())
            movedItems.clear()
        }
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<ReplaceRule>(Mode.ToggleAndReverse) {
            override fun currentSelectedId(): MutableSet<ReplaceRule> {
                return selected
            }

            override fun getItemId(position: Int): ReplaceRule {
                return ruleAt(position) ?: ReplaceRule()
            }

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                when (val displayItem = displayItems.getOrNull(position)) {
                    is DisplayItem.Rule -> {
                        if (isSelected) {
                            selected.add(displayItem.rule)
                        } else {
                            selected.remove(displayItem.rule)
                        }
                        notifyItemChanged(position, Bundle().apply {
                            putString("selected", null)
                        })
                        callBack.upCountView()
                        return true
                    }

                    is DisplayItem.Section -> {
                        // Slide-select covers the checkbox area of section headers;
                        // treat it as toggling the whole section.
                        val rules = displayItem.data.rules
                        if (rules.isEmpty()) {
                            return false
                        }
                        val allSelected = rules.all { it in selected }
                        rules.forEach {
                            if (allSelected) {
                                selected.remove(it)
                            } else {
                                selected.add(it)
                            }
                        }
                        sectionRulePositions(displayItem.data.key).forEach {
                            notifyItemChanged(it, Bundle().apply {
                                putString("selected", null)
                            })
                        }
                        notifyItemChanged(position, Bundle().apply {
                            putString("selected", null)
                        })
                        callBack.upCountView()
                        return true
                    }

                    else -> return false
                }
            }
        }

    interface CallBack {
        fun update(vararg rule: ReplaceRule)
        fun delete(rule: ReplaceRule)
        fun edit(rule: ReplaceRule)
        fun toTop(rule: ReplaceRule)
        fun toBottom(rule: ReplaceRule)
        fun deleteSection(title: String, rules: List<ReplaceRule>)
        fun upOrder()
        fun upCountView()
    }

    companion object {
        private const val TYPE_RULE = 0
        private const val TYPE_SECTION = 1
    }

}

private data class SectionData(
    val key: String,
    val title: String,
    val rank: Int,
    val rules: List<ReplaceRule>,
)

private sealed class DisplayItem {
    abstract val key: String

    data class Section(
        val data: SectionData,
        val expanded: Boolean,
    ) : DisplayItem() {
        override val key: String = "section:${data.key}"
    }

    data class Rule(
        val sectionKey: String?,
        val rule: ReplaceRule,
    ) : DisplayItem() {
        override val key: String = "rule:${sectionKey.orEmpty()}:${rule.id}"
    }
}
