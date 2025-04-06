package com.example.ginga_nowayhome

data class RouteResponse(val features: List<Feature>)
data class Feature(val geometry: Geometry)
data class Geometry(val coordinates: List<List<Double>>)