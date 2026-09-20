# Publicação de versões

O workflow `release` publica uma versão somente quando iniciado manualmente na branch `main`.
Ele lê `versionName` de `app/build.gradle.kts`, compila um APK assinado, valida a assinatura,
gera SHA-256, cria a tag `vX.Y.Z` e publica a release.

## Preparar a chave de assinatura

A chave é permanente. Guarde uma cópia segura fora do repositório: futuras atualizações do
mesmo aplicativo precisam ser assinadas por ela.

Exemplo para criar uma chave nova:

```powershell
keytool -genkeypair -v -keystore nexora-release.keystore -alias nexora -keyalg RSA -keysize 4096 -validity 10000
```

Não use uma chave de depuração e nunca versione o arquivo `.keystore`.

## GitHub Secrets necessários

Cadastre em **Settings → Secrets and variables → Actions**:

- `ANDROID_KEYSTORE_BASE64`: conteúdo Base64 completo da chave.
- `ANDROID_KEYSTORE_PASSWORD`: senha do arquivo.
- `ANDROID_KEY_ALIAS`: alias da chave.
- `ANDROID_KEY_PASSWORD`: senha do alias.

No PowerShell, copie a chave em Base64 sem expô-la no terminal:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\caminho\nexora-release.keystore")) | Set-Clipboard
```

## Publicar

1. Atualize `versionCode` e `versionName` em `app/build.gradle.kts`.
2. Registre a mudança com um commit, por exemplo `build(release): prepara versão 0.5.0`.
3. Envie a branch `main` e aguarde o workflow `ci` terminar.
4. Abra **Actions → release → Run workflow** e selecione `main`.
5. O workflow recusará versões inválidas, tags existentes, segredos ausentes ou APK sem assinatura válida.

O artefato publicado segue o padrão `Nexora-Sessions-Hub-vX.Y.Z.apk` e acompanha
`SHA256SUMS.txt` e atestado de procedência.
