package com.example.smartplants

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.material.bottomnavigation.BottomNavigationView

class HomeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HomeActivity"
    }

    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var tvWelcome: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var cardStats: CardView
    private lateinit var cardRecentPlants: CardView
    private lateinit var cardQuickActions: CardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        initViews()
        setupBottomNavigation()
        setupUI()
    }

    private fun initViews() {
        bottomNavigation = findViewById(R.id.bottom_navigation)
        tvWelcome = findViewById(R.id.tv_welcome)
        tvSubtitle = findViewById(R.id.tv_subtitle)
        cardStats = findViewById(R.id.card_stats)
        cardRecentPlants = findViewById(R.id.card_recent_plants)
        cardQuickActions = findViewById(R.id.card_quick_actions)
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
}