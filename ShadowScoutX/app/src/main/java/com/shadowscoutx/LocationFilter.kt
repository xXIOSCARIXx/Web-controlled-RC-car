package com.shadowscoutx

import android.location.Location

class LocationFilter {
    private var latX = 0.0
    private var latP = 1.0
    
    private var lonX = 0.0
    private var lonP = 1.0
    
    private var altX = 0.0
    private var altP = 1.0
    
    private val q = 1e-7
    
    private var isInitialized = false

    fun filter(location: Location): Location {
        val accuracy = location.accuracy.toDouble().coerceAtLeast(1.0)
        
        val degreeAccuracy = accuracy * 0.000009
        val r = degreeAccuracy * degreeAccuracy

        if (!isInitialized) {
            latX = location.latitude
            lonX = location.longitude
            altX = location.altitude
            latP = r
            lonP = r
            altP = accuracy * accuracy
            isInitialized = true
            return location
        }

        latP += q
        val latK = latP / (latP + r)
        latX += latK * (location.latitude - latX)
        latP *= (1 - latK)

        lonP += q
        val lonK = lonP / (lonP + r)
        lonX += lonK * (location.longitude - lonX)
        lonP *= (1 - lonK)
        
        val filtered = Location(location)
        filtered.latitude = latX
        filtered.longitude = lonX
        
        if (location.hasAltitude()) {
            val altR = accuracy * accuracy
            altP += q * 100
            val altK = altP / (altP + altR)
            altX += altK * (location.altitude - altX)
            altP *= (1 - altK)
            filtered.altitude = altX
        }

        return filtered
    }
}
