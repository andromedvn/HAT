package andromedvn.heuristic.activity.tracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.data.AppUsageItem
import andromedvn.heuristic.activity.tracker.ui.components.*
import kotlinx.coroutines.launch

class HiddenAppsViewModel(private val repository: ActivityRepository) : ViewModel() {
    var hiddenApps by mutableStateOf<List<AppUsageItem>>(emptyList())
    fun loadHiddenApps() = viewModelScope.launch { hiddenApps = repository.getHiddenAppList() }
    fun unhideApp(packageName: String) = viewModelScope.launch { repository.unhideApp(packageName); loadHiddenApps() }
}

@Suppress("UNCHECKED_CAST")
class HiddenAppsViewModelFactory(private val repository: ActivityRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T { 
        if (modelClass.isAssignableFrom(HiddenAppsViewModel::class.java)) return HiddenAppsViewModel(repository) as T
        throw IllegalArgumentException("Unknown ViewModel") 
    }
}

@Composable
fun HiddenAppsScreen(repository: ActivityRepository) {
    val viewModel: HiddenAppsViewModel = viewModel(factory = HiddenAppsViewModelFactory(repository))
    val listState = rememberLazyListState()
    
    LaunchedEffect(Unit) { viewModel.loadHiddenApps() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalFadingEdges(listState.canScrollBackward, listState.canScrollForward)
        ) {
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 20.dp)) {
                item { 
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        HatDynamicHeader(title = "Hidden Apps", subtitle = "Manage") 
                    }
                }
                
                if (viewModel.hiddenApps.isEmpty()) {
                    item { Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) { EmptyStateCard(Icons.Default.Visibility, "No Hidden Applications", "All applications are visible in the timeline.") } }
                } else {
                    items(viewModel.hiddenApps, key = { it.packageName }) { app ->
                        Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                            HiddenAppListItem(app = app, color = MaterialTheme.colorScheme.primary) {
                                viewModel.unhideApp(app.packageName)
                            }
                        }
                    }
                }
            }
        }
    }
}
