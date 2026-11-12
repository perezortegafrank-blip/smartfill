package com.example.ecotechapp.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ecotechapp.data.model.RefillLog
import com.example.ecotechapp.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter : ListAdapter<RefillLog, HistoryAdapter.HistoryViewHolder>(DiffCallback) {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(log: RefillLog) {
            binding.tvHistoryDispenser.text = "${log.dispenserName} (${log.location})"
            binding.tvHistoryTech.text = "👤 Técnico: ${log.technicianName}"
            binding.tvHistoryNotes.text = "📝 ${log.notes}"
            binding.tvHistoryTime.text = "🕒 " + dateFormat.format(Date(log.timestamp))

            if (log.syncedToMySql) {
                binding.chipMysqlStatus.text = "MySQL Sync OK"
                binding.chipMysqlStatus.setChipBackgroundColorResource(android.R.color.holo_green_dark)
            } else {
                binding.chipMysqlStatus.text = "MySQL Pendiente"
                binding.chipMysqlStatus.setChipBackgroundColorResource(android.R.color.holo_orange_dark)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<RefillLog>() {
        override fun areItemsTheSame(oldItem: RefillLog, newItem: RefillLog): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: RefillLog, newItem: RefillLog): Boolean {
            return oldItem == newItem
        }
    }
}
