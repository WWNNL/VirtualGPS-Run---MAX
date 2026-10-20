package com.virtualrun.app.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.virtualrun.app.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.algorithm.GpsQuality
import com.virtualrun.app.algorithm.InjectScenario
import com.virtualrun.app.map.CHINA_MAP_MAX_ZOOM_LEVEL
import com.virtualrun.app.map.CHINA_MAP_MIN_ZOOM_LEVEL
import com.virtualrun.app.map.ChinaMapTileSource
import com.virtualrun.app.map.CoordinateConverter
import com.virtualrun.app.map.SatelliteLabelTileSource
import com.virtualrun.app.map.SatelliteMapTileSource
import com.virtualrun.app.service.InjectLog
import com.virtualrun.app.service.MockLocationService
import kotlinx.coroutines.delay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.roundToInt

private val PlannedRouteColor = Color(0xFF1E88E5)
private val ActualTrailColor = Color(0xFFFF7043)
private val RunGreen = Color(0xFF2EAD65)
private val StopRed = Color(0xFFE04F5F)
private const val MIN_CURRENT_PACE_DISPLAY_SPEED_MPS = 0.6f

private enum class RunUiErrorAction {
    DISMISS,
    APP_SETTINGS,
    DEVELOPER_SETTINGS
}

private data class RunUiError(
    val message: String,
    val action: RunUiErrorAction
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OSMapScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val routePoints by viewModel.routePoints.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val basePace by viewModel.basePace.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val lapCount by viewModel.lapCount.collectAsState()
    val isLoopMode by viewModel.isLoopMode.collectAsState()
    val currentSpeed by viewModel.speed.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val fixedPoint by viewModel.fixedPoint.collectAsState()
    val scenario by viewModel.scenario.collectAsState()
    val quality by viewModel.quality.collectAsState()
    val latestIsRunning by rememberUpdatedState(isRunning)
    val latestIsLoopMode by rememberUpdatedState(isLoopMode)
    val latestMode by rememberUpdatedState(mode)

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var satelliteOn by remember { mutableStateOf(false) }
    var labelsOn by remember { mutableStateOf(true) }
    var labelOverlay by remember { mutableStateOf<TilesOverlay?>(null) }
    var sliderPace by remember(basePace) { mutableFloatStateOf(basePace) }
    var routePolyline by remember { mutableStateOf<Polyline?>(null) }
    var routeMarkers by remember { mutableStateOf<FolderOverlay?>(null) }
    var actualTrail by remember { mutableStateOf<Polyline?>(null) }
    val rawTrailPoints = remember { mutableListOf<GeoPoint>() }
    var currentMarker by remember { mutableStateOf<Marker?>(null) }
    var locationCrosshair by remember { mutableStateOf<Marker?>(null) }
    var fixedMarker by remember { mutableStateOf<Marker?>(null) }
    var bottomPanelHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bottomPanelHeight = with(density) { bottomPanelHeightPx.toDp() }
    var markerAnimator by remember { mutableStateOf<ValueAnimator?>(null) }
    var pendingLocationListener by remember { mutableStateOf<LocationListener?>(null) }
    var lastCameraFollowAt by remember { mutableLongStateOf(0L) }
    var wasRunning by remember { mutableStateOf(false) }
    var uiError by remember { mutableStateOf<RunUiError?>(null) }

    val animatedProgress = remember { Animatable(0f) }
    var animatedProgressLap by remember { mutableIntStateOf(0) }
    val animatedSpeed by animateFloatAsState(
        targetValue = currentSpeed,
        animationSpec = tween(durationMillis = 180, easing = LinearEasing),
        label = "current speed"
    )
    val actionColor by animateColorAsState(
        targetValue = if (isRunning) StopRed else RunGreen,
        animationSpec = tween(250),
        label = "run action color"
    )
    // 定位按钮按底部面板的实测高度让位，避免写死 dp 后和卡片叠在一起
    val locationButtonOffset by animateDpAsState(
        targetValue = if (bottomPanelHeightPx > 0) -(bottomPanelHeight + 12.dp) else (-210).dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "location button offset"
    )

    fun animateToDeviceLocation(location: Location, zoom: Double = 18.0) {
        val gcjLocation = CoordinateConverter.wgs84ToGcj02(location.latitude, location.longitude)
        val target = GeoPoint(gcjLocation.first, gcjLocation.second)

        // 在当前位置落一个准星标记（重复点定位时只移动同一个标记，不叠加）
        mapView?.let { mv ->
            val crosshair = locationCrosshair ?: Marker(mv).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
                icon = ContextCompat.getDrawable(context, R.drawable.ic_crosshair)
                setOnMarkerClickListener { _, _ -> false }
            }.also {
                mv.overlays.add(it)
                locationCrosshair = it
            }
            crosshair.position = target
            mv.invalidate()
        }

        mapView?.controller?.animateTo(target, zoom, 1000L)
    }

    /** 取一次性定位：优先用缓存位置，没有就向 NETWORK_PROVIDER 要一次单次更新。 */
    fun locateDeviceOnce(zoom: Double = 18.0) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            uiError = RunUiError("请先授予位置权限", RunUiErrorAction.APP_SETTINGS)
            return
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (location != null) {
            pendingLocationListener?.let { runCatching { locationManager.removeUpdates(it) } }
            pendingLocationListener = null
            animateToDeviceLocation(location, zoom)
        } else {
            pendingLocationListener?.let { runCatching { locationManager.removeUpdates(it) } }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    animateToDeviceLocation(location, zoom)
                    runCatching { locationManager.removeUpdates(this) }
                    pendingLocationListener = null
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            pendingLocationListener = listener
            locationManager.requestSingleUpdate(
                LocationManager.NETWORK_PROVIDER,
                listener,
                Looper.getMainLooper()
            )
        }
    }

    // 打开 App 初始化：等权限授予、地图就绪后，自动飞到当前位置并落下准星
    var didAutoLocate by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(mapView) {
        if (mapView == null || didAutoLocate) return@LaunchedEffect
        repeat(30) {
            if (didAutoLocate) return@LaunchedEffect
            val granted = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                didAutoLocate = true
                locateDeviceOnce(zoom = 17.0)
                return@LaunchedEffect
            }
            // 权限弹窗刚出现，给用户一点时间点「允许」，最多等 15 秒
            delay(500)
        }
    }

    LaunchedEffect(progress, lapCount, isRunning) {
        if (!isRunning) {
            animatedProgress.snapTo(0f)
        } else if (lapCount != animatedProgressLap || progress + 0.05f < animatedProgress.value) {
            animatedProgress.snapTo(progress)
        } else {
            animatedProgress.animateTo(progress, tween(850, easing = LinearEasing))
        }
        animatedProgressLap = lapCount
    }

    LaunchedEffect(isRunning, mapView) {
        if (isRunning && !wasRunning) {
            markerAnimator?.cancel()
            rawTrailPoints.clear()
            actualTrail?.let { mapView?.overlays?.remove(it) }
            actualTrail = null
            lastCameraFollowAt = 0L
            // 起跑后清掉定位准星，避免和模拟位置的轨迹标记混淆
            locationCrosshair?.let { mapView?.overlays?.remove(it) }
            locationCrosshair = null
        }
        wasRunning = isRunning
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val activeMap = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> activeMap?.onResume()
                Lifecycle.Event.ON_PAUSE -> activeMap?.onPause()
                Lifecycle.Event.ON_DESTROY -> activeMap?.onDetach()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            markerAnimator?.cancel()
        }
    }

    DisposableEffect(pendingLocationListener) {
        val listener = pendingLocationListener
        onDispose {
            if (listener != null) {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                runCatching { locationManager.removeUpdates(listener) }
            }
        }
    }

    LaunchedEffect(mapView, routePoints) {
        val mv = mapView ?: return@LaunchedEffect
        routePolyline?.let(mv.overlays::remove)
        routeMarkers?.let(mv.overlays::remove)
        routePolyline = null
        routeMarkers = null

        if (routePoints.size >= 2) {
            routePolyline = Polyline(mv).apply {
                routePoints.forEach { addPoint(GeoPoint(it.latitude, it.longitude)) }
                outlinePaint.apply {
                    color = PlannedRouteColor.toArgb()
                    strokeWidth = 6f
                }
            }.also(mv.overlays::add)
        }

        if (routePoints.isNotEmpty()) {
            routeMarkers = FolderOverlay().also { folder ->
                routePoints.forEachIndexed { index, point ->
                    val marker = Marker(mv).apply {
                        position = GeoPoint(point.latitude, point.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        infoWindow = null
                        icon = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(if (index == 0) android.graphics.Color.parseColor("#2EAD65") else android.graphics.Color.parseColor("#1E88E5"))
                            setStroke(if (index == 0) 3 else 2, android.graphics.Color.WHITE)
                            val targetDp = if (index == 0) 28 else 22
                            val targetPx = (targetDp * context.resources.displayMetrics.density).roundToInt()
                            setSize(targetPx, targetPx)
                        }
                        setOnMarkerClickListener { _, _ ->
                            if (index == 0 && !latestIsRunning && routePoints.size >= 3) {
                                viewModel.closeLoop()
                            }
                            true
                        }
                    }
                    folder.add(marker)
                }
                mv.overlays.add(folder)
            }
        }

        if (routePoints.isEmpty() && !latestIsRunning) {
            actualTrail?.let(mv.overlays::remove)
            actualTrail = null
            rawTrailPoints.clear()
        }
        // 路线编辑后重新建立稳定层级：计划线 < 路线点 < 原始实跑线 < 当前定位点。
        actualTrail?.let {
            mv.overlays.remove(it)
            mv.overlays.add(it)
        }
        currentMarker?.let {
            mv.overlays.remove(it)
            mv.overlays.add(it)
        }
        mv.invalidate()
    }

    LaunchedEffect(mapView, fixedPoint, mode) {
        val mv = mapView ?: return@LaunchedEffect
        val point = fixedPoint
        if (mode != SimMode.FIXED || point == null) {
            fixedMarker?.let(mv.overlays::remove)
            fixedMarker = null
            mv.invalidate()
            return@LaunchedEffect
        }
        val marker = fixedMarker ?: Marker(mv).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null
            icon = ContextCompat.getDrawable(context, R.drawable.ic_pin)
            setOnMarkerClickListener { _, _ -> false }
        }.also {
            mv.overlays.add(it)
            fixedMarker = it
        }
        marker.position = GeoPoint(point.latitude, point.longitude)
        mv.invalidate()
    }

    LaunchedEffect(mapView, currentLocation, mode) {
        val mv = mapView ?: return@LaunchedEffect
        // 固定定位不动，画轨迹没有意义：清掉实跑线和定位点，地图上只留固定点针
        if (mode == SimMode.FIXED) {
            markerAnimator?.cancel()
            actualTrail?.let(mv.overlays::remove)
            actualTrail = null
            rawTrailPoints.clear()
            currentMarker?.let(mv.overlays::remove)
            currentMarker = null
            mv.invalidate()
            return@LaunchedEffect
        }
        val loc = currentLocation
        if (loc == null) {
            markerAnimator?.cancel()
            currentMarker?.let(mv.overlays::remove)
            currentMarker = null
            mv.invalidate()
            return@LaunchedEffect
        }

        val gcjLoc = CoordinateConverter.wgs84ToGcj02(loc.latitude, loc.longitude)
        val target = GeoPoint(gcjLoc.first, gcjLoc.second)
        val previousTrailPoint = rawTrailPoints.lastOrNull()
        if (previousTrailPoint == null || previousTrailPoint.latitude != target.latitude || previousTrailPoint.longitude != target.longitude) {
            rawTrailPoints.add(target)
            if (rawTrailPoints.size > 3600) rawTrailPoints.removeAt(0)
        }

        val trail = actualTrail ?: Polyline(mv).apply {
            outlinePaint.apply {
                color = ActualTrailColor.toArgb()
                strokeWidth = 6f
            }
        }.also {
            actualTrail = it
            mv.overlays.add(it)
        }
        trail.setPoints(rawTrailPoints.toList())

        val marker = currentMarker ?: Marker(mv).apply {
            position = target
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#FF7043"))
                setStroke(5, android.graphics.Color.WHITE)
                setSize(44, 44)
            }
        }.also {
            currentMarker = it
            mv.overlays.add(it)
        }

        markerAnimator?.cancel()
        val start = GeoPoint(marker.position.latitude, marker.position.longitude)
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 880L
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                marker.position = GeoPoint(
                    start.latitude + (target.latitude - start.latitude) * fraction,
                    start.longitude + (target.longitude - start.longitude) * fraction
                )
                mv.invalidate()
            }
            start()
        }

        val now = SystemClock.elapsedRealtime()
        if (isRunning && now - lastCameraFollowAt >= 2500L) {
            mv.controller.animateTo(target, mv.zoomLevelDouble, 900L)
            lastCameraFollowAt = now
        }
        mv.invalidate()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "路线地图。移动地图后双击，在地图中心添加路线点"
                    onClick(label = if (mode == SimMode.FIXED) "把地图中心设为固定位置" else "在地图中心添加路线点") {
                        val center = mapView?.mapCenter
                        if (!latestIsRunning && center != null) {
                            if (latestMode == SimMode.FIXED) {
                                viewModel.setFixedPoint(LatLng(center.latitude, center.longitude))
                                true
                            } else if (!latestIsLoopMode) {
                                viewModel.addRoutePoint(LatLng(center.latitude, center.longitude))
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }
                },
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(ChinaMapTileSource())
                    setMinZoomLevel(CHINA_MAP_MIN_ZOOM_LEVEL.toDouble())
                    setMaxZoomLevel(CHINA_MAP_MAX_ZOOM_LEVEL.toDouble())
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(16.0)
                    controller.setCenter(GeoPoint(39.9042, 116.4074))

                    // 卫星地名地标层：透明底 PNG，只在卫星模式下打开
                    val labels = TilesOverlay(
                        MapTileProviderBasic(ctx, SatelliteLabelTileSource()), ctx
                    ).apply {
                        loadingBackgroundColor = 0
                        loadingLineColor = 0
                        isEnabled = false
                    }
                    overlays.add(0, labels)
                    labelOverlay = labels

                    overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            if (!latestIsRunning && p != null) {
                                if (latestMode == SimMode.FIXED) {
                                    viewModel.setFixedPoint(LatLng(p.latitude, p.longitude))
                                } else if (!latestIsLoopMode) {
                                    viewModel.addRoutePoint(LatLng(p.latitude, p.longitude))
                                }
                            }
                            return p != null
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    }))
                    mapView = this
                }
            }
        )

        AnimatedVisibility(
            visible = !isRunning,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120))
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(
                    label = "路线跑",
                    selected = mode == SimMode.ROUTE,
                    onClick = { viewModel.setMode(SimMode.ROUTE) }
                )
                ModeChip(
                    label = "固定定位",
                    selected = mode == SimMode.FIXED,
                    onClick = { viewModel.setMode(SimMode.FIXED) }
                )
            }
        }

        AnimatedVisibility(
            visible = isRunning,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { -it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { -it / 2 }
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.DirectionsRun, contentDescription = null, tint = RunGreen)
                    Text(
                        when {
                            mode == SimMode.FIXED -> "固定定位中"
                            isLoopMode -> "跑步中 · 第 ${lapCount + 1} 圈"
                            else -> "跑步中"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = mode == SimMode.ROUTE && routePoints.size >= 3 && !isLoopMode && !isRunning,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 16.dp, start = 16.dp),
            enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { -it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { -it / 2 }
        ) {
            ExtendedFloatingActionButton(
                onClick = { viewModel.closeLoop() },
                modifier = Modifier.semantics { contentDescription = "闭合路线" },
                icon = { Icon(Icons.Default.Loop, contentDescription = null) },
                text = { Text("闭合路线") },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            )
        }

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    val next = !satelliteOn
                    satelliteOn = next
                    mapView?.let { mv ->
                        mv.setTileSource(
                            if (next) SatelliteMapTileSource() else ChinaMapTileSource()
                        )
                        labelOverlay?.isEnabled = next && labelsOn
                        mv.invalidate()
                    }
                },
                shape = CircleShape,
                containerColor = if (satelliteOn) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.86f),
                contentColor = if (satelliteOn) Color.White else MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    Icons.Default.Satellite,
                    contentDescription = if (satelliteOn) "切换到路网地图" else "切换到卫星地图"
                )
            }
            if (satelliteOn) {
                SmallFloatingActionButton(
                    onClick = {
                        labelsOn = !labelsOn
                        labelOverlay?.isEnabled = labelsOn
                        mapView?.invalidate()
                    },
                    shape = CircleShape,
                    containerColor = if (labelsOn) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.86f),
                    contentColor = if (labelsOn) Color.White else MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        Icons.Default.Label,
                        if (labelsOn) "隐藏地名地标" else "显示地名地标"
                    )
                }
            }
            SmallFloatingActionButton(
                onClick = {
                    mapView?.let { mv ->
                        if (mv.canZoomIn()) {
                            mv.controller.zoomIn()
                        } else {
                            Toast.makeText(
                                context,
                                "已达到地图最大缩放级别",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                shape = CircleShape,
                containerColor = Color.White.copy(alpha = 0.86f)
            ) { Icon(Icons.Default.Add, "放大") }
            SmallFloatingActionButton(
                onClick = { mapView?.controller?.zoomOut() },
                shape = CircleShape,
                containerColor = Color.White.copy(alpha = 0.86f)
            ) { Icon(Icons.Default.Remove, "缩小") }
            if (!isRunning && (mode == SimMode.FIXED || !isLoopMode)) {
                SmallFloatingActionButton(
                    onClick = {
                        mapView?.mapCenter?.let { center ->
                            if (mode == SimMode.FIXED) {
                                viewModel.setFixedPoint(LatLng(center.latitude, center.longitude))
                            } else {
                                viewModel.addRoutePoint(LatLng(center.latitude, center.longitude))
                            }
                        }
                    },
                    shape = CircleShape,
                    containerColor = Color.White.copy(alpha = 0.92f)
                ) {
                    Icon(
                        Icons.Default.AddLocation,
                        if (mode == SimMode.FIXED) "把地图中心设为固定位置" else "在地图中心添加路线点"
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { locateDeviceOnce() },
            modifier = Modifier.padding(16.dp).align(Alignment.BottomStart).offset(y = locationButtonOffset),
            containerColor = MaterialTheme.colorScheme.primary
        ) { Icon(Icons.Default.MyLocation, "定位") }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .onGloballyPositioned { bottomPanelHeightPx = it.size.height },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = routePoints.size >= 2,
                enter = fadeIn(tween(180)) + slideInVertically(tween(260)) { it / 3 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(220)) { it / 3 }
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).animateContentSize(
                        spring(stiffness = Spring.StiffnessMediumLow)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("路线信息", fontWeight = FontWeight.Bold)
                            AnimatedVisibility(visible = isLoopMode, enter = fadeIn(), exit = fadeOut()) {
                                Badge(containerColor = RunGreen) {
                                    Text(
                                        "循环模式",
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val distanceKm = viewModel.getCurrentRouteDistance() / 1000
                            Text("距离: ${"%.2f".format(distanceKm)} km")
                            Text("预计: ${formatDuration(viewModel.getCurrentRouteDuration())}")
                        }
                        AnimatedVisibility(visible = isRunning, enter = fadeIn(tween(220)), exit = fadeOut(tween(150))) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = animatedProgress.value,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = RunGreen
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("进度: ${"%.1f".format(animatedProgress.value * 100)}%", fontSize = 12.sp)
                                    if (isLoopMode) Text("第 ${lapCount + 1} 圈", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (mode == SimMode.ROUTE) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).animateContentSize(
                    spring(stiffness = Spring.StiffnessMediumLow)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("目标配速: ${"%.1f".format(sliderPace)} 分/km", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        if (isRunning && animatedSpeed >= MIN_CURRENT_PACE_DISPLAY_SPEED_MPS) {
                            val currentPace = 1000f / (animatedSpeed.coerceAtLeast(0.1f) * 60f)
                            Text("当前: ${"%.1f".format(currentPace)} 分/km", fontSize = 14.sp, color = RunGreen)
                        }
                    }
                    Slider(
                        value = sliderPace,
                        onValueChange = { sliderPace = it },
                        onValueChangeFinished = { viewModel.setBasePace(sliderPace, context) },
                        valueRange = 3f..15f,
                        steps = 11
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("3'00\"", fontSize = 10.sp, color = Color.Gray)
                        Text("快", fontSize = 10.sp, color = Color.Gray)
                        Text("慢", fontSize = 10.sp, color = Color.Gray)
                        Text("15'00\"", fontSize = 10.sp, color = Color.Gray)
                    }
                }
            }
            }

            if (mode == SimMode.FIXED) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("固定定位", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        val point = fixedPoint
                        if (point == null) {
                            Text(
                                "在地图上点一下选位置，或用右下角按钮取地图中心",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        } else {
                            Text(
                                "位置: ${"%.6f".format(point.latitude)}, ${"%.6f".format(point.longitude)}",
                                fontSize = 13.sp
                            )
                            Text(
                                if (isRunning) "模拟中：系统定位固定在这个点" else "点「开始」后系统定位将固定在这个点",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (mode == SimMode.ROUTE) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            onClick = { menuOpen = true },
                            enabled = !isRunning,
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Text("场景·${scenario.label}", fontSize = 11.sp)
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            InjectScenario.entries.forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(item.label, fontSize = 14.sp)
                                            Text(item.hint, fontSize = 11.sp, color = Color.Gray)
                                        }
                                    },
                                    onClick = {
                                        viewModel.setScenario(item)
                                        menuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }

                var qualityMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { qualityMenu = true },
                        enabled = !isRunning,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text("信号·${quality.label}", fontSize = 11.sp)
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                        GpsQuality.entries.forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            "${item.label}　${item.satellitesMin}-${item.satellitesMax} 星 / ${item.accuracyTarget} m",
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            if (item.looksTooPerfect) "指标恒定不游走（最可疑的样本）"
                                            else "精度与星数缓慢游走",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setQuality(item)
                                    qualityMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { shareLatestInjectLog(context) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "导出注入日志",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { viewModel.clearRoute() },
                    enabled = !isRunning && (routePoints.isNotEmpty() || fixedPoint != null),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6F7782)),
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Icon(Icons.Default.Clear, null)
                    Spacer(Modifier.width(8.dp))
                    Text("清除", fontSize = 18.sp)
                }

                Button(
                    onClick = {
                        if (isRunning) {
                            viewModel.stopRunning(context)
                        } else if (mode == SimMode.FIXED) {
                            if (!MockLocationService.isMockEnabled(context)) {
                                uiError = RunUiError(
                                    "请先在开发者选项中「选择模拟位置信息应用」为本应用",
                                    RunUiErrorAction.DEVELOPER_SETTINGS
                                )
                            } else if (fixedPoint == null) {
                                uiError = RunUiError("请先在地图上选择一个固定位置", RunUiErrorAction.DISMISS)
                            } else {
                                viewModel.startFixed(context)
                            }
                        } else if (routePoints.size >= 2) {
                            if (!MockLocationService.isMockEnabled(context)) {
                                uiError = RunUiError(
                                    "请先在开发者选项中「选择模拟位置信息应用」为本应用",
                                    RunUiErrorAction.DEVELOPER_SETTINGS
                                )
                            } else viewModel.startRunning(context)
                        } else {
                            uiError = RunUiError("请至少选择2个点", RunUiErrorAction.DISMISS)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    val actionLabel = if (isRunning) "停止" else "开始"
                    Crossfade(targetState = actionLabel, animationSpec = tween(180), label = "run action") { label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (label == "开始") Icons.Default.PlayArrow else Icons.Default.Stop, null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }

    uiError?.let { error ->
        AlertDialog(
            onDismissRequest = { uiError = null },
            title = { Text("提示") },
            text = { Text(error.message) },
            confirmButton = {
                TextButton(onClick = {
                    uiError = null
                    when (error.action) {
                        RunUiErrorAction.DISMISS -> Unit
                        RunUiErrorAction.APP_SETTINGS -> try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } catch (_: Exception) {
                            Toast.makeText(context, "请手动打开应用权限设置", Toast.LENGTH_SHORT).show()
                        }
                        RunUiErrorAction.DEVELOPER_SETTINGS -> try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (_: Exception) {
                            Toast.makeText(context, "请手动打开开发者选项", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text(if (error.action == RunUiErrorAction.DISMISS) "知道了" else "去设置")
                }
            },
            dismissButton = if (error.action == RunUiErrorAction.DISMISS) null else {
                { TextButton(onClick = { uiError = null }) { Text("取消") } }
            }
        )
    }
}

/** 分享最新一份注入真值日志（CSV），用于检测端做漏报/误报评估。 */
private fun shareLatestInjectLog(context: android.content.Context) {
    val file = InjectLog.listSessions(context).firstOrNull()
    if (file == null) {
        Toast.makeText(context, "还没有注入日志，先跑一次再说", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出注入日志"))
    } catch (e: Exception) {
        Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
    }
}

/** 顶部模式切换胶囊：路线跑 / 固定定位。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White.copy(alpha = 0.92f),
            labelColor = Color(0xFF2B2B33),
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = Color.White
        )
    )
}

fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", minutes, secs)
}
