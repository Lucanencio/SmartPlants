package com.example.smartplants

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
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

class HomeActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "HomeActivity"
        // Configurazione server (stessa dell'altra activity)
        private const val IP_SERVER = "172.17.5.37"
        private const val INFLUX_PORT = "8086"
        private const val INFLUX_TOKEN = "CwlazpO4oqRT4I1qYlAJ2E50rwv9JaJE00ENjqvcDSVgFE4vG1dGBO15uL2ug4B1aXE8GorqDPhRXYAAPUlSwg=="
        private const val INFLUX_ORG = "scuola"
        private const val INFLUX_BUCKET = "smart_plant"
        // Configurazione MQTT - rimossa interpolazione dalla const val
        private const val MQTT_PORT = "1883"
        private const val MQTT_TOPIC = "smart_plant/piantaSelezionata"

        // Funzione per ottenere l'URL del broker MQTT
        private fun getMqttBroker(): String = "tcp://$IP_SERVER:$MQTT_PORT"

        // Funzione per ottenere il client ID MQTT
        private fun getMqttClientId(): String = "SmartPlantApp_${System.currentTimeMillis()}"
    }

    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var tvWelcome: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var cardStats: CardView
    private lateinit var cardRecentPlants: CardView
    private lateinit var cardQuickActions: CardView

    // Nuovi elementi per la selezione piante
    private lateinit var cardPlantSelector: CardView
    private lateinit var spinnerPlants: Spinner
    private lateinit var btnSavePlant: Button
    private lateinit var tvSelectedPlant: TextView

    private var plantsList = mutableListOf<String>()
    private var plantsAdapter: ArrayAdapter<String>? = null
    private var mqttClient: MqttClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        initViews()
        setupBottomNavigation()
        setupUI()
        setupPlantSelector()
        loadPlantsFromDatabase()
        connectToMqtt()
    }

    private fun initViews() {
        bottomNavigation = findViewById(R.id.bottom_navigation)
        tvWelcome = findViewById(R.id.tv_welcome)
        tvSubtitle = findViewById(R.id.tv_subtitle)
        cardStats = findViewById(R.id.card_stats)
        cardRecentPlants = findViewById(R.id.card_recent_plants)
        cardQuickActions = findViewById(R.id.card_quick_actions)

        // Nuovi elementi
        cardPlantSelector = findViewById(R.id.card_plant_selector)
        spinnerPlants = findViewById(R.id.spinner_plants)
        btnSavePlant = findViewById(R.id.btn_save_plant)
        tvSelectedPlant = findViewById(R.id.tv_selected_plant)
    }

    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    // Già nella home
                    true
                }
                R.id.nav_add_plant -> {
                    // Naviga alla MainActivity (pagina per aggiungere piante)
                    val intent = Intent(this, MainActivity::class.java)
                    startActivity(intent)
                    true
                }
                else -> false
            }
        }
        // Imposta home come selezionato
        bottomNavigation.selectedItemId = R.id.nav_home
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

        cardStats.setOnClickListener {
            // Qui in futuro si potranno aggiungere statistiche dettagliate
        }

        cardRecentPlants.setOnClickListener {
            // Qui in futuro si potrà mostrare la lista delle piante
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
        Log.d(TAG, "Connecting to MQTT for plant selection...")

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

                    mqttClient?.connect(connOpts)
                    Log.d(TAG, "MQTT Connected successfully for plant selection")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to MQTT", e)
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
                        mqttClient?.publish(MQTT_TOPIC, message)
                    }

                    Log.d(TAG, "Plant selection sent via MQTT: $selectedPlant")

                    runOnUiThread {
                        Toast.makeText(this@HomeActivity,
                            "✅ Pianta '$selectedPlant' selezionata e salvata!",
                            Toast.LENGTH_LONG).show()

                        tvSelectedPlant.text = "Ultima selezione: $selectedPlant"
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