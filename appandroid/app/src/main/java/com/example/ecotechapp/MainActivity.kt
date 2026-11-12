package com.example.ecotechapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.example.ecotechapp.data.repository.WaterRepository
import com.example.ecotechapp.databinding.ActivityMainBinding
import com.google.firebase.database.FirebaseDatabase

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    companion object {
        /** Debe ejecutarse UNA sola vez y ANTES de usar cualquier DatabaseReference */
        private var firebasePersistenceEnabled = false
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "🔔 Notificaciones de garrafón activadas", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Persistencia offline (ISO 27400): la app recibe las alertas apenas
        // recupere señal Wi-Fi/4G, incluso si el dispositivo estaba sin conexión.
        // Debe llamarse ANTES de que cualquier DatabaseReference se use.
        if (!firebasePersistenceEnabled) {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            firebasePersistenceEnabled = true
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        binding.fab.setImageResource(android.R.drawable.ic_dialog_alert)
        binding.fab.contentDescription = "Alertas de garrafones vacíos"
        binding.fab.setOnClickListener {
            navController.navigate(R.id.action_DashboardFragment_to_AlertsFragment)
        }

        checkNotificationPermission()

        // ── Listener en tiempo real de datos del ESP32 (SmartFill) ──
        WaterRepository.getInstance(applicationContext).startRealtimeSync()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return when (item.itemId) {
            R.id.action_alerts -> {
                navController.navigate(R.id.AlertsFragment)
                true
            }
            R.id.action_history -> {
                navController.navigate(R.id.HistoryFragment)
                true
            }
            R.id.action_settings -> {
                navController.navigate(R.id.SettingsFragment)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}
