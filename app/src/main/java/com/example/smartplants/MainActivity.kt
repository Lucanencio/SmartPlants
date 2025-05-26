package com.example.smartplants

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

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

    // Configurazione MQTT - Modifica questi valori secondo la tua configurazione
    private val MQTT_BROKER = "tcp://192.168.1.72:1883"
    private val MQTT_TOPIC = "smart_plant/piante"
    private val MQTT_CLIENT_ID = "SmartPlantApp_${System.currentTimeMillis()}"

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
                inviaConfigurazioneImpianto(nomePianta, valoreSoglia.toInt())
            }
        }

        btnConnetti.setOnClickListener {
            Log.d(TAG, "Connect button clicked")
            connectToMqtt()
        }

        // Inizialmente disabilita il pulsante invia
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

    private fun testNetworkConnection(): Boolean {
        return try {
            val socket = Socket()
            val address = InetSocketAddress("192.168.1.72", 1883)
            socket.connect(address, 5000) // 5 second timeout
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
                // Update UI on main thread
                runOnUiThread {
                    tvStatus.text = "Testando connessione di rete..."
                    btnConnetti.isEnabled = false
                }

                // Test network connectivity first
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

                // Disconnect existing client if any
                mqttClient?.let { client ->
                    if (client.isConnected) {
                        client.disconnect()
                    }
                    client.close()
                }

                // Create new MQTT client
                val persistence = MemoryPersistence()
                mqttClient = MqttClient(MQTT_BROKER, MQTT_CLIENT_ID, persistence)
                Log.d(TAG, "MQTT Client created with ID: $MQTT_CLIENT_ID")

                val connOpts = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30 // Increased timeout
                    keepAliveInterval = 60
                    isAutomaticReconnect = true
                    maxInflight = 10

                    // Uncommenta se hai username e password:
                    // userName = "your_username"
                    // password = "your_password".toCharArray()
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

                // Connect with timeout handling
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

        Log.d(TAG, "Sending configuration: $nome = $valore%")

        lifecycleScope.launch {
            try {
                // Crea il payload JSON semplificato per Node-RED
                val payload = JSONObject().apply {
                    put("nome", nome)
                    put("valore", valore)
                }

                val message = MqttMessage(payload.toString().toByteArray())
                message.qos = 1 // QoS level 1 per garantire la consegna

                withContext(Dispatchers.IO) {
                    mqttClient?.publish(MQTT_TOPIC, message)
                }

                Log.d(TAG, "Message published successfully: ${payload}")

                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "✅ Configurazione inviata per $nome (soglia: $valore%)",
                        Toast.LENGTH_SHORT).show()
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