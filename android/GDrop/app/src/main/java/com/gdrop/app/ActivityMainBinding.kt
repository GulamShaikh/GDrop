package com.gdrop.app

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView

class ActivityMainBinding private constructor(
    val root: View,
    val pairButton: Button,
    val refreshButton: Button,
    val downloadButton: Button,
    val connectionCard: CardView,
    val pairedLabel: TextView,
    val deviceId: TextView,
    val statusLabel: TextView,
    val transferName: TextView,
    val transferStatus: TextView,
    val progressBar: ProgressBar
) {
    companion object {
        fun inflate(inflater: LayoutInflater): ActivityMainBinding {
            val root = inflater.inflate(R.layout.activity_main, null, false)
            return ActivityMainBinding(
                root = root,
                pairButton = root.findViewById(R.id.pairButton),
                refreshButton = root.findViewById(R.id.refreshButton),
                downloadButton = root.findViewById(R.id.downloadButton),
                connectionCard = root.findViewById(R.id.connectionCard),
                pairedLabel = root.findViewById(R.id.pairedLabel),
                deviceId = root.findViewById(R.id.deviceId),
                statusLabel = root.findViewById(R.id.statusLabel),
                transferName = root.findViewById(R.id.transferName),
                transferStatus = root.findViewById(R.id.transferStatus),
                progressBar = root.findViewById(R.id.progressBar)
            )
        }
    }
}
