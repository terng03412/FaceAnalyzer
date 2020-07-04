package com.example.faceanalyzer

import android.graphics.Rect

data class Prediction( var bbox : Rect, var label : String )