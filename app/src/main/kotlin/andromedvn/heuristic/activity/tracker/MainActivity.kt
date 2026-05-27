package andromedvn.heuristic.activity.tracker

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.data.OfflineStorage
import andromedvn.heuristic.activity.tracker.data.ThemeType
import andromedvn.heuristic.activity.tracker.data.UserSettings
import andromedvn.heuristic.activity.tracker.ui.screens.*
import andromedvn.heuristic.activity.tracker.ui.theme.ComposeEmptyActivityTheme
import andromedvn.heuristic.activity.tracker.utils.WallpaperColorExtractor
import andromedvn.heuristic.activity.tracker.utils.hasUsageStatsPermission
import andromedvn.heuristic.activity.tracker.utils.HatLogger
import andromedvn.heuristic.activity.tracker.viewmodel.DashboardViewModel
import andromedvn.heuristic.activity.tracker.viewmodel.DashboardViewModelFactory
import andromedvn.heuristic.activity.tracker.worker.WorkerScheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        HatLogger.setupCrashHandler(applicationContext)
        HatLogger.log("MainActivity: onCreate")

        val storage = OfflineStorage.getInstance(applicationContext)
        val repository = ActivityRepository(applicationContext, storage)

        setContent {
            var settings by remember { mutableStateOf<UserSettings?>(null) }
            val lifecycleOwner = LocalLifecycleOwner.current
            val navController = rememberNavController()
            
            DisposableEffect(Unit) {
                val listener = Consumer<Intent> { intent -> navController.handleDeepLink(intent) }
                addOnNewIntentListener(listener)
                onDispose { removeOnNewIntentListener(listener) }
            }

            LaunchedEffect(Unit) { storage.settings.collect { settings = it } }

            if (settings == null) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B1B1F))) 
                return@setContent
            }
            
            ComposeEmptyActivityTheme(settings = settings!!) {
                
                LaunchedEffect(settings!!.themeType) {
                    if (settings!!.themeType == ThemeType.DYNAMIC && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        val extColor = WallpaperColorExtractor.getWallpaperDominantColor(applicationContext)
                        if (extColor != null && extColor != settings!!.extractedColor) {
                            storage.saveSettings(settings!!.copy(extractedColor = extColor))
                        }
                    }
                }

                val dashboardViewModel: DashboardViewModel = viewModel(factory = DashboardViewModelFactory(repository, applicationContext))
                var isFirstLaunch by rememberSaveable { mutableStateOf(true) }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            HatLogger.log("MainActivity: ON_RESUME (Global Propagation)")
                            repository.forceInvalidateCache()
                            WorkerScheduler.updateWorkers(applicationContext, settings!!.archiveSyncIntervalHours)
                            dashboardViewModel.triggerBackgroundArchiveSync()
                            
                            if (isFirstLaunch) {
                                isFirstLaunch = false
                            } else {
                                dashboardViewModel.loadDashboardData(force = true, isBackgroundTick = true)
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val actualStartDestination = remember(settings) {
                    if (!hasUsageStatsPermission(applicationContext)) "permissions" else "dashboard"
                }

                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                ) {
                    NavHost(navController = navController, startDestination = "splash") {
                        
                        composable("splash") {
                            HatSplashScreen(
                                themeColor = MaterialTheme.colorScheme.primary,
                                onSplashComplete = {
                                    navController.navigate(actualStartDestination) {
                                        popUpTo("splash") { inclusive = true }
                                    }
                                }
                            )
                        }
                        
                        composable("permissions") { 
                            PermissionsScreen(navController, storage, repository) 
                        }

                        composable(
                            route = "dashboard",
                            deepLinks = listOf(navDeepLink { uriPattern = "hat://dashboard" })
                        ) { 
                            DashboardScreen(navController, dashboardViewModel) 
                        }
                        
                        composable(
                            route = "details/{type}/{id}",
                            arguments = listOf(navArgument("type") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val type = backStackEntry.arguments?.getString("type") ?: "app"
                            val id = backStackEntry.arguments?.getString("id") ?: ""
                            ActivityDetailsScreen(navController, type, id, dashboardViewModel, repository)
                        }
                        
                        composable("app_usage_stats") { 
                            AppUsageStatsScreen(navController, dashboardViewModel) 
                        }
                        composable("offline_stats") { 
                            OfflineStatsScreen(navController, dashboardViewModel) 
                        }
                        
                        composable(
                            route = "label_gaps?target={target}",
                            arguments = listOf(navArgument("target") { type = NavType.StringType; nullable = true }),
                            deepLinks = listOf(
                                navDeepLink { uriPattern = "hat://label_gaps?target={target}" },
                                navDeepLink { uriPattern = "hat://label_gaps" }
                            )
                        ) { backStackEntry -> 
                            val targetStr = backStackEntry.arguments?.getString("target")
                            LaunchedEffect(targetStr) {
                                targetStr?.toLongOrNull()?.let { dashboardViewModel.jumpToDate(it) }
                            }
                            LabelGapsScreen(navController, dashboardViewModel) 
                        }
                        
                        composable("settings") { 
                            SettingsScreen(navController, repository) 
                        }
                        composable("hidden_apps") { 
                            HiddenAppsScreen(repository) 
                        }
                        composable("diagnostics") { 
                            DiagnosticsScreen(navController, repository) 
                        }
                        composable("licenses") { 
                            LicensesScreen(navController) 
                        }
                    }
                }
            }
        }
    }
}
