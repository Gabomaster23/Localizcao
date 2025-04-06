package com.example.ginga_nowayhome

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.*
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.Polyline
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : ComponentActivity() {

    private val REQUEST_LOCATION = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION
            )
        }

        setContent {
            MapWithRoutesScreen()
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MapWithRoutesScreen() {
    val context = LocalContext.current
    val permisoUbicacion = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    val mapa = remember { MapView(context) }

    var ubicacionUsuario by remember { mutableStateOf<GeoPoint?>(null) }
    var puntoCasa by remember { mutableStateOf<GeoPoint?>(null) }
    val marcadorTemporal = remember { mutableStateOf<Marker?>(null) }
    val coordenadasSeleccionadas = remember { mutableStateOf<GeoPoint?>(null) }
    var rutaActual: Polyline? = null

    fun recuperarCasa(context: Context): GeoPoint? {
        val prefs = context.getSharedPreferences("prefs_app", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("casa_lat", 0f)
        val lon = prefs.getFloat("casa_lon", 0f)
        return if (lat != 0f && lon != 0f) GeoPoint(lat.toDouble(), lon.toDouble()) else null
    }

    fun guardarCasa(context: Context, punto: GeoPoint) {
        context.getSharedPreferences("prefs_app", Context.MODE_PRIVATE).edit().apply {
            putFloat("casa_lat", punto.latitude.toFloat())
            putFloat("casa_lon", punto.longitude.toFloat())
            apply()
        }
    }

    fun pintarRuta(mapa: MapView, listaCoords: List<List<Double>>) {
        rutaActual?.let { mapa.overlays.remove(it) }

        rutaActual = Polyline().apply {
            setPoints(listaCoords.map { GeoPoint(it[1], it[0]) })
            width = 5f
        }

        mapa.overlays.add(rutaActual)
        mapa.invalidate()
    }

    fun borrarRuta() {
        rutaActual?.let {
            mapa.overlays.remove(it)
            rutaActual = null
            mapa.invalidate()
        }
    }

    fun generarRuta(inicio: String, destino: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val api = getRetrofit().create(ApiService::class.java)
            val respuesta = api.getRoute("5b3ce3597851110001cf62480181b45d1a924d79ae1d3dfd66e73e30", inicio, destino)
            if (respuesta.isSuccessful) {
                val datos = respuesta.body()
                val coords = datos?.features?.firstOrNull()?.geometry?.coordinates ?: emptyList()
                withContext(Dispatchers.Main) {
                    pintarRuta(mapa, coords)
                }
            } else {
                Log.e("RUTA_ERROR", "Falló la solicitud de ruta")
            }
        }
    }

    LaunchedEffect(permisoUbicacion.status) {
        if (permisoUbicacion.status.isGranted) {
            obtenerUbicacion(context) { loc ->
                mapa.setTileSource(TileSourceFactory.MAPNIK)
                mapa.setMultiTouchControls(true)

                val geoUsuario = GeoPoint(loc.latitude, loc.longitude)
                ubicacionUsuario = geoUsuario

                mapa.controller.apply {
                    setZoom(16.0)
                    setCenter(geoUsuario)
                }

                val drawablePin = ContextCompat.getDrawable(context, R.drawable.alfiler)!!
                val pinBitmap = (drawablePin as BitmapDrawable).bitmap
                val iconoPin = BitmapDrawable(context.resources, Bitmap.createScaledBitmap(pinBitmap, 22, 22, false))

                val markerUsuario = Marker(mapa).apply {
                    position = geoUsuario
                    icon = iconoPin
                    title = "Estás aquí"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }

                mapa.overlays.add(markerUsuario)

                puntoCasa = recuperarCasa(context)
                puntoCasa?.let { casa ->
                    val iconoCasa = ContextCompat.getDrawable(context, R.drawable.casa)!!
                    val bmpCasa = (iconoCasa as BitmapDrawable).bitmap
                    val iconoScaled = BitmapDrawable(context.resources, Bitmap.createScaledBitmap(bmpCasa, 22, 22, false))

                    val marcadorCasa = Marker(mapa).apply {
                        position = casa
                        icon = iconoScaled
                        title = "Casa guardada"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    mapa.overlays.add(marcadorCasa)
                }

                val receptorEventos = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        p?.let {
                            marcadorTemporal.value?.let { marcador -> mapa.overlays.remove(marcador) }

                            val nuevo = Marker(mapa).apply {
                                position = it
                                icon = iconoPin
                                title = "Nuevo punto"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            }
                            marcadorTemporal.value = nuevo
                            coordenadasSeleccionadas.value = it
                            mapa.overlays.add(nuevo)
                            mapa.invalidate()
                        }
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint?) = false
                }

                mapa.overlays.add(MapEventsOverlay(receptorEventos))
                mapa.invalidate()

                ubicacionUsuario?.let { origen ->
                    puntoCasa?.let { destino ->
                        generarRuta("${origen.longitude},${origen.latitude}", "${destino.longitude},${destino.latitude}")
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { mapa }, modifier = Modifier.fillMaxSize())

            // Botón para centrar ubicación
            FloatingActionButton(
                onClick = {
                    ubicacionUsuario?.let { mapa.controller.setCenter(it) }
                },
                containerColor = colorResource(id = R.color.blue_500),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Text("📍")
            }

            // Botón para ir al punto seleccionado (ruta más corta)
            Button(
                onClick = {
                    borrarRuta()
                    val destino = coordenadasSeleccionadas.value
                    val origen = ubicacionUsuario
                    if (origen != null && destino != null) {
                        generarRuta("${origen.longitude},${origen.latitude}", "${destino.longitude},${destino.latitude}")
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(id = R.color.teal_700),
                    contentColor = colorResource(id = R.color.white)
                ),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 80.dp)
                    .height(48.dp)
            ) {
                Text("🧭")
            }

            // Botón para establecer casa
            Button(
                onClick = {
                    borrarRuta()
                    coordenadasSeleccionadas.value?.let { punto ->
                        mapa.overlays.removeAll { it is Marker && it.title == "Casa guardada" }

                        val drawableCasa = ContextCompat.getDrawable(context, R.drawable.casa)!!
                        val bmpCasa = (drawableCasa as BitmapDrawable).bitmap
                        val iconoCasa = BitmapDrawable(context.resources, Bitmap.createScaledBitmap(bmpCasa, 22, 22, false))

                        guardarCasa(context, punto)

                        val marcador = Marker(mapa).apply {
                            position = punto
                            icon = iconoCasa
                            title = "Casa guardada"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }

                        mapa.overlays.add(marcador)
                        mapa.invalidate()

                        ubicacionUsuario?.let { origen ->
                            generarRuta("${origen.longitude},${origen.latitude}", "${punto.longitude},${punto.latitude}")
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(id = R.color.accent_orange),
                    contentColor = colorResource(id = R.color.white)
                ),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp)
                    .height(48.dp)
            ) {
                Text("🏠")
            }
        }
    }
}

@SuppressLint("MissingPermission")
fun obtenerUbicacion(context: Context, callback: (Location) -> Unit) {
    val cliente = LocationServices.getFusedLocationProviderClient(context)
    cliente.lastLocation
        .addOnSuccessListener { loc -> loc?.let(callback) }
        .addOnFailureListener { Log.e("Ubicacion", "Error al obtener ubicación", it) }
}

private fun getRetrofit(): Retrofit = Retrofit.Builder()
    .baseUrl("https://api.openrouteservice.org/")
    .addConverterFactory(GsonConverterFactory.create())
    .build()
