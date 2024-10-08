package com.geo.tracking.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Paint.Style
import android.graphics.Point
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.geo.tracking.R
import com.geo.tracking.data.models.Anomaly
import com.geo.tracking.data.models.LocationData
import com.geo.tracking.data.models.TripStatus
import com.geo.tracking.ui.components.MapInfoWindowContent
import org.osmdroid.api.IMapController
import org.osmdroid.api.IMapView
import org.osmdroid.events.MapAdapter
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.IOverlayMenuProvider
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import java.time.LocalDateTime
import java.util.LinkedList
import java.util.Random

class MapActivity(
    initialPoint: Location,
    private val mapView: MapView,
    private var myLocationProvider: IMyLocationProvider = GpsMyLocationProvider(mapView.context)
) : Overlay(), IMyLocationConsumer, IOverlayMenuProvider, Overlay.Snappable {

    private val paint = Paint().apply { isFilterBitmap = true }
    private val circlePaint = Paint().apply {
        setARGB(0, 100, 100, 255)
        isAntiAlias = true
    }

    private var lastWaveUpdateTime = 0L
    private val waveDuration = 2000L
    private val waveMaxAlpha = 150
    private val wavePaint = Paint().apply {
        setARGB(0, 100, 100, 255)
        isAntiAlias = true
        style = Style.FILL
    }
    private val frameDelay = 16L
    private val locationList = mutableListOf<Location>()
    private var polyline: Polyline? = null

    private var infoWindowBitmap: Bitmap? = null
    private var directionArrowBitmap: Bitmap? = null
    private var mapController: IMapController? = mapView.controller
    private var location: Location = initialPoint
    private var lastLoc: Location? = null
    private val geoPoint = GeoPoint(initialPoint.latitude, initialPoint.longitude)
    private var isLocationEnabled = false
    private var isFollowing = false
    private var drawAccuracyEnabled = true
    private var directionArrowCenterX = 0f
    private var directionArrowCenterY = 0f
    private var enableAutoStop = true
    private val drawPixel = Point()
    private val snapPixel = Point()
    private val handler = Handler(Looper.getMainLooper())
    private val runOnFirstFix = LinkedList<Runnable>()
    private var optionsMenuEnabled = true
    private var wasEnabledOnPause = false


    //trip riding variables
    private var onGoing = true
    private var tripId = ""
    private var userId = ""
    private var vehicleId = ""
    private var startTime: LocalDateTime? = null
    private var endTime: LocalDateTime? = null
    private var startLocation: LocationData? = null
    private var currentLocation: LocationData? = null
    private var destinationLocation: LocationData? = null
    private var locationHistory: List<LocationData> = emptyList()
    private var distanceTravelled: Double = 0.0
    private var tripStatus: TripStatus? = null
    private var timestamp: Long = 0
    private var batteryLevel: Int = 0
    private var networkType: String = ""
    private var deviceOrientation: String = ""
    private var tripDuration: Long = 0
    private var busOccupancy: Int = 0
    private var predictedTravelTime: Long = 0
    private var predictedBusOccupancy: Int = 0
    private var predictedRoute: List<LocationData> = emptyList()
    private var anomaliesDetected: List<Anomaly> = emptyList()

    init {
        initializeIcons()
    }

    private fun initializeIcons() {
        setDirectionIcon(ContextCompat.getDrawable(mapView.context, R.drawable.rocket_direction)!!)
        setDirectionAnchor()
        setupMap()
    }

    private fun setupMap() {
        mapView.addMapListener(object : MapAdapter() {
            override fun onZoom(event: ZoomEvent?): Boolean {
                updatePolylineWidth()
                return super.onZoom(event)
            }
        })
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (isLocationEnabled) {
            drawMyLocation(canvas, projection, location)
        }
    }

    private fun drawMyLocation(canvas: Canvas, projection: Projection, lastFix: Location) {
        projection.toPixels(geoPoint, drawPixel)
        drawAccuracyCircle(canvas, lastFix, projection)
        drawDirectionArrow(canvas, lastFix)
        drawPersonIcon(canvas)
        drawFlamingRocket(canvas, lastFix)
    }

    private fun drawAccuracyCircle(canvas: Canvas, lastFix: Location, projection: Projection) {
        if (drawAccuracyEnabled) {
            val radius = (lastFix.accuracy * 5) / TileSystem.GroundResolution(
                lastFix.latitude,
                projection.zoomLevel
            ).toFloat()
            circlePaint.alpha = 50
            circlePaint.style = Style.FILL
            canvas.drawCircle(drawPixel.x.toFloat(), drawPixel.y.toFloat(), radius, circlePaint)

            circlePaint.alpha = 150
            circlePaint.style = Style.STROKE
            canvas.drawCircle(drawPixel.x.toFloat(), drawPixel.y.toFloat(), radius, circlePaint)

            if (lastWaveUpdateTime == 0L) {
                lastWaveUpdateTime = System.currentTimeMillis()
            }

            val currentTime = System.currentTimeMillis()
            val elapsedTime = currentTime - lastWaveUpdateTime
            var progress = elapsedTime.toFloat() / waveDuration

            if (progress >= 1.0f) {
                lastWaveUpdateTime = currentTime
                progress = 0f
            }

            val waveRadius = progress * radius
            val alpha = (1.0f - progress) * waveMaxAlpha
            wavePaint.alpha = alpha.toInt()

            canvas.drawCircle(drawPixel.x.toFloat(), drawPixel.y.toFloat(), waveRadius, wavePaint)

            mapView.postInvalidateDelayed(frameDelay)
        }
    }

    private fun drawDirectionArrow(canvas: Canvas, lastFix: Location) {
        canvas.save()
        val mapRotation = lastFix.bearing % 360f
        canvas.rotate(mapRotation, drawPixel.x.toFloat(), drawPixel.y.toFloat())
        directionArrowBitmap?.let {
            canvas.drawBitmap(
                it,
                drawPixel.x - directionArrowCenterX,
                drawPixel.y - directionArrowCenterY,
                paint
            )
        }
        canvas.restore()
    }

    private fun drawPersonIcon(canvas: Canvas) {
        val personIconX = drawPixel.x - directionArrowCenterX
        val personIconY = drawPixel.y - directionArrowCenterY
        infoWindowBitmap?.let { infoBitmap ->
            val infoWindowX = (personIconX - (infoBitmap.width / 2.25)).toFloat()
            val infoWindowY = personIconY - infoBitmap.height - 25
            canvas.drawBitmap(infoBitmap, infoWindowX, infoWindowY, paint)
        }
    }

    private fun drawFlamingRocket(canvas: Canvas, lastFix: Location) {
        canvas.save()
        val mapRotation = lastFix.bearing % 360f
        canvas.rotate(mapRotation, drawPixel.x.toFloat(), drawPixel.y.toFloat())

        val flameSpeed = if (lastFix.speed < 0.5) {
            0.05F
        } else {
            lastFix.speed / 10
        }

        renderFlameAnimation(canvas, flameSpeed)

        directionArrowBitmap?.let {
            canvas.drawBitmap(
                it,
                drawPixel.x - directionArrowCenterX,
                drawPixel.y - directionArrowCenterY,
                paint
            )
        }
        canvas.restore()
    }

    private fun renderFlameAnimation(canvas: Canvas, speed: Float) {
        val flameSize = speed * 100f

        val flamePaint = Paint().apply {
            isAntiAlias = true
            style = Style.FILL
        }

        flamePaint.shader = LinearGradient(
            drawPixel.x.toFloat(),
            drawPixel.y + directionArrowCenterY,
            drawPixel.x.toFloat(),
            drawPixel.y + directionArrowCenterY + flameSize,
            intArrayOf(Color.RED, Color.parseColor("#FF4500"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(
            drawPixel.x - flameSize / 4,
            drawPixel.y + directionArrowCenterY - 15,
            drawPixel.x + flameSize / 4,
            drawPixel.y + directionArrowCenterY + flameSize,
            flamePaint
        )

        flamePaint.shader = LinearGradient(
            drawPixel.x.toFloat(),
            drawPixel.y + directionArrowCenterY,
            drawPixel.x.toFloat(),
            drawPixel.y + directionArrowCenterY + flameSize * 0.7f,
            intArrayOf(Color.YELLOW, Color.parseColor("#FFA500"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(
            drawPixel.x - flameSize * 0.7f / 4,
            drawPixel.y + directionArrowCenterY - 15,
            drawPixel.x + flameSize * 0.7f / 4,
            drawPixel.y + directionArrowCenterY + flameSize * 0.7f,
            flamePaint
        )

        flamePaint.shader = LinearGradient(
            drawPixel.x.toFloat(),
            drawPixel.y + directionArrowCenterY,
            drawPixel.x.toFloat(),
            drawPixel.y + directionArrowCenterY + flameSize * 0.4f,
            intArrayOf(Color.WHITE, Color.YELLOW, Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(
            drawPixel.x - flameSize * 0.4f / 4,
            drawPixel.y + directionArrowCenterY - 15,
            drawPixel.x + flameSize * 0.4f / 4,
            drawPixel.y + directionArrowCenterY + flameSize * 0.4f,
            flamePaint
        )

        renderSparks(canvas, flameSize, speed)
    }

    private fun renderSparks(canvas: Canvas, flameSize: Float, speed: Float) {
        val sparksCount = (speed * 0.2f).toInt().coerceAtLeast(5)
        val random = Random()

        val sparkPaint = Paint().apply {
            isAntiAlias = true
            style = Style.FILL
            color = Color.YELLOW
        }

        for (i in 0 until sparksCount) {
            val xOffset = random.nextFloat() * flameSize - flameSize / 2
            val yOffset = random.nextFloat() * flameSize + directionArrowCenterY
            val sparkSize = random.nextFloat() * 5f + 2f

            sparkPaint.alpha = (random.nextFloat() * 200 + 55).toInt()

            canvas.drawCircle(
                drawPixel.x + xOffset,
                drawPixel.y + yOffset,
                sparkSize,
                sparkPaint
            )
        }
    }

    private fun renderInfoWindow(position: Location) {
        renderComposableToBitmapSafely(mapView.context, position) { bitmap ->
            infoWindowBitmap = bitmap
            mapView.postInvalidate()
        }
    }


    fun updateInfoWindow(position: Location) {
        renderInfoWindow(position)
        mapView.postInvalidate()
    }

    override fun onSnapToItem(x: Int, y: Int, snapPoint: Point, mapView: IMapView?): Boolean {
        location.let {
            mapView?.projection?.toPixels(geoPoint, snapPixel)
            snapPoint.set(snapPixel.x, snapPixel.y)
            val xDiff = (x - snapPixel.x).toDouble()
            val yDiff = (y - snapPixel.y).toDouble()
            return xDiff * xDiff + yDiff * yDiff < 64
        }
    }

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        val isSingleFingerDrag = event.action == MotionEvent.ACTION_MOVE && event.pointerCount == 1
        if (event.action == MotionEvent.ACTION_DOWN && enableAutoStop) {
            disableFollowLocation()
        } else if (isSingleFingerDrag && isFollowing) {
            return true
        }
        return super.onTouchEvent(event, mapView)
    }

    override fun onResume() {
        super.onResume()
        if (wasEnabledOnPause) enableFollowLocation()
        enableMyLocation()
    }

    override fun onPause() {
        wasEnabledOnPause = isFollowing
        disableMyLocation()
        super.onPause()
    }

    override fun onDetach(mapView: MapView?) {
        disableMyLocation()
        mapController = null
        directionArrowBitmap = null
        handler.removeCallbacksAndMessages(null)
        myLocationProvider.destroy()
        super.onDetach(mapView)
    }

    override fun setOptionsMenuEnabled(pOptionsMenuEnabled: Boolean) {
        optionsMenuEnabled = pOptionsMenuEnabled
    }

    override fun isOptionsMenuEnabled(): Boolean = optionsMenuEnabled

    override fun onCreateOptionsMenu(menu: Menu, pMenuIdOffset: Int, pMapView: MapView?): Boolean {
        menu.add(
            0,
            MENU_MY_LOCATION + pMenuIdOffset,
            Menu.NONE,
            pMapView?.context?.resources?.getString(R.string.my_location)
        )
            .setIcon(
                ContextCompat.getDrawable(
                    pMapView?.context!!,
                    R.drawable.round_my_location_24
                )
            )
            .isCheckable = true

        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu, pMenuIdOffset: Int, pMapView: MapView?): Boolean {
        menu.findItem(MENU_MY_LOCATION + pMenuIdOffset).isChecked = isLocationEnabled
        return false
    }

    override fun onOptionsItemSelected(
        item: MenuItem,
        pMenuIdOffset: Int,
        pMapView: MapView?
    ): Boolean {
        if (item.itemId - pMenuIdOffset == MENU_MY_LOCATION) {
            if (isLocationEnabled) {
                disableFollowLocation()
                disableMyLocation()
            } else {
                enableFollowLocation()
                enableMyLocation()
            }
            return true
        }
        return false
    }

    fun enableFollowLocation() {
        isFollowing = true
        myLocationProvider.lastKnownLocation?.let { setLocation(it) }
        mapView.postInvalidate()
    }

    private fun disableFollowLocation() {
        mapController?.stopAnimation(false)
        isFollowing = false
    }

    fun enableMyLocation(): Boolean {
        return myLocationProvider.startLocationProvider(this).also {
            isLocationEnabled = it
            myLocationProvider.lastKnownLocation?.let { loc -> setLocation(loc) }
            mapView.postInvalidate()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !handler.hasCallbacks(animationRunnable)
            ) {
                handler.post(animationRunnable)
            }
        }
    }

    private fun disableMyLocation() {
        isLocationEnabled = false
        myLocationProvider.stopLocationProvider()
        handler.removeCallbacks(animationRunnable)
        mapView.postInvalidate()
    }

    private fun setLocation(loc: Location) {
        val lastIndex = locationList.size - 1

        val distance =
            lastLoc?.distanceTo(loc) ?: locationList.getOrNull(lastIndex)?.distanceTo(loc) ?: 0.0F
        val firstDistance =
            if (lastIndex >= 1) locationList[lastIndex - 1].distanceTo(loc) else 30.0F
        val secondDistance =
            if (lastIndex >= 2) locationList[lastIndex - 2].distanceTo(loc) else 40.0F
//        val thirdDistance =
//            if (lastIndex >= 3) locationList[lastIndex - 3].distanceTo(loc) else 50.0F

        if (distance in 10.0F..30.0F &&
            firstDistance > 20.0F &&
            secondDistance > 30.0F //&&
//            thirdDistance > 40.0F
        ) {
            this.location = loc
            lastLoc = null
            geoPoint.setCoords(location.latitude, location.longitude)
            locationList.add(loc)

            if (isFollowing) {
                mapController?.animateTo(geoPoint)
                mapController?.setCenter(geoPoint)
            } else {
                mapView.postInvalidate()
            }

            updateInfoWindow(location)
            updatePolyline()
        } else if (distance > 10.0F) {
            if (locationList.isEmpty()) {
                locationList.add(loc)
            } else {
                lastLoc = loc

            }
        }
    }


    private fun updatePolyline() {
        if (locationList.isNotEmpty()) {
            if (polyline == null) {
                polyline = Polyline().apply {
                    outlinePaint.color = Color.BLUE
                    mapView.overlays.add(this)
                }
            }

            polyline?.apply {
                setPoints(locationList.map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.strokeWidth = mapView.zoomLevelDouble.toFloat()
            }

            mapView.invalidate()
        }
    }

    private fun updatePolylineWidth() {
        polyline?.outlinePaint?.strokeWidth = mapView.zoomLevelDouble.toFloat()
        mapView.invalidate()
    }

    private fun setDirectionIcon(drawable: Drawable) {
        directionArrowBitmap = drawable.toBitmap()
    }

    private fun setDirectionAnchor() {
        directionArrowBitmap?.let {
            directionArrowCenterX = it.width * 0.5f
            directionArrowCenterY = it.height * 0.5f
        }
    }

    private fun Drawable.toBitmap(): Bitmap {
        return when (this) {
            is BitmapDrawable -> this.bitmap
            is VectorDrawable -> {
                val bitmap =
                    Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                setBounds(0, 0, canvas.width, canvas.height)
                draw(canvas)
                bitmap
            }

            else -> throw IllegalArgumentException("Unsupported drawable type")
        }
    }

    private fun renderComposableToBitmapSafely(
        context: Context,
        position: Location,
        onBitmapReady: (Bitmap) -> Unit
    ) {
        val frameLayout = FrameLayout(context)
        val composeView = ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MapInfoWindowContent(location = position)
            }
        }

        frameLayout.addView(composeView)

        val rootView =
            (context as Activity).window.decorView.findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(frameLayout)

        frameLayout.doOnLayout {
            val width = composeView.width
            val height = composeView.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            frameLayout.draw(canvas)

            rootView.removeView(frameLayout)
            onBitmapReady(bitmap)
        }
    }

    companion object {
        private const val MENU_MY_LOCATION = 1
    }

    override fun onLocationChanged(location: Location, source: IMyLocationProvider) {
        handler.postAtTime({
            setLocation(location)
            runOnFirstFix.forEach {
                Thread(it).apply { name = this.javaClass.name + "#onLocationChanged" }.start()
            }
            runOnFirstFix.clear()
        }, Any(), 0)
    }

    private val animationRunnable = object : Runnable {
        override fun run() {
            mapView.postInvalidate()
            handler.postDelayed(this, frameDelay)
        }
    }
}
