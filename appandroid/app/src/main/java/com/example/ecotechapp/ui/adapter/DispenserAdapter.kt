package com.example.ecotechapp.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ecotechapp.data.model.DispenserStatus
import com.example.ecotechapp.data.model.WaterDispenser
import com.example.ecotechapp.databinding.ItemDispenserBinding

class DispenserAdapter(
    private val onRefillClicked: (WaterDispenser) -> Unit,
    private val onItemClicked: (WaterDispenser) -> Unit
) : ListAdapter<WaterDispenser, DispenserAdapter.DispenserViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DispenserViewHolder {
        val binding = ItemDispenserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DispenserViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DispenserViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DispenserViewHolder(private val binding: ItemDispenserBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(dispenser: WaterDispenser) {
            binding.tvDispenserName.text = dispenser.name
            binding.tvDispenserLocation.text = "📍 ${dispenser.location}"
            binding.tvLevelPercent.text = "${dispenser.currentLevelPercent}% (${String.format("%.1f", dispenser.litersRemaining)}L)"
            binding.progressWaterLevel.progress = dispenser.currentLevelPercent

            when (dispenser.status) {
                DispenserStatus.OK -> {
                    binding.chipStatus.text = "OK (${dispenser.currentLevelPercent}%)"
                    binding.chipStatus.setChipBackgroundColorResource(android.R.color.holo_green_dark)
                    binding.progressWaterLevel.setIndicatorColor(Color.parseColor("#0288D1"))
                    binding.cardDispenser.strokeColor = Color.parseColor("#E0E0E0")
                }
                DispenserStatus.LOW_WATER -> {
                    binding.chipStatus.text = "⚠️ Nivel Bajo (${dispenser.currentLevelPercent}%)"
                    binding.chipStatus.setChipBackgroundColorResource(android.R.color.holo_orange_dark)
                    binding.progressWaterLevel.setIndicatorColor(Color.parseColor("#F57C00"))
                    binding.cardDispenser.strokeColor = Color.parseColor("#F57C00")
                }
                DispenserStatus.EMPTY -> {
                    binding.chipStatus.text = "🚨 Garrafón Vacío (${dispenser.currentLevelPercent}%)"
                    binding.chipStatus.setChipBackgroundColorResource(android.R.color.holo_red_dark)
                    binding.progressWaterLevel.setIndicatorColor(Color.parseColor("#D32F2F"))
                    binding.cardDispenser.strokeColor = Color.parseColor("#D32F2F")
                }
                DispenserStatus.MAINTENANCE -> {
                    binding.chipStatus.text = "🛠️ Mantenimiento"
                    binding.chipStatus.setChipBackgroundColorResource(android.R.color.darker_gray)
                    binding.progressWaterLevel.setIndicatorColor(Color.GRAY)
                }
            }

            binding.btnQuickRefill.setOnClickListener { onRefillClicked(dispenser) }
            binding.btnDetails.setOnClickListener { onItemClicked(dispenser) }
            binding.root.setOnClickListener { onItemClicked(dispenser) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WaterDispenser>() {
        override fun areItemsTheSame(oldItem: WaterDispenser, newItem: WaterDispenser): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: WaterDispenser, newItem: WaterDispenser): Boolean {
            return oldItem == newItem
        }
    }
}
