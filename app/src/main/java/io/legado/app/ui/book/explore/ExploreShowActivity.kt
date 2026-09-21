package io.legado.app.ui.book.explore

import android.graphics.Rect
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setTintMutate
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 发现列表
 */
class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>(),
    ExploreShowAdapter.CallBack {
    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()

    private var layoutMode: Int = AppConfig.exploreShowLayoutMode
    private val listAdapter by lazy { ExploreShowAdapter(this, this) }
    private val gridAdapter by lazy { ExploreShowGridAdapter(this, this) }
    private val loadMoreView by lazy { LoadMoreView(this) }
    private val loadMoreViewTop by lazy { LoadMoreView(this) }
    private var verticalDivider: VerticalDivider? = null
    private val gridSpacingDecoration by lazy {
        val spacing = (4 * resources.displayMetrics.density + 0.5f).toInt()
        object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                outRect.set(spacing, spacing, spacing, spacing)
            }
        }
    }
    private var oldPage = -1
    private var isClearAll = false
    private val menuAddLoadedBooks by lazy {
        binding.titleBar.menu.add(R.string.add_loaded_books_to_bookshelf).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                alertAddLoadedBooksToShelf()
                true
            }
        }
    }
    private var pageActionView: TextView? = null
    private var listActionButton: ImageButton? = null
    private var gridActionButton: ImageButton? = null

    private fun ensureTitleActions() {
        val menu = binding.titleBar.menu
        if (menu.findItem(MENU_TITLE_ACTIONS_ID) != null) return
        menu.add(
            Menu.NONE, MENU_TITLE_ACTIONS_ID, MENU_TITLE_ACTIONS_ORDER, ""
        ).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setActionView(R.layout.view_explore_title_actions)
            actionView?.let { root ->
                pageActionView = root.findViewById<TextView>(R.id.tv_page).apply {
                    text = getString(R.string.menu_page, viewModel.pageLiveData.value ?: 1)
                    setOnClickListener { showPagePicker() }
                }
                listActionButton = root.findViewById<ImageButton>(R.id.btn_list).apply {
                    setImageResource(R.drawable.ic_chapter_list)
                    setOnClickListener { setLayoutMode(0) }
                }
                gridActionButton = root.findViewById<ImageButton>(R.id.btn_grid).apply {
                    setImageResource(R.drawable.ic_view_quilt)
                    setOnClickListener { setLayoutMode(1) }
                }
            }
        }
    }

    private fun showPagePicker() {
        val page = viewModel.pageLiveData.value ?: 1
        NumberPickerDialog(this@ExploreShowActivity)
            .setTitle(getString(R.string.change_page))
            .setMaxValue(999)
            .setMinValue(1)
            .setValue(page)
            .show {
                if (page != it) {
                    val adapter = currentAdapter()
                    if (oldPage == -1 && it != 1) { //初次添加头
                        adapter.addHeaderView {
                            ViewLoadMoreBinding.bind(loadMoreViewTop)
                        }
                    } else if (it != 1) { //把头显示出来
                        val layoutParams = loadMoreViewTop.layoutParams
                        if (layoutParams?.height == 0) {
                            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            loadMoreViewTop.layoutParams = layoutParams
                        }
                    }
                    oldPage = it
                    viewModel.skipPage(it)
                    loadMoreViewTop.stopLoad()
                    loadMoreView.hasMore()
                    isClearAll = true
                    adapter.clearItems()
                    viewModel.explore()
                }
            }
    }

    private fun upPageActionTitle(page: Int) {
        pageActionView?.text = getString(R.string.menu_page, page)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = intent.getStringExtra("exploreName")
        layoutMode = AppConfig.exploreShowLayoutMode.coerceIn(0, 1)
        initRecyclerView()
        menuAddLoadedBooks
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.addBooksData.observe(this) { upDataTop(it) }
        viewModel.initData(intent)
        viewModel.errorLiveData.observe(this) {
            loadMoreView.error(it)
        }
        viewModel.errorTopLiveData.observe(this) {
            loadMoreViewTop.error(it)
        }
        viewModel.upAdapterLiveData.observe(this) {
            currentAdapter().notifyItemRangeChanged(0, currentAdapter().itemCount, Bundle().apply {
                putString(it, null)
            })
        }
        viewModel.pageLiveData.observe(this) {
            upPageActionTitle(it)
        }
        viewModel.addBooksBusy.observe(this) {
            menuAddLoadedBooks.isEnabled = !it
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        val result = super.onCompatCreateOptionsMenu(menu)
        binding.titleBar.toolbar.post {
            ensureTitleActions()
            updateLayoutMenuIcons()
        }
        return result
    }

    private fun alertAddLoadedBooksToShelf() {
        val loadedBooks = viewModel.getLoadedBooks()
        if (loadedBooks.isEmpty()) {
            toastOnUi(R.string.no_loaded_books_to_add)
            return
        }
        alert(titleResource = R.string.add_loaded_books_to_bookshelf) {
            setMessage(
                getString(R.string.add_loaded_books_to_bookshelf_message, loadedBooks.size)
            )
            yesButton {
                val started = viewModel.addLoadedBooksToShelf(loadedBooks)
                if (!started) {
                    toastOnUi(R.string.add_loaded_books_to_bookshelf_in_progress)
                }
            }
            noButton()
        }
    }

    private fun currentAdapter(): RecyclerAdapter<SearchBook, *> {
        return if (layoutMode == 1) gridAdapter else listAdapter
    }

    private fun setLayoutMode(mode: Int) {
        if (layoutMode == mode) return
        layoutMode = mode
        AppConfig.exploreShowLayoutMode = mode
        applyLayoutMode(restoreData = true)
        updateLayoutMenuIcons()
    }

    private fun updateLayoutMenuIcons() {
        kotlin.runCatching {
            //未选中与“第x页”文字同色,选中用强调色高亮
            val normalColor = primaryTextColor
            val selectedColor = accentColor
            listActionButton?.drawable?.setTintMutate(
                if (layoutMode == 0) selectedColor else normalColor
            )
            gridActionButton?.drawable?.setTintMutate(
                if (layoutMode == 1) selectedColor else normalColor
            )
        }
    }

    private fun initRecyclerView() {
        verticalDivider = VerticalDivider(this)
        binding.recyclerView.applyNavigationBarPadding()
        applyLayoutMode(restoreData = false)
        loadMoreView.startLoad()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                } else if (!recyclerView.canScrollVertically(-1) && dy < 0) {
                    scrollToTop()
                }
            }
        })
    }

    private fun applyLayoutMode(restoreData: Boolean) {
        val recyclerView = binding.recyclerView
        val oldAdapter = recyclerView.adapter as? RecyclerAdapter<*, *>
        val hadHeader = (oldAdapter?.getHeaderCount() ?: 0) > 0
        val firstVisiblePosition =
            (recyclerView.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
        verticalDivider?.let { recyclerView.removeItemDecoration(it) }
        recyclerView.removeItemDecoration(gridSpacingDecoration)
        val newAdapter = currentAdapter()
        if (layoutMode == 1) {
            recyclerView.layoutManager = GridLayoutManager(this, ExploreShowGridAdapter.SPAN_COUNT)
            recyclerView.addItemDecoration(gridSpacingDecoration)
        } else {
            recyclerView.layoutManager = LinearLayoutManager(this)
            verticalDivider?.let { recyclerView.addItemDecoration(it) }
        }
        //先设置 LayoutManager,再设置 adapter,
        //使 header/footer 在网格模式下占满整行(spanSizeLookup 生效)
        if (restoreData && oldAdapter !== newAdapter) {
            newAdapter.addFooterView {
                ViewLoadMoreBinding.bind(loadMoreView)
            }
            if (hadHeader) {
                newAdapter.addHeaderView {
                    ViewLoadMoreBinding.bind(loadMoreViewTop)
                }
                loadMoreViewTop.post {
                    val layoutParams = loadMoreViewTop.layoutParams
                    if (layoutParams != null) {
                        layoutParams.height = if (oldPage <= 1) {
                            0
                        } else {
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        loadMoreViewTop.layoutParams = layoutParams
                    }
                }
            }
            newAdapter.setItems(viewModel.getLoadedBooks())
        } else if (!restoreData) {
            newAdapter.addFooterView {
                ViewLoadMoreBinding.bind(loadMoreView)
            }
        }
        if (recyclerView.adapter !== newAdapter) {
            recyclerView.adapter = newAdapter
        }
        if (restoreData && firstVisiblePosition > 0) {
            recyclerView.scrollToPosition(firstVisiblePosition)
        }
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if ((loadMoreView.hasMore && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreView.hasMore()
            viewModel.explore()
        }
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if ((oldPage > 1 && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreViewTop.hasMore()
            oldPage--
            viewModel.explore(oldPage)
        }
    }

    private fun upData(books: List<SearchBook>) {
        loadMoreView.stopLoad()
        val adapter = currentAdapter()
        if (books.isEmpty() && adapter.isEmpty()) {
            loadMoreView.noMore(getString(R.string.empty))
        } else if (adapter.getActualItemCount() == books.size) {
            loadMoreView.noMore()
        } else {
            adapter.setItems(books)
            if (isClearAll) { //全清空后,加了头,位置下移一个
                val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
                layoutManager.scrollToPositionWithOffset(1, 0)
                isClearAll = false
            }
        }
    }

    private fun upDataTop(books: List<SearchBook>) {
        loadMoreViewTop.stopLoad()
        val adapter = currentAdapter()
        adapter.addItems(0, books)
        val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
        if (layoutManager.findFirstVisibleItemPosition() <= 1) { //顶部刷新,未滚动，矫正位置
            layoutManager.scrollToPositionWithOffset(books.size, 0)
        }
        if (oldPage <= 1) { //已到顶,隐藏头
            val layoutParams = loadMoreViewTop.layoutParams
            if (layoutParams != null) {
                layoutParams.height = 0
                loadMoreViewTop.layoutParams = layoutParams
            }
        }
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    override fun showBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    companion object {
        private const val MENU_TITLE_ACTIONS_ID = Menu.FIRST + 1
        private const val MENU_TITLE_ACTIONS_ORDER = Menu.FIRST + 1
    }
}
