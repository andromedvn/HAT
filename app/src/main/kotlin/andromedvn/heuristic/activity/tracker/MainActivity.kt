package andromedvn.heuristic.activity.tracker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import andromedvn.heuristic.activity.tracker.domain.TourStep
import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import andromedvn.heuristic.activity.tracker.domain.AppTourState
import andromedvn.heuristic.activity.tracker.domain.DashboardTourSteps
import andromedvn.heuristic.activity.tracker.ui.components.TourStepOverlay
import andromedvn.heuristic.activity.tracker.ui.components.AppTourRegistry
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

        // REMOVED 2026-09-30: the automatic startup dedupe.
        //
        // It deleted rows before the user saw anything, with no prompt, no undo, and no
        // record of what was removed. An earlier version of it grouped by time window
        // only and destroyed genuinely distinct labels. The user's decision (2026-10-01)
        // is that duplicates are SHOWN inside Total Offline Time and the user chooses.
        //
        // Detection now lives in OfflineStorage.findDuplicateOfflineLabels(), which
        // deletes nothing. Removal is deleteOfflineActivityById(id), called only from an
        // explicit tap on a visible row.
        HatLogger.log("MainActivity: duplicate labels are reported, never auto-deleted")

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
                            WorkerScheduler.updateWorkers(applicationContext, settings!!.archiveSyncIntervalHours, settings!!.autoBackupIntervalHours)
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
                    // Usage access alone is not completion: a user who escaped
                    // onboarding (killed app, skipped battery) must land back in
                    // onboarding, or the guided tour never arms.
                    if (!hasUsageStatsPermission(applicationContext) || settings?.hasSeenOnboarding == false) "permissions" else "dashboard"
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
                        
                        composable(
                            "permissions?review={review}",
                            arguments = listOf(navArgument("review") { defaultValue = "false" })
                        ) { backStackEntry ->
                            val review = backStackEntry.arguments?.getString("review")?.toBoolean() ?: false
                            PermissionsScreen(navController, storage, repository, reviewMode = review)
                        }

                        composable(
                            route = "dashboard?startTour={startTour}",
                            arguments = listOf(navArgument("startTour") { defaultValue = "false" }),
                            deepLinks = listOf(navDeepLink { uriPattern = "hat://dashboard" })
                        ) { backStackEntry ->
                            val startTour = backStackEntry.arguments?.getString("startTour")?.toBoolean() ?: false
                            DashboardScreen(navController, dashboardViewModel, startTour = startTour)
                        }
                        
                        composable(
                            route = "details/{type}/{id}",
                            arguments = listOf(navArgument("type") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val type = backStackEntry.arguments?.getString("type") ?: "app"
                            // Nav path segments are URI-encoded on the way in; labels are free user
                            // text, so decode before using the value as a lookup key.
                            val id = Uri.decode(backStackEntry.arguments?.getString("id") ?: "")
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

                    // The app tour: ONE overlay above the whole navigation
                    // (2026-11-01). It reads the global step and the global
                    // registry, so screen handoffs can never drop it. The
                    // label-dialog step is the exception: the dialog is its
                    // own window, so it hosts its own banner (r12) and the
                    // main overlay stays hidden for that step.
                    val tourStep = AppTourState.current
                    val tourInDialog = tourStep?.screen == DashboardTourSteps.SCREEN_LABEL_DIALOG
                    // Keep the last rendered step so the fade-out has content.
                    // The overlay STAYS MOUNTED while the tour is on the
                    // label-dialog step (r12f): it simply sits behind the two
                    // dialog windows, so the tour never vanishes between the
                    // tap and the dialog appearing. The fade is only for the
                    // end of the tour.
                    var lastTourStep by remember { mutableStateOf<TourStep?>(null) }
                    if (tourStep != null) lastTourStep = tourStep
                    val overlayVisible = AppTourState.active
                    val overlayAlpha by animateFloatAsState(
                        targetValue = if (overlayVisible) 1f else 0f,
                        animationSpec = tween(150),
                        label = "tourOverlayAlpha"
                    )
                    androidx.activity.compose.BackHandler(enabled = AppTourState.active && !tourInDialog) {
                        val prev = DashboardTourSteps.steps.getOrNull(AppTourState.stepIndex - 1)
                        if (prev != null && prev.route != (tourStep?.route ?: "")) {
                            navController.popBackStack(prev.route, false)
                        } else if (prev == null) {
                            AppTourState.end()
                            lifecycleScope.launch { storage.saveSettings(storage.settings.first().copy(hasSeenTour = true)) }
                        }
                        // The dialog step has no overlay of its own in this
                        // window; back must never land on it.
                        AppTourState.stepIndex = (AppTourState.stepIndex - 1).coerceAtLeast(0)
                        if (AppTourState.current?.screen == DashboardTourSteps.SCREEN_LABEL_DIALOG) {
                            AppTourState.stepIndex = (AppTourState.stepIndex - 1).coerceAtLeast(0)
                        }
                    }
                    if (overlayAlpha > 0.02f && lastTourStep != null) TourStepOverlay(
                        registry = AppTourRegistry,
                        currentStep = lastTourStep,
                        globalIndex = AppTourState.stepIndex.coerceAtMost(DashboardTourSteps.steps.size - 1),
                        globalCount = DashboardTourSteps.steps.size,
                        inputEnabled = overlayVisible,
                        modifier = Modifier.alpha(overlayAlpha),
                        onNext = {
                            val next = DashboardTourSteps.steps.getOrNull(AppTourState.stepIndex + 1)
                            if (next == null) {
                                AppTourState.end()
                                lifecycleScope.launch { storage.saveSettings(storage.settings.first().copy(hasSeenTour = true)) }
                            } else if (next.route != (tourStep?.route ?: "")) {
                                navController.navigate(next.route)
                            }
                            AppTourState.stepIndex++
                        },
                        onBack = {
                            val prev = DashboardTourSteps.steps.getOrNull(AppTourState.stepIndex - 1)
                            if (prev != null && prev.route != (tourStep?.route ?: "")) {
                                navController.popBackStack(prev.route, false)
                            }
                            AppTourState.stepIndex--
                            if (AppTourState.current?.screen == DashboardTourSteps.SCREEN_LABEL_DIALOG) {
                                AppTourState.stepIndex--
                            }
                        },
                        onSkip = {
                            AppTourState.end()
                            lifecycleScope.launch { storage.saveSettings(storage.settings.first().copy(hasSeenTour = true)) }
                        }
                    )
                }
            }
        }
    }
}
