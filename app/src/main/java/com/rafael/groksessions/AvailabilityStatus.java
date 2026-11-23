package com.rafael.groksessions;

import android.graphics.Color;

enum AvailabilityStatus {
    GREEN(Color.rgb(53, 232, 155), "verde"),
    YELLOW(Color.rgb(255, 204, 77), "amarelo"),
    RED(Color.rgb(255, 36, 77), "vermelho");

    final int color;
    final String label;

    AvailabilityStatus(int color, String label) {
        this.color = color;
        this.label = label;
    }

    String description(int hours) {
        if (this == GREEN) return "Disponível";
        return (this == YELLOW ? "Amarelo · " : "") + "Em espera por " + hours + " horas";
    }
}
