package com.rafael.groksessions;

enum AvailabilityMode {
    OPEN_WITH("Aberto com", "Quando outro aplicativo abre um link no Nexora e você escolhe uma conta.",
            "account_last_used", "availability_folders", "availability_duration_hours"),
    PROFILE_USED("Perfil usado", "Quando você abre uma conta diretamente na lista de perfis do Nexora.",
            "profile_last_used", "profile_availability_folders", "profile_availability_duration_hours");

    final String label, description, lastUsedKey, foldersKey, durationKey;

    AvailabilityMode(String label, String description, String lastUsedKey, String foldersKey, String durationKey) {
        this.label = label;
        this.description = description;
        this.lastUsedKey = lastUsedKey;
        this.foldersKey = foldersKey;
        this.durationKey = durationKey;
    }
}
