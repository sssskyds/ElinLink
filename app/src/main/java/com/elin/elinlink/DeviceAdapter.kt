package com.elin.elinlink

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private val onClick: (ScannedDevice) -> Unit
) : ListAdapter<ScannedDevice, DeviceAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScannedDevice>() {
            override fun areItemsTheSame(a: ScannedDevice, b: ScannedDevice) =
                a.address == b.address
            override fun areContentsTheSame(a: ScannedDevice, b: ScannedDevice) =
                a.name == b.name && a.rssi == b.rssi
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.tvName)
        val address: TextView = itemView.findViewById(R.id.tvAddress)
        val rssi: TextView = itemView.findViewById(R.id.tvRssi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.name.text = item.name
        holder.address.text = item.address
        holder.rssi.text = "${item.rssi} dBm"
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
