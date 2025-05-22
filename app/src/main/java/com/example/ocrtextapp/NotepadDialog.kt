package com.example.ocrtextapp

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class NotepadDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.notepad, null)
        val editText = view.findViewById<EditText>(R.id.noteEditText)
        val saveButton = view.findViewById<Button>(R.id.saveNoteButton)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        saveButton.setOnClickListener {
            val note = editText.text.toString().trim()
            if (note.isNotEmpty()) {
                Toast.makeText(requireContext(), "Note saved: $note", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(requireContext(), "Note is empty", Toast.LENGTH_SHORT).show()
            }
        }

        return dialog
    }
}