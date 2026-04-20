package com.example.doomtodoomscroll
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
        val icon: ImageView = view.findViewById(R.id.imgIcon)
        val name: TextView = view.findViewById(R.id.txtAppName)
        val limit: EditText = view.findViewById(R.id.editLimit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]

        // 1. Remove the listener temporarily by setting it to null or
        // using a flag to prevent the "code-driven" update from triggering logic.
        holder.limit.clearFocus()

        // 2. Update the UI to match the current data model
        holder.name.text = app.appName
        holder.icon.setImageDrawable(app.icon)
        holder.limit.setText(app.hourLimit.toString())

        // 3. Re-attach the listener with a focus check
        holder.limit.doAfterTextChanged { text ->
            // CRITICAL: Only update the model if the user is actually
            // interacting with this specific EditText.
            if (holder.limit.hasFocus()) {
                val newValue = text.toString().toIntOrNull() ?: 0
                app.hourLimit = newValue
            }
        }
    }

    override fun getItemCount() = apps.size
}