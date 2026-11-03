package io.legado.app.ui.replace

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.cnCompare
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.renameGroupExact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import splitties.init.appCtx

/**
 * 替换规则数据修改
 * 修改数据要copy,直接修改会导致界面不刷新
 */
class ReplaceRuleViewModel(application: Application) : BaseViewModel(application) {

    fun update(vararg rule: ReplaceRule) {
        execute {
            appDb.replaceRuleDao.update(*rule)
        }
    }

    fun delete(rule: ReplaceRule) {
        execute {
            appDb.replaceRuleDao.delete(rule)
            ReplacePreviewConfig.removeSample(rule.id)
        }
    }

    fun toTop(rule: ReplaceRule) {
        execute {
            rule.order = appDb.replaceRuleDao.minOrder - 1
            appDb.replaceRuleDao.update(rule)
        }
    }

    fun topSelect(rules: List<ReplaceRule>) {
        execute {
            var minOrder = appDb.replaceRuleDao.minOrder - rules.size
            rules.forEach {
                it.order = minOrder++
            }
            appDb.replaceRuleDao.update(*rules.toTypedArray())
        }
    }

    fun toBottom(rule: ReplaceRule) {
        execute {
            rule.order = appDb.replaceRuleDao.maxOrder + 1
            appDb.replaceRuleDao.update(rule)
        }
    }

    fun bottomSelect(rules: List<ReplaceRule>) {
        execute {
            var maxOrder = appDb.replaceRuleDao.maxOrder + 1
            rules.forEach {
                it.order = maxOrder++
            }
            appDb.replaceRuleDao.update(*rules.toTypedArray())
        }
    }

    fun upOrder() {
        execute {
            val rules = appDb.replaceRuleDao.all
            for ((index, rule) in rules.withIndex()) {
                rule.order = index + 1
            }
            appDb.replaceRuleDao.update(*rules.toTypedArray())
        }
    }

    fun enableSelection(rules: List<ReplaceRule>) {
        execute {
            val array = Array(rules.size) {
                rules[it].copy(isEnabled = true)
            }
            appDb.replaceRuleDao.update(*array)
        }
    }

    fun disableSelection(rules: List<ReplaceRule>) {
        execute {
            val array = Array(rules.size) {
                rules[it].copy(isEnabled = false)
            }
            appDb.replaceRuleDao.update(*array)
        }
    }

    fun selectionAddToGroups(rules: List<ReplaceRule>, groups: String) {
        execute {
            val array = Array(rules.size) {
                rules[it].copy().addGroup(groups)
            }
            appDb.replaceRuleDao.update(*array)
        }
    }

    fun selectionRemoveFromGroups(rules: List<ReplaceRule>, groups: String) {
        execute {
            val array = Array(rules.size) {
                rules[it].copy().removeGroup(groups)
            }
            appDb.replaceRuleDao.update(*array)
        }
    }

    fun delSelection(rules: List<ReplaceRule>) {
        execute {
            appDb.replaceRuleDao.delete(*rules.toTypedArray())
            rules.forEach { ReplacePreviewConfig.removeSample(it.id) }
        }
    }

    fun addGroup(group: String) {
        execute {
            val sources = appDb.replaceRuleDao.noGroup
            sources.forEach { source ->
                source.group = group
            }
            appDb.replaceRuleDao.update(*sources.toTypedArray())
        }
    }

    fun upGroup(oldGroup: String, newGroup: String?) {
        execute {
            val sources = appDb.replaceRuleDao.getByGroup(oldGroup).mapNotNull { source ->
                source.group.renameGroupExact(oldGroup, newGroup)?.let { groups ->
                    source.apply { group = groups }
                }
            }
            if (sources.isNotEmpty()) {
                appDb.replaceRuleDao.update(*sources.toTypedArray())
            }
        }
        // 重命名/删除分组时同步自定义排序，避免改个名字就掉到列表末尾
        upSavedGroupOrderOnGroupChanged(oldGroup, newGroup)
    }

    fun delGroup(group: String) = upGroup(group, null)

    private val _groupOrder = MutableStateFlow(readSavedGroupOrder())

    /**
     * 分组自定义排序。分组管理弹框里拖拽后更新，替换管理页面的分组 tab 与分组菜单跟随它刷新。
     */
    val groupOrder: StateFlow<List<String>> = _groupOrder.asStateFlow()

    fun upGroupOrder(groups: List<String>) {
        _groupOrder.value = groups
        appCtx.putPrefString(PreferKey.replaceGroupOrder, GSON.toJson(groups))
    }

    fun applySavedGroupOrder(groups: List<String>): List<String> {
        val savedOrder = _groupOrder.value
        if (savedOrder.isEmpty()) return groups
        val ordered = savedOrder.filter { it in groups }
        val remaining = groups.filter { it !in savedOrder }
            .sortedWith { o1, o2 -> o1.cnCompare(o2) }
        return ordered + remaining
    }

    /**
     * 分组重命名时把保存的排序里的旧名字替换成新名字，删除分组时移除该名字。
     */
    private fun upSavedGroupOrderOnGroupChanged(oldGroup: String, newGroup: String?) {
        val savedOrder = _groupOrder.value
        if (oldGroup !in savedOrder) return
        val renamed = newGroup?.takeIf { it.isNotBlank() }
        val newOrder = savedOrder.mapNotNull {
            if (it == oldGroup) renamed else it
        }
        if (newOrder != savedOrder) {
            upGroupOrder(newOrder)
        }
    }

    private fun readSavedGroupOrder(): List<String> {
        return try {
            appCtx.getPrefString(PreferKey.replaceGroupOrder)
                ?.let { GSON.fromJsonArray<String>(it).getOrDefault(emptyList()) }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
