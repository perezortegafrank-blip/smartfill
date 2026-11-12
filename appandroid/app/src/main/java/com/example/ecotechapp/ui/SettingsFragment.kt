package com.example.ecotechapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.ecotechapp.data.repository.WaterRepository
import com.example.ecotechapp.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: WaterRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = WaterRepository.getInstance(requireContext())

        val currentUrl = repository.mysqlSync.serverUrl
        binding.etMysqlUrl.setText(currentUrl)

        binding.btnSaveMysqlUrl.setOnClickListener {
            val newUrl = binding.etMysqlUrl.text.toString().trim()
            if (newUrl.isNotEmpty()) {
                repository.mysqlSync.serverUrl = newUrl
                Toast.makeText(
                    requireContext(),
                    "✅ Servidor MySQL configurado: $newUrl",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(requireContext(), "Ingresa una URL válida para MySQL", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
