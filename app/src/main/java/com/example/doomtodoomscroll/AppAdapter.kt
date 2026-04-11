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
        holder.name.text = app.appName
        holder.icon.setImageDrawable(app.icon)

        // Update the model whenever the user types a limit
        holder.limit.doAfterTextChanged {
            app.hourLimit = it.toString().toIntOrNull() ?: 0
        }
    }

    override fun getItemCount() = apps.size
}