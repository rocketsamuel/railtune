package com.example.railtune.model

data class Z21LocoState(
    val speedPercent: Float,
    val isForward: Boolean,
    val activeFunctions: Set<Int>
)
