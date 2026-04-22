package com.example.doomtodoomscroll
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(private val apps: List<AppLimitModel>) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var textWatcher: TextWatcher? = null
        val icon: ImageView = view.findViewById(R.id.imgIcon)
        val name: TextView = view.findViewById(R.id.txtAppName)
        val usageTime: TextView = view.findViewById(R.id.usageTimeText) // Add this
        val limit: EditText = view.findViewById(R.id.editLimit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.textWatcher?.let { holder.limit.removeTextChangedListener(it) }

        holder.name.text = app.appName
        holder.icon.setImageDrawable(app.icon)

        holder.limit.clearFocus()
        holder.limit.setText(if (app.hourLimit > 0) app.hourLimit.toString() else "")

        // 2. Remove the FocusChangeListener and use doAfterTextChanged instead
        // This ensures that the moment you type '6', the model has 6.
        // The moment you type '0', the model has 60.
        holder.textWatcher = holder.limit.doAfterTextChanged { text ->
            val newValue = text.toString().toIntOrNull() ?: 0
            app.hourLimit = newValue
        }

        // Display usage
        holder.usageTime.text = if (app.usageMinutes > 0) "${app.usageMinutes}m used today" else "Not used today"
        holder.usageTime.alpha = if (app.usageMinutes > 0) 1.0f else 0.5f
    }

    override fun getItemCount() = apps.size
}