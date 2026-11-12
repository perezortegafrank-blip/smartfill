package com.example.ecotechapp.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.ecotechapp.data.model.DispenserStatus
import com.example.ecotechapp.data.model.WaterDispenser
import com.example.ecotechapp.data.repository.WaterRepository
import com.example.ecotechapp.databinding.FragmentDispenserDetailBinding

class DispenserDetailFragment : Fragment() {

    private var _binding: FragmentDispenserDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: WaterRepository
    private var dispenserId: String? = null
    private var currentDispenser: WaterDispenser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispenserId = arguments?.getString("dispenser_id")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDispenserDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = WaterRepository.getInstance(requireContext())

        setupListeners()
        observeDispenser()
    }

    private fun observeDispenser() {
        repository.dispensers.observe(viewLifecycleOwner) { list ->
            currentDispenser = list.find { it.id == dispenserId }
            currentDispenser?.let { updateUi(it) }
        }
    }

    private fun updateUi(dispenser: WaterDispenser) {
        binding.tvDetailName.text = dispenser.name
        binding.tvDetailLocation.text = "📍 Ubicación: ${dispenser.location}"
        binding.tvLitersInfo.text = "${String.format("%.1f", dispenser.litersRemaining)} L de ${dispenser.capacityLiters} L restantes"

        // Datos reales del sensor HC-SR04 publicados por el ESP32 (distancia_cm)
        val origen = if (dispenser.isRealtime) "📡 ESP32 en vivo" else "🧪 Modo simulación"
        binding.tvSensoresInfo.text =
            "$origen · Distancia HC-SR04: ${String.format("%.1f", dispenser.distanciaCm)} cm · ${dispenser.sensorId}"

        binding.progressDetail.progress = dispenser.currentLevelPercent
        binding.sliderLevel.value = dispenser.currentLevelPercent.toFloat()

        when (dispenser.status) {
            DispenserStatus.OK -> {
                binding.chipDetailStatus.text = "🟢 ${dispenser.estadoBidon} (${dispenser.currentLevelPercent}%)"
                binding.chipDetailStatus.setChipBackgroundColorResource(android.R.color.holo_green_dark)
                binding.progressDetail.setIndicatorColor(Color.parseColor("#0288D1"))
            }
            DispenserStatus.LOW_WATER -> {
                binding.chipDetailStatus.text = "🟠 ${dispenser.estadoBidon} (${dispenser.currentLevelPercent}%)"
                binding.chipDetailStatus.setChipBackgroundColorResource(android.R.color.holo_orange_dark)
                binding.progressDetail.setIndicatorColor(Color.parseColor("#F57C00"))
            }
            DispenserStatus.EMPTY -> {
                binding.chipDetailStatus.text = "🚨 ${dispenser.estadoBidon} (${dispenser.currentLevelPercent}%)"
                binding.chipDetailStatus.setChipBackgroundColorResource(android.R.color.holo_red_dark)
                binding.progressDetail.setIndicatorColor(Color.parseColor("#D32F2F"))
            }
            DispenserStatus.MAINTENANCE -> {
                binding.chipDetailStatus.text = "🛠️ Mantenimiento"
                binding.chipDetailStatus.setChipBackgroundColorResource(android.R.color.darker_gray)
            }
        }

        updateAlertCard(dispenser)
        updateBombaSwitch(dispenser)
    }

    /** Tarjeta de alerta: roja si crítico (<15%, buzzer activo), amarilla si requiere cambio (15-19%) */
    private fun updateAlertCard(dispenser: WaterDispenser) {
        if (!dispenser.requiereCambio) {
            binding.cardAlertChange.visibility = View.GONE
            return
        }

        binding.cardAlertChange.visibility = View.VISIBLE
        if (dispenser.isCritico) {
            binding.cardAlertChange.setCardBackgroundColor(Color.parseColor("#FDECEA"))
            binding.cardAlertChange.strokeColor = Color.parseColor("#D32F2F")
            binding.tvAlertChangeTitle.text = "🚨 Cambio URGENTE de bidón"
            binding.tvAlertChangeTitle.setTextColor(Color.parseColor("#B71C1C"))
            binding.tvAlertChangeSubtitle.text =
                "Garrafón crítico (${dispenser.currentLevelPercent}%). El operario debe reemplazarlo de inmediato — el buzzer del ESP32 está sonando."
        } else {
            binding.cardAlertChange.setCardBackgroundColor(Color.parseColor("#FFF8E1"))
            binding.cardAlertChange.strokeColor = Color.parseColor("#F57C00")
            binding.tvAlertChangeTitle.text = "⚠️ Requiere cambio de bidón"
            binding.tvAlertChangeTitle.setTextColor(Color.parseColor("#E65100"))
            binding.tvAlertChangeSubtitle.text =
                "Nivel ${dispenser.currentLevelPercent}% — prepara un garrafón de 20 L antes del agotamiento."
        }
    }

    /** Switch de bomba: escribe bomba_estado en Firebase; el ESP32 reacciona en tiempo real */
    private fun updateBombaSwitch(dispenser: WaterDispenser) {
        // Desactivar listener temporalmente para no reenviar la orden al actualizar la UI
        binding.switchBomba.setOnCheckedChangeListener(null)
        binding.switchBomba.isChecked = dispenser.bombaEstado
        binding.switchBomba.setOnCheckedChangeListener { _, checked ->
            dispenserId?.let { id ->
                repository.setBombaEstado(id, checked)
                Toast.makeText(
                    requireContext(),
                    if (checked) "🚰 Orden: ENCENDER bomba (bomba_estado=true)" else "⏹️ Orden: APAGAR bomba (bomba_estado=false)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupListeners() {
        binding.sliderLevel.addOnChangeListener { _, value, fromUser ->
            if (fromUser && dispenserId != null) {
                repository.updateDispenserLevel(dispenserId!!, value.toInt())
            }
        }

        binding.btnSimulateEmpty.setOnClickListener {
            dispenserId?.let { id ->
                repository.updateDispenserLevel(id, 0)
                Toast.makeText(requireContext(), "🚨 Simulación: Garrafón reportado VACÍO", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSimulateLow.setOnClickListener {
            dispenserId?.let { id ->
                repository.updateDispenserLevel(id, 20)
                Toast.makeText(requireContext(), "⚠️ Simulación: Garrafón nivel BAJO (20%)", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRefillBottle.setOnClickListener {
            dispenserId?.let { id ->
                val success = repository.refillDispenser(id, "Técnico Mantenimiento")
                if (success) {
                    Toast.makeText(requireContext(), "✅ Garrafón Reemplazado Exitosamente (100%)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
