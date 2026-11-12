package com.example.ecotechapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ecotechapp.data.repository.WaterRepository
import com.example.ecotechapp.databinding.FragmentAlertsBinding
import com.example.ecotechapp.ui.adapter.AlertAdapter

class AlertsFragment : Fragment() {

    private var _binding: FragmentAlertsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: WaterRepository
    private lateinit var alertAdapter: AlertAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlertsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = WaterRepository.getInstance(requireContext())

        setupRecyclerView()
        observeAlerts()
    }

    private fun setupRecyclerView() {
        alertAdapter = AlertAdapter(
            onResolveAlertClicked = { alert ->
                val success = repository.refillDispenser(alert.dispenserId, "Técnico de Turno")
                if (success) {
                    Toast.makeText(
                        requireContext(),
                        "✅ Alerta Resuelta: Garrafón reemplazado en ${alert.dispenserName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        binding.rvAlerts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = alertAdapter
        }
    }

    private fun observeAlerts() {
        repository.alerts.observe(viewLifecycleOwner) { alerts ->
            val activeAlerts = alerts.filter { !it.isResolved }
            alertAdapter.submitList(activeAlerts)

            if (activeAlerts.isEmpty()) {
                binding.tvEmptyAlerts.visibility = View.VISIBLE
                binding.rvAlerts.visibility = View.GONE
            } else {
                binding.tvEmptyAlerts.visibility = View.GONE
                binding.rvAlerts.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
