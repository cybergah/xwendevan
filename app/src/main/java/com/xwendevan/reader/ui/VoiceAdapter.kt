package com.xwendevan.reader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xwendevan.reader.R
import com.xwendevan.reader.tts.VoiceInfo

class VoiceAdapter(
    private var items: List<VoiceInfo>,
    private var selectedId: String?,
    private val onPick: (VoiceInfo) -> Unit
) : RecyclerView.Adapter<VoiceAdapter.VH>() {

    fun update(list: List<VoiceInfo>, selected: String?) {
        items = list
        selectedId = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_voice, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val flag: TextView = itemView.findViewById(R.id.voiceFlag)
        private val name: TextView = itemView.findViewById(R.id.voiceName)
        private val meta: TextView = itemView.findViewById(R.id.voiceMeta)
        private val radio: RadioButton = itemView.findViewById(R.id.voiceSelected)

        fun bind(v: VoiceInfo) {
            val ctx = itemView.context
            flag.text = v.flag
            name.text = v.displayName
            val parts = ArrayList<String>()
            parts.add(v.language)
            parts.add("${v.sampleRate / 1000} kHz")
            if (v.numSpeakers > 1) parts.add(ctx.getString(R.string.speakers_n, v.numSpeakers))
            parts.add(ctx.getString(if (v.isPrepared) R.string.voice_ready else R.string.voice_embedded))
            meta.text = parts.joinToString(" · ")
            radio.isChecked = v.id == selectedId
            itemView.setOnClickListener { onPick(v) }
        }
    }
}
