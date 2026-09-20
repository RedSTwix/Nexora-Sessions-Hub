# Contribuindo

## Preparação

- Use JDK 17 e Android SDK 36.
- Não versione chaves, senhas, cookies, capturas de sessão ou `local.properties`.
- Antes de enviar uma alteração, execute `./gradlew lintDebug testDebugUnitTest assembleDebug`.

## Commits

O projeto usa Conventional Commits em português:

```text
tipo(escopo): descrição curta no imperativo
```

Tipos principais:

- `feat`: novo recurso visível ou funcionalidade.
- `fix`: correção de comportamento.
- `refactor`: reorganização sem mudança funcional planejada.
- `perf`: melhoria de desempenho.
- `docs`: documentação.
- `test`: testes.
- `build`: Gradle, SDK ou empacotamento.
- `ci`: GitHub Actions e publicação.
- `chore`: manutenção sem impacto funcional.

Exemplos:

```text
feat(pastas): adiciona seleção múltipla na organização
fix(sessoes): preserva o perfil ao atualizar o aplicativo
docs(project): documenta compilação e publicação
ci(release): automatiza APK assinado e checksum
```

Prefira commits pequenos e completos. Não misture correções independentes no mesmo commit.

## Versões

As versões seguem SemVer (`MAJOR.MINOR.PATCH`) e são definidas por `versionName` em
`app/build.gradle.kts`. Aumente também `versionCode` a cada nova versão Android.

Consulte [docs/RELEASING.md](docs/RELEASING.md) antes de publicar.
