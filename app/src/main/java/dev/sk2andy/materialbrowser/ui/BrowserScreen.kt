@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
)

package dev.sk2andy.materialbrowser.ui

import dev.sk2andy.materialbrowser.shared.ui.TabOverviewHeroRules

import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import android.content.res.Configuration
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import dev.sk2andy.materialbrowser.browser.MediaStreamDetectorBridge
import dev.sk2andy.materialbrowser.browser.YouTubeStreamPreloader
import dev.sk2andy.materialbrowser.browser.PreloadedYouTubeStream
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sk2andy.materialbrowser.R
import dev.sk2andy.materialbrowser.browser.AddressResolver
import dev.sk2andy.materialbrowser.browser.SearchShortcutRules
import dev.sk2andy.materialbrowser.browser.BLANK_URL
import dev.sk2andy.materialbrowser.browser.BrowserController
import dev.sk2andy.materialbrowser.browser.BrowserProfile
import dev.sk2andy.materialbrowser.browser.BrowserTab
import dev.sk2andy.materialbrowser.browser.FindInPageRules
import dev.sk2andy.materialbrowser.browser.FullscreenVideoRules
import dev.sk2andy.materialbrowser.browser.cast.CastUiState
import dev.sk2andy.materialbrowser.browser.CapsuleSaveResult
import dev.sk2andy.materialbrowser.browser.MAX_PROFILES
import dev.sk2andy.materialbrowser.browser.MAX_TABS
import dev.sk2andy.materialbrowser.browser.ProfileLockTrigger
import dev.sk2andy.materialbrowser.browser.ProfileProtection
import dev.sk2andy.materialbrowser.browser.ProfileProtectionRules
import dev.sk2andy.materialbrowser.browser.ProfileWallpaperEditorContract
import dev.sk2andy.materialbrowser.browser.ProfileWallpaperEditorRequest
import dev.sk2andy.materialbrowser.browser.ProfileWallpaperTarget
import dev.sk2andy.materialbrowser.browser.RootTabBackDecision
import dev.sk2andy.materialbrowser.browser.wallpaperFor
import dev.sk2andy.materialbrowser.browser.suggestions.SearchSuggestionClient
import dev.sk2andy.materialbrowser.browser.suggestions.SearchSuggestionRules
import dev.sk2andy.materialbrowser.browser.commands.AddressAiModeRules
import dev.sk2andy.materialbrowser.browser.commands.AddressSuggestionItem
import dev.sk2andy.materialbrowser.browser.commands.AddressSubmission
import dev.sk2andy.materialbrowser.browser.commands.AddressSubmissionRules
import dev.sk2andy.materialbrowser.browser.SiteDataTarget
import dev.sk2andy.materialbrowser.browser.commands.AndroidCommandCatalog
import dev.sk2andy.materialbrowser.browser.commands.BrowserCommand
import dev.sk2andy.materialbrowser.browser.commands.BrowserCommandRegistry
import dev.sk2andy.materialbrowser.browser.commands.BrowserCommandKind
import dev.sk2andy.materialbrowser.browser.commands.CommandActions
import dev.sk2andy.materialbrowser.browser.commands.CommandConfirmation
import dev.sk2andy.materialbrowser.browser.commands.CommandDispatchOutcome
import dev.sk2andy.materialbrowser.browser.commands.CommandDispatcher
import dev.sk2andy.materialbrowser.browser.commands.CommandMatcher
import dev.sk2andy.materialbrowser.browser.commands.CommandSuggestion
import dev.sk2andy.materialbrowser.capsule.SiteCapsule
import dev.sk2andy.materialbrowser.capsule.CapsuleIconMode
import dev.sk2andy.materialbrowser.capsule.SiteCapsuleDraft
import dev.sk2andy.materialbrowser.data.AddressSuggestion
import dev.sk2andy.materialbrowser.data.FavoriteMutation
import dev.sk2andy.materialbrowser.reader.ReaderExtractionResult
import dev.sk2andy.materialbrowser.reader.ReaderExtractionFailure
import dev.sk2andy.materialbrowser.reader.ReaderLibraryRepository
import dev.sk2andy.materialbrowser.reader.ReaderStudioSession
import dev.sk2andy.materialbrowser.reader.ReaderStudioSessionRules
import dev.sk2andy.materialbrowser.shared.browser.AddressBarLongPressAction
import dev.sk2andy.materialbrowser.recall.RecallMatch
import eightbitlab.com.blurview.BlurTarget
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import dev.sk2andy.materialbrowser.data.SnoozedTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import kotlin.coroutines.resume

private enum class BrowserBackTarget {
    ReaderStudio,
    FilterStudio,
    SnoozedTabs,
    SettingsSubpage,
    Settings,
    AddressEditor,
    FindInPage,
    CandyTrail,
    TabOverview,
    WebContentFullscreen,
    WebHistory,
    ExternalApp,
    RootTab,
    System,
}

private data class PendingLinkSnooze(
    val url: String,
    val title: String?,
    val sourceTabId: String,
) {
    fun displayTab(): BrowserTab = BrowserTab(
        id = "link-peek-snooze:$sourceTabId:${url.hashCode()}",
        lastAccessedAt = 0L,
        title = title.orEmpty(),
        url = url,
    )
}

private data class PendingProfileConfiguration(
    val profileId: String,
    val remainingWallpaperTargets: List<ProfileWallpaperTarget>,
    val protection: ProfileProtection?,
    val activeWallpaperTarget: ProfileWallpaperTarget? = null,
)

private val PendingProfileConfigurationSaver =
    Saver<PendingProfileConfiguration?, List<String>>(
        save = { pending ->
            pending?.let {
                listOf(
                    it.profileId,
                    it.remainingWallpaperTargets.joinToString(",") { target ->
                        target.wireValue
                    },
                    it.protection?.lockTrigger?.wireValue.orEmpty(),
                    it.protection?.cooldownMinutes?.toString().orEmpty(),
                    it.activeWallpaperTarget?.wireValue.orEmpty(),
                )
            }
        },
        restore = { saved ->
            val protection = ProfileLockTrigger.fromWireValue(saved.getOrNull(2))?.let { trigger ->
                ProfileProtectionRules.normalize(
                    ProfileProtection(
                        lockTrigger = trigger,
                        cooldownMinutes = saved.getOrNull(3)?.toIntOrNull()
                            ?: ProfileProtectionRules.DEFAULT_COOLDOWN_MINUTES,
                    ),
                )
            }
            PendingProfileConfiguration(
                profileId = saved.first(),
                remainingWallpaperTargets = saved.getOrNull(1)
                    .orEmpty()
                    .split(',')
                    .mapNotNull(ProfileWallpaperTarget::fromWireValue),
                protection = protection,
                activeWallpaperTarget = ProfileWallpaperTarget.fromWireValue(saved.getOrNull(4)),
            )
        },
    )

@Composable
internal fun BrowserScreen(
    controller: BrowserController,
    fullscreenVideoGestureState: FullscreenVideoGestureState? = null,
    castUiState: CastUiState = CastUiState(),
    onToggleCastPlayback: () -> Unit = {},
    onSeekCast: (Long) -> Unit = {},
    onCastVolumeChange: (Float) -> Unit = {},
    onDisconnectCast: () -> Unit = {},
    webViewVideoOnlyPresentation: Boolean = false,
    videoOnlyPresentation: Boolean = webViewVideoOnlyPresentation,
    incomingBrowserNavigationRequestId: Int = 0,
    externalLaunchTabId: String? = null,
    onReturnToExternalApp: () -> Unit = {},
    onExternalPreviewCommitted: (String) -> Unit = {},
    onTabOverviewPortraitLockChanged: (Boolean) -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onImportUserScript: () -> Unit = {},
    onImportFavoriteBookmarks: () -> Unit = {},
    onExportAppData: () -> Unit = {},
    onExportAppLogs: () -> Unit = {},
    onClearAppLogs: () -> Unit = {},
    onExportGeckoLogs: () -> Unit = {},
    onClearGeckoLogs: () -> Unit = {},
    onImportAppData: () -> Unit = {},
    onShowGestureOnboarding: () -> Unit = {},
    onShowReleaseNotes: () -> Unit = {},
    onManageFirefoxExtensions: (() -> Unit)? = null,
    openAddressEditorOnLaunch: Boolean = false,
    launcherAddressEditorRequestId: Int = 0,
    hardwareTabChangeRequestId: Int = 0,
    onOpenMpvRx: ((String, String?) -> Unit)? = null,
) {
    val activeProfileLocked = controller.isActiveProfileLocked
    LaunchedEffect(controller, activeProfileLocked) {
        if (activeProfileLocked) controller.publishStatusBarScrollPageContext(visible = false)
    }
    if (activeProfileLocked) return
    WebAnnotationDialogs(controller.webAnnotations, onOpenLink = controller::openWebAnnotationLink)
    val hideBrowserChrome = FullscreenVideoRules.hidesBrowserChrome(
        isWebContentFullscreen = controller.isSelectedWebContentFullscreen,
        placement = controller.fullscreenVideoPlacement(videoOnlyPresentation),
        videoOnlyPresentation = videoOnlyPresentation,
    )
    val currentTabOverviewPortraitLockChanged by rememberUpdatedState(
        onTabOverviewPortraitLockChanged,
    )
    controller.externalLinkPreviewState?.let { state ->
        ExternalLinkPreviewScreen(
            controller = controller,
            state = state,
            onReturnToExternalApp = onReturnToExternalApp,
            onCommitted = onExternalPreviewCommitted,
            onTabOverviewPortraitLockChanged = currentTabOverviewPortraitLockChanged,
        )
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var tabOverviewVisible by rememberSaveable { mutableStateOf(false) }
    var candyTrailTabId by rememberSaveable { mutableStateOf<String?>(null) }
    var candyTrailSourceBounds by remember { mutableStateOf<Rect?>(null) }
    var addressEditorVisible by remember { mutableStateOf(openAddressEditorOnLaunch) }
    val aiModeSelectedState = remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var settingsDestination by rememberSaveable { mutableStateOf(SettingsDestination.Home) }
    var snoozedTabsVisible by rememberSaveable { mutableStateOf(false) }
    var snoozeTabId by remember { mutableStateOf<String?>(null) }
    var moveTabToProfileId by remember { mutableStateOf<String?>(null) }
    var pendingLinkSnooze by remember { mutableStateOf<PendingLinkSnooze?>(null) }
    var privacyXRayTabId by remember { mutableStateOf<String?>(null) }
    var permissionRadarTabId by remember { mutableStateOf<String?>(null) }
    var permissionRadarOrigin by remember { mutableStateOf<String?>(null) }
    var filterStudioVisible by rememberSaveable { mutableStateOf(false) }
    var filterStudioSelectedRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerStudioSession by remember { mutableStateOf<ReaderStudioSession?>(null) }
    var readerStudioResult by remember { mutableStateOf<ReaderExtractionResult?>(null) }
    var readerStudioRequestId by remember { mutableIntStateOf(0) }
    var clearDialogVisible by remember { mutableStateOf(false) }
    var pendingCapsuleDelete by remember { mutableStateOf<SiteCapsule?>(null) }
    var addressValue by remember {
        val initialAddress = controller.selectedTab.url
            .takeUnless { it == BLANK_URL }
            .orEmpty()
        mutableStateOf(
            if (openAddressEditorOnLaunch) {
                TextFieldValue(
                    text = initialAddress,
                    selection = TextRange(initialAddress.length, 0),
                )
            } else {
                TextFieldValue()
            },
        )
    }
    var remoteSearchSuggestions by remember { mutableStateOf(emptyList<String>()) }
    var localRecallMatches by remember { mutableStateOf(emptyList<RecallMatch>()) }
    val searchSuggestionClient = remember { SearchSuggestionClient() }
    var highlightedSuggestionIndex by remember { mutableIntStateOf(-1) }
    var addressFocusNonce by remember { mutableIntStateOf(0) }
    var addressEditorOpenGeneration by remember { mutableIntStateOf(0) }
    var pendingCommand by remember { mutableStateOf<CommandSuggestion?>(null) }
    val overviewGestureProgress = remember { mutableFloatStateOf(0f) }
    val overviewMorphProgress = remember { mutableFloatStateOf(0f) }
    var overviewGestureSettleJob by remember { mutableStateOf<Job?>(null) }
    var overviewMorphJob by remember { mutableStateOf<Job?>(null) }
    var overviewEntryHeroCompleted by remember { mutableStateOf(false) }
    var overviewExitHeroVisible by remember { mutableStateOf(false) }
    val overviewGestureScope = rememberCoroutineScope()
    var favoriteFeedbackId by remember { mutableIntStateOf(0) }
    var favoriteFeedbackEvent by remember { mutableStateOf<FavoriteFeedbackEvent?>(null) }
    var feedbackSnackbarJob by remember { mutableStateOf<Job?>(null) }
    val feedbackSnackbarHostState = remember { SnackbarHostState() }
    var activeCommandExecutionId by remember { mutableStateOf<String?>(null) }
    var commandFeedback by remember { mutableStateOf<AddressCommandFeedback?>(null) }
    val browserDragOffset = remember { mutableFloatStateOf(0f) }
    var browserWidthPx by remember { mutableFloatStateOf(1f) }
    var browserHeightPx by remember { mutableFloatStateOf(1f) }
    var browserRootBottomInWindowPx by remember { mutableIntStateOf(0) }
    val bottomBarTopPx = remember { mutableFloatStateOf(Float.NaN) }
    var addressNewTabButtonBounds by remember { mutableStateOf<Rect?>(null) }
    var keepLinkPeekAddressBarExpanded by remember { mutableStateOf(false) }
    var tabOverviewOpening by remember { mutableStateOf(false) }
    var tabHandoff by remember { mutableStateOf<TabHandoff?>(null) }
    var browserContentBlurTarget by remember { mutableStateOf<BlurTarget?>(null) }
    val liveFrameTabIdState = remember { mutableStateOf<String?>(null) }
    var liveFrameTabId by liveFrameTabIdState
    val reportLiveFrame = remember { { tabId: String -> liveFrameTabIdState.value = tabId } }
    val tabHandoffAlpha = rememberTabHandoffAlpha(tabHandoff)
    val settingsBackProgress = remember { Animatable(0f) }
    val candyTrailBackProgress = remember { Animatable(0f) }
    var settingsPredictiveBackCommitted by remember { mutableStateOf(false) }
    var candyTrailPredictiveBackCommitted by remember { mutableStateOf(false) }
    val backAnimationScope = rememberCoroutineScope()
    var settingsBackEdgeSign by remember { mutableIntStateOf(1) }
    var candyTrailBackEdgeSign by remember { mutableIntStateOf(1) }
    val overviewDestinationChromeVisible by remember {
        derivedStateOf {
            overviewEntryHeroCompleted &&
                AddressBarOverviewGestureRules.isDestinationButtonVisible(
                    overviewMorphProgress.floatValue,
                )
        }
    }
    val addressBarMorphInFront = AddressBarOverviewGestureRules.isMorphInFront(
        tabOverviewVisible = tabOverviewVisible,
        destinationChromeVisible = overviewDestinationChromeVisible,
        exitHeroVisible = overviewExitHeroVisible,
    )
    val selectedTab = controller.selectedTab
    val statusBarScrollPageVisible = !settingsVisible && !tabOverviewVisible &&
        !addressEditorVisible && !hideBrowserChrome
    LaunchedEffect(
        controller,
        selectedTab.id,
        selectedTab.url,
        controller.isStatusBarScrollEnabled,
        controller.isSelectedStatusBarScrollEnabled,
        statusBarScrollPageVisible,
    ) {
        controller.publishStatusBarScrollPageContext(statusBarScrollPageVisible)
    }
    val activeLocalProfile = controller.localBrowserProfiles
        .firstOrNull { profile -> profile.id == controller.activeProfileId }
    val profileWallpaperRuntime = activeLocalProfile
        ?.newTabWallpaper
        ?.let { wallpaper ->
            controller.activeProfileWallpaperBitmap
                ?.takeIf { bitmap -> !bitmap.isRecycled }
                ?.asImageBitmap()
                ?.let { bitmap -> ProfileWallpaperRuntime(bitmap, wallpaper) }
        }
    val tabSwitcherWallpaperRuntime = activeLocalProfile
        ?.tabSwitcherWallpaper
        ?.let { wallpaper ->
            controller.activeProfileTabSwitcherWallpaperBitmap
                ?.takeIf { bitmap -> !bitmap.isRecycled }
                ?.asImageBitmap()
                ?.let { bitmap -> ProfileWallpaperRuntime(bitmap, wallpaper) }
        }
    val visibleProfiles = if (controller.profilesEnabled) {
        controller.localBrowserProfiles
    } else {
        controller.localBrowserProfiles.take(1)
    }
    val visibleProfileIds = visibleProfiles.mapTo(hashSetOf(), BrowserProfile::id)
    val visibleSnoozedTabs = controller.snoozedTabs.filter {
        it.tab.profileId in visibleProfileIds && it.tab.profileId !in controller.lockedProfileIds
    }
    LaunchedEffect(
        controller.contentActions.isLinkPeekVisible,
        controller.contentActions.linkPeekNewTabPulseNonce,
    ) {
        if (controller.contentActions.isLinkPeekVisible) {
            keepLinkPeekAddressBarExpanded = true
        } else if (keepLinkPeekAddressBarExpanded) {
            delay(620)
            keepLinkPeekAddressBarExpanded = false
        }
    }
    val linkPeekAddressBarExpanded = controller.contentActions.isLinkPeekVisible ||
        keepLinkPeekAddressBarExpanded
    val blankTabModeProgress = rememberBlankTabModeProgress(
        tabId = selectedTab.id,
        incognito = selectedTab.isIncognito,
    )
    var blankTabModeRevealOrigin by remember(selectedTab.id) {
        mutableStateOf(Offset.Unspecified)
    }
    val context = LocalContext.current
    val readerLibraryRepository = remember(context) { ReaderLibraryRepository.get(context) }
    val accessibilityManager = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)
    }
    val density = LocalDensity.current
    val rootView = LocalView.current

    var pendingProfileConfiguration by rememberSaveable(
        stateSaver = PendingProfileConfigurationSaver,
    ) {
        mutableStateOf<PendingProfileConfiguration?>(null)
    }
    val profileWallpaperEditorLauncher = rememberLauncherForActivityResult(
        contract = ProfileWallpaperEditorContract(),
    ) { submission ->
        val pending = pendingProfileConfiguration
        val activeTarget = pending?.activeWallpaperTarget
        val submissionMatches = submission == null ||
            pending != null &&
            activeTarget != null &&
            submission.profileId == pending.profileId &&
            submission.target == activeTarget
        if (!submissionMatches) {
            pendingProfileConfiguration = null
            controller.restoreActiveProfileWallpapersAfterEditing()
            Toast.makeText(
                context,
                R.string.profile_wallpaper_save_failed,
                Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        if (submission != null) {
            controller.updateProfileWallpaper(
                submission.profileId,
                submission.target,
                submission.wallpaper,
            )
        }
        pendingProfileConfiguration = pending?.copy(activeWallpaperTarget = null)
    }
    LaunchedEffect(pendingProfileConfiguration) {
        val pending = pendingProfileConfiguration ?: return@LaunchedEffect
        if (pending.activeWallpaperTarget != null) return@LaunchedEffect
        val wallpaperTarget = pending.remainingWallpaperTargets.firstOrNull()
        if (wallpaperTarget != null) {
            val profile = controller.localBrowserProfiles
                .firstOrNull { profile -> profile.id == pending.profileId }
            if (profile == null) {
                pendingProfileConfiguration = null
                controller.restoreActiveProfileWallpapersAfterEditing()
                return@LaunchedEffect
            }
            pendingProfileConfiguration = pending.copy(
                remainingWallpaperTargets = pending.remainingWallpaperTargets.drop(1),
                activeWallpaperTarget = wallpaperTarget,
            )
            profileWallpaperEditorLauncher.launch(
                ProfileWallpaperEditorRequest(
                    profileId = profile.id,
                    target = wallpaperTarget,
                    wallpaper = profile.wallpaperFor(wallpaperTarget),
                ),
            )
            return@LaunchedEffect
        }
        pendingProfileConfiguration = null
        controller.restoreActiveProfileWallpapersAfterEditing()
        pending.protection?.let { protection ->
            controller.updateProfileProtection(pending.profileId, protection) { changed ->
                if (changed) {
                    rootView.performConfirmHaptic()
                } else {
                    Toast.makeText(
                        context,
                        R.string.profile_creation_protection_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
    fun configureProfile(
        profileId: String,
        wallpaperTargets: List<ProfileWallpaperTarget>,
        protection: ProfileProtection? = null,
    ) {
        if (controller.localBrowserProfiles.none { it.id == profileId }) return
        if (pendingProfileConfiguration != null) return
        val orderedTargets = ProfileWallpaperTarget.entries.filter(wallpaperTargets::contains)
        if (orderedTargets.isEmpty() && protection == null) return
        if (orderedTargets.isNotEmpty()) {
            controller.releaseActiveProfileWallpaperForEditing(profileId)
        }
        pendingProfileConfiguration = PendingProfileConfiguration(
            profileId = profileId,
            remainingWallpaperTargets = orderedTargets,
            protection = protection,
        )
    }
    fun openProfileWallpaperEditor(profileId: String, wallpaperTarget: ProfileWallpaperTarget) {
        // Debloated
    }
    fun openSiteCapsuleEditor(existing: SiteCapsule?, sourceTab: BrowserTab?) {
        // Debloated
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val addressTabImeHandoff = rememberBrowserImeHandoff()
    val favoriteAddedMessage = stringResource(R.string.favorite_added_confirmation)
    val favoriteRemovedMessage = stringResource(R.string.favorite_removed_confirmation)
    val snoozeConfirmationMessage = stringResource(R.string.snooze_confirmation)
    val undoLabel = stringResource(R.string.action_undo)
    val showFavoriteMutation: (FavoriteMutation) -> Unit = { mutation ->
        rootView.performConfirmHaptic()
        favoriteFeedbackId++
        favoriteFeedbackEvent = FavoriteFeedbackEvent(
            id = favoriteFeedbackId,
            added = mutation.added,
        )
        feedbackSnackbarJob?.cancel()
        feedbackSnackbarJob = backAnimationScope.launch {
            val result = feedbackSnackbarHostState.showSnackbar(
                message = if (mutation.added) {
                    favoriteAddedMessage
                } else {
                    favoriteRemovedMessage
                },
                actionLabel = undoLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                controller.undoFavorite(mutation)
            }
        }
    }
    val toggleFavoriteWithFeedback: (String) -> Unit = { tabId ->
        controller.toggleFavorite(tabId)?.let(showFavoriteMutation)
    }
    BrowserOfferSnackbarEffects(controller, feedbackSnackbarHostState)
    ClosedTabUndoSnackbarEffect(controller, feedbackSnackbarHostState)
    val tabSwitchGapPx = with(density) { 8.dp.toPx() }
    val tabSwitchTravelPx = browserWidthPx + tabSwitchGapPx
    val settleOverviewGesture: () -> Unit = {
        overviewGestureSettleJob?.cancel()
        overviewGestureSettleJob = overviewGestureScope.launch {
            val settleProgress = Animatable(overviewGestureProgress.floatValue)
            settleProgress.updateBounds(lowerBound = 0f, upperBound = 1f)
            settleProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
            ) { overviewGestureProgress.floatValue = value }
        }
    }
    val openTabOverview = {
        if (!tabOverviewVisible && !tabOverviewOpening) {
            overviewMorphJob?.cancel()
            overviewMorphProgress.floatValue = 0f
            overviewEntryHeroCompleted = false
            overviewExitHeroVisible = false
            tabOverviewOpening = true
            controller.loadActiveProfileTabSwitcherWallpaper wallpaperReady@{
                if (!tabOverviewOpening) return@wallpaperReady
                controller.prepareTabOverview prepared@{
                    if (!tabOverviewOpening) return@prepared
                    tabOverviewOpening = false
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        tabOverviewVisible = true
                    }
                }
            }
        }
    }
    val closeTabOverview = {
        overviewGestureSettleJob?.cancel()
        overviewMorphJob?.cancel()
        overviewGestureProgress.floatValue = 0f
        overviewMorphProgress.floatValue = 0f
        overviewEntryHeroCompleted = false
        overviewExitHeroVisible = false
        tabOverviewOpening = false
        tabOverviewVisible = false
        controller.releaseActiveProfileTabSwitcherWallpaper()
    }
    LaunchedEffect(incomingBrowserNavigationRequestId) {
        if (incomingBrowserNavigationRequestId != 0) {
            closeTabOverview()
            settingsVisible = false
            addressEditorOpenGeneration++
            addressEditorVisible = false
            highlightedSuggestionIndex = -1
            activeCommandExecutionId = null
            pendingCommand = null
            commandFeedback = null
            readerStudioSession = null
            readerStudioRequestId++
            candyTrailTabId = null
            candyTrailSourceBounds = null
            privacyXRayTabId = null
            permissionRadarTabId = null
            permissionRadarOrigin = null
            snoozedTabsVisible = false
            filterStudioVisible = false
        }
    }
    val openAddressEditor: () -> Unit = {
        if (activeCommandExecutionId == null) {
            controller.closeFindInPage()
            addressEditorOpenGeneration++
            val initialAddress = controller.selectedTab.url
                .takeUnless { it == BLANK_URL }
                .orEmpty()
            addressValue = TextFieldValue(
                text = initialAddress,
                selection = TextRange(initialAddress.length, 0),
            )
            addressEditorVisible = true
            highlightedSuggestionIndex = -1
            addressFocusNonce++
        }
    }
    LaunchedEffect(launcherAddressEditorRequestId) {
        if (launcherAddressEditorRequestId > 0) {
            closeTabOverview()
            settingsVisible = false
            openAddressEditor()
        }
    }
    LaunchedEffect(hardwareTabChangeRequestId) {
        if (hardwareTabChangeRequestId > 0) {
            addressEditorOpenGeneration++
            addressEditorVisible = false
            highlightedSuggestionIndex = -1
            pendingCommand = null
            commandFeedback = null
        }
    }
    fun createTabAndConfirm(isIncognito: Boolean, emitHaptic: Boolean): Boolean {
        val previousTabId = controller.selectedTabId
        val createdTabId = controller.createTab(isIncognito = isIncognito)
        if (createdTabId == previousTabId) return false
        if (emitHaptic) rootView.performConfirmHaptic()
        return true
    }
    fun openNewTabAndEdit(isIncognito: Boolean = false) {
        val openGeneration = ++addressEditorOpenGeneration
        val requestedTabId = controller.selectedTabId
        val createAndEdit = {
            if (
                openGeneration == addressEditorOpenGeneration &&
                requestedTabId == controller.selectedTabId &&
                createTabAndConfirm(isIncognito = isIncognito, emitHaptic = true)
            ) {
                addressValue = TextFieldValue()
                addressEditorVisible = true
                highlightedSuggestionIndex = -1
                addressFocusNonce++
            }
        }
        if (addressEditorVisible || tabOverviewVisible) {
            createAndEdit()
        } else {
            controller.refreshSelectedTabPreview(createAndEdit)
        }
    }
    fun openReaderStudio() {
        readerStudioResult = null
        val requestId = ++readerStudioRequestId
        readerStudioSession = ReaderStudioSession(
            tabId = selectedTab.id,
            sourceUrl = selectedTab.url,
            isPrivate = selectedTab.isIncognito,
            requestId = requestId,
        )
        controller.extractSelectedPageForReader { result ->
            if (ReaderStudioSessionRules.acceptsResult(readerStudioSession, requestId)) {
                readerStudioResult = result
            }
        }
    }
    fun performAddressBarLongPress(action: AddressBarLongPressAction) {
        when (action) {
            AddressBarLongPressAction.OpenReader -> openReaderStudio()
            AddressBarLongPressAction.SaveReaderOffline -> {
                controller.saveSelectedPageToReader { result ->
                    Toast.makeText(
                        context,
                        result.readerActionMessageRes(),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            AddressBarLongPressAction.FindInPage -> controller.openFindInPage()
            AddressBarLongPressAction.TranslatePage -> controller.translateSelectedPage()
            AddressBarLongPressAction.ToggleDesktopView ->
                controller.setSelectedDesktopView(!controller.isSelectedDesktopView)
            AddressBarLongPressAction.CopyUrl -> controller.copyLink(selectedTab.url)
            AddressBarLongPressAction.ShareUrl -> controller.shareSelectedPage()
            AddressBarLongPressAction.Print -> controller.printSelectedPage()
            AddressBarLongPressAction.SendToAssistant ->
                controller.summarizeSelectedPageWithAssistant()
            AddressBarLongPressAction.ToggleFavorite ->
                toggleFavoriteWithFeedback(selectedTab.id)
            AddressBarLongPressAction.TogglePinned -> {
                if (controller.setTabPinned(selectedTab.id, !selectedTab.isPinned)) {
                    rootView.performConfirmHaptic()
                }
            }
            AddressBarLongPressAction.Reload -> controller.reload()
            AddressBarLongPressAction.GoBack -> controller.goBack()
            AddressBarLongPressAction.DuplicateTab -> controller.duplicateSelectedTab()
            AddressBarLongPressAction.SnoozeTab -> snoozeTabId = selectedTab.id
            AddressBarLongPressAction.MoveToProfile -> moveTabToProfileId = selectedTab.id
            AddressBarLongPressAction.NewTab -> openNewTabAndEdit()
            AddressBarLongPressAction.NewPrivateTab -> openNewTabAndEdit(isIncognito = true)
            AddressBarLongPressAction.OpenHistory -> onOpenHistory()
            AddressBarLongPressAction.ParkAddressBar -> controller.parkAddressBarOnRight()
            AddressBarLongPressAction.OpenCandyTrail -> {
                candyTrailSourceBounds = null
                candyTrailTabId = selectedTab.id
                rootView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            AddressBarLongPressAction.CreateSiteCapsule ->
                openSiteCapsuleEditor(existing = null, sourceTab = selectedTab)
        }
    }
    val activeSearchShortcut = SearchShortcutRules.match(addressValue.text, controller.searchShortcuts)
    val addressSuggestionQuery = activeSearchShortcut?.query ?: addressValue.text
    LaunchedEffect(
        addressEditorVisible,
        addressSuggestionQuery,
        controller.searchSuggestionProvider,
        controller.searxngSettings,
        selectedTab.id,
        selectedTab.isIncognito,
    ) {
        remoteSearchSuggestions = emptyList()
        if (
            !addressEditorVisible ||
            !SearchSuggestionRules.shouldRequest(
                query = addressSuggestionQuery,
                provider = controller.searchSuggestionProvider,
                isIncognito = selectedTab.isIncognito,
                searxngInstanceUrl = controller.searxngSettings.instanceUrl,
                allowGeckoInternalPages = controller.usesGeckoEngine,
            )
        ) {
            return@LaunchedEffect
        }
        delay(SearchSuggestionRules.DEBOUNCE_MILLIS)
        remoteSearchSuggestions = searchSuggestionClient.suggestions(
            provider = controller.searchSuggestionProvider,
            query = addressSuggestionQuery.trim(),
            searxngSettings = controller.searxngSettings,
        )
    }
    LaunchedEffect(
        addressEditorVisible,
        addressValue.text,
        controller.searchShortcuts,
        selectedTab.id,
        selectedTab.profileId,
        selectedTab.isIncognito,
        controller.isRecallEnabled,
        controller.isHistorySuggestionsEnabled,
    ) {
        localRecallMatches = emptyList()
        if (!addressEditorVisible || activeSearchShortcut != null) return@LaunchedEffect
        if (addressValue.text.isNotEmpty()) {
            delay(50)
        }
        localRecallMatches = suspendCancellableCoroutine { continuation ->
            controller.searchRecallForAddress(addressValue.text) { matches ->
                if (continuation.isActive) continuation.resume(matches)
            }
        }
    }
    var suggestionItems by remember { mutableStateOf<List<AddressSuggestionItem>>(emptyList()) }
    LaunchedEffect(
        addressEditorVisible,
        addressValue.text,
        remoteSearchSuggestions,
        localRecallMatches,
    ) {
        if (!addressEditorVisible) {
            suggestionItems = emptyList()
            return@LaunchedEffect
        }
        suggestionItems = withContext(Dispatchers.Default) {
            controller.addressSuggestionItems(
                query = addressValue.text,
                searchQueries = remoteSearchSuggestions,
                recallMatches = localRecallMatches,
                limit = 10,
            )
        }
    }
    var domainCompletion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(
        addressEditorVisible,
        addressValue.text,
        addressValue.selection,
    ) {
        if (
            addressEditorVisible &&
            activeSearchShortcut == null &&
            addressValue.selection.start == addressValue.selection.end &&
            addressValue.selection.end == addressValue.text.length
        ) {
            domainCompletion = withContext(Dispatchers.Default) {
                controller.addressDomainCompletion(addressValue.text)
            }
        } else {
            domainCompletion = null
        }
    }
    val showAiModeToggle = addressEditorVisible && activeSearchShortcut == null && AddressAiModeRules.isToggleVisible(
        input = addressValue.text,
        searchEngine = controller.searchEngine,
        settingEnabled = controller.isAiModeToggleVisible,
        allowGeckoInternalPages = controller.usesGeckoEngine,
    )
    LaunchedEffect(addressEditorVisible, selectedTab.id, showAiModeToggle) {
        if (!addressEditorVisible || !showAiModeToggle) {
            aiModeSelectedState.value = false
        }
    }
    val commandActions = object : CommandActions {
        override fun clearCacheAndReload(onComplete: (Boolean) -> Unit): Boolean =
            controller.clearCacheAndReload(onComplete)
        override fun clearCookiesAndReload(onComplete: (Boolean) -> Unit): Boolean =
            controller.clearCookiesAndReload(onComplete)
        override fun clearSiteDataAndReload(
            target: SiteDataTarget,
            onComplete: (Boolean) -> Unit,
        ): Boolean = controller.clearSiteDataAndReload(target, onComplete)
        override fun reload(): Boolean {
            if (controller.selectedTab.url == BLANK_URL || controller.selectedTab.isLoading) return false
            controller.reload()
            return true
        }
        override fun stopLoading(): Boolean {
            if (!controller.selectedTab.isLoading) return false
            controller.stopLoading()
            return true
        }
        override fun setSelectedTabPinned(isPinned: Boolean): Boolean =
            controller.setTabPinned(controller.selectedTabId, isPinned)
        override fun closeDuplicateTabs(confirmedTabIds: List<String>): Int =
            controller.closeDuplicateTabs(confirmedTabIds)
        override fun moveSelectedTabToProfile(profileId: String): Boolean =
            controller.moveTabToProfile(controller.selectedTabId, profileId)
        override fun switchProfile(
            profileId: String,
            onComplete: (Boolean) -> Unit,
        ): Boolean = controller.requestProfileSelection(profileId, onComplete)
        override fun createTab(isIncognito: Boolean): Boolean = createTabAndConfirm(
            isIncognito = isIncognito,
            emitHaptic = false,
        )
        override fun openSettings(): Boolean = true
    }

    fun handleCommandOutcome(
        command: BrowserCommand,
        outcome: CommandDispatchOutcome,
    ): Boolean = when (outcome) {
        is CommandDispatchOutcome.Pending -> true
        is CommandDispatchOutcome.Rejected -> {
            commandFeedback = checkNotNull(AddressCommandFeedbackRules.from(outcome))
            addressEditorVisible = true
            rootView.performRejectHaptic()
            false
        }
        is CommandDispatchOutcome.Succeeded -> {
            commandFeedback = checkNotNull(AddressCommandFeedbackRules.from(outcome))
            when (command.kind) {
                BrowserCommandKind.NewRegularTab,
                BrowserCommandKind.NewIncognitoTab,
                -> {
                    addressValue = TextFieldValue()
                    highlightedSuggestionIndex = -1
                    addressEditorVisible = true
                }
                BrowserCommandKind.OpenSettings -> {
                    addressEditorVisible = false
                    settingsDestination = SettingsDestination.Home
                    settingsVisible = true
                }
                else -> addressEditorVisible = false
            }
            rootView.performConfirmHaptic()
            true
        }
    }

    fun runCommand(command: BrowserCommand): Boolean {
        if (activeCommandExecutionId != null) return false
        activeCommandExecutionId = command.executionId
        val outcome = CommandDispatcher.dispatch(
            command = command,
            actions = commandActions,
            onPendingOutcome = { completedOutcome ->
                if (activeCommandExecutionId == command.executionId) {
                    activeCommandExecutionId = null
                    handleCommandOutcome(command, completedOutcome)
                }
            },
        )
        if (outcome !is CommandDispatchOutcome.Pending) {
            activeCommandExecutionId = null
        } else if (activeCommandExecutionId == command.executionId) {
            addressEditorVisible = false
        }
        return handleCommandOutcome(command, outcome)
    }

    fun selectCommand(suggestion: CommandSuggestion): Unit {
        if (suggestion.command.confirmation == CommandConfirmation.None) {
            runCommand(suggestion.command)
        } else {
            pendingCommand = suggestion
            keyboard?.hide()
        }
    }

    fun searchModeFor(input: String) = AddressAiModeRules.searchMode(
        toggleVisible = AddressAiModeRules.isToggleVisible(
            input = input,
            searchEngine = controller.searchEngine,
            settingEnabled = controller.isAiModeToggleVisible,
            allowGeckoInternalPages = controller.usesGeckoEngine,
        ),
        toggleSelected = aiModeSelectedState.value,
    )

    fun selectNavigation(suggestion: AddressSuggestion): Unit {
        val target = suggestion.openTabId
            ?.let { tabId -> controller.activeTabs.firstOrNull { it.id == tabId } }
        addressTabImeHandoff.cancel()
        val selectionGeneration = ++addressEditorOpenGeneration
        addressEditorVisible = false
        if (target == null) {
            rootView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            controller.submitAddress(suggestion.url)
        } else {
            val sourceTabId = controller.selectedTabId
            val sourceProfileId = controller.activeProfileId
            val sourceIsIncognito = controller.selectedTab.isIncognito
            val selectTarget = {
                val currentTarget = controller.activeTabs.firstOrNull { it.id == target.id }
                val targetHandoff = currentTarget?.let { currentTab ->
                    TabHandoff(
                        tab = currentTab,
                        preview = controller.previews[currentTab.id].takeUnless { currentTab.isIncognito },
                        favicon = controller.favicons[currentTab.id],
                        previewTopInsetPx = controller.previewTopInsetPx(currentTab.id),
                        isRestoring = TabHandoffRules.isRestoring(
                            currentTab,
                            controller.isTabSessionResident(currentTab.id),
                        ),
                    )
                }
                if (targetHandoff != null && controller.switchToOpenTab(target.id)) {
                    liveFrameTabId = null
                    tabHandoff = targetHandoff
                    backAnimationScope.launch { tabHandoffAlpha.snapTo(1f) }
                    rootView.performConfirmHaptic()
                } else {
                    controller.submitAddress(suggestion.url)
                }
            }
            if (controller.usesGeckoEngine) {
                keyboard?.hide()
                addressTabImeHandoff.run(
                    isCurrent = {
                        selectionGeneration == addressEditorOpenGeneration &&
                            sourceTabId == controller.selectedTabId &&
                            sourceProfileId == controller.activeProfileId &&
                            sourceIsIncognito == controller.selectedTab.isIncognito &&
                            !addressEditorVisible && !tabOverviewVisible &&
                            !tabOverviewOpening && !settingsVisible
                    },
                    onReady = selectTarget,
                )
            } else {
                selectTarget()
            }
        }
    }

    fun selectSuggestion(item: AddressSuggestionItem): Unit {
        when (item) {
            is AddressSuggestionItem.Navigation -> selectNavigation(item.suggestion)
            is AddressSuggestionItem.Command -> selectCommand(item.suggestion)
            is AddressSuggestionItem.Search -> {
                rootView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val input = AddressSearchShortcutRules.fill(addressValue.text, item.query, controller.searchShortcuts)
                controller.submitAddress(input, searchModeFor(input))
                addressEditorVisible = false
            }
            is AddressSuggestionItem.Recall -> selectNavigation(
                AddressSuggestion(
                    url = item.match.url,
                    title = item.match.title,
                ),
            )
        }
    }

    fun fillAddressFromSuggestion(item: AddressSuggestionItem): Unit {
        val text = when (item) {
            is AddressSuggestionItem.Navigation -> item.suggestion.url
            is AddressSuggestionItem.Search -> AddressSearchShortcutRules.fill(
                addressValue.text,
                item.query,
                controller.searchShortcuts,
            )
            is AddressSuggestionItem.Recall -> item.match.url
            is AddressSuggestionItem.Command -> return
        }
        addressValue = TextFieldValue(text = text, selection = TextRange(text.length))
        highlightedSuggestionIndex = -1
        addressFocusNonce++
        rootView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun submitAddressOrCommand(input: String): Unit {
        if (SearchShortcutRules.match(input, controller.searchShortcuts)?.query?.isBlank() == true) return
        when (
            val submission = AddressSubmissionRules.resolve(
                input = input,
                suggestions = suggestionItems,
                highlightedIndex = highlightedSuggestionIndex,
            )
        ) {
            is AddressSubmission.Select -> selectSuggestion(submission.suggestion)
            is AddressSubmission.Navigate -> {
                controller.submitAddress(submission.input, searchModeFor(submission.input))
                addressEditorVisible = false
            }
            AddressSubmission.None -> Unit
        }
    }

    fun moveSuggestionHighlight(delta: Int): Unit {
        if (suggestionItems.isEmpty()) return
        highlightedSuggestionIndex = when {
            highlightedSuggestionIndex < 0 && delta > 0 -> 0
            highlightedSuggestionIndex < 0 -> suggestionItems.lastIndex
            else -> (highlightedSuggestionIndex + delta).coerceIn(-1, suggestionItems.lastIndex)
        }
    }

    LaunchedEffect(addressValue.text) {
        highlightedSuggestionIndex = if (
            CommandMatcher.isExplicitCommandQuery(addressValue.text)
        ) {
            0
        } else {
            -1
        }
    }

    LaunchedEffect(commandFeedback) {
        val shownFeedback = commandFeedback ?: return@LaunchedEffect
        val baseDuration = AddressCommandFeedbackRules.displayDurationMillis(shownFeedback)
        val recommendedDuration = accessibilityManager?.getRecommendedTimeoutMillis(
            baseDuration.toInt(),
            AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS,
        )?.toLong() ?: baseDuration
        delay(
            AddressCommandFeedbackRules.accessibleDurationMillis(
                feedback = shownFeedback,
                recommendedTimeoutMillis = recommendedDuration,
            ),
        )
        if (commandFeedback == shownFeedback) {
            commandFeedback = null
            if (addressEditorVisible) addressFocusNonce++
        }
    }

    LaunchedEffect(
        tabHandoff?.visualIdentity,
        liveFrameTabId,
        tabOverviewVisible,
        selectedTab.id,
        selectedTab.url,
    ) {
        val handoff = tabHandoff ?: return@LaunchedEffect
        if (handoff.tabId != selectedTab.id) {
            tabHandoffAlpha.snapTo(1f)
            tabHandoff = null
            return@LaunchedEffect
        }
        if (!TabHandoffRules.shouldRevealLiveContent(
                handoff = handoff,
                tabOverviewVisible = tabOverviewVisible,
                liveFrameTabId = liveFrameTabId,
            )
        ) {
            return@LaunchedEffect
        }
        if (handoff.tab.url == BLANK_URL) {
            withFrameNanos { }
        }
        tabHandoffAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = TabHandoffRules.revealDurationMillis(handoff),
                easing = FastOutSlowInEasing,
            ),
        )
        if (tabHandoff === handoff) tabHandoff = null
    }

    LaunchedEffect(selectedTab.id, selectedTab.error) {
        if (selectedTab.error != null && tabHandoff?.tabId == selectedTab.id) {
            tabHandoff = null
        }
    }

    LaunchedEffect(tabHandoff?.visualIdentity) {
        val handoff = tabHandoff ?: return@LaunchedEffect
        delay(600)
        if (tabHandoff === handoff) {
            tabHandoffAlpha.animateTo(0f, tween(150))
            tabHandoff = null
        }
    }

    LaunchedEffect(selectedTab.id, readerStudioSession) {
        if (ReaderStudioSessionRules.shouldClose(readerStudioSession, selectedTab.id)) {
            readerStudioSession = null
            readerStudioRequestId++
        }
    }

    LaunchedEffect(controller.tabs.size, privacyXRayTabId) {
        val xRayTabId = privacyXRayTabId ?: return@LaunchedEffect
        if (controller.tabs.none { it.id == xRayTabId }) privacyXRayTabId = null
    }
    LaunchedEffect(controller.tabs.size, permissionRadarTabId) {
        val radarTabId = permissionRadarTabId ?: return@LaunchedEffect
        if (controller.tabs.none { it.id == radarTabId }) {
            permissionRadarTabId = null
            permissionRadarOrigin = null
        }
    }
    LaunchedEffect(settingsVisible, settingsDestination) {
        if (settingsVisible && settingsDestination == SettingsDestination.ToppingCatalog) {
            controller.refreshToppingCatalog()
        }
    }

    LaunchedEffect(
        addressEditorVisible,
        tabOverviewVisible,
        settingsVisible,
        readerStudioSession,
    ) {
        if (
            addressEditorVisible ||
            tabOverviewVisible ||
            settingsVisible ||
            readerStudioSession != null
        ) {
            controller.closeFindInPage()
        }
    }


    val currentBackTarget by rememberUpdatedState(
        when {
            readerStudioSession != null -> BrowserBackTarget.ReaderStudio
            filterStudioVisible -> BrowserBackTarget.FilterStudio
            snoozedTabsVisible -> BrowserBackTarget.SnoozedTabs
            settingsVisible && settingsDestination != SettingsDestination.Home ->
                BrowserBackTarget.SettingsSubpage
            settingsVisible -> BrowserBackTarget.Settings
            addressEditorVisible || addressTabImeHandoff.isPending -> BrowserBackTarget.AddressEditor
            controller.findInPageState != null -> BrowserBackTarget.FindInPage
            candyTrailTabId != null -> BrowserBackTarget.CandyTrail
            tabOverviewVisible || tabOverviewOpening -> BrowserBackTarget.TabOverview
            controller.isSelectedWebContentFullscreen -> BrowserBackTarget.WebContentFullscreen
            selectedTab.canGoBack -> BrowserBackTarget.WebHistory
            selectedTab.id == externalLaunchTabId -> BrowserBackTarget.ExternalApp
            controller.selectedRootTabBackDecision ==
                RootTabBackDecision.DelegateToSystem -> BrowserBackTarget.System
            else -> BrowserBackTarget.RootTab
        },
    )
    PredictiveBackHandler(enabled = currentBackTarget != BrowserBackTarget.System) { events ->
        controller.cancelSelectedBrowserEngineTouch()
        val target = currentBackTarget
        var receivedProgress = false
        try {
            events.collect { event ->
                if (target == BrowserBackTarget.Settings) {
                    receivedProgress = true
                    settingsBackEdgeSign = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1 else -1
                    settingsBackProgress.snapTo(event.progress.coerceIn(0f, 1f))
                } else if (target == BrowserBackTarget.CandyTrail) {
                    receivedProgress = true
                    candyTrailBackEdgeSign =
                        if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1 else -1
                    candyTrailBackProgress.snapTo(event.progress.coerceIn(0f, 1f))
                }
            }
            when (target) {
                BrowserBackTarget.ReaderStudio -> readerStudioSession = null
                BrowserBackTarget.FilterStudio -> filterStudioVisible = false
                BrowserBackTarget.SnoozedTabs -> snoozedTabsVisible = false
                BrowserBackTarget.SettingsSubpage -> {
                    settingsDestination = when (settingsDestination) {
                        SettingsDestination.ToppingCatalog -> SettingsDestination.Userscripts
                        SettingsDestination.AddressBarLongPressActions,
                        SettingsDestination.AddressBarActions ->
                            SettingsDestination.TabsAndGestures
                        SettingsDestination.MenuActions ->
                            SettingsDestination.TabsAndGestures
                        SettingsDestination.LinkPeekActions ->
                            SettingsDestination.TabsAndGestures
                        else -> SettingsDestination.Home
                    }
                }
                BrowserBackTarget.Settings -> {
                    settingsPredictiveBackCommitted = receivedProgress
                    if (receivedProgress) {
                        settingsBackProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = PredictiveBackMotion.remainingDurationMillis(
                                    settingsBackProgress.value,
                                ),
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    }
                    settingsVisible = false
                }
                BrowserBackTarget.AddressEditor -> {
                    addressEditorOpenGeneration++
                    addressTabImeHandoff.cancel()
                    addressEditorVisible = false
                }
                BrowserBackTarget.FindInPage -> controller.closeFindInPage()
                BrowserBackTarget.CandyTrail -> {
                    candyTrailPredictiveBackCommitted = receivedProgress
                    if (receivedProgress) {
                        candyTrailBackProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = PredictiveBackMotion.remainingDurationMillis(
                                    candyTrailBackProgress.value,
                                ),
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    }
                    candyTrailTabId = null
                    candyTrailSourceBounds = null
                }
                BrowserBackTarget.TabOverview -> closeTabOverview()
                BrowserBackTarget.WebContentFullscreen ->
                    controller.exitSelectedWebContentFullscreen()
                BrowserBackTarget.WebHistory -> controller.goBack()
                BrowserBackTarget.ExternalApp -> onReturnToExternalApp()
                BrowserBackTarget.RootTab -> {
                    if (
                        controller.performSelectedRootTabBack() ==
                        RootTabBackDecision.CloseAndShowTabOverview
                    ) {
                        openTabOverview()
                    }
                }
                BrowserBackTarget.System -> Unit
            }
        } catch (cancellation: CancellationException) {
            if (target == BrowserBackTarget.Settings) {
                settingsPredictiveBackCommitted = false
                backAnimationScope.launch {
                    settingsBackProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
                    )
                }
            } else if (target == BrowserBackTarget.CandyTrail) {
                candyTrailPredictiveBackCommitted = false
                backAnimationScope.launch {
                    candyTrailBackProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
                    )
                }
            }
            throw cancellation
        }
    }
    LaunchedEffect(settingsVisible) {
        if (settingsVisible) {
            settingsPredictiveBackCommitted = false
            if (settingsBackProgress.value > 0f) {
                settingsBackProgress.snapTo(0f)
            }
        } else if (!settingsVisible && settingsBackProgress.value > 0f) {
            delay(PredictiveBackMotion.EXIT_DURATION_MILLIS.toLong())
            settingsBackProgress.snapTo(0f)
            settingsPredictiveBackCommitted = false
        }
    }
    LaunchedEffect(candyTrailTabId, controller.activeTabs) {
        val trailTabId = candyTrailTabId
        if (trailTabId != null && controller.activeTabs.none { it.id == trailTabId }) {
            candyTrailTabId = null
            candyTrailSourceBounds = null
        }
    }
    LaunchedEffect(candyTrailTabId) {
        val trailTabId = candyTrailTabId
        if (trailTabId != null) {
            candyTrailPredictiveBackCommitted = false
            if (candyTrailBackProgress.value > 0f) {
                candyTrailBackProgress.snapTo(0f)
            }
        } else if (candyTrailBackProgress.value > 0f) {
            delay(PredictiveBackMotion.EXIT_DURATION_MILLIS.toLong())
            candyTrailBackProgress.snapTo(0f)
            candyTrailPredictiveBackCommitted = false
        }
    }
    LaunchedEffect(tabOverviewVisible) {
        currentTabOverviewPortraitLockChanged(tabOverviewVisible)
        if (!tabOverviewVisible) {
            overviewGestureProgress.floatValue = 0f
        }
    }

    BrowserImeOwnershipEffect(
        controller = controller,
        ownsIme = addressEditorVisible || controller.findInPageState != null,
    )
    val firefoxExtensionOptionsTitle = controller.selectedFirefoxExtensionOptionsTitle
    LaunchedEffect(selectedTab.id, firefoxExtensionOptionsTitle) {
        if (firefoxExtensionOptionsTitle != null) {
            settingsVisible = false
            addressEditorVisible = false
        }
    }
    val showFirefoxExtensionOptionsChrome =
        firefoxExtensionOptionsTitle != null && !hideBrowserChrome
    val showInteractiveBlankStart = addressEditorVisible &&
        selectedTab.url == BLANK_URL &&
        addressValue.text.isEmpty() &&
        !selectedTab.isIncognito &&
        controller.favorites.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                browserWidthPx = it.width.toFloat()
                browserHeightPx = it.height.toFloat()
            }
            .onGloballyPositioned { coordinates ->
                browserRootBottomInWindowPx = coordinates.boundsInWindow().bottom.roundToInt()
            }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        BrowserMainViewportContent(
            controller = controller,
            selectedTab = selectedTab,
            profileWallpaperRuntime = profileWallpaperRuntime,
            showFirefoxExtensionOptionsChrome = showFirefoxExtensionOptionsChrome,
            firefoxExtensionOptionsTitle = firefoxExtensionOptionsTitle,
            fullscreenVideoGestureState = fullscreenVideoGestureState,
            webViewVideoOnlyPresentation = webViewVideoOnlyPresentation,
            hideBrowserChrome = hideBrowserChrome,
            browserDragOffset = browserDragOffset,
            tabSwitchTravelPx = tabSwitchTravelPx,
            browserHeightPx = browserHeightPx,
            bottomBarTopPx = bottomBarTopPx,
            tabHandoff = tabHandoff,
            tabHandoffAlpha = tabHandoffAlpha.value,
            liveFrameTabId = liveFrameTabId,
            tabOverviewVisible = tabOverviewVisible,
            reportLiveFrame = reportLiveFrame,
            showInteractiveBlankStart = showInteractiveBlankStart,
            addressEditorVisible = addressEditorVisible,
            openAddressEditor = openAddressEditor,
            onOpenFavorites = onOpenFavorites,
            blankTabModeProgress = blankTabModeProgress,
            blankTabModeRevealOrigin = blankTabModeRevealOrigin,
            browserContentBlurTarget = browserContentBlurTarget,
            onBlurTargetAttached = { target -> browserContentBlurTarget = target },
            onBlurTargetReleased = { target ->
                if (browserContentBlurTarget === target) browserContentBlurTarget = null
            },
            settingsVisible = settingsVisible,
            readerStudioSession = readerStudioSession,
            onOpenMpvRx = onOpenMpvRx,
            onAddressEditorDismiss = { addressEditorVisible = false },
            onHandoffExpired = { expired ->
                if (tabHandoff === expired) tabHandoff = null
            },
        )


        controller.findInPageState
            ?.takeIf { firefoxExtensionOptionsTitle == null && !hideBrowserChrome }
            ?.let { findState ->
            val matchPosition = FindInPageRules.displayPosition(findState)
            FindInPageBar(
                query = findState.query,
                onQueryChange = controller::updateFindInPageQuery,
                matchText = stringResource(
                    R.string.find_in_page_match_count,
                    matchPosition.activeMatchNumber,
                    matchPosition.matchCount,
                ),
                isCounting = findState.query.isNotEmpty() && !findState.isDoneCounting,
                canNavigate = FindInPageRules.canNavigate(findState),
                focusNonce = 0,
                autoFocus = true,
                placeholder = stringResource(R.string.action_find_in_page),
                queryContentDescription = stringResource(R.string.cd_find_in_page_query),
                countingContentDescription = stringResource(R.string.cd_find_in_page_counting),
                previousMatchContentDescription = stringResource(
                    R.string.cd_find_in_page_previous,
                ),
                nextMatchContentDescription = stringResource(R.string.cd_find_in_page_next),
                closeContentDescription = stringResource(R.string.cd_find_in_page_close),
                onPreviousMatch = { controller.findNextInPage(forward = false) },
                onNextMatch = { controller.findNextInPage(forward = true) },
                onClose = controller::closeFindInPage,
                backdropSource = browserContentBlurTarget.asCandyChromeBackdropSource(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .zIndex(25f),
            )
        }

        if (firefoxExtensionOptionsTitle == null && !hideBrowserChrome) {
            BrowserAddressChrome(
                controller = controller,
                selectedTab = selectedTab,
                addressEditorVisible = addressEditorVisible,
                showInteractiveBlankStart = showInteractiveBlankStart,
                blankTabModeProgress = blankTabModeProgress,
                blankTabModeRevealOrigin = blankTabModeRevealOrigin,
                profileWallpaperRuntime = profileWallpaperRuntime,
                commandFeedback = commandFeedback,
                suggestionItems = suggestionItems,
                highlightedSuggestionIndex = highlightedSuggestionIndex,
                browserHeightPx = browserHeightPx,
                bottomBarTopPx = bottomBarTopPx,
                browserContentBlurTarget = browserContentBlurTarget,
                linkPeekAddressBarExpanded = linkPeekAddressBarExpanded,
                castUiState = castUiState,
                settingsVisible = settingsVisible,
                menuDismissRequestId = incomingBrowserNavigationRequestId,
                addressValue = addressValue,
                domainCompletion = domainCompletion,
                addressFocusNonce = addressFocusNonce,
                showAiModeToggle = showAiModeToggle,
                aiModeSelectedState = aiModeSelectedState,
                browserDragOffset = browserDragOffset,
                browserWidthPx = browserWidthPx,
                tabSwitchTravelPx = tabSwitchTravelPx,
                tabHandoffAlpha = tabHandoffAlpha,
                tabOverviewOpening = tabOverviewOpening,
                tabOverviewVisible = tabOverviewVisible,
                overviewGestureProgress = overviewGestureProgress,
                addressBarMorphInFront = addressBarMorphInFront,
                browserRootBottomInWindowPx = browserRootBottomInWindowPx,
                visibleSnoozedTabCount = visibleSnoozedTabs.size,
                onToggleCastPlayback = onToggleCastPlayback,
                onSeekCast = onSeekCast,
                onCastVolumeChange = onCastVolumeChange,
                onDisconnectCast = onDisconnectCast,
                openAddressEditor = openAddressEditor,
                onAddressEditorDismiss = { addressEditorVisible = false },
                onAddressValueChanged = {
                    addressValue = AddressSearchShortcutRules.edit(addressValue, it, controller.searchShortcuts)
                },
                onHighlightedSuggestionChanged = { highlightedSuggestionIndex = it },
                selectSuggestion = ::selectSuggestion,
                fillAddressFromSuggestion = ::fillAddressFromSuggestion,
                moveSuggestionHighlight = ::moveSuggestionHighlight,
                submitAddressOrCommand = ::submitAddressOrCommand,
                onLiveFrameCleared = { liveFrameTabId = null },
                onTabHandoffChanged = { tabHandoff = it },
                onTabs = {
                    if (addressEditorVisible) {
                        addressEditorVisible = false
                        overviewGestureScope.launch {
                            withFrameNanos { }
                            withFrameNanos { }
                            openTabOverview()
                        }
                    } else {
                        openTabOverview()
                    }
                },
                onOverviewGestureStarted = { overviewGestureSettleJob?.cancel() },
                onOverviewGestureCancelled = settleOverviewGesture,
                openNewTabAndEdit = { openNewTabAndEdit() },
                toggleFavoriteWithFeedback = toggleFavoriteWithFeedback,
                onBlankTabModeRevealOriginChanged = { blankTabModeRevealOrigin = it },
                onSnoozedTabs = { snoozedTabsVisible = true },
                onOpenFavorites = onOpenFavorites,
                onOpenDownloads = onOpenDownloads,
                onOpenHistory = onOpenHistory,
                onSettings = {
                    settingsDestination = SettingsDestination.Home
                    settingsVisible = true
                },
                onPrivacyXRay = {
                    privacyXRayTabId = selectedTab.id
                    permissionRadarOrigin = null
                },
                onNewTabButtonBoundsChanged = { addressNewTabButtonBounds = it },
                onReaderStudio = { performAddressBarLongPress(AddressBarLongPressAction.OpenReader) },
                onOpenCandyTrail = {
                    performAddressBarLongPress(AddressBarLongPressAction.OpenCandyTrail)
                },
                onSnooze = { snoozeTabId = selectedTab.id },
                onAddSiteCapsule = {
                    performAddressBarLongPress(AddressBarLongPressAction.CreateSiteCapsule)
                },
                onAddressBarLongPressAction = ::performAddressBarLongPress,
                onManageFirefoxExtensions = onManageFirefoxExtensions ?: {},
            )
        }

        BrowserReaderOverlay(
            readerStudioSession = readerStudioSession,
            readerStudioResult = readerStudioResult,
            readerLibraryRepository = readerLibraryRepository,
            selectedTab = selectedTab,
            onRetry = {
                val session = readerStudioSession ?: return@BrowserReaderOverlay
                readerStudioResult = null
                val requestId = ++readerStudioRequestId
                readerStudioSession = session.copy(requestId = requestId)
                controller.extractSelectedPageForReader { result ->
                    if (ReaderStudioSessionRules.acceptsResult(
                            readerStudioSession,
                            requestId,
                        )
                    ) {
                        readerStudioResult = result
                    }
                }
            },
            onDismiss = { readerStudioSession = null },
            onOpenOriginal = { url ->
                readerStudioSession = null
                if (url != selectedTab.url) controller.openUrl(url)
            },
            onOpenLink = { url ->
                readerStudioSession = null
                controller.openUrl(url)
            },
        )

        BrowserAuxiliaryOverlays(
            controller = controller,
            profileWallpaperRuntime = profileWallpaperRuntime,
            tabSwitcherWallpaperRuntime = tabSwitcherWallpaperRuntime,
            tabOverviewVisible = tabOverviewVisible,
            bottomBarTopPx = bottomBarTopPx,
            closeTabOverview = closeTabOverview,
            selectOverviewTab = { tabId, forceHandoff ->
                val target = controller.activeTabs.firstOrNull { tab -> tab.id == tabId }
                if (target != null && (forceHandoff || target.id != controller.selectedTabId)) {
                    liveFrameTabId = null
                    tabHandoff = TabHandoff(
                        tab = target,
                        preview = controller.previews[target.id].takeUnless { target.isIncognito },
                        favicon = controller.favicons[target.id],
                        previewTopInsetPx = controller.previewTopInsetPx(target.id),
                    )
                }
                controller.selectTab(tabId)
            },
            openNewTabAndEdit = {
                val previousTabId = controller.selectedTabId
                openNewTabAndEdit()
                if (controller.selectedTabId != previousTabId) closeTabOverview()
            },
            settingsDestination = settingsDestination,
            onSettingsDestinationChanged = { settingsDestination = it },
            settingsVisible = settingsVisible,
            onSettingsVisibleChanged = { settingsVisible = it },
            openProfileWallpaperEditor = ::openProfileWallpaperEditor,
            configureProfile = ::configureProfile,
            overviewDestinationChromeVisible = overviewDestinationChromeVisible,
            onEntryHeroStarted = { animated ->
                overviewMorphJob?.cancel()
                overviewMorphProgress.floatValue = 0f
                overviewEntryHeroCompleted = false
                if (animated) {
                    overviewMorphJob = overviewGestureScope.launch {
                        val progress = Animatable(0f)
                        progress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = TabOverviewHeroRules.ENTRY_DURATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) { overviewMorphProgress.floatValue = value }
                    }
                } else {
                    overviewMorphProgress.floatValue = 1f
                }
            },
            onEntryHeroCompleted = { overviewEntryHeroCompleted = true },
            onExitHeroVisibilityChanged = { overviewExitHeroVisible = it },
            candyTrailTabId = candyTrailTabId,
            candyTrailSourceBounds = candyTrailSourceBounds,
            candyTrailBackProgress = candyTrailBackProgress.value,
            candyTrailBackEdgeSign = candyTrailBackEdgeSign,
            candyTrailPredictiveBackCommitted = candyTrailPredictiveBackCommitted,
            onOpenCandyTrail = { tabId, bounds ->
                candyTrailSourceBounds = bounds
                candyTrailTabId = tabId
            },
            onCloseCandyTrail = {
                candyTrailTabId = null
                candyTrailSourceBounds = null
            },
            toggleFavoriteWithFeedback = toggleFavoriteWithFeedback,
            openSiteCapsuleEditor = ::openSiteCapsuleEditor,
            snoozeTabId = snoozeTabId,
            onSnoozeTabIdChanged = { snoozeTabId = it },
            pendingLinkSnooze = pendingLinkSnooze,
            onPendingLinkSnoozeChanged = { pendingLinkSnooze = it },
            onSnoozeTab = { wakeAtMillis ->
                pendingLinkSnooze?.let { pending ->
                    val undoToken = controller.snoozeContextLink(
                        url = pending.url,
                        title = pending.title,
                        wakeAtMillis = wakeAtMillis,
                        sourceTabId = pending.sourceTabId,
                    )
                    if (undoToken != null) {
                        feedbackSnackbarJob?.cancel()
                        feedbackSnackbarJob = backAnimationScope.launch {
                            showSnoozeUndoFeedback(
                                hostState = feedbackSnackbarHostState,
                                message = snoozeConfirmationMessage,
                                undoLabel = undoLabel,
                            ) {
                                controller.undoSnooze(undoToken)
                            }
                        }
                    }
                    return@BrowserAuxiliaryOverlays undoToken != null
                }
                val tabId = snoozeTabId ?: return@BrowserAuxiliaryOverlays false
                val undoToken = controller.snoozeTab(tabId, wakeAtMillis)
                if (undoToken != null) {
                    feedbackSnackbarJob?.cancel()
                    feedbackSnackbarJob = backAnimationScope.launch {
                        showSnoozeUndoFeedback(
                            hostState = feedbackSnackbarHostState,
                            message = snoozeConfirmationMessage,
                            undoLabel = undoLabel,
                        ) {
                            controller.undoSnooze(undoToken)
                        }
                    }
                }
                undoToken != null
            },
            moveTabToProfileId = moveTabToProfileId,
            onMoveTabToProfileIdChanged = { moveTabToProfileId = it },
            settingsPredictiveBackCommitted = settingsPredictiveBackCommitted,
            settingsBackProgress = settingsBackProgress.value,
            settingsBackEdgeSign = settingsBackEdgeSign,
            selectedTab = selectedTab,
            visibleProfileIds = visibleProfileIds,
            privacyXRayTabId = privacyXRayTabId,
            onPrivacyXRayTabIdChanged = { privacyXRayTabId = it },
            permissionRadarTabId = permissionRadarTabId,
            onPermissionRadarTabIdChanged = { permissionRadarTabId = it },
            permissionRadarOrigin = permissionRadarOrigin,
            onPermissionRadarOriginChanged = { permissionRadarOrigin = it },
            onPendingCapsuleDeleteChanged = { pendingCapsuleDelete = it },
            onImportUserScript = onImportUserScript,
            onImportFavoriteBookmarks = onImportFavoriteBookmarks,
            filterStudioSelectedRuleId = filterStudioSelectedRuleId,
            onFilterStudioSelectedRuleIdChanged = { filterStudioSelectedRuleId = it },
            filterStudioVisible = filterStudioVisible,
            onFilterStudioVisibleChanged = { filterStudioVisible = it },
            onExportAppData = onExportAppData,
            onExportAppLogs = onExportAppLogs,
            onClearAppLogs = onClearAppLogs,
            onExportGeckoLogs = onExportGeckoLogs,
            onClearGeckoLogs = onClearGeckoLogs,
            onImportAppData = onImportAppData,
            onShowGestureOnboarding = {
                settingsVisible = false
                onShowGestureOnboarding()
            },
            onShowReleaseNotes = {
                settingsVisible = false
                onShowReleaseNotes()
            },
            clearDialogVisible = clearDialogVisible,
            onClearDialogVisibleChanged = { clearDialogVisible = it },
            onManageFirefoxExtensions = onManageFirefoxExtensions,
            browserContentBlurTarget = browserContentBlurTarget,
            visibleProfiles = visibleProfiles,
            favoriteFeedbackEvent = favoriteFeedbackEvent,
            onFavoriteFeedbackEventFinished = { completedId ->
                if (favoriteFeedbackEvent?.id == completedId) favoriteFeedbackEvent = null
            },
            feedbackSnackbarHostState = feedbackSnackbarHostState,
            snoozedTabsVisible = snoozedTabsVisible,
            onSnoozedTabsVisibleChanged = { snoozedTabsVisible = it },
            visibleSnoozedTabs = visibleSnoozedTabs,
            selectCommand = ::selectCommand,
            pendingCommand = pendingCommand,
            onPendingCommandDismiss = {
                pendingCommand = null
                addressFocusNonce++
            },
            onPendingCommandConfirmed = { command ->
                pendingCommand = null
                runCommand(command)
            },
            addressNewTabButtonBounds = addressNewTabButtonBounds,
            pendingCapsuleDelete = pendingCapsuleDelete,
            showFavoriteMutation = showFavoriteMutation,
        )
    }
}

@Composable
private fun BoxScope.BrowserMainViewportContent(
    controller: BrowserController,
    selectedTab: BrowserTab,
    profileWallpaperRuntime: ProfileWallpaperRuntime?,
    showFirefoxExtensionOptionsChrome: Boolean,
    firefoxExtensionOptionsTitle: String?,
    fullscreenVideoGestureState: FullscreenVideoGestureState?,
    webViewVideoOnlyPresentation: Boolean,
    hideBrowserChrome: Boolean,
    browserDragOffset: MutableFloatState,
    tabSwitchTravelPx: Float,
    browserHeightPx: Float,
    bottomBarTopPx: FloatState,
    tabHandoff: TabHandoff?,
    tabHandoffAlpha: Float,
    liveFrameTabId: String?,
    tabOverviewVisible: Boolean,
    reportLiveFrame: (String) -> Unit,
    showInteractiveBlankStart: Boolean,
    addressEditorVisible: Boolean,
    openAddressEditor: () -> Unit,
    onOpenFavorites: () -> Unit,
    blankTabModeProgress: Float,
    blankTabModeRevealOrigin: Offset,
    browserContentBlurTarget: BlurTarget?,
    onBlurTargetAttached: (BlurTarget) -> Unit,
    onBlurTargetReleased: (BlurTarget) -> Unit,
    settingsVisible: Boolean,
    readerStudioSession: Any?,
    onOpenMpvRx: ((String, String?) -> Unit)?,
    onAddressEditorDismiss: () -> Unit,
    onHandoffExpired: (TabHandoff) -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
    )
    CompositionLocalProvider(LocalProfileWallpaper provides profileWallpaperRuntime) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showFirefoxExtensionOptionsChrome) {
                FirefoxExtensionOptionsTopBar(
                    title = requireNotNull(firefoxExtensionOptionsTitle),
                    onBack = controller::goBack,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (fullscreenVideoGestureState != null) {
                            Color.Black
                        } else {
                            Color.Transparent
                        },
                    ),
            ) {
                BrowserViewport(
                    controller = controller,
                    fullscreenVideoGestureState = fullscreenVideoGestureState,
                    webViewVideoOnlyPresentation = webViewVideoOnlyPresentation,
                    videoOnlyPresentation = hideBrowserChrome,
                    selectedTab = selectedTab,
                    dragOffset = browserDragOffset,
                    travelDistance = tabSwitchTravelPx,
                    rootHeightPx = browserHeightPx,
                    bottomBarTopPx = bottomBarTopPx,
                    handoff = tabHandoff,
                    handoffAlpha = tabHandoffAlpha,
                    liveFrameTabId = liveFrameTabId,
                    tabOverviewVisible = tabOverviewVisible,
                    onLiveFrame = reportLiveFrame,
                    onHandoffExpired = onHandoffExpired,
                    onSearch = if (showInteractiveBlankStart) {
                        onAddressEditorDismiss
                    } else {
                        openAddressEditor
                    },
                    onFavorite = { url ->
                        onAddressEditorDismiss()
                        controller.submitAddress(url)
                    },
                    onOpenFavorites = onOpenFavorites,
                    onReorderFavorite = controller::reorderFavorite,
                    blankTabModeProgress = blankTabModeProgress,
                    blankTabModeRevealOrigin = blankTabModeRevealOrigin,
                    onRetry = controller::retryFailedPage,
                    onBlurTargetAttached = onBlurTargetAttached,
                    onBlurTargetReleased = onBlurTargetReleased,
                )

                BrowserMediaStreamLaunchBadge(
                    selectedTab = selectedTab,
                    isVideoDetectionRedirectEnabled = controller.isVideoDetectionRedirectEnabled,
                    hideBrowserChrome = hideBrowserChrome,
                    tabOverviewVisible = tabOverviewVisible,
                    addressEditorVisible = addressEditorVisible,
                    settingsVisible = settingsVisible,
                    readerStudioSession = readerStudioSession,
                    controller = controller,
                    onOpenMpvRx = onOpenMpvRx,
                )
            }
        }
    }
}

@Composable
private fun BrowserReaderOverlay(
    readerStudioSession: ReaderStudioSession?,
    readerStudioResult: ReaderExtractionResult?,
    readerLibraryRepository: ReaderLibraryRepository,
    selectedTab: BrowserTab,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenOriginal: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    readerStudioSession?.let { session ->
        ReaderStudioScreen(
            result = readerStudioResult,
            sourceUrl = session.sourceUrl,
            isPrivate = session.isPrivate,
            repository = readerLibraryRepository,
            onRetry = onRetry,
            onDismiss = onDismiss,
            onOpenOriginal = onOpenOriginal,
            onOpenLink = onOpenLink,
        )
    }
}

@Composable
private fun BoxScope.BrowserAuxiliaryOverlays(
    controller: BrowserController,
    profileWallpaperRuntime: ProfileWallpaperRuntime?,
    tabSwitcherWallpaperRuntime: ProfileWallpaperRuntime?,
    tabOverviewVisible: Boolean,
    bottomBarTopPx: FloatState,
    closeTabOverview: () -> Unit,
    selectOverviewTab: (tabId: String, forceHandoff: Boolean) -> Unit,
    openNewTabAndEdit: () -> Unit,
    settingsDestination: SettingsDestination,
    onSettingsDestinationChanged: (SettingsDestination) -> Unit,
    settingsVisible: Boolean,
    onSettingsVisibleChanged: (Boolean) -> Unit,
    openProfileWallpaperEditor: (String, ProfileWallpaperTarget) -> Unit,
    configureProfile: (profileId: String, wallpaperTargets: List<ProfileWallpaperTarget>, protection: ProfileProtection?) -> Unit,
    overviewDestinationChromeVisible: Boolean,
    onEntryHeroStarted: (Boolean) -> Unit,
    onEntryHeroCompleted: () -> Unit,
    onExitHeroVisibilityChanged: (Boolean) -> Unit,
    candyTrailTabId: String?,
    candyTrailSourceBounds: Rect?,
    candyTrailBackProgress: Float,
    candyTrailBackEdgeSign: Int,
    candyTrailPredictiveBackCommitted: Boolean,
    onOpenCandyTrail: (String, Rect?) -> Unit,
    onCloseCandyTrail: () -> Unit,
    toggleFavoriteWithFeedback: (String) -> Unit,
    openSiteCapsuleEditor: (SiteCapsule?, BrowserTab?) -> Unit,
    snoozeTabId: String?,
    onSnoozeTabIdChanged: (String?) -> Unit,
    pendingLinkSnooze: PendingLinkSnooze?,
    onPendingLinkSnoozeChanged: (PendingLinkSnooze?) -> Unit,
    onSnoozeTab: (Long) -> Boolean,
    moveTabToProfileId: String?,
    onMoveTabToProfileIdChanged: (String?) -> Unit,
    settingsPredictiveBackCommitted: Boolean,
    settingsBackProgress: Float,
    settingsBackEdgeSign: Int,
    selectedTab: BrowserTab,
    visibleProfileIds: Set<String>,
    privacyXRayTabId: String?,
    onPrivacyXRayTabIdChanged: (String?) -> Unit,
    permissionRadarTabId: String?,
    onPermissionRadarTabIdChanged: (String?) -> Unit,
    permissionRadarOrigin: String?,
    onPermissionRadarOriginChanged: (String?) -> Unit,
    onPendingCapsuleDeleteChanged: (SiteCapsule?) -> Unit,
    onImportUserScript: () -> Unit,
    onImportFavoriteBookmarks: () -> Unit,
    filterStudioSelectedRuleId: String?,
    onFilterStudioSelectedRuleIdChanged: (String?) -> Unit,
    filterStudioVisible: Boolean,
    onFilterStudioVisibleChanged: (Boolean) -> Unit,
    onExportAppData: () -> Unit,
    onExportAppLogs: () -> Unit,
    onClearAppLogs: () -> Unit,
    onExportGeckoLogs: () -> Unit,
    onClearGeckoLogs: () -> Unit,
    onImportAppData: () -> Unit,
    onShowGestureOnboarding: () -> Unit,
    onShowReleaseNotes: () -> Unit,
    clearDialogVisible: Boolean,
    onClearDialogVisibleChanged: (Boolean) -> Unit,
    onManageFirefoxExtensions: (() -> Unit)?,
    browserContentBlurTarget: BlurTarget?,
    visibleProfiles: List<BrowserProfile>,
    favoriteFeedbackEvent: FavoriteFeedbackEvent?,
    onFavoriteFeedbackEventFinished: (Int) -> Unit,
    feedbackSnackbarHostState: SnackbarHostState,
    snoozedTabsVisible: Boolean,
    onSnoozedTabsVisibleChanged: (Boolean) -> Unit,
    visibleSnoozedTabs: List<SnoozedTab>,
    selectCommand: (CommandSuggestion) -> Unit,
    pendingCommand: CommandSuggestion?,
    onPendingCommandDismiss: () -> Unit,
    onPendingCommandConfirmed: (BrowserCommand) -> Unit,
    addressNewTabButtonBounds: Rect?,
    pendingCapsuleDelete: SiteCapsule?,
    showFavoriteMutation: (FavoriteMutation) -> Unit,
) {
    val rootView = LocalView.current

    CompositionLocalProvider(LocalProfileWallpaper provides profileWallpaperRuntime) {
        TabOverview(
            controller = controller,
            backgroundWallpaper = tabSwitcherWallpaperRuntime,
            visible = tabOverviewVisible,
            bottomBarTopPx = bottomBarTopPx,
            onClose = closeTabOverview,
            onSelect = { tabId ->
                selectOverviewTab(tabId, false)
            },
            onSelectDismissAnchor = { tabId ->
                selectOverviewTab(tabId, true)
            },
            onNewTab = openNewTabAndEdit,
            onOpenSettings = {
                onSettingsDestinationChanged(SettingsDestination.Home)
                onSettingsVisibleChanged(true)
            },
            onOpenSyncSettings = {
                onSettingsDestinationChanged(SettingsDestination.Sync)
                onSettingsVisibleChanged(true)
            },
            onEditProfileWallpaper = openProfileWallpaperEditor,
            onConfigureCreatedProfile = { profileId, options ->
                configureProfile(
                    profileId,
                    options.wallpaperTargets.toList(),
                    options.protection,
                )
            },
            destinationChromeVisible = overviewDestinationChromeVisible,
            onEntryHeroStarted = onEntryHeroStarted,
            onEntryHeroCompleted = onEntryHeroCompleted,
            onExitHeroVisibilityChanged = onExitHeroVisibilityChanged,
            candyTrailTabId = candyTrailTabId,
            candyTrailSourceBounds = candyTrailSourceBounds,
            candyTrailBackProgress = candyTrailBackProgress,
            candyTrailBackEdgeSign = candyTrailBackEdgeSign,
            candyTrailPredictiveBackCommitted = candyTrailPredictiveBackCommitted,
            onOpenCandyTrail = onOpenCandyTrail,
            onCloseCandyTrail = onCloseCandyTrail,
            onToggleFavoriteTab = toggleFavoriteWithFeedback,
            onAddSiteCapsule = { tabId ->
                openSiteCapsuleEditor(
                    null,
                    controller.tabs.firstOrNull { it.id == tabId },
                )
            },
            onSnoozeTab = { tabId -> onSnoozeTabIdChanged(tabId) },
        )
    }

    SnoozeTabDialog(
        tab = pendingLinkSnooze?.displayTab()
            ?: snoozeTabId?.let { id -> controller.tabs.firstOrNull { it.id == id } },
        onSnooze = onSnoozeTab,
        onDismiss = {
            onSnoozeTabIdChanged(null)
            onPendingLinkSnoozeChanged(null)
        },
    )

    MoveTabToProfileDialog(
        tab = moveTabToProfileId?.let { tabId ->
            controller.tabs.firstOrNull { tab -> tab.id == tabId }
        },
        profiles = moveTabToProfileId
            ?.let(controller::compatibleMoveTargetProfiles)
            .orEmpty(),
        onMove = { profileId ->
            val tabId = moveTabToProfileId ?: return@MoveTabToProfileDialog
            onMoveTabToProfileIdChanged(null)
            if (controller.moveTabToProfile(tabId, profileId)) {
                rootView.performConfirmHaptic()
            }
        },
        onDismiss = { onMoveTabToProfileIdChanged(null) },
    )

    BrowserSettingsOverlay(
        controller = controller,
        visible = settingsVisible,
        destination = settingsDestination,
        predictiveBackCommitted = settingsPredictiveBackCommitted,
        predictiveBackProgress = settingsBackProgress,
        predictiveBackEdgeSign = settingsBackEdgeSign,
        selectedTab = selectedTab,
        visibleProfileIds = visibleProfileIds,
        onDestinationChanged = onSettingsDestinationChanged,
        onOpenPrivacyXRay = {
            onPrivacyXRayTabIdChanged(selectedTab.id)
            rootView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        },
        onOpenPermissionRadar = {
            onPermissionRadarTabIdChanged(selectedTab.id)
            onPermissionRadarOriginChanged(null)
            rootView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        },
        onEditCapsule = { capsule ->
            openSiteCapsuleEditor(capsule, null)
        },
        onDeleteCapsule = onPendingCapsuleDeleteChanged,
        onImportUserScript = onImportUserScript,
        onImportFavoriteBookmarks = onImportFavoriteBookmarks,
        onOpenFilterStudio = {
            onFilterStudioSelectedRuleIdChanged(null)
            onFilterStudioVisibleChanged(true)
        },
        onExportAppData = onExportAppData,
        onExportAppLogs = onExportAppLogs,
        onClearAppLogs = onClearAppLogs,
        onExportGeckoLogs = onExportGeckoLogs,
        onClearGeckoLogs = onClearGeckoLogs,
        onImportAppData = onImportAppData,
        onShowGestureOnboarding = {
            onSettingsVisibleChanged(false)
            onShowGestureOnboarding()
        },
        onShowReleaseNotes = {
            onSettingsVisibleChanged(false)
            onShowReleaseNotes()
        },
        onClearData = { onClearDialogVisibleChanged(true) },
        onOpenLegalUrl = { url ->
            onSettingsVisibleChanged(false)
            controller.openUrl(url, inNewTab = true)
        },
        onOpenFirefoxExtensions = onManageFirefoxExtensions,
        onDismiss = { onSettingsVisibleChanged(false) },
    )

    BrowserModalSurfaces(
        controller = controller,
        privacyXRayTabId = privacyXRayTabId,
        permissionRadarTabId = permissionRadarTabId,
        permissionRadarOrigin = permissionRadarOrigin,
        filterStudioVisible = filterStudioVisible,
        filterStudioSelectedRuleId = filterStudioSelectedRuleId,
        browserContentBlurTarget = browserContentBlurTarget,
        visibleProfiles = visibleProfiles,
        favoriteFeedbackEvent = favoriteFeedbackEvent,
        feedbackSnackbarHostState = feedbackSnackbarHostState,
        snoozedTabsVisible = snoozedTabsVisible,
        visibleSnoozedTabs = visibleSnoozedTabs,
        onOpenFilterStudio = { ruleId: String? ->
            onFilterStudioSelectedRuleIdChanged(ruleId)
            onPrivacyXRayTabIdChanged(null)
            onFilterStudioVisibleChanged(true)
        },
        onClearSiteData = { target: dev.sk2andy.materialbrowser.browser.SiteDataTarget ->
            onPrivacyXRayTabIdChanged(null)
            AndroidCommandCatalog(rootView.context).localize(
                commands = listOf(BrowserCommandRegistry.siteDataCommand(target)),
                cookieScope = controller.commandCookieScope,
            ).firstOrNull()?.let(selectCommand)
        },
        onPrivacyXRayDismiss = { onPrivacyXRayTabIdChanged(null) },
        onPermissionOriginSelected = onPermissionRadarOriginChanged,
        onPermissionRadarDismiss = {
            onPermissionRadarTabIdChanged(null)
            onPermissionRadarOriginChanged(null)
        },
        onFilterStudioDismiss = {
            onFilterStudioVisibleChanged(false)
            onFilterStudioSelectedRuleIdChanged(null)
        },
        onFavoriteFeedbackFinished = onFavoriteFeedbackEventFinished,
        onSnoozedTabsDismiss = { onSnoozedTabsVisibleChanged(false) },
    )

    BrowserTransientOverlays(
        controller = controller,
        clearDialogVisible = clearDialogVisible,
        onClearDialogDismiss = { onClearDialogVisibleChanged(false) },
        pendingCommand = pendingCommand,
        onPendingCommandDismiss = onPendingCommandDismiss,
        onPendingCommandConfirmed = onPendingCommandConfirmed,
        addressNewTabButtonBounds = addressNewTabButtonBounds,
        pendingCapsuleDelete = pendingCapsuleDelete,
        onPendingCapsuleDeleteDismiss = { onPendingCapsuleDeleteChanged(null) },
        onFavoriteLink = { url, title ->
            controller.toggleContextLinkFavorite(url, title)?.let(showFavoriteMutation)
        },
        onSnoozeLink = { url, title, sourceTabId ->
            onPendingLinkSnoozeChanged(PendingLinkSnooze(url, title, sourceTabId))
        },
    )

    FirefoxExtensionChrome(controller)
}

private fun ReaderExtractionResult.readerActionMessageRes(): Int = when (this) {
    is ReaderExtractionResult.Success -> R.string.reader_saved_offline_confirmation
    is ReaderExtractionResult.Failure -> when (reason) {
        ReaderExtractionFailure.UnsupportedPage -> R.string.reader_extraction_unsupported
        ReaderExtractionFailure.EmptyArticle -> R.string.reader_extraction_empty
        ReaderExtractionFailure.InvalidResponse -> R.string.reader_extraction_invalid
    }
}

@Composable
private fun BoxScope.BrowserMediaStreamLaunchBadge(
    selectedTab: BrowserTab,
    isVideoDetectionRedirectEnabled: Boolean = true,
    hideBrowserChrome: Boolean,
    tabOverviewVisible: Boolean,
    addressEditorVisible: Boolean,
    settingsVisible: Boolean,
    readerStudioSession: Any?,
    controller: BrowserController? = null,
    onOpenMpvRx: ((String, String?) -> Unit)?,
) {
    val currentContext = LocalContext.current
    LaunchedEffect(selectedTab.url) {
        YouTubeStreamPreloader.onUrlChanged(currentContext, selectedTab.url)
    }
    val preloadedYtStream by YouTubeStreamPreloader.preloadedStream.collectAsState()
    val activeDirectStream by MediaStreamDetectorBridge.activeStreamPayload.collectAsState()
    val isYtPreloading by YouTubeStreamPreloader.isLoading.collectAsState()
    val isPrefetchedAndReady by YouTubeStreamPreloader.isPrefetchedAndReady.collectAsState()
    val isKnownCandidate = YouTubeStreamPreloader.isCandidateVideoPage(selectedTab.url)
    val hasDirectMedia = activeDirectStream != null || preloadedYtStream != null
    val isCandidate = isKnownCandidate || hasDirectMedia
    val currentMediaKey = YouTubeStreamPreloader.extractMediaKey(selectedTab.url)

    val validPreloaded = (preloadedYtStream ?: activeDirectStream?.let { payload ->
        PreloadedYouTubeStream(
            videoId = payload.url,
            pageUrl = selectedTab.url,
            videoUrl = payload.url,
            title = payload.title ?: selectedTab.title,
            resolution = "Direct Stream",
            headers = payload.headers,
            isDirectMedia = true,
            isPreloaded = true,
        )
    })?.takeIf { stream ->
        !YouTubeStreamPreloader.isAdOrAuxiliaryMedia(stream.videoUrl)
    }
    val hasValidReadyStream = validPreloaded != null
    val isPrefetching = isCandidate && !hasValidReadyStream && isYtPreloading

    val shouldShowButton = hasValidReadyStream || isPrefetching

    if (
        !isVideoDetectionRedirectEnabled ||
        !shouldShowButton ||
        hideBrowserChrome ||
        tabOverviewVisible ||
        addressEditorVisible ||
        settingsVisible ||
        readerStudioSession != null ||
        onOpenMpvRx == null
    ) {
        return
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val baseTopDp = if (isLandscape) {
        16.dp
    } else {
        (configuration.screenWidthDp.dp * 9f / 16f) + 56.dp
    }
    val minTopPx = with(density) { 16.dp.toPx() }
    val maxTopPx = with(density) { (configuration.screenHeightDp.dp - 120.dp).toPx() }
    val baseTopPx = with(density) { baseTopDp.toPx() }

    var dragOffsetY by remember(selectedTab.url) { mutableFloatStateOf(0f) }
    val draggableState = rememberDraggableState { delta ->
        dragOffsetY += delta
    }
    val clampedY = (baseTopPx + dragOffsetY).coerceIn(minTopPx, maxTopPx)

    Surface(
        onClick = {
            if (!hasValidReadyStream) {
                if (isPrefetching) {
                    Toast.makeText(currentContext, "Prefetching video (5-10%), please wait...", Toast.LENGTH_SHORT).show()
                }
                return@Surface
            }
            if (validPreloaded != null) {
                val launched = YouTubeStreamPreloader.launchPreloadedStream(currentContext, validPreloaded) {
                    controller?.stopWebVideoPlaybackForExternalPlayer()
                    controller?.pauseActiveMedia()
                    controller?.exitSelectedWebContentFullscreen()
                }
                if (launched) {
                    Toast.makeText(currentContext, "Playing in MPV RX...", Toast.LENGTH_SHORT).show()
                    return@Surface
                }
            }
            onOpenMpvRx.invoke(
                selectedTab.url,
                validPreloaded?.title ?: selectedTab.title,
            )
        },
        shape = CircleShape,
        color = if (hasValidReadyStream) Color(0xFFCC0000) else Color(0xDD1E1E1E),
        shadowElevation = 6.dp,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset(x = 0, y = clampedY.roundToInt()) }
            .padding(end = 12.dp)
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
            )
            .size(46.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (isPrefetching) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_mpvrx_logo),
                    contentDescription = "Play in mpvRx",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}


