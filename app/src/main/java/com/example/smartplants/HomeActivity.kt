package com.example.smartplants

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class HomeActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "HomeActivity"
        // Configurazione server (stessa dell'altra activity)
        private const val IP_SERVER = "172.20.10.2"
        private const val INFLUX_PORT = "8086"
        private const val INFLUX_TOKEN = "CwlazpO4oqRT4I1qYlAJ2E50rwv9JaJE00ENjqvcDSVgFE4vG1dGBO15uL2ug4B1aXE8GorqDPhRXYAAPUlSwg=="
        private const val INFLUX_ORG = "scuola"
        private const val INFLUX_BUCKET = "smart_plant"
        // Configurazione MQTT - rimossa interpolazione dalla const val
        private const val MQTT_PORT = "1883"
        private const val MQTT_TOPIC_SELECTION = "smart_plant/piantaSelezionata"
        private const val MQTT_TOPIC_SENSORS = "smart_plant/dati_sensori"

        // Funzione per ottenere l'URL del broker MQTT
        private fun getMqttBroker(): String = "tcp://$IP_SERVER:$MQTT_PORT"

        // Funzione per ottenere il client ID MQTT
        private fun getMqttClientId(): String = "SmartPlantApp_${System.currentTimeMillis()}"
    }

    private lateinit var tvWelcome: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var cardQuickActions: CardView

    // Elementi per la selezione piante
    private lateinit var cardPlantSelector: CardView
    private lateinit var spinnerPlants: Spinner
    private lateinit var btnSavePlant: Button
    private lateinit var tvSelectedPlant: TextView

    // Elementi per i dati sensori
    private lateinit var cardSensorData: CardView
    private lateinit var tvSoilHumidity: TextView
    private lateinit var tvAirHumidity: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvLastUpdate: TextView

    // Elemento SwipeRefreshLayout
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private var plantsList = mutableListOf<String>()
    private var plantsAdapter: ArrayAdapter<String>? = null
    private var mqttClient: MqttClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        initViews()
        setupUI()
        setupPlantSelector()
        setupSwipeRefresh()
        loadPlantsFromDatabase()
        connectToMqtt()
    }

    private fun initViews() {
        tvWelcome = findViewById(R.id.tv_welcome)
        tvSubtitle = findViewById(R.id.tv_subtitle)
        cardQuickActions = findViewById(R.id.card_quick_actions)

        // Elementi selezione piante
        cardPlantSelector = findViewById(R.id.card_plant_selector)
        spinnerPlants = findViewById(R.id.spinner_plants)
        btnSavePlant = findViewById(R.id.btn_save_plant)
        tvSelectedPlant = findViewById(R.id.tv_selected_plant)

        // Elementi dati sensori
        cardSensorData = findViewById(R.id.card_sensor_data)
        tvSoilHumidity = findViewById(R.id.tv_soil_humidity)
        tvAirHumidity = findViewById(R.id.tv_air_humidity)
        tvTemperature = findViewById(R.id.tv_temperature)
        tvLastUpdate = findViewById(R.id.tv_last_update)

        // SwipeRefreshLayout
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            // Quando l'utente trascina verso il basso per aggiornare
            Log.d(TAG, "Pull-to-refresh triggered")
            refreshData()
        }

        // Personalizza i colori dell'indicatore di refresh
        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_green_dark,
            android.R.color.holo_blue_dark,
            android.R.color.holo_orange_dark
        )
    }

    private fun refreshData() {
        Log.d(TAG, "Refreshing plant data...")

        // Mostra un messaggio di aggiornamento
        Toast.makeText(this, "🔄 Aggiornamento piante...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                // Ricarica le piante dal database
                val plants = withContext(Dispatchers.IO) {
                    getPlantsFromInfluxDB()
                }

                runOnUiThread {
                    if (plants.isNotEmpty()) {
                        plantsList.clear()
                        plantsList.addAll(plants)
                        plantsAdapter?.notifyDataSetChanged()
                        tvSelectedPlant.text = "Seleziona una pianta dal menu (aggiornato)"
                        Log.d(TAG, "Refreshed ${plants.size} plants: $plants")

                        // Mostra messaggio di successo
                        Toast.makeText(this@HomeActivity,
                            "✅ Aggiornate ${plants.size} piante!",
                            Toast.LENGTH_SHORT).show()
                    } else {
                        tvSelectedPlant.text = "Nessuna pianta trovata nel database"
                        Log.w(TAG, "No plants found in database after refresh")

                        Toast.makeText(this@HomeActivity,
                            "⚠ Nessuna pianta trovata",
                            Toast.LENGTH_SHORT).show()
                    }

                    // Nasconde l'indicatore di refresh
                    swipeRefreshLayout.isRefreshing = false
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing plants from database", e)
                runOnUiThread {
                    tvSelectedPlant.text = "Errore nell'aggiornamento delle piante"

                    Toast.makeText(this@HomeActivity,
                        "❌ Errore aggiornamento: ${e.message}",
                        Toast.LENGTH_LONG).show()

                    // Nasconde l'indicatore di refresh anche in caso di errore
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    private fun setupUI() {
        // Imposta il testo di benvenuto
        tvWelcome.text = "Benvenuto in Smart Plants"
        tvSubtitle.text = "Monitora e gestisci le tue piante intelligenti"

        // Setup click listeners per le card
        cardQuickActions.setOnClickListener {
            // Naviga alla MainActivity per aggiungere una nuova pianta
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }

        cardSensorData.setOnClickListener {
            // Qui in futuro si potranno aggiungere dettagli sensori
            Toast.makeText(this, "Dettagli sensori in arrivo...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPlantSelector() {
        // Inizializza l'adapter per lo spinner
        plantsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, plantsList)
        plantsAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPlants.adapter = plantsAdapter

        // Setup listener per lo spinner
        spinnerPlants.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (plantsList.isNotEmpty() && position >= 0) {
                    val selectedPlant = plantsList[position]
                    tvSelectedPlant.text = "Pianta selezionata: $selectedPlant"
                    btnSavePlant.isEnabled = true
                    Log.d(TAG, "Plant selected: $selectedPlant")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                tvSelectedPlant.text = "Nessuna pianta selezionata"
                btnSavePlant.isEnabled = false
            }
        }

        // Setup listener per il pulsante salva
        btnSavePlant.setOnClickListener {
            savePlantSelection()
        }

        // Inizialmente disabilita il pulsante
        btnSavePlant.isEnabled = false
    }

    private fun loadPlantsFromDatabase() {
        Log.d(TAG, "Loading plants from InfluxDB...")

        lifecycleScope.launch {
            try {
                runOnUiThread {
                    // Mostra loading
                    tvSelectedPlant.text = "Caricamento piante..."
                }

                val plants = withContext(Dispatchers.IO) {
                    getPlantsFromInfluxDB()
                }

                runOnUiThread {
                    if (plants.isNotEmpty()) {
                        plantsList.clear()
                        plantsList.addAll(plants)
                        plantsAdapter?.notifyDataSetChanged()
                        tvSelectedPlant.text = "Seleziona una pianta dal menu"
                        Log.d(TAG, "Loaded ${plants.size} plants: $plants")
                    } else {
                        tvSelectedPlant.text = "Nessuna pianta trovata nel database"
                        Log.w(TAG, "No plants found in database")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading plants from database", e)
                runOnUiThread {
                    tvSelectedPlant.text = "Errore nel caricamento delle piante"
                    Toast.makeText(this@HomeActivity,
                        "Errore caricamento piante: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun getPlantsFromInfluxDB(): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val fluxQuery = """
                    from(bucket: "$INFLUX_BUCKET")
                    |> range(start: -30d)
                    |> filter(fn: (r) => r["_measurement"] == "Piante")
                    |> filter(fn: (r) => r["_field"] == "nome")
                    |> distinct(column: "_value")
                    |> yield(name: "plants")
                """.trimIndent()

                Log.d(TAG, "InfluxDB Query for plants: $fluxQuery")

                val url = URL("http://$IP_SERVER:$INFLUX_PORT/api/v2/query?org=$INFLUX_ORG")
                val connection = url.openConnection() as HttpURLConnection

                connection.apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Authorization", "Token $INFLUX_TOKEN")
                    setRequestProperty("Content-Type", "application/vnd.flux")
                    setRequestProperty("Accept", "application/csv")
                    doOutput = true
                }

                connection.outputStream.use { outputStream ->
                    outputStream.write(fluxQuery.toByteArray())
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "InfluxDB Response Code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        reader.readText()
                    }

                    Log.d(TAG, "InfluxDB CSV Response: $response")
                    return@withContext parsePlantsFromCsv(response)
                } else {
                    val errorResponse = BufferedReader(InputStreamReader(connection.errorStream)).use { reader ->
                        reader.readText()
                    }
                    Log.e(TAG, "InfluxDB Error Response: $errorResponse")
                    throw Exception("InfluxDB query failed: HTTP $responseCode - $errorResponse")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying InfluxDB for plants", e)
                throw e
            }
        }
    }

    private fun parsePlantsFromCsv(csvResponse: String): List<String> {
        val plants = mutableListOf<String>()
        try {
            val lines = csvResponse.trim().split('\n')
            Log.d(TAG, "CSV Lines count: ${lines.size}")

            if (lines.size > 1) {
                // Prima riga (header) - rimuovi virgole vuote iniziali
                val headerLine = lines[0].trim(',')
                val headers = headerLine.split(',')
                Log.d(TAG, "Headers: $headers")
                Log.d(TAG, "Headers raw bytes: ${headers.map { it.toByteArray().contentToString() }}")

                // Cerca _value in diversi modi
                var valueIndex = headers.indexOf("_value")
                if (valueIndex == -1) {
                    // Prova a cercare per contenuto (potrebbe esserci un carattere nascosto)
                    valueIndex = headers.indexOfFirst { it.contains("value") }
                    Log.d(TAG, "Searching for 'value' substring, found at index: $valueIndex")
                }
                if (valueIndex == -1) {
                    // Fallback: assume che _value sia l'ultima colonna (posizione 6 in base al formato InfluxDB)
                    valueIndex = 6
                    Log.d(TAG, "Using fallback index 6 for _value column")
                }

                Log.d(TAG, "Value index: $valueIndex")

                if (valueIndex >= 0 && valueIndex < headers.size) {
                    for (i in 1 until lines.size) {
                        val line = lines[i].trim()
                        if (line.isNotEmpty()) {
                            // Rimuovi virgole vuote iniziali anche dalle righe di dati
                            val cleanLine = line.trim(',')
                            val values = cleanLine.split(',')
                            Log.d(TAG, "Processing line $i: $cleanLine -> values: $values")

                            if (values.size > valueIndex && values[valueIndex].isNotEmpty()) {
                                val plantName = values[valueIndex].trim().trim('"')
                                Log.d(TAG, "Found plant name: '$plantName'")

                                if (plantName.isNotEmpty() && !plants.contains(plantName)) {
                                    plants.add(plantName)
                                    Log.d(TAG, "Added plant: $plantName")
                                }
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "_value column not found in headers, valueIndex: $valueIndex")
                }
            } else {
                Log.w(TAG, "CSV has no data rows")
            }

            Log.d(TAG, "Final parsed plants: $plants")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing CSV response", e)
        }
        return plants.sorted()
    }

    private fun connectToMqtt() {
        Log.d(TAG, "Connecting to MQTT for plant selection and sensor data...")

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val persistence = MemoryPersistence()
                    mqttClient = MqttClient(getMqttBroker(), getMqttClientId(), persistence)

                    val connOpts = MqttConnectOptions().apply {
                        isCleanSession = true
                        connectionTimeout = 30
                        keepAliveInterval = 60
                        isAutomaticReconnect = true
                        maxInflight = 10
                    }

                    // Setup callback per messaggi ricevuti
                    mqttClient?.setCallback(object : MqttCallback {
                        override fun connectionLost(cause: Throwable?) {
                            Log.w(TAG, "MQTT connection lost", cause)
                            runOnUiThread {
                                tvLastUpdate.text = "Connessione persa"
                            }
                        }

                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            Log.d(TAG, "MQTT message arrived on topic: $topic")
                            message?.let { msg ->
                                val payload = String(msg.payload)
                                Log.d(TAG, "MQTT payload: $payload")

                                when (topic) {
                                    MQTT_TOPIC_SENSORS -> {
                                        handleSensorData(payload)
                                    }
                                }
                            }
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {
                            Log.d(TAG, "MQTT delivery complete")
                        }
                    })

                    mqttClient?.connect(connOpts)
                    Log.d(TAG, "MQTT Connected successfully")

                    // Sottoscrivi al topic dei sensori
                    mqttClient?.subscribe(MQTT_TOPIC_SENSORS, 1)
                    Log.d(TAG, "Subscribed to sensor data topic: $MQTT_TOPIC_SENSORS")
                }

                runOnUiThread {
                    tvLastUpdate.text = "Connesso - In attesa dati..."
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to MQTT", e)
                runOnUiThread {
                    tvLastUpdate.text = "Errore connessione"
                }
            }
        }
    }

    private fun handleSensorData(payload: String) {
        try {
            val jsonData = JSONObject(payload)
            val soilHumidity = jsonData.optDouble("umidita_terreno", -1.0)
            val airHumidity = jsonData.optDouble("umidita_aria", -1.0)
            val temperature = jsonData.optDouble("temperatura", -1.0)

            runOnUiThread {
                // Aggiorna umidità del terreno
                if (soilHumidity >= 0) {
                    tvSoilHumidity.text = "${soilHumidity.toInt()}%"
                } else {
                    tvSoilHumidity.text = "--"
                }

                // Aggiorna umidità dell'aria
                if (airHumidity >= 0) {
                    tvAirHumidity.text = "${airHumidity.toInt()}%"
                } else {
                    tvAirHumidity.text = "--"
                }

                // Aggiorna temperatura
                if (temperature >= 0) {
                    tvTemperature.text = "${temperature.toInt()}°C"
                } else {
                    tvTemperature.text = "--"
                }

                // Aggiorna timestamp ultimo aggiornamento
                val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                tvLastUpdate.text = "Aggiornato: $currentTime"

                Log.d(TAG, "Sensor data updated - Soil: ${soilHumidity}%, Air: ${airHumidity}%, Temp: ${temperature}°C")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing sensor data JSON", e)
            runOnUiThread {
                tvLastUpdate.text = "Errore dati sensori"
            }
        }
    }

    private fun savePlantSelection() {
        val selectedPosition = spinnerPlants.selectedItemPosition
        if (selectedPosition >= 0 && selectedPosition < plantsList.size) {
            val selectedPlant = plantsList[selectedPosition]

            lifecycleScope.launch {
                try {
                    val message = MqttMessage(selectedPlant.toByteArray())
                    message.qos = 1

                    withContext(Dispatchers.IO) {
                        mqttClient?.publish(MQTT_TOPIC_SELECTION, message)
                    }

                    Log.d(TAG, "Plant selection sent via MQTT: $selectedPlant")

                    runOnUiThread {
                        Toast.makeText(this@HomeActivity,
                            "✅ Pianta '$selectedPlant' selezionata e salvata!",
                            Toast.LENGTH_LONG).show()

                        tvSelectedPlant.text = "Ultima selezione: $selectedPlant"

                        // Reset dei dati sensori per la nuova pianta
                        tvSoilHumidity.text = "--"
                        tvAirHumidity.text = "--"
                        tvTemperature.text = "--"
                        tvLastUpdate.text = "In attesa dati..."
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error sending plant selection via MQTT", e)
                    runOnUiThread {
                        Toast.makeText(this@HomeActivity,
                            "❌ Errore nell'invio: ${e.message}",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up MQTT connection", e)
        }
    }
}