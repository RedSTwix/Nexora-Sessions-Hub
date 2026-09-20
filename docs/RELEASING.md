# Publicação de versões

O workflow `release` é iniciado automaticamente depois que o workflow `ci` conclui com sucesso
um push na branch `main`. Ele lê `versionName` de `app/build.gradle.kts`, compila um APK assinado,
valida a assinatura, gera SHA-256, cria a tag `vX.Y.Z` e publica a release. Se essa tag já existir,
o workflow termina sem republicar a mesma versão.

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
4. O workflow `release` será iniciado automaticamente após a validação.
5. Ele recusará versões inválidas, segredos ausentes ou APK sem assinatura válida.

O botão **Run workflow** permanece disponível para repetir o processo após corrigir uma falha
operacional. Uma tag já publicada nunca é substituída.

O artefato publicado segue o padrão `Nexora-Sessions-Hub-vX.Y.Z.apk` e acompanha
`SHA256SUMS.txt` e atestado de procedência.
