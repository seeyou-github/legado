package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemExploreGridBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor


/**
 * 发现列表网格视图,固定三列,仅显示封面+书名
 */
class ExploreShowGridAdapter(context: Context, val callBack: ExploreShowAdapter.CallBack) :
    RecyclerAdapter<SearchBook, ItemExploreGridBinding>(context) {

    //主题色只解析一次,避免列表滚动时反复读取主题属性
    private val readRecordNameColor by lazy { context.accentColor }
    private val normalNameColor by lazy { context.primaryTextColor }

    override fun getViewBinding(parent: ViewGroup): ItemExploreGridBinding {
        return ItemExploreGridBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemExploreGridBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            bind(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bindChange(binding, item, bundle)
            }
        }
    }

    private fun bind(binding: ItemExploreGridBinding, item: SearchBook) {
        binding.run {
            tvName.text = item.name
            upNameColor(binding, item)
            ivInBookshelf.isVisible = callBack.isInBookshelf(item)
            ivCover.load(
                item,
                AppConfig.loadCoverOnlyWifi
            )
        }
    }

    /**
     * 读过的书(阅读时长超过1分钟)书名用主题强调色,提示用户读过,其它书恢复默认色
     */
    private fun upNameColor(binding: ItemExploreGridBinding, item: SearchBook) {
        binding.tvName.setTextColor(
            if (callBack.hasReadRecordOverMinute(item)) readRecordNameColor else normalNameColor
        )
    }

    private fun bindChange(binding: ItemExploreGridBinding, item: SearchBook, bundle: Bundle) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "isInBookshelf" -> ivInBookshelf.isVisible =
                        callBack.isInBookshelf(item)

                    "hasReadRecord" -> upNameColor(binding, item)
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemExploreGridBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                callBack.showBookInfo(it)
            }
        }
    }

    override fun getSpanSize(viewType: Int, position: Int): Int {
        return if (position < getHeaderCount()
            || position >= getHeaderCount() + getActualItemCount()
        ) {
            SPAN_COUNT
        } else {
            1
        }
    }

    companion object {
        const val SPAN_COUNT = 3
    }
}
