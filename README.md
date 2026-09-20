# Nexora Sessions Hub

Aplicativo Android para organizar várias contas em sessões WebView isoladas. Cada conta mantém
cookies, cache e armazenamento web próprios, permitindo alternar entre perfis sem misturar logins.

> O Nexora Sessions Hub trabalha somente com dados locais do Android WebView. O aplicativo não
> envia, sincroniza ou exporta credenciais.

## Recursos principais

- **Sessões isoladas** — cada conta utiliza um perfil independente do Android System WebView.
- **Pastas de contas** — organize, renomeie, personalize e reordene pastas e contas.
- **Seleção múltipla** — mova várias contas ou reorganize grupos preservando a ordem relativa.
- **Indicadores de disponibilidade** — marque contas utilizadas por um período configurável.
- **Autenticação direcionada** — escolha a pasta e a conta quando outro aplicativo abrir um login.
- **Grok Dual** — automação opcional para recriar o aplicativo dual em dispositivos Xiaomi/HyperOS.
- **Privacidade local** — sem servidor próprio, telemetria ou sincronização externa.

## Como funciona

1. Crie uma pasta.
2. Adicione uma conta e faça o login manualmente no navegador isolado.
3. Repita o processo para os demais perfis.
4. Ao receber uma solicitação externa de autenticação, escolha o Nexora Sessions Hub.
5. Selecione primeiro a pasta e depois a conta que receberá a URL.

Pastas e contas podem ser renomeadas ou reordenadas sem perder suas sessões. Mover uma conta para
outra pasta também não altera os dados do perfil WebView.

## Disponibilidade

O controle de disponibilidade ajuda a espaçar o uso das contas:

- ao abrir uma conta controlada, o indicador fica vermelho pelo período configurado;
- terminado o período, o indicador volta a ficar verde;
- uma pasta fica vermelha quando todas as suas contas controladas estão indisponíveis;
- **Liberar todos agora** redefine somente os indicadores, sem apagar logins, cookies ou sessões.

## Trocar o Grok Dual

Não use **Encerrar sessão** dentro do Grok para apenas trocar a conta: essa ação pode revogar a
sessão no servidor. Use **Trocar Grok Dual** na tela inicial.

Com a Acessibilidade ativada, o aplicativo abre a tela oficial de Apps duplos da Xiaomi, remove o
clone local, confirma a recriação e abre o novo atalho no perfil `XSpace`. O serviço observa apenas
os componentes Xiaomi necessários ao fluxo.

Essa automação é específica para o comportamento atual de dispositivos Xiaomi/HyperOS e pode
precisar de ajustes em outras versões do sistema.

## Privacidade e dados locais

- Cookies e armazenamento web permanecem no perfil privado do Android WebView.
- O aplicativo não armazena senhas em arquivos próprios.
- URLs temporárias de retorno não são exportadas nem sincronizadas.
- Excluir uma conta remove permanentemente o perfil WebView correspondente.
- Desinstalar o aplicativo ou limpar seus dados remove as sessões locais.

## Requisitos

- Android 7.0 ou superior.
- Android System WebView atualizado e com suporte a `MULTI_PROFILE`.
- Para compilar: JDK 17 e Android SDK 36.
- A automação do Grok Dual requer um dispositivo Xiaomi/HyperOS compatível.

## Instalação

Quando o projeto estiver publicado, baixe o APK assinado na página de
[releases](https://github.com/RedSTwix/Nexora-Sessions-Hub/releases). Para atualizar sem perder as
sessões, instale a nova versão sobre a anterior e nunca limpe os dados do aplicativo.

## Compilar o projeto

No Linux ou macOS:

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```

No Windows:

```powershell
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

O APK de desenvolvimento será criado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## CI e releases

O workflow `ci` valida lint, testes e build em pushes para `main`, pull requests e execuções
manuais. O APK gerado fica disponível temporariamente como artefato de validação.

Após um push na branch `main`, o workflow `release` aguarda o `ci` terminar com sucesso e:

1. lê `versionName` e `versionCode` do Gradle;
2. exige uma chave de assinatura configurada nos GitHub Secrets;
3. compila e valida o APK assinado;
4. gera `SHA256SUMS.txt`;
5. cria a tag `vX.Y.Z` quando ela ainda não existe;
6. publica automaticamente a release e o atestado de procedência.

Novos commits com o mesmo `versionName` passam pelo CI normalmente, mas não recriam uma release
existente. O disparo manual permanece disponível para recuperação operacional.

As instruções completas estão em [docs/RELEASING.md](docs/RELEASING.md).

## Estrutura do repositório

```text
Nexora-Sessions-Hub/
├── .github/workflows/       # Validação e publicação automatizadas
├── app/src/main/java/       # Aplicação, perfis e automação do Grok Dual
├── app/src/main/res/        # Tema, ícones e configuração Android
├── docs/RELEASING.md        # Assinatura e processo de release
├── CONTRIBUTING.md          # Padrão de commits e contribuições
├── LICENSE                  # Licença MIT
└── README.md                # Documentação do projeto
```

## Limitações

Alguns provedores recusam autenticação dentro de WebViews. O projeto não tenta contornar políticas
do provedor por alteração de `User-Agent` ou mecanismos equivalentes.

## Licença

Este projeto é distribuído sob a [licença MIT](LICENSE).
