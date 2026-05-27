package andromedvn.heuristic.activity.tracker.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.data.SessionItem
import andromedvn.heuristic.activity.tracker.data.UnifiedActivity
import andromedvn.heuristic.activity.tracker.data.TimeRangeLabel
import andromedvn.heuristic.activity.tracker.domain.HeuristicEngine
import andromedvn.heuristic.activity.tracker.ui.components.ChartPoint
import andromedvn.heuristic.activity.tracker.utils.UsageStatsEngine
import andromedvn.heuristic.activity.tracker.utils.HatLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class DetailsUiState(
    val activity: UnifiedActivity? = null,
    val chartData: List<ChartPoint> = emptyList(),
    val sessionList: List<SessionItem> = emptyList(),
    val isLoading: Boolean = true,
    val animationSalt: String = java.util.UUID.randomUUID().toString()
)

class ActivityDetailsViewModel(private val repository: ActivityRepository, private val heuristicEngine: HeuristicEngine) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()
    private var currentRange: TimeRangeLabel? = null
    
    private var loadJob: Job? = null

    fun loadDetails(type: String, id: String, start: Long, end: Long, range: TimeRangeLabel) {
        HatLogger.log("ActivityDetailsViewModel: Loading details for type=$type, id=$id")
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (currentRange != range) {
                _uiState.value = _uiState.value.copy(animationSalt = java.util.UUID.randomUUID().toString())
            }
            currentRange = range
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            val settings = repository.settings.first()
            val ackGhosts = repository.getAcknowledgedGhostsBetween(start - 86400000L, end + 86400000L)
            
            val item = if (type == "app") heuristicEngine.getAppDetail(id, start, end, settings.ghostTimeTriggerHours, ackGhosts) else heuristicEngine.getOfflineDetail(id, start, end)
            val points = heuristicEngine.getActivityChartData(type, id, start, end, range)
            val sessions = if (type == "app") heuristicEngine.getAppSessions(id, start, end, settings.sessionClusteringMins) else heuristicEngine.getOfflineSessions(id, start, end, settings.sessionClusteringMins)
            
            _uiState.value = DetailsUiState(activity = item, chartData = points, sessionList = sessions, isLoading = false, animationSalt = _uiState.value.animationSalt)
        }
    }

    fun deleteActivity(title: String, start: Long, end: Long, onSuccess: () -> Unit) = viewModelScope.launch { 
        repository.deleteOfflineActivitiesByTitle(title, start, end)
        repository.forceInvalidateCache()
        onSuccess() 
    }
    
    fun updateActivity(oldTitle: String, newTitle: String, newIconName: String, start: Long, end: Long, onSuccess: () -> Unit) = viewModelScope.launch { 
        repository.updateOfflineActivitiesByTitle(oldTitle, newTitle, newIconName, start, end)
        repository.forceInvalidateCache()
        onSuccess() 
    }
    
    fun hideApp(packageName: String, onSuccess: () -> Unit) = viewModelScope.launch { 
        repository.hideApp(packageName)
        repository.forceInvalidateCache()
        onSuccess() 
    }
}

@Suppress("UNCHECKED_CAST")
class ActivityDetailsViewModelFactory(private val repository: ActivityRepository, private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ActivityDetailsViewModel::class.java)) return ActivityDetailsViewModel(repository, HeuristicEngine(repository, UsageStatsEngine(context))) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
