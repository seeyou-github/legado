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
    }

    fun delGroup(group: String) = upGroup(group, null)

    fun upGroupOrder(groups: List<String>) {
        appCtx.putPrefString(PreferKey.replaceGroupOrder, GSON.toJson(groups))
    }

    fun applySavedGroupOrder(groups: List<String>): List<String> {
        val savedOrder = try {
            appCtx.getPrefString(PreferKey.replaceGroupOrder)
                ?.let { GSON.fromJsonArray<String>(it).getOrDefault(emptyList()) }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (savedOrder.isEmpty()) return groups
        val ordered = savedOrder.filter { it in groups }
        val remaining = groups.filter { it !in savedOrder }
            .sortedWith { o1, o2 -> o1.cnCompare(o2) }
        return ordered + remaining
    }
}
