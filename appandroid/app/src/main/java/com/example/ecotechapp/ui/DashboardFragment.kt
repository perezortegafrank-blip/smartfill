package com.example.ecotechapp.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ecotechapp.R
import com.example.ecotechapp.data.repository.WaterRepository
import com.example.ecotechapp.databinding.FragmentDashboardBinding
import com.example.ecotechapp.ui.adapter.DispenserAdapter

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: WaterRepository
    private lateinit var dispenserAdapter: DispenserAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = WaterRepository.getInstance(requireContext())

        // Activa el listener en tiempo real del ESP32 (idempotente)
        repository.startRealtimeSync()

        setupRecyclerView()
        setupListeners()
        observeData()
    }

    private fun setupRecyclerView() {
        dispenserAdapter = DispenserAdapter(
            onRefillClicked = { dispenser ->
                val success = repository.refillDispenser(dispenser.id, "Técnico en Turno")
                if (success) {
                    Toast.makeText(
                        requireContext(),
                        "🔄 Garrafón de 20L reemplazado en ${dispenser.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onItemClicked = { dispenser ->
                val bundle = Bundle().apply {
                    putString("dispenser_id", dispenser.id)
                }
                findNavController().navigate(R.id.action_DashboardFragment_to_DispenserDetailFragment, bundle)
            }
        )

        binding.rvDispensers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = dispenserAdapter
        }
    }

    private fun setupListeners() {
        binding.switchSimulation.isChecked = repository.isSimulating()
        binding.switchSimulation.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                repository.startSensorSimulation()
            } else {
                repository.stopSensorSimulation()
            }
        }
    }

    private fun observeData() {
        repository.dispensers.observe(viewLifecycleOwner) { list ->
            dispenserAdapter.submitList(list)

            binding.tvCountTotal.text = list.size.toString()
            val requiereCambioCount = list.count { it.requiereCambio }
            val criticosCount = list.count { it.isCritico }
            binding.tvCountAlerts.text = requiereCambioCount.toString()
            updateGlobalAlertBanner(requiereCambioCount, criticosCount)
        }

        repository.refillLogs.observe(viewLifecycleOwner) { logs ->
            binding.tvCountRefills.text = logs.size.toString()
        }

        repository.syncStatusMessage.observe(viewLifecycleOwner) { message ->
            binding.tvStatusSummary.text = message
        }
    }

    /** Tarjeta de alerta global: roja si hay garrafones críticos (<15%), amarilla si solo nivel bajo */
    private fun updateGlobalAlertBanner(requiereCambioCount: Int, criticosCount: Int) {
        if (requiereCambioCount == 0) {
            binding.cardGlobalAlert.visibility = View.GONE
            return
        }

        binding.cardGlobalAlert.visibility = View.VISIBLE
        if (criticosCount > 0) {
            // Rojo: garrafón crítico, el buzzer del ESP32 está sonando
            binding.cardGlobalAlert.setCardBackgroundColor(Color.parseColor("#B71C1C"))
            binding.tvGlobalAlertText.setTextColor(Color.WHITE)
            binding.tvGlobalAlertText.text =
                "🚨 $criticosCount garrafón(es) VACÍO(S) — cambio de bidón urgente"
        } else {
            // Amarillo: se acercan al vacío, preparar reposición
            binding.cardGlobalAlert.setCardBackgroundColor(Color.parseColor("#FFF8E1"))
            binding.tvGlobalAlertText.setTextColor(Color.parseColor("#7A5C00"))
            binding.tvGlobalAlertText.text =
                "⚠️ $requiereCambioCount garrafón(es) por quedarse sin agua — cambio de bidón"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
