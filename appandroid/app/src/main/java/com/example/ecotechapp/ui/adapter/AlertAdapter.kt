package com.example.ecotechapp.ui.adapter

import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ecotechapp.data.model.AlertSeverity
import com.example.ecotechapp.data.model.WaterAlert
import com.example.ecotechapp.databinding.ItemAlertBinding

class AlertAdapter(
    private val onResolveAlertClicked: (WaterAlert) -> Unit
) : ListAdapter<WaterAlert, AlertAdapter.AlertViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val binding = ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AlertViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AlertViewHolder(private val binding: ItemAlertBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(alert: WaterAlert) {
            binding.tvAlertDispenser.text = alert.dispenserName
            binding.tvAlertLocation.text = "📍 ${alert.location}"
            binding.tvAlertTime.text = "🕒 " + DateUtils.getRelativeTimeSpanString(
                alert.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )

            if (alert.severity == AlertSeverity.CRITICAL) {
                binding.tvAlertTitle.text = "🚨 ¡GARRAFÓN VACÍO! (${alert.levelPercent}%)"
                binding.tvAlertTitle.setTextColor(Color.parseColor("#D32F2F"))
                binding.chipSeverity.text = "CRÍTICO"
                binding.chipSeverity.setChipBackgroundColorResource(android.R.color.holo_red_dark)
                binding.cardAlert.strokeColor = Color.parseColor("#D32F2F")
            } else {
                binding.tvAlertTitle.text = "⚠️ Nivel Bajo de Agua (${alert.levelPercent}%)"
                binding.tvAlertTitle.setTextColor(Color.parseColor("#F57C00"))
                binding.chipSeverity.text = "ADVERTENCIA"
                binding.chipSeverity.setChipBackgroundColorResource(android.R.color.holo_orange_dark)
                binding.cardAlert.strokeColor = Color.parseColor("#F57C00")
            }

            binding.btnResolveAlert.setOnClickListener { onResolveAlertClicked(alert) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WaterAlert>() {
        override fun areItemsTheSame(oldItem: WaterAlert, newItem: WaterAlert): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: WaterAlert, newItem: WaterAlert): Boolean {
            return oldItem == newItem
        }
    }
}
