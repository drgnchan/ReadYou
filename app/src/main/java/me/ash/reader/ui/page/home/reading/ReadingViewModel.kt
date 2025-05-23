package me.ash.reader.ui.page.home.reading

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.ItemSnapshotList
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidImageDownloader
import me.ash.reader.infrastructure.cache.DiffMapHolder
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.RssHelper
import java.util.Date

@HiltViewModel(assistedFactory = ReadingViewModel.ReadingViewModelFactory::class)
class ReadingViewModel @AssistedInject constructor(
    @Assisted private val initialArticleId: String,
    @Assisted private var pagingItems: ItemSnapshotList<ArticleFlowItem>,
    private val rssService: RssService,
    private val rssHelper: RssHelper,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val imageDownloader: AndroidImageDownloader,
    private val diffMapHolder: DiffMapHolder,
) : ViewModel() {

    private val _readingUiState = MutableStateFlow(ReadingUiState())
    val readingUiState: StateFlow<ReadingUiState> = _readingUiState.asStateFlow()

    private val _readerState: MutableStateFlow<ReaderState> = MutableStateFlow(ReaderState())
    val readerStateStateFlow = _readerState.asStateFlow()

    private val currentArticle: Article?
        get() = readingUiState.value.articleWithFeed?.article
    private val currentFeed: Feed?
        get() = readingUiState.value.articleWithFeed?.feed

    fun injectPagingData(pagingItems: ItemSnapshotList<ArticleFlowItem>) {
        this.pagingItems = pagingItems
    }

    init {
        initData(initialArticleId)
    }

    fun initData(articleId: String) {
        viewModelScope.launch(ioDispatcher) {
            rssService.get().findArticleById(articleId)?.run {
                diffMapHolder.updateDiff(this, isUnread = false)
                _readingUiState.update {
                    it.copy(
                        articleWithFeed = this,
                        isStarred = article.isStarred,
                        isUnread = false
                    )
                }
                _readerState.update {
                    it.copy(
                        articleId = article.id,
                        feedName = feed.name,
                        title = article.title,
                        author = article.author,
                        link = article.link,
                        publishedDate = article.date,
                    ).prefetchArticleId().renderContent(this)
                }
            }
        }
    }

    fun ReaderState.renderContent(articleWithFeed: ArticleWithFeed): ReaderState {
        if (articleWithFeed.feed.isFullContent) {
            renderFullContent()
            return this.copy(content = ReaderState.Loading)
        } else return this.copy(
            content = ReaderState.Description(articleWithFeed.article.let {
                it.fullContent ?: it.rawDescription
            })
        )
    }

    fun renderDescriptionContent() {
        _readerState.update {
            it.copy(
                content = ReaderState.Description(
                    content = currentArticle?.fullContent ?: currentArticle?.rawDescription ?: ""
                )
            )
        }
    }

    fun renderFullContent() {
        viewModelScope.launch {
            internalRenderFullContent()
        }
    }

    suspend fun internalRenderFullContent() {
        setLoading()
        runCatching {
            rssHelper.parseFullContent(
                currentArticle?.link ?: "", currentArticle?.title ?: ""
            )
        }.onSuccess { content ->
            _readerState.update { it.copy(content = ReaderState.FullContent(content = content)) }
        }.onFailure { th ->
            Log.i("RLog", "renderFullContent: ${th.message}")
            _readerState.update { it.copy(content = ReaderState.Error(th.message.toString())) }
        }
    }

    fun updateReadStatus(isUnread: Boolean) {
        readingUiState.value.articleWithFeed?.let { diffMapHolder.updateDiff(it, isUnread) }
        _readingUiState.update { it.copy(isUnread = diffMapHolder.checkIfUnread(it.articleWithFeed!!)) }
    }

    fun updateStarredStatus(isStarred: Boolean) {
        applicationScope.launch(ioDispatcher) {
            _readingUiState.update { it.copy(isStarred = isStarred) }
            currentArticle?.let {
                rssService.get().markAsStarred(
                    articleId = it.id,
                    isStarred = isStarred,
                )
            }
        }
    }

    private fun setLoading() {
        _readerState.update {
            it.copy(content = ReaderState.Loading)
        }
    }

    fun ReaderState.prefetchArticleId(): ReaderState {
        val items = pagingItems
        val currentId = currentArticle?.id
        val index = items.indexOfFirst { item ->
            item is ArticleFlowItem.Article && item.articleWithFeed.article.id == currentId
        }
        var previousId: String? = null
        var nextId: String? = null

        if (index != -1 || currentId == null) {
            val prevIterator = items.listIterator(index)
            while (prevIterator.hasPrevious()) {
                Log.d("Log", "index: $index, previous: ${prevIterator.previousIndex()}")
                val prev = prevIterator.previous()
                if (prev is ArticleFlowItem.Article) {
                    previousId = prev.articleWithFeed.article.id
                    break
                }
            }
            val nextIterator = items.listIterator(index + 1)
            while (nextIterator.hasNext()) {
                Log.d("Log", "index: $index, next: ${nextIterator.nextIndex()}")
                val next = nextIterator.next()
                if (next is ArticleFlowItem.Article && next.articleWithFeed.article.id != currentId) {
                    nextId = next.articleWithFeed.article.id
                    break
                }
            }
        }

        return copy(
            nextArticleId = nextId, previousArticleId = previousId, listIndex = index
        )
    }

    fun loadPrevious(): Boolean {
        readerStateStateFlow.value.previousArticleId?.run {
            initData(this)
        } ?: return false
        return true
    }

    fun loadNext(): Boolean {
        readerStateStateFlow.value.nextArticleId?.run {
            initData(this)
        } ?: return false
        return true
    }

    fun downloadImage(
        url: String, onSuccess: (Uri) -> Unit = {}, onFailure: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch {
            imageDownloader.downloadImage(url).onSuccess(onSuccess).onFailure(onFailure)
        }
    }

    @AssistedFactory
    interface ReadingViewModelFactory {
        fun create(
            articleId: String,
            pagingItems: ItemSnapshotList<ArticleFlowItem>
        ): ReadingViewModel
    }
}

data class ReadingUiState(
    val articleWithFeed: ArticleWithFeed? = null,
    val isUnread: Boolean = false,
    val isStarred: Boolean = false,
)

data class ReaderState(
    val articleId: String? = null,
    val feedName: String = "",
    val title: String? = null,
    val author: String? = null,
    val link: String? = null,
    val publishedDate: Date = Date(0L),
    val content: ContentState = Loading,
    val listIndex: Int? = null,
    val nextArticleId: String? = null,
    val previousArticleId: String? = null
) {
    sealed interface ContentState {
        val text: String?
            get() {
                return when (this) {
                    is Description -> content
                    is Error -> message
                    is FullContent -> content
                    Loading -> null
                }
            }
    }

    data class FullContent(val content: String) : ContentState
    data class Description(val content: String) : ContentState
    data class Error(val message: String) : ContentState
    data object Loading : ContentState
}
