package com.gennakersystems.locshare

import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Geo {
    /** How wide (east-west, in meters) the map viewport is once a precise fix exists. */
    const val VIEW_WIDTH_METERS = 30.0

    /**
     * A fix is "precise" once its reported accuracy is at or below this radius.
     *
     * Kept below a quarter of [VIEW_WIDTH_METERS] so the accuracy circle fits
     * inside the viewport it triggers: at 20 m the circle was 40 m across and
     * overflowed the 30 m view entirely, which made the zoom meaningless.
     */
    const val PRECISE_ACCURACY_METERS = 8f

    private const val M_PER_DEG_LAT = 111_320.0

    private fun metersPerDegLng(lat: Double): Double = M_PER_DEG_LAT * cos(lat * PI / 180.0)

    /** Bounds whose east-west span is [widthMeters]; the north-south span is ±1 m so width drives the camera fit. */
    fun boundsAround(lat: Double, lng: Double, widthMeters: Double = VIEW_WIDTH_METERS): LatLngBounds {
        val halfLng = (widthMeters / 2.0) / metersPerDegLng(lat)
        val halfLat = 1.0 / M_PER_DEG_LAT
        return LatLngBounds.from(lat + halfLat, lng + halfLng, lat - halfLat, lng - halfLng)
    }

    /** A ~circular polygon around a point; radius in meters. */
    fun circlePolygon(lat: Double, lng: Double, radiusMeters: Double, points: Int = 64): Polygon {
        val dLat = radiusMeters / M_PER_DEG_LAT
        val dLng = radiusMeters / metersPerDegLng(lat)
        val ring = ArrayList<Point>(points + 1)
        for (i in 0..points) {
            val a = 2 * PI * i / points
            ring.add(Point.fromLngLat(lng + dLng * cos(a), lat + dLat * sin(a)))
        }
        return Polygon.fromLngLats(listOf(ring))
    }
}
