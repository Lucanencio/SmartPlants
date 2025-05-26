package com.example.smartplants

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SmartPlants"
    }

    // Riferimenti alle view
    private lateinit var etNomePianta: EditText
    private lateinit var etValoreSoglia: EditText
    private lateinit var btnInvia: Button
    private lateinit var btnConnetti: Button
    private lateinit var tvStatus: TextView

    private var mqttClient: MqttClient? = null

    // Configurazione MQTT
    private val MQTT_BROKER = "tcp://192.168.1.72:1883"
    private val MQTT_TOPIC = "smart_plant/piante"
    private val MQTT_CLIENT_ID = "SmartPlantApp_${System.currentTimeMillis()}"

    // Configurazione InfluxDB 2.x
    private val INFLUX_HOST = "192.168.1.72"
    private val INFLUX_PORT = "8086"
    private val INFLUX_TOKEN = "CwlazpO4oqRT4I1qYlAJ2E50rwv9JaJE00ENjqvcDSVgFE4vG1dGBO15uL2ug4B1aXE8GorqDPhRXYAAPUlSwg=="
    private val INFLUX_ORG = "scuola"
    private val INFLUX_BUCKET = "smart_plant"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started")

        setContentView(R.layout.activity_main)
        Log.d(TAG, "Layout set successfully")

        initViews()
        setupUI()
    }

    private fun initViews() {
        try {
            etNomePianta = findViewById(R.id.et_nome_pianta)
            etValoreSoglia = findViewById(R.id.et_valore_soglia)
            btnInvia = findViewById(R.id.btn_invia)
            btnConnetti = findViewById(R.id.btn_connetti)
            tvStatus = findViewById(R.id.tv_status)
            Log.d(TAG, "Views initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing views", e)
        }
    }

    private fun setupUI() {
        btnInvia.setOnClickListener {
            val nomePianta = etNomePianta.text.toString().trim()
            val valoreSoglia = etValoreSoglia.text.toString().trim()

            if (validateInput(nomePianta, valoreSoglia)) {
                checkPlantExistsAndProceed(nomePianta, valoreSoglia.toInt())
            }
        }

        btnConnetti.setOnClickListener {
            Log.d(TAG, "Connect button clicked")
            connectToMqtt()
        }

        btnInvia.isEnabled = false
        tvStatus.text = "Non connesso - Clicca 'Connetti MQTT'"
    }

    private fun validateInput(nome: String, valore: String): Boolean {
        if (nome.isEmpty()) {
            etNomePianta.error = "Inserisci il nome della pianta"
            return false
        }

        if (valore.isEmpty()) {
            etValoreSoglia.error = "Inserisci il valore soglia"
            return false
        }

        try {
            val soglia = valore.toInt()
            if (soglia < 0 || soglia > 100) {
                etValoreSoglia.error = "Il valore deve essere tra 0 e 100"
                return false
            }
        } catch (e: NumberFormatException) {
            etValoreSoglia.error = "Inserisci un numero valido"
            return false
        }

        return true
    }

    private fun checkPlantExistsAndProceed(nomePianta: String, valoreSoglia: Int) {
        Log.d(TAG, "Checking if plant '$nomePianta' exists in InfluxDB...")

        lifecycleScope.launch {
            try {
                runOnUiThread {
                    tvStatus.text = "Verificando esistenza pianta..."
                    btnInvia.isEnabled = false
                }

                val plantExists = withContext(Dispatchers.IO) {
                    checkPlantInInfluxDB(nomePianta)
                }

                runOnUiThread {
                    btnInvia.isEnabled = true
                    tvStatus.text = "✅ Connesso al broker MQTT"
                }

                if (plantExists.exists) {
                    // Pianta esistente - mostra dialog per conferma sostituzione
                    runOnUiThread {
                        showDuplicateDialog(nomePianta, valoreSoglia, plantExists.currentValue)
                    }
                } else {
                    // Pianta non esistente - procedi con creazione normale
                    Log.d(TAG, "Plant '$nomePianta' not found, proceeding with creation")
                    inviaConfigurazioneImpianto(nomePianta, valoreSoglia)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error checking plant existence", e)
                runOnUiThread {
                    btnInvia.isEnabled = true
                    tvStatus.text = "✅ Connesso al broker MQTT"
                    Toast.makeText(this@MainActivity,
                        "⚠️ Errore verifica esistenza: ${e.message}. Procedendo comunque...",
                        Toast.LENGTH_LONG).show()
                    // In caso di errore, procedi come nuova pianta
                    inviaConfigurazioneImpianto(nomePianta, valoreSoglia)
                }
            }
        }
    }

    private data class PlantCheckResult(val exists: Boolean, val currentValue: Int?)

    private suspend fun checkPlantInInfluxDB(nomePianta: String): PlantCheckResult {
        return withContext(Dispatchers.IO) {
            try {
                val fluxQuery = """
                from(bucket: "$INFLUX_BUCKET")
                  |> range(start: -30d)
                  |> filter(fn: (r) => r["_measurement"] == "Piante")
                  |> filter(fn: (r) => r["_field"] == "nome" and r["_value"] == "$nomePianta")
                  |> last()
                  |> yield(name: "last")
                """.trimIndent()

                Log.d(TAG, "InfluxDB Flux Query: $fluxQuery")

                val url = URL("http://$INFLUX_HOST:$INFLUX_PORT/api/v2/query?org=$INFLUX_ORG")
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

                    val lines = response.trim().split('\n')

                    if (lines.size > 1) {
                        val headers = lines[0].split(',')
                        val valueIndex = headers.indexOf("_value")

                        if (valueIndex != -1 && lines.size > 1) {
                            val dataLine = lines.last()
                            val values = dataLine.split(',')

                            if (values.size > valueIndex) {
                                try {
                                    val currentValue = values[valueIndex].toIntOrNull()
                                    Log.d(TAG, "Plant '$nomePianta' found with current value: $currentValue")
                                    return@withContext PlantCheckResult(true, currentValue)
                                } catch (e: NumberFormatException) {
                                    Log.w(TAG, "Could not parse current value: ${values[valueIndex]}")
                                    return@withContext PlantCheckResult(true, null)
                                }
                            }
                        }

                        Log.d(TAG, "Plant '$nomePianta' exists but couldn't parse value")
                        return@withContext PlantCheckResult(true, null)
                    }

                    Log.d(TAG, "Plant '$nomePianta' not found in InfluxDB")
                    PlantCheckResult(false, null)

                } else {
                    val errorResponse = BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream)).use { reader ->
                        reader.readText()
                    }
                    Log.e(TAG, "InfluxDB 2.x Error Response: $errorResponse")
                    throw Exception("InfluxDB 2.x query failed: HTTP $responseCode - $errorResponse")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error querying InfluxDB 2.x", e)
                throw e
            }
        }
    }

    private fun showDuplicateDialog(nomePianta: String, nuovoValore: Int, valoreCorrente: Int?) {
        val message = buildString {
            append("La pianta '$nomePianta' esiste già nel database")
            if (valoreCorrente != null) {
                append(" con valore soglia: $valoreCorrente%")
            }
            append("\n\nVuoi continuare comunque?")
        }

        AlertDialog.Builder(this)
            .setTitle("Pianta già esistente")
            .setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("Continua") { _, _ ->
                Log.d(TAG, "User chose to replace existing plant '$nomePianta'")
                inviaConfigurazioneImpianto(nomePianta, nuovoValore)
            }
            .setNegativeButton("Annulla") { dialog, _ ->
                Log.d(TAG, "User cancelled plant configuration for '$nomePianta'")
                dialog.dismiss()
                Toast.makeText(this, "Operazione annullata", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun testNetworkConnection(): Boolean {
        return try {
            val socket = Socket()
            val address = InetSocketAddress("192.168.1.72", 1883)
            socket.connect(address, 5000)
            socket.close()
            Log.d(TAG, "Network connection test: SUCCESS")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Network connection test: FAILED", e)
            false
        }
    }

    private fun connectToMqtt() {
        Log.d(TAG, "Starting MQTT connection process")

        lifecycleScope.launch {
            try {
                runOnUiThread {
                    tvStatus.text = "Testando connessione di rete..."
                    btnConnetti.isEnabled = false
                }

                val networkAvailable = withContext(Dispatchers.IO) {
                    testNetworkConnection()
                }

                if (!networkAvailable) {
                    runOnUiThread {
                        tvStatus.text = "Errore: Impossibile raggiungere il broker MQTT"
                        btnConnetti.isEnabled = true
                        Toast.makeText(this@MainActivity,
                            "Verifica che il broker MQTT sia attivo su 192.168.1.72:1883",
                            Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                runOnUiThread {
                    tvStatus.text = "Connessione MQTT in corso..."
                }

                mqttClient?.let { client ->
                    if (client.isConnected) {
                        client.disconnect()
                    }
                    client.close()
                }

                val persistence = MemoryPersistence()
                mqttClient = MqttClient(MQTT_BROKER, MQTT_CLIENT_ID, persistence)
                Log.d(TAG, "MQTT Client created with ID: $MQTT_CLIENT_ID")

                val connOpts = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 60
                    isAutomaticReconnect = true
                    maxInflight = 10
                }

                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT Connection lost", cause)
                        runOnUiThread {
                            tvStatus.text = "Connessione persa: ${cause?.message ?: "Motivo sconosciuto"}"
                            btnConnetti.isEnabled = true
                            btnInvia.isEnabled = false
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        Log.d(TAG, "Message arrived on topic: $topic")
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        Log.d(TAG, "Message delivery complete")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Messaggio inviato con successo!", Toast.LENGTH_SHORT).show()
                        }
                    }
                })

                withContext(Dispatchers.IO) {
                    mqttClient?.connect(connOpts)
                }

                Log.d(TAG, "MQTT Connection successful")
                runOnUiThread {
                    tvStatus.text = "✅ Connesso al broker MQTT"
                    btnConnetti.isEnabled = true
                    btnInvia.isEnabled = true
                    Toast.makeText(this@MainActivity, "Connesso al broker MQTT!", Toast.LENGTH_SHORT).show()
                }

            } catch (e: MqttException) {
                Log.e(TAG, "MQTT Connection error: ${e.message}", e)
                val errorMsg = when (e.reasonCode.toInt()) {
                    MqttException.REASON_CODE_BROKER_UNAVAILABLE.toInt() -> "Broker non disponibile"
                    MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() -> "Timeout connessione"
                    MqttException.REASON_CODE_CONNECTION_LOST.toInt() -> "Connessione persa"
                    MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt() -> "Errore di connessione al server"
                    else -> "Errore MQTT: ${e.message} (Code: ${e.reasonCode})"
                }

                runOnUiThread {
                    tvStatus.text = "❌ $errorMsg"
                    btnConnetti.isEnabled = true
                    btnInvia.isEnabled = false
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "General connection error", e)
                runOnUiThread {
                    tvStatus.text = "❌ Errore di connessione: ${e.message}"
                    btnConnetti.isEnabled = true
                    btnInvia.isEnabled = false
                    Toast.makeText(this@MainActivity, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun inviaConfigurazioneImpianto(nome: String, valore: Int) {
        if (mqttClient?.isConnected != true) {
            Toast.makeText(this, "Non connesso al broker MQTT", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val payload = JSONObject().apply {
                    put("nome", nome)
                    put("valore", valore)
                }

                val message = MqttMessage(payload.toString().toByteArray())
                message.qos = 1 // Garantisce che il messaggio venga consegnato almeno una volta

                withContext(Dispatchers.IO) {
                    mqttClient?.publish(MQTT_TOPIC, message)
                }

                Log.d(TAG, "Message published successfully: $payload")

                runOnUiThread {
                    val successMessage = "✅ Configurazione creata per '$nome' (soglia: $valore%)"
                    Toast.makeText(this@MainActivity, successMessage, Toast.LENGTH_LONG).show()

                    // Pulisci i campi dopo l'invio
                    etNomePianta.text?.clear()
                    etValoreSoglia.text?.clear()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "❌ Errore invio: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity destroying, cleaning up MQTT connection")
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up MQTT connection", e)
        }
    }
}