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
        val limit: EditText = view.findViewById(R.id.editLimit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.limit.onFocusChangeListener = null

        // 2. Update the UI to match the current data model
        holder.name.text = app.appName
        holder.icon.setImageDrawable(app.icon)
        holder.limit.setText(app.hourLimit.toString())

        // 3. Only update the model when the user FINISHES typing or changes focus
        holder.limit.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                // User stopped touching this box, save the value to the model
                val newValue = (v as EditText).text.toString().toIntOrNull() ?: 0
                app.hourLimit = newValue
            }
        }
    }

    override fun getItemCount() = apps.size
}