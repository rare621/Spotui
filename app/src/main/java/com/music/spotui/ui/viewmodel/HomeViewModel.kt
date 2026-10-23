package com.music.spotui.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(private val repository: AppRepository)  : ViewModel() {


    private val _albums : MutableStateFlow<Response<List<AlbumsModel>>> = MutableStateFlow(Response.Loading())
    val albums : StateFlow<Response<List<AlbumsModel>>> = _albums

    private val _artists : MutableStateFlow<Response<List<ArtistsModel>>> = MutableStateFlow(Response.Loading())
    val artists : StateFlow<Response<List<ArtistsModel>>> = _artists

    private val _home : MutableStateFlow<Response<HomeFeedModel>> = MutableStateFlow(Response.Loading())
    val home : StateFlow<Response<HomeFeedModel>> = _home


    private val _discovery = MutableStateFlow<List<com.music.spotui.data.entity.HomeSection>>(emptyList())
    val discovery: StateFlow<List<com.music.spotui.data.entity.HomeSection>> = _discovery

    private val _presentation = MutableStateFlow<com.music.spotui.data.recommendation.HomePresentation?>(null)
    val presentation: StateFlow<com.music.spotui.data.recommendation.HomePresentation?> = _presentation
    private val _renderRevision = MutableStateFlow(0L)
    val renderRevision: StateFlow<Long> = _renderRevision
    private data class RenderRequest(val feed: HomeFeedModel, val discoveries: List<com.music.spotui.data.entity.HomeSection>,
        val revisions: List<Long>, val accountName: String?)
    private var preparedRequest: RenderRequest? = null
    private var pendingRequest: RenderRequest? = null
    private var presentationJob: kotlinx.coroutines.Job? = null
    private var renderGeneration = 0L
    private var lastBaseFeed: HomeFeedModel? = null
    private val preferenceLock = Any()
    private var cleared = false
    private var observedPreferences = emptyList<android.content.SharedPreferences>()
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _renderRevision.update { it + 1L }
    }
    private fun observeHomePreferences(context: android.content.Context) {
        if (synchronized(preferenceLock) { cleared || observedPreferences.isNotEmpty() }) return
        val stores = listOf("home_hidden_items", "spotui_modified_home_recents", "spotui_home_frequency_v1", "spotui_radio_sessions", "ListeningHistory")
            .map { context.getSharedPreferences(it, android.content.Context.MODE_PRIVATE) }
        synchronized(preferenceLock) {
            if (cleared || observedPreferences.isNotEmpty()) return
            observedPreferences = stores
            stores.forEach { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
        }
    }
    fun prepareHome(context: android.content.Context, feed: HomeFeedModel,
                    discoveries: List<com.music.spotui.data.entity.HomeSection>, revisions: List<Long>, accountName: String?) {
        // Local refreshes must still run while Spotify is unavailable, using its last good baseline.
        val usableFeed = if (feed.sections.isNotEmpty()) feed.also { lastBaseFeed = it } else lastBaseFeed ?: feed
        val request = RenderRequest(usableFeed, discoveries, revisions, accountName)
        if (preparedRequest == request) {
            // Returning to an already prepared input must also cancel a superseded refresh.
            if (pendingRequest != null && pendingRequest != request) { renderGeneration++; presentationJob?.cancel(); pendingRequest = null }
            return
        }
        if (pendingRequest == request && presentationJob?.isActive == true) return
        val generation = ++renderGeneration
        presentationJob?.cancel()
        pendingRequest = request
        val app = context.applicationContext
        presentationJob = viewModelScope.launch {
            try {
                val ready = withContext(Dispatchers.IO) {
                    observeHomePreferences(app)
                    com.music.spotui.data.recommendation.HomePresentationBuilder.build(app, usableFeed, discoveries, accountName)
                }
                if (generation != renderGeneration) return@launch
                _presentation.value = ready
                preparedRequest = request
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("HomeViewModel", "Home layout refresh deferred", error)
            } finally { if (renderGeneration == generation) pendingRequest = null }
        }
    }
    fun hideHomeItem(context: android.content.Context, item: com.music.spotui.data.entity.HomeItem) {
        renderGeneration++
        presentationJob?.cancel()
        pendingRequest = null
        preparedRequest = null
        val key = com.music.spotui.data.preferences.HomeHiddenItems.key(item)
        _presentation.value = _presentation.value?.let { model ->
            model.copy(topTiles = model.topTiles.filterNot { com.music.spotui.data.preferences.HomeHiddenItems.key(it) == key },
                sections = model.sections.map { row -> row.copy(items = row.items.filterNot { com.music.spotui.data.preferences.HomeHiddenItems.key(it) == key }) })
        }
        viewModelScope.launch(Dispatchers.IO) {
            com.music.spotui.data.preferences.HomeHiddenItems.hide(context.applicationContext, item)
            _renderRevision.update { it + 1L }
        }
    }
    override fun onCleared() {
        synchronized(preferenceLock) {
            cleared = true
            observedPreferences.forEach { it.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
            observedPreferences = emptyList()
        }
        super.onCleared()
    }

    private val _following = MutableStateFlow<Response<com.music.spotui.data.entity.FollowingFeed>>(Response.Loading())
    val following: StateFlow<Response<com.music.spotui.data.entity.FollowingFeed>> = _following
    private var followingJob: kotlinx.coroutines.Job? = null
    fun loadFollowing(refresh: Boolean = false) {
        if (followingJob?.isActive == true || (!refresh && _following.value is Response.Success)) return
        _following.value = Response.Loading()
        followingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _following.value = Response.Success(repository.provideFollowingFeed { partial -> _following.value = Response.Success(partial) })
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _following.value = Response.Error(error.message ?: "Couldn't load followed artists.")
            }
        }
    }

    private var homeLoadJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Restore discovery before exposing the base feed, without blocking ViewModel creation.
            try { _discovery.value = repository.cachedHomeDiscoveries() }
            catch (error: Exception) { android.util.Log.w("HomeViewModel", "Saved discoveries unavailable", error) }
            fetchHome()
            val timer = MutableStateFlow(0L)
            launch { while (true) { delay(60_000L); timer.value++; _renderRevision.update { it + 1L } } }
            combine(com.music.spotui.data.preferences.ListeningHistorySignals.revision,
                com.music.spotui.data.preferences.HomePersonalization.revision, timer,
                com.music.spotui.data.preferences.RadioTaste.revision, BackupSignals.revision) { _, _, _, _, _ -> Unit }.collect {
                kotlinx.coroutines.delay(1000L)
                try {
                    _discovery.value = repository.discoverHomeCollections()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("HomeViewModel", "Local discoveries unavailable", error)
                }
            }
        }
        fetchArtists()
        fetchAlbums()
    }

    fun retryHome() {
        if (_home.value is Response.Loading) return
        _home.value = Response.Loading()
        fetchHome()
    }

    private fun fetchHome() {
        homeLoadJob?.cancel()
        homeLoadJob = viewModelScope.launch(Dispatchers.IO) {
            repository.provideHomeFeed().collect { feed ->
                _home.value = feed
            }
        }
    }

    private fun fetchAlbums() = viewModelScope.launch(Dispatchers.IO) {
            repository.provideAlbums().collect{ album ->
                _albums.value = album as Response<List<AlbumsModel>>
            }
    }

    private fun fetchArtists() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideArtists().collect { artist ->
            _artists.value = artist as Response<List<ArtistsModel>>
        }
    }



}
