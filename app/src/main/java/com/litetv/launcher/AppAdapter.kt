package com.litetv.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Pinned apps come first, everything else is alphabetical.
 * Press and hold OK/Enter on a tile to pin or unpin it.
 */
class AppAdapter(
    private val cellWidthPx: Int,
    private val loader: IconLoader,
    private val pinned: MutableSet<String>,
    private val onLaunch: (AppItem) -> Unit,
    private val onLongPress: (Int) -> Unit,
    private val onPinsChanged: (Set<String>) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class PinResult(val pinned: Boolean, val newPosition: Int)

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.card)
        val icon: ImageView = view.findViewById(R.id.icon)
        val pin: View = view.findViewById(R.id.pin)
        val label: TextView = view.findViewById(R.id.label)
    }

    private var all: List<AppItem> = emptyList()
    private var items: List<AppItem> = emptyList()

    private val order = Comparator<AppItem> { a, b ->
        val pa = a.key in pinned
        val pb = b.key in pinned
        when {
            pa == pb -> a.label.compareTo(b.label, ignoreCase = true)
            pa -> -1
            else -> 1
        }
    }

    fun setData(list: List<AppItem>) {
        all = list
        items = list.sortedWith(order)
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): AppItem = items[position]

    /** Toggles the pin and animates the tile to its new place. Focus stays on the moved tile. */
    fun togglePin(position: Int): PinResult {
        val item = items[position]
        val nowPinned = if (pinned.remove(item.key)) {
            false
        } else {
            pinned.add(item.key)
            true
        }
        val sorted = all.sortedWith(order)
        val newPosition = sorted.indexOf(item)
        items = sorted
        if (newPosition != position) notifyItemMoved(position, newPosition)
        notifyItemChanged(newPosition, PAYLOAD_PIN)
        onPinsChanged(pinned)
        return PinResult(nowPinned, newPosition)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val pad = view.paddingLeft
        view.layoutParams = RecyclerView.LayoutParams(cellWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)

        val holder = VH(view)
        holder.card.layoutParams.height = (cellWidthPx - 2 * pad) * 9 / 16

        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) FOCUS_SCALE else 1f)
                .scaleY(if (hasFocus) FOCUS_SCALE else 1f)
                .translationZ(if (hasFocus) 12f else 0f)
                .setDuration(90)
                .start()
        }
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onLaunch(items[position])
        }
        view.setOnLongClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onLongPress(position)
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.label.text = item.label
        holder.pin.visibility = if (item.key in pinned) View.VISIBLE else View.GONE
        loader.load(item, holder.icon, holder.card.layoutParams.height)
    }

    override fun onViewRecycled(holder: VH) {
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        holder.itemView.translationZ = 0f
    }

    private companion object {
        const val FOCUS_SCALE = 1.08f
        const val PAYLOAD_PIN = "pin"
    }
}
